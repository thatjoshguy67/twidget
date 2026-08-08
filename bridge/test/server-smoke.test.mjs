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

const BRIDGE_START_TIMEOUT_MS = 15000;

async function waitForBridgeStart(child) {
  const stderr = [];
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      const detail = stderr.length ? `\n${stderr.join("")}` : "";
      reject(new Error(`Bridge did not start within ${BRIDGE_START_TIMEOUT_MS}ms${detail}`));
    }, BRIDGE_START_TIMEOUT_MS);
    child.once("error", reject);
    child.stderr.on("data", (chunk) => stderr.push(String(chunk)));
    child.stdout.on("data", (chunk) => {
      if (String(chunk).includes("Twidget bridge listening")) {
        clearTimeout(timeout);
        resolve();
      }
    });
    child.once("exit", (code, signal) => {
      clearTimeout(timeout);
      const detail = stderr.length ? `\n${stderr.join("")}` : "";
      reject(new Error(`Bridge exited before listening (${code ?? "null"}, ${signal ?? "null"})${detail}`));
    });
  });
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
  await waitForBridgeStart(child);
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

  await waitForBridgeStart(child);

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
