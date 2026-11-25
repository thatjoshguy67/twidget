import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import net from "node:net";
import http from "node:http";
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

async function startBridge(t, environment, { store, onOutput = () => {}, onStorePath = () => {} } = {}) {
  const port = await availablePort();
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-bridge-test-"));
  const storePath = path.join(temp, "history.json");
  if (store) await writeFile(storePath, JSON.stringify(store));
  onStorePath(storePath);
  const child = spawn(process.execPath, ["src/server.js"], {
    cwd: path.resolve(import.meta.dirname, ".."),
    env: {
      ...process.env,
      PORT: String(port),
      HISTORY_STORE_PATH: storePath,
      WAYBACK_BACKFILL: "0",
      TEST_MOCK_UPSTREAM: "1",
      ...environment,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", (chunk) => onOutput(String(chunk)));
  child.stderr.on("data", (chunk) => onOutput(String(chunk)));
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

const DAY = 24 * 60 * 60 * 1000;

function followerStore(meta, topFollowers = {}) {
  return {
    version: 4,
    accounts: Object.fromEntries(Object.keys(meta).map((key) => [key, [{ ts: Date.now(), followers: 10, src: "live" }]])),
    meta,
    topFollowers,
  };
}

function completedFollowerScan(completedAt) {
  return {
    scanId: "old-scan", status: "complete", startedAt: completedAt, updatedAt: completedAt, completedAt,
    pages: 1, scanned: 1, cursor: "", followers: [{ id: "old", username: "oldfan", followers: 1 }],
  };
}

async function waitFor(check, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (!(await check())) {
    assert.ok(Date.now() < deadline, "Timed out waiting for bridge work");
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
}

test("scheduled refresh skips dormant accounts, queues active/legacy accounts, and resumes running scans", async (t) => {
  const now = Date.now();
  const enrolled = { topFollowersEnrolledAt: now - 20 * DAY };
  const meta = {
    dormant: { ...enrolled, topFollowersLastSeenAt: now - 8 * DAY },
    active: { ...enrolled, topFollowersLastSeenAt: now - DAY },
    running: { ...enrolled, topFollowersLastSeenAt: now - 20 * DAY },
    legacyold: { topFollowersEnrolledAt: now - 8 * DAY },
    legacyrecent: { topFollowersEnrolledAt: now - DAY },
    legacyunknown: { topFollowers: { cachedAt: now - 20 * DAY, top: [{ username: "oldfan" }] } },
    unenrolled: {},
  };
  let output = "";
  let storePath;
  await startBridge(t, { TWITTERAPIS_API_KEY: "test-key", TOP_FOLLOWERS_ACTIVE_DAYS: "7" }, {
    store: followerStore(meta, {
      running: { ...completedFollowerScan(now - 2 * DAY), status: "running", completedAt: 0, cursor: "", pages: 0, scanned: 0, followers: [] },
    }),
    onOutput: (text) => { output += text; },
    onStorePath: (value) => { storePath = value; },
  });
  await waitFor(() => output.includes("Top Followers refresh skipped 2 dormant accounts"), 20_000);
  await waitFor(() => output.includes("scan completed for legacyunknown"));
  const stored = JSON.parse(await readFile(storePath, "utf8"));
  assert.equal(stored.topFollowers.dormant, undefined);
  assert.equal(stored.topFollowers.legacyold, undefined);
  assert.equal(stored.topFollowers.unenrolled, undefined);
  for (const key of ["active", "running", "legacyrecent", "legacyunknown"]) {
    assert.equal(stored.topFollowers[key].status, "complete", key);
  }
  assert.equal(stored.topFollowers.running.scanId, "old-scan");
  assert.equal(stored.meta.legacyold.topFollowersLastSeenAt, meta.legacyold.topFollowersEnrolledAt);
  assert.equal(stored.meta.legacyrecent.topFollowersLastSeenAt, meta.legacyrecent.topFollowersEnrolledAt);
  assert.ok(stored.meta.legacyunknown.topFollowersLastSeenAt >= now);
  assert.equal((output.match(/skipped 2 dormant accounts/g) || []).length, 1);
});

test("all client Top Followers routes record activity and share the write throttle", async (t) => {
  const now = Date.now();
  const keys = ["preview", "archive", "status", "start"];
  let storePath;
  const base = await startBridge(t, { TWITTERAPIS_API_KEY: "test-key", TOP_FOLLOWERS_ACTIVE_DAYS: "3" }, {
    store: followerStore(
      Object.fromEntries(keys.map((key) => [key, { topFollowersEnrolledAt: now - 10 * DAY }])),
      Object.fromEntries(keys.map((key) => [key, completedFollowerScan(now)])),
    ),
    onStorePath: (value) => { storePath = value; },
  });
  assert.equal((await (await fetch(`${base}/health`)).json()).history.topFollowers.activeDays, 3);
  for (const [key, suffix, method] of [["preview", "", "GET"], ["archive", "/all", "GET"], ["status", "/scan", "GET"], ["start", "/scan", "POST"]]) {
    assert.equal((await fetch(`${base}/history/${key}/top-followers${suffix}`, { method })).status, 200);
    const firstSeen = JSON.parse(await readFile(storePath, "utf8")).meta[key].topFollowersLastSeenAt;
    assert.ok(firstSeen >= now, key);
    assert.equal((await fetch(`${base}/history/${key}/top-followers/scan`)).status, 200);
    assert.equal(JSON.parse(await readFile(storePath, "utf8")).meta[key].topFollowersLastSeenAt, firstSeen);
  }
});

test("returning preview readers get stale data immediately and share POST daily limits", async (t) => {
  for (const [limit, expectedError] of [
    [{ TOP_FOLLOWERS_DAILY_SCAN_LIMIT_PER_IP: "1" }, "top_followers_client_daily_limit_reached"],
    [{ TOP_FOLLOWERS_DAILY_SCAN_LIMIT: "1", TOP_FOLLOWERS_DAILY_SCAN_LIMIT_PER_IP: "10" }, "top_followers_daily_limit_reached"],
  ]) {
    await t.test(expectedError, async (t) => {
      const now = Date.now();
      const base = await startBridge(t, { TWITTERAPIS_API_KEY: "test-key", ...limit }, {
        store: followerStore(
          { returning: { topFollowersEnrolledAt: now - 20 * DAY }, limited: { topFollowersEnrolledAt: now - 20 * DAY } },
          { returning: completedFollowerScan(now - 8 * DAY), limited: completedFollowerScan(now - 8 * DAY) },
        ),
      });
      const preview = await fetch(`${base}/history/returning/top-followers`);
      assert.equal(preview.status, 200);
      assert.equal((await preview.json()).top[0].username, "oldfan");
      await waitFor(async () => (await (await fetch(`${base}/history/returning/top-followers/scan`)).json()).completedAt > now);
      const stale = await fetch(`${base}/history/limited/top-followers`);
      assert.equal(stale.status, 200);
      assert.equal((await stale.json()).top[0].username, "oldfan");
      const denied = await fetch(`${base}/history/limited/top-followers/scan`, { method: "POST" });
      assert.equal(denied.status, 429);
      assert.equal((await denied.json()).error, expectedError);
      const unchanged = await (await fetch(`${base}/history/limited/top-followers/scan`)).json();
      assert.equal(unchanged.completedAt, now - 8 * DAY);
    });
  }
});

test("preview refresh respects scan capacity without delaying stale responses", async (t) => {
  let providerResponse;
  const provider = http.createServer((_req, res) => { providerResponse = res; });
  await new Promise((resolve) => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => {
    provider.closeAllConnections();
    provider.close();
  });
  const now = Date.now();
  const base = await startBridge(t, {
    TWITTERAPIS_API_KEY: "test-key",
    TWITTERAPIS_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/followers`,
    TEST_MOCK_UPSTREAM: "0",
    TOP_FOLLOWERS_MAX_CONCURRENT: "1",
  }, {
    store: followerStore(
      { first: { topFollowersEnrolledAt: now }, second: { topFollowersEnrolledAt: now } },
      { first: completedFollowerScan(now - 8 * DAY), second: completedFollowerScan(now - 8 * DAY) },
    ),
  });
  assert.equal((await fetch(`${base}/history/first/top-followers`)).status, 200);
  await waitFor(() => providerResponse);
  assert.equal((await fetch(`${base}/history/second/top-followers`)).status, 200);
  const denied = await fetch(`${base}/history/second/top-followers/scan`, { method: "POST" });
  assert.equal(denied.status, 503);
  assert.equal((await denied.json()).error, "top_followers_scan_capacity_reached");
  assert.equal((await (await fetch(`${base}/history/second/top-followers/scan`)).json()).completedAt, now - 8 * DAY);
  providerResponse.end(JSON.stringify({ users: [{ id: "42", username: "freshfan", followers_count: 10 }] }));
  await waitFor(async () => (await (await fetch(`${base}/history/first/top-followers/scan`)).json()).status === "complete");
});

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
  assert.equal(healthBody.history.topFollowers.activeDays, 7);

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

test("weekly cadence reuses two-day snapshots on both preview and explicit starts", async (t) => {
  const now = Date.now();
  const base = await startBridge(t, { TWITTERAPIS_API_KEY: "test-key" }, {
    store: followerStore({ example: { topFollowersEnrolledAt: now - 20 * DAY } }, {
      example: completedFollowerScan(now - 2 * DAY),
    }),
  });
  const settings = (await (await fetch(`${base}/health`)).json()).history.topFollowers;
  assert.equal(settings.fullScanDays, 7);
  assert.equal(settings.headCheck, false);
  assert.equal(settings.headPages, 1);
  assert.equal(settings.providerOrderingVerified, false);
  assert.equal(settings.refreshHours, 24);
  const preview = await (await fetch(`${base}/history/example/top-followers`)).json();
  assert.equal(preview.completedAt, now - 2 * DAY);
  assert.equal(preview.fullCompletedAt, now - 2 * DAY);
  const start = await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" });
  assert.equal(start.status, 200);
  assert.equal((await start.json()).completedAt, now - 2 * DAY);
  assert.equal((await (await fetch(`${base}/history/example/top-followers/scan`)).json()).completedAt, now - 2 * DAY);
});

test("opt-in head checks cap provider pages and merge new followers without removing old ones", async (t) => {
  const cursors = [];
  const provider = http.createServer((req, res) => {
    const cursor = new URL(req.url, "http://localhost").searchParams.get("cursor") || "";
    cursors.push(cursor);
    const username = cursor ? "secondfan" : "newfan";
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({
      users: [{ id: username, username, followers_count: cursor ? 50 : 100 }],
      next_cursor: cursor ? "never-fetch" : "second-page",
    }));
  });
  await new Promise((resolve) => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => { provider.closeAllConnections(); provider.close(); });
  for (const pages of [1, 2]) {
    await t.test(`${pages} head pages`, async (t) => {
      cursors.length = 0;
      const now = Date.now();
      const base = await startBridge(t, {
        TWITTERAPIS_API_KEY: "test-key",
        TEST_MOCK_UPSTREAM: "0",
        TWITTERAPIS_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/followers`,
        TOP_FOLLOWERS_HEAD_CHECK: "1",
        TOP_FOLLOWERS_HEAD_PAGES: String(pages),
      }, {
        store: followerStore({ example: { topFollowersEnrolledAt: now } }, {
          example: completedFollowerScan(now - 2 * DAY),
        }),
      });
      const health = (await (await fetch(`${base}/health`)).json()).history.topFollowers;
      assert.equal(health.headCheck, true);
      assert.equal(health.headPages, pages);
      assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 202);
      await waitFor(async () => (await (await fetch(`${base}/history/example/top-followers/scan`)).json()).status === "complete");
      const archive = await (await fetch(`${base}/history/example/top-followers/all`)).json();
      assert.equal(archive.scanKind, "head");
      assert.equal(archive.fullCompletedAt, now - 2 * DAY);
      assert.ok(archive.completedAt >= now);
      assert.equal(archive.total, 1 + pages);
      assert.equal(archive.followers[0].username, "newfan");
      assert.equal(archive.followers.at(-1).username, "oldfan");
      assert.deepEqual(cursors, pages === 1 ? [""] : ["", "second-page"]);
      assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 200);
      assert.equal(cursors.length, pages, "same-day polling reuses the merged snapshot");
    });
  }
});

test("invalid cadence settings fall back to safe defaults", async (t) => {
  const base = await startBridge(t, {
    TOP_FOLLOWERS_FULL_SCAN_DAYS: "91", TOP_FOLLOWERS_HEAD_PAGES: "6", TOP_FOLLOWERS_HEAD_CHECK: "true",
  });
  const settings = (await (await fetch(`${base}/health`)).json()).history.topFollowers;
  assert.equal(settings.fullScanDays, 7);
  assert.equal(settings.headPages, 1);
  assert.equal(settings.headCheck, false);
});

test("a checkpointed terminal page is published on resume without fetching the first page again", async (t) => {
  const now = Date.now();
  const base = await startBridge(t, { TWITTERAPIS_API_KEY: "test-key" }, {
    store: followerStore({ example: { topFollowersEnrolledAt: now } }, {
      example: { ...completedFollowerScan(now), status: "running", completedAt: 0 },
    }),
  });
  await fetch(`${base}/history/example/top-followers/scan`);
  await waitFor(async () => (await (await fetch(`${base}/history/example/top-followers/scan`)).json()).status === "complete");
  const archive = await (await fetch(`${base}/history/example/top-followers/all`)).json();
  assert.equal(archive.total, 1);
  assert.equal(archive.followers[0].username, "oldfan");
});

test("handle migration verifies destination identity and makes history identity explicit", async t => {
  const base = await startBridge(t, { BRIDGE_API_TOKEN: "", HISTORY_ADMIN_TOKEN: "admin-test-token" });
  const before = await fetch(`${base}/history/example`).then(r => r.json());
  assert.equal(before.userId, "123456");
  const move = body => fetch(`${base}/history/example/handle`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  assert.equal((await move({ username: "other", userId: "123456" })).status, 409);
  const result = await move({ username: "renamed", userId: "123456" });
  assert.equal(result.status, 200);
  assert.equal((await result.json()).moved, true);
  assert.equal((await move({ username: "renamed", userId: "123456" })).status, 200);
  const after = await fetch(`${base}/history/renamed`).then(r => r.json());
  assert.equal(after.userId, "123456");
  assert.deepEqual(after.history, before.history);
  const profile = await fetch(`${base}/user/renamed`).then(r => r.json());
  assert.equal(profile.historyUserId, profile.userId);
  assert.equal((await fetch(`${base}/admin/history/example/handle`, { method: "POST" })).status, 401);
});


test("legacy clients receive existing history and fresh observations without an ownership backfill", async t => {
  const day = Math.floor(Date.now() / DAY) * DAY;
  const history = [3, 2, 1].map((age, i) => ({ ts: day - age * DAY, followers: 10 + i, following: 1, posts: 2, likes: 3, src: 'live' }));
  let storePath;
  const base = await startBridge(t, {}, { store: { version: 4, accounts: { example: history }, meta: { example: {} } }, onStorePath: value => { storePath = value; } });
  const profile = await fetch(`${base}/user/example`).then(r => r.json());
  assert.equal(profile.userId, '123456');
  assert.equal(profile.historyUserId, '');
  assert.deepEqual(profile.history.map(row => row.followers), [10, 11, 12, 1234]);
  const pooled = await fetch(`${base}/history/example`).then(r => r.json());
  assert.equal(pooled.userId, '');
  assert.deepEqual(pooled.history, profile.history);
  const cached = await fetch(`${base}/user/example`).then(r => r.json());
  assert.deepEqual(cached.history, profile.history);
  await waitFor(async () => JSON.parse(await readFile(storePath, 'utf8')).accounts.example.length === 4);
  assert.equal(JSON.parse(await readFile(storePath, 'utf8')).meta.example.userId, undefined);
  const migration = await fetch(`${base}/history/example/handle`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'renamed', userId: '123456' }) });
  assert.equal(migration.status, 409);
});

test("a recycled handle never receives or overwrites history bound to another ID", async t => {
  const history = [{ ts: Date.now() - DAY, followers: 99, following: 1, posts: 2, likes: 3, src: 'live' }];
  const base = await startBridge(t, {}, { store: { version: 4, accounts: { example: history }, meta: { example: { userId: '999' } } } });
  const profile = await fetch(`${base}/user/example`).then(r => r.json());
  assert.deepEqual(profile.history, []);
  assert.equal(profile.historyUserId, '');
  const pooled = await fetch(`${base}/history/example`).then(r => r.json());
  assert.equal(pooled.userId, '999');
  assert.deepEqual(pooled.history.map(row => row.followers), [99]);
});


test("an empty legacy enrollment can resume sampling without being silently bound", async t => {
  const base = await startBridge(t, {}, { store: { version: 4, accounts: { example: [] }, meta: { example: {} } } });
  const response = await fetch(`${base}/history/example`);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.userId, '');
  assert.deepEqual(body.history.map(row => row.followers), [1234]);
});

test('interaction sync validates identity, merges snapshots and retains nulls', async t => {
  const base = await startBridge(t, {});
  const history = await (await fetch(`${base}/history/example`)).json();
  const send = body => fetch(`${base}/history/example/interactions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  const data = { userId: history.userId, daily: [{ date: '2026-01-01', likes: 0 }],
    posts: [{ id: '123', createdAt: 1000, capturedAt: 2000, isReply: false, likes: 5 }] };
  assert.equal((await send({ ...data, userId: 'wrong' })).status, 409);
  assert.equal((await send({ ...data, posts: [{ ...data.posts[0], likes: -1 }] })).status, 400);
  assert.equal((await send(data)).status, 200);
  assert.equal((await send({ ...data, daily: [], posts: [{ ...data.posts[0], capturedAt: 3000, likes: 2, reposts: 3 }] })).status, 200);
  const saved = await (await fetch(`${base}/history/example/interactions`)).json();
  assert.equal(saved.userId, history.userId);
  assert.equal(saved.daily[0].likes, 0);
  assert.equal(saved.daily[0].shares, null);
  assert.equal(saved.posts[0].likes, 5);
  assert.equal(saved.posts[0].reposts, 3);
});
