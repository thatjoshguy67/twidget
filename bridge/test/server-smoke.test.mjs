import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import test from "node:test";

async function availablePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => server.listen(0, "127.0.0.1", resolve).once("error", reject));
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

async function startBridge(t, environment) {
  const port = await availablePort();
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-bridge-test-"));
  const child = spawn(process.execPath, ["src/server.js"], {
    cwd: path.resolve(import.meta.dirname, ".."),
    env: {
      ...process.env,
      PORT: String(port),
      HISTORY_STORE_PATH: path.join(temp, "history.json"),
      WAYBACK_BACKFILL: "0",
      TEST_MOCK_UPSTREAM: "1",
      ...environment,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(async () => {
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      await new Promise((resolve) => child.once("exit", resolve));
    }
    await rm(temp, { recursive: true, force: true });
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Bridge did not start")), 5000);
    child.once("error", reject);
    child.stdout.on("data", (chunk) => {
      if (String(chunk).includes("Twidget bridge listening")) {
        clearTimeout(timeout);
        resolve();
      }
    });
  });
  return `http://127.0.0.1:${port}`;
}

test("public bridge hides shared-ranking writes without a publisher credential", async (t) => {
  const base = await startBridge(t, {
    BRIDGE_API_TOKEN: "",
    HISTORY_ADMIN_TOKEN: "",
    TOP_FOLLOWERS_PUBLISH_TOKEN: "",
  });
  const registered = await fetch(`${base}/history/example`);
  assert.equal(registered.status, 200);

  const forgedRanking = await fetch(`${base}/history/example/top-followers`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      scanned: 1,
      pages: 1,
      top: [{
        id: "attacker",
        username: "attacker",
        name: "Attacker",
        followers: 1,
        verified: false,
        avatar: "https://example.com/attacker.jpg",
      }],
    }),
  });
  assert.equal(forgedRanking.status, 404);
  assert.deepEqual(await forgedRanking.json(), { error: "not_found" });
});

test("public bridge accepts shared rankings only from its trusted publisher", async (t) => {
  const base = await startBridge(t, {
    BRIDGE_API_TOKEN: "",
    HISTORY_ADMIN_TOKEN: "",
    TOP_FOLLOWERS_PUBLISH_TOKEN: "publisher-token",
  });
  const registered = await fetch(`${base}/history/example`);
  assert.equal(registered.status, 200);
  const body = JSON.stringify({
    scanned: 1,
    pages: 1,
    top: [{
      id: "42",
      username: "topfan",
      name: "Top Fan",
      followers: 9001,
      verified: false,
      avatar: "https://example.com/avatar.jpg",
    }],
  });

  const unauthorized = await fetch(`${base}/history/example/top-followers`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  });
  assert.equal(unauthorized.status, 401);

  const authorized = await fetch(`${base}/history/example/top-followers`, {
    method: "POST",
    headers: {
      Authorization: "Bearer publisher-token",
      "Content-Type": "application/json",
    },
    body,
  });
  assert.equal(authorized.status, 201);
});

test("opted-in accounts can reuse a bridge-owned completed follower scan", async (t) => {
  const base = await startBridge(t, {
    BRIDGE_API_TOKEN: "",
    HISTORY_ADMIN_TOKEN: "",
    TWITTERAPIS_API_KEY: "server-only-key",
    EXPENSIVE_RATE_LIMIT_MAX: "12",
  });
  assert.equal((await fetch(`${base}/history/example`)).status, 200);
  const started = await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" });
  assert.equal(started.status, 202);

  let status;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const response = await fetch(`${base}/history/example/top-followers/scan`);
    status = await response.json();
    if (status.status !== "running") break;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.equal(status.status, "complete");
  assert.equal(status.scanned, 2);

  const all = await fetch(`${base}/history/example/top-followers/all?offset=0&limit=2000`);
  assert.equal(all.status, 200);
  const body = await all.json();
  assert.equal(body.followers[0].username, "topfan");
  assert.equal(body.limit, 2000);
  assert.equal(body.nextOffset, null);

  // Archive pagination is a bounded DB read, not a remote provider action. It
  // must retain the general limiter but not exhaust the expensive-route budget.
  for (let page = 0; page < 13; page += 1) {
    const archived = await fetch(`${base}/history/example/top-followers/all?offset=0&limit=1`);
    assert.equal(archived.status, 200);
  }

  const reused = await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" });
  assert.equal(reused.status, 200);
  assert.equal((await reused.json()).status, "complete");
});

test("public server scans enforce a separate daily start budget per client", async (t) => {
  const base = await startBridge(t, {
    BRIDGE_API_TOKEN: "",
    HISTORY_ADMIN_TOKEN: "",
    TWITTERAPIS_API_KEY: "server-only-key",
    TOP_FOLLOWERS_DAILY_SCAN_LIMIT_PER_IP: "1",
  });
  assert.equal((await fetch(`${base}/history/example`)).status, 200);
  assert.equal((await fetch(`${base}/history/another`)).status, 200);
  assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 202);
  const limited = await fetch(`${base}/history/another/top-followers/scan`, { method: "POST" });
  assert.equal(limited.status, 429);
  assert.deepEqual(await limited.json(), { error: "top_followers_client_daily_limit_reached" });
});

test("bridge security and health defaults", async (t) => {
  const port = await availablePort();
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-bridge-test-"));
  const child = spawn(process.execPath, ["src/server.js"], {
    cwd: path.resolve(import.meta.dirname, ".."),
    env: {
      ...process.env,
      PORT: String(port),
      BRIDGE_API_TOKEN: "test-token",
      HISTORY_ADMIN_TOKEN: "admin-token",
      HISTORY_STORE_PATH: path.join(temp, "history.json"),
      WAYBACK_BACKFILL: "0",
      TEST_MOCK_UPSTREAM: "1",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(async () => {
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      await new Promise((resolve) => child.once("exit", resolve));
    }
    await rm(temp, { recursive: true, force: true });
  });

  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Bridge did not start")), 5000);
    child.once("error", reject);
    child.stdout.on("data", (chunk) => {
      if (String(chunk).includes("Twidget bridge listening")) {
        clearTimeout(timeout);
        resolve();
      }
    });
  });

  const base = `http://127.0.0.1:${port}`;
  const health = await fetch(`${base}/health`);
  assert.equal(health.status, 200);
  assert.equal(health.headers.get("access-control-allow-origin"), null);
  assert.equal(health.headers.get("x-content-type-options"), "nosniff");
  const healthBody = await health.json();
  assert.equal(healthBody.authMode, "bearer");
  assert.equal(healthBody.publicMode, false);
  assert.equal(healthBody.history.analyticsImport, true);

  const unauthorizedUser = await fetch(`${base}/user/example`);
  assert.equal(unauthorizedUser.status, 401);

  const authorizedUser = await fetch(`${base}/user/example`, {
    headers: { Authorization: "Bearer test-token" },
  });
  assert.equal(authorizedUser.status, 200);
  const userBody = await authorizedUser.json();
  assert.equal(userBody.userName, "example");
  assert.equal(userBody.followersCount, 1234);
  assert.equal(authorizedUser.headers.get("x-twidget-cache"), "miss");

  const analytics = await fetch(`${base}/analytics/example`, {
    headers: { Authorization: "Bearer test-token" },
  });
  assert.equal(analytics.status, 200);
  assert.match(analytics.headers.get("ratelimit-limit") ?? "", /^\d+$/);
  const analyticsBody = await analytics.json();
  assert.ok(Array.isArray(analyticsBody.activityTimestamps));
  assert.ok(Array.isArray(analyticsBody.recentPosts));
  assert.equal(analyticsBody.activityComplete, true);

  const unauthorized = await fetch(`${base}/official/user/example`);
  assert.equal(unauthorized.status, 401);
  assert.deepEqual(await unauthorized.json(), { error: "unauthorized" });

  const protectedRoute = await fetch(`${base}/official/user/example`, {
    headers: { Authorization: "Bearer test-token" },
  });
  assert.equal(protectedRoute.status, 501);

  const removedLegacyBackfill = await fetch(`${base}/history/example/backfill`, {
    method: "POST",
    headers: {
      Authorization: "Bearer test-token",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ samples: [] }),
  });
  assert.equal(removedLegacyBackfill.status, 404);

  const registeredHistory = await fetch(`${base}/history/example`, {
    headers: { Authorization: "Bearer test-token" },
  });
  assert.equal(registeredHistory.status, 200);

  const savedTopFollowers = await fetch(`${base}/history/example/top-followers`, {
    method: "POST",
    headers: {
      Authorization: "Bearer test-token",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      scanned: 100,
      pages: 2,
      top: [{
        id: "42",
        username: "topfan",
        name: "Top Fan",
        followers: 9001,
        verified: false,
        avatar: "https://example.com/avatar.jpg",
      }],
    }),
  });
  assert.equal(savedTopFollowers.status, 201);

  const sharedTopFollowers = await fetch(`${base}/history/example/top-followers`, {
    headers: { Authorization: "Bearer test-token" },
  });
  assert.equal(sharedTopFollowers.status, 200);
  const sharedTopFollowersBody = await sharedTopFollowers.json();
  assert.equal(sharedTopFollowersBody.scanned, 100);
  assert.equal(sharedTopFollowersBody.top[0].username, "topfan");

  const unauthorizedDelete = await fetch(`${base}/admin/history/example`, { method: "DELETE" });
  assert.equal(unauthorizedDelete.status, 401);

  const deleted = await fetch(`${base}/admin/history/example`, {
    method: "DELETE",
    headers: { Authorization: "Bearer admin-token" },
  });
  assert.equal(deleted.status, 204);
});
