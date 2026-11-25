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
      TOP_FOLLOWERS_PROVIDER: "twitterapis",
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

test("shared history exposes interval follower losses only for its current account ID", async t => {
  const observation = { userId: '123', scanId: 'full-2', from: 1, to: 2, newFollowers: 1, lostFollowers: 1,
    source: 'complete_follower_scans', semantics: 'observed_membership_changes_not_daily_unfollow_events' };
  const base = await startBridge(t, { BRIDGE_API_TOKEN: '' }, { store: followerStore({
    example: { userId: '123', followerChanges: [observation, { ...observation, userId: '999' }] },
  }) });
  const response = await fetch(`${base}/history/example`);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.userId, '123');
  assert.deepEqual(body.followerChanges, [observation]);
  assert.equal(body.history[0].unfollows, undefined);
});

test("default profile reports are private, replaceable, removable and do not enroll handles", async t => {
  const base = await startBridge(t, { BRIDGE_API_TOKEN: "", HISTORY_ADMIN_TOKEN: "operator", EXPENSIVE_RATE_LIMIT_MAX: "100" });
  const headers = { "Content-Type": "application/json", "X-Twidget-Install-Key": "a".repeat(64) };
  const report = username => fetch(`${base}/installation/primary-profile`, { method: "POST", headers, body: JSON.stringify({ username }) });
  assert.equal((await report("missing")).status, 409);
  assert.equal((await fetch(`${base}/admin/audience`)).status, 401);
  assert.equal((await fetch(`${base}/installation/primary-profile`)).status, 404);
  assert.equal((await fetch(`${base}/history/example`)).status, 200);
  assert.equal((await report("EXAMPLE")).status, 204);
  const audience = async () => (await fetch(`${base}/admin/audience`, { headers: { Authorization: "Bearer operator" } })).json();
  let data = await audience();
  assert.equal(data.registeredAccounts, 1);
  assert.equal(data.reportedInstallations, 1);
  assert.deepEqual(data.primaryAccounts, [{ username: "example", primaryInstalls: 1, active7d: 1, active30d: 1 }]);
  assert.ok(!JSON.stringify(data).includes("a".repeat(64)));
  assert.equal((await report("example")).status, 204);
  assert.equal((await audience()).reportedInstallations, 1);
  assert.equal((await fetch(`${base}/installation/primary-profile`, { method: "POST", headers: { "Content-Type": "application/json" }, body: '{"username":null}' })).status, 400);
  assert.equal((await report(null)).status, 204);
  data = await audience();
  assert.equal(data.reportedInstallations, 0);
  assert.deepEqual(data.accountsWithoutPrimaryReport, ["example"]);
});

test("self-hosted default reports require the configured bridge token", async t => {
  const base = await startBridge(t, { BRIDGE_API_TOKEN: "private", HISTORY_ADMIN_TOKEN: "" });
  assert.equal((await fetch(`${base}/installation/primary-profile`, { method: "POST" })).status, 401);
  assert.equal((await fetch(`${base}/admin/audience`)).status, 404);
});

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
  await waitFor(async () => {
    const saved = JSON.parse(await readFile(storePath, "utf8"));
    return ["active", "running", "legacyrecent", "legacyunknown"].every(key => saved.topFollowers[key]?.status === "complete");
  });
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

test("weekly cadence reuses two-day snapshots when daily checks are disabled", async (t) => {
  const now = Date.now();
  const base = await startBridge(t, { TWITTERAPIS_API_KEY: "test-key", TOP_FOLLOWERS_HEAD_CHECK: "0" }, {
    store: followerStore({ example: { topFollowersEnrolledAt: now - 20 * DAY } }, {
      example: completedFollowerScan(now - 2 * DAY),
    }),
  });
  const settings = (await (await fetch(`${base}/health`)).json()).history.topFollowers;
  assert.equal(settings.fullScanDays, 7);
  assert.equal(settings.headCheck, false);
  assert.equal(settings.headRows, 100);
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

test("request budget pauses mid-scan with its cursor and previous snapshot intact", async (t) => {
  let calls = 0;
  const provider = http.createServer((req, res) => {
    calls += 1;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ users: [{ id: "new", username: "newfan", followers_count: 500 }], next_cursor: "saved-cursor" }));
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => { provider.closeAllConnections(); provider.close(); });
  let output = "";
  let checkpointPath;
  const now = Date.now();
  const base = await startBridge(t, {
    TWITTERAPIS_API_KEY: "test-key", TEST_MOCK_UPSTREAM: "0",
    TWITTERAPIS_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/followers`,
    TOP_FOLLOWERS_DAILY_REQUEST_LIMIT: "1",
  }, {
    store: followerStore({ example: { topFollowersEnrolledAt: now } }, {
      example: completedFollowerScan(now - 8 * DAY),
    }),
    onOutput: value => { output += value; },
    onStorePath: value => { checkpointPath = value; },
  });
  assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 202);
  await waitFor(() => output.includes("top_followers_daily_budget_reached"));
  assert.equal(calls, 1);
  const progress = await (await fetch(`${base}/history/example/top-followers/scan`)).json();
  assert.equal(progress.status, "running");
  assert.equal(progress.pages, 1);
  const stored = JSON.parse(await readFile(checkpointPath, "utf8"));
  assert.equal(stored.topFollowers.example.cursor, "saved-cursor");
  const cached = await (await fetch(`${base}/history/example/top-followers/all`)).json();
  assert.equal(cached.followers[0].username, "oldfan");
  const limited = await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" });
  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers.get("retry-after")) > 0);
  assert.equal(calls, 1);
});

test("one credit-empty response pauses other accounts before calling the provider", async (t) => {
  let calls = 0;
  const provider = http.createServer((req, res) => { calls += 1; res.writeHead(402); res.end(); });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => { provider.closeAllConnections(); provider.close(); });
  const base = await startBridge(t, {
    TWITTERAPIS_API_KEY: "test-key", TEST_MOCK_UPSTREAM: "0",
    TWITTERAPIS_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/followers`,
  }, { store: followerStore({ example: {}, another: {} }) });
  assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 202);
  await waitFor(async () => (await (await fetch(`${base}/history/example/top-followers/scan`)).json()).status === "failed");
  const blocked = await fetch(`${base}/history/another/top-followers/scan`, { method: "POST" });
  assert.equal(blocked.status, 429);
  assert.equal((await blocked.json()).error, "provider_credit_empty");
  assert.equal(calls, 1);
});

test("daily scans scale with +200 growth and use actual page yields, preserving the full cache", async t => {
  for (const pageSize of [50, 100]) {
    await t.test(`${pageSize} rows per page`, async t => {
      const old = Array.from({ length: 500 }, (_, i) => ({ id: String(i + 1), username: `fan${i + 1}`, name: "Cached", followers: 10, avatar: "", verified: false, mutual: null }));
      const fresh = Array.from({ length: 200 }, (_, i) => ({ id: String(i + 1001), username: `new${i}`, name: "New", followers_count: i === 199 ? 99999 : 20 }));
      const all = [...fresh, ...old.map(v => ({ ...v, followers_count: 777 }))];
      let calls = 0;
      const provider = http.createServer((req, res) => {
        calls += 1;
        const url = new URL(req.url, "http://localhost");
        assert.equal(url.searchParams.get("count"), "100");
        const offset = Number(url.searchParams.get("cursor") || 0);
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ users: all.slice(offset, offset + pageSize), next_cursor: String(offset + pageSize) }));
      });
      await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
      t.after(() => { provider.closeAllConnections(); provider.close(); });
      const now = Date.now();
      const store = followerStore({ example: { topFollowersEnrolledAt: now } }, {
        example: { ...completedFollowerScan(now - 2 * DAY), sourceFollowers: 1000, followers: old, scanned: old.length },
      });
      store.accounts.example = [{ ts: now, followers: 1200, followersKnown: true, src: "live" }];
      store.primaryProfiles = { ["b".repeat(64)]: { username: "example", lastSeenAt: now } };
      let checkpointPath;
      const base = await startBridge(t, {
        TWITTERAPIS_API_KEY: "test-key", TEST_MOCK_UPSTREAM: "0",
        TWITTERAPIS_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/followers`,
      }, { store, onStorePath: value => { checkpointPath = value; } });
      assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 202);
      await waitFor(async () => (await (await fetch(`${base}/history/example/top-followers/scan`)).json()).status === "complete");
      const archive = await (await fetch(`${base}/history/example/top-followers/all?limit=2000`)).json();
      assert.equal(archive.total, 700);
      assert.equal(archive.followers[0].username, "new199", "notable follower beyond the first 100 must be found");
      assert.equal(archive.fullCompletedAt, now - 2 * DAY, "daily updates cannot postpone the weekly refresh");
      assert.equal(archive.followers.find(v => v.username === "fan1").followers, 10, "cached counts retain their weekly refresh cadence");
      assert.equal(calls, 400 / pageSize);
      const saved = JSON.parse(await readFile(checkpointPath, "utf8")).topFollowers.example;
      assert.equal(saved.sourceFollowers, 1200);
      assert.equal(saved.processed, 400);
      assert.equal(saved.targetRows, 400);
      assert.ok(saved.knownStreak >= 100);
      assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 200);
      assert.equal(calls, 400 / pageSize);
    });
  }
});

test("invalid cadence settings fall back to safe defaults", async (t) => {
  const base = await startBridge(t, {
    TOP_FOLLOWERS_FULL_SCAN_DAYS: "91", TOP_FOLLOWERS_HEAD_ROWS: "0", TOP_FOLLOWERS_OVERLAP_ROWS: "-1",
  });
  const settings = (await (await fetch(`${base}/health`)).json()).history.topFollowers;
  assert.equal(settings.fullScanDays, 7);
  assert.equal(settings.headRows, 100);
  assert.equal(settings.headCheck, true);
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


test("legacy history remains unbound while fresh observations acquire separate identity proof", async t => {
  const day = Math.floor(Date.now() / DAY) * DAY;
  const history = [3, 2, 1].map((age, i) => ({ ts: day - age * DAY, followers: 10 + i, following: 1, posts: 2, likes: 3, src: 'live' }));
  let storePath;
  const base = await startBridge(t, {}, { store: { version: 4, accounts: { example: history }, meta: { example: {} } }, onStorePath: value => { storePath = value; } });
  const profile = await fetch(`${base}/user/example`).then(r => r.json());
  assert.equal(profile.userId, '123456');
  assert.equal(profile.historyUserId, '123456');
  assert.deepEqual(profile.history.map(row => row.followers), [1234]);
  const pooled = await fetch(`${base}/history/example`).then(r => r.json());
  assert.equal(pooled.userId, '123456');
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

test("excluded test accounts stop persisted scans before budget reset and cannot spend on any trigger", async t => {
  const now = Date.now();
  let storePath;
  const base = await startBridge(t, {
    TWITTERAPIS_API_KEY: "test-key", TOP_FOLLOWERS_EXCLUDED_USERNAMES: "@ELONMUSK,apple",
    TOP_FOLLOWERS_DAILY_REQUEST_LIMIT: "0", HISTORY_ADMIN_TOKEN: "operator", EXPENSIVE_RATE_LIMIT_MAX: "100",
  }, {
    store: followerStore({ elonmusk: { topFollowersEnrolledAt: now }, apple: { topFollowersEnrolledAt: now } }, {
      elonmusk: { ...completedFollowerScan(now), status: "running", completedAt: 0, cursor: "next" },
      apple: completedFollowerScan(now - 8 * DAY),
    }), onStorePath: value => { storePath = value; },
  });
  const scan = await fetch(`${base}/history/elonmusk/top-followers/scan`).then(r => r.json());
  assert.equal(scan.status, "failed");
  assert.equal(scan.error, "top_followers_account_excluded");
  for (const handle of ["ElonMusk", "apple"]) {
    const response = await fetch(`${base}/history/${handle}/top-followers/scan`, { method: "POST" });
    assert.equal(response.status, 403);
    assert.equal((await response.json()).error, "top_followers_account_excluded");
  }
  const cached = await fetch(`${base}/history/apple/top-followers`).then(r => r.json());
  assert.equal(cached.top[0].username, "oldfan");
  assert.equal((await fetch(`${base}/admin/top-followers/usage`)).status, 401);
  const usage = await fetch(`${base}/admin/top-followers/usage`, { headers: { Authorization: "Bearer operator" } }).then(r => r.json());
  assert.equal(usage.day.requests, 0);
  assert.deepEqual(usage.excludedUsernames, ["apple", "elonmusk"]);
  const stored = JSON.parse(await readFile(storePath, "utf8"));
  assert.equal(stored.accounts.elonmusk.length, 1);
  assert.equal(stored.topFollowers.apple.status, "complete");
});

test("real accounts retain scans and account-attributed allowance with exclusions configured", async t => {
  const now = Date.now();
  const base = await startBridge(t, {
    TWITTERAPIS_API_KEY: "test-key", TOP_FOLLOWERS_EXCLUDED_USERNAMES: "elonmusk", HISTORY_ADMIN_TOKEN: "operator",
    EXPENSIVE_RATE_LIMIT_MAX: "100",
  }, { store: followerStore({ howtomen: { topFollowersEnrolledAt: now } }) });
  assert.equal((await fetch(`${base}/history/howtomen/top-followers/scan`, { method: "POST" })).status, 202);
  await waitFor(async () => (await fetch(`${base}/history/howtomen/top-followers/scan`).then(r => r.json())).status === "complete");
  const usage = await fetch(`${base}/admin/top-followers/usage`, { headers: { Authorization: "Bearer operator" } }).then(r => r.json());
  assert.equal(usage.day.requests, 1);
  assert.equal(usage.day.byAccount.howtomen, 1);
  assert.equal(usage.day.unattributed, 0);
});

test("tracked cache keeps weekly cadence and a primary report immediately enables daily checks", async t => {
  const now = Date.now();
  const base = await startBridge(t, { TWITTERAPIS_API_KEY: "test-key", HISTORY_ADMIN_TOKEN: "operator", EXPENSIVE_RATE_LIMIT_MAX: "100" }, {
    store: followerStore({ example: { topFollowersEnrolledAt: now } }, { example: completedFollowerScan(now - 2 * DAY) }),
  });
  const settings = await fetch(`${base}/health`).then(r => r.json()).then(v => v.history.topFollowers);
  assert.equal(settings.trackedRefreshHours, 168);
  assert.equal(settings.trackedFullScanDays, 30);
  assert.equal(settings.trackedDailyRequestLimit, 250);
  assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 200);
  assert.equal((await fetch(`${base}/history/example/top-followers/scan`).then(r => r.json())).completedAt, now - 2 * DAY);
  const report = await fetch(`${base}/installation/primary-profile`, { method: "POST", headers: {
    "Content-Type": "application/json", "X-Twidget-Install-Key": "a".repeat(64),
  }, body: JSON.stringify({ username: "example" }) });
  assert.equal(report.status, 204);
  assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 202);
  await waitFor(async () => (await fetch(`${base}/history/example/top-followers/scan`).then(r => r.json())).completedAt > now);
  const usage = await fetch(`${base}/admin/top-followers/usage`, { headers: { Authorization: "Bearer operator" } }).then(r => r.json());
  assert.equal(usage.tracked.requests, 0);
  assert.equal(usage.primary.requests, 1);
});

test("tracked resumes stop at their ceiling while primaries use the protected allowance", async t => {
  const now = Date.now();
  const running = { ...completedFollowerScan(now), status: "running", completedAt: 0, cursor: "", pages: 0, scanned: 0, followers: [] };
  const store = followerStore({ first: {}, second: {}, primary: {}, cached: {} }, { first: running, second: running, primary: completedFollowerScan(now - 2 * DAY), cached: completedFollowerScan(now - 2 * DAY) });
  store.primaryProfiles = { ["b".repeat(64)]: { username: "primary", lastSeenAt: now } };
  const base = await startBridge(t, { TWITTERAPIS_API_KEY: "test-key", HISTORY_ADMIN_TOKEN: "operator",
    TOP_FOLLOWERS_DAILY_REQUEST_LIMIT: "10", TOP_FOLLOWERS_TRACKED_DAILY_REQUEST_LIMIT: "1", EXPENSIVE_RATE_LIMIT_MAX: "100",
  }, { store });
  await fetch(`${base}/history/first/top-followers/scan`);
  await waitFor(async () => (await fetch(`${base}/history/first/top-followers/scan`).then(r => r.json())).status === "complete");
  const denied = await fetch(`${base}/history/second/top-followers/scan`, { method: "POST" });
  assert.equal(denied.status, 429);
  assert.equal((await denied.json()).error, "top_followers_tracked_daily_budget_reached");
  assert.equal((await fetch(`${base}/history/cached/top-followers/scan`, { method: "POST" })).status, 200);
  const second = await fetch(`${base}/history/second/top-followers/scan`).then(r => r.json());
  assert.equal(second.status, "running"); assert.equal(second.pages, 0);
  assert.equal((await fetch(`${base}/history/primary/top-followers/scan`, { method: "POST" })).status, 202);
  await waitFor(async () => (await fetch(`${base}/history/primary/top-followers/scan`).then(r => r.json())).status === "complete");
  const usage = await fetch(`${base}/admin/top-followers/usage`, { headers: { Authorization: "Bearer operator" } }).then(r => r.json());
  assert.equal(usage.day.requests, 2); assert.equal(usage.tracked.requests, 1); assert.equal(usage.primary.requests, 1);
});

test("scheduler prioritises primary full scans over tracked head checks and uses separate full-scan clocks", async t => {
  const now = Date.now(), calls = [];
  const provider = http.createServer((req, res) => {
    calls.push(new URL(req.url, "http://localhost").searchParams.get("username"));
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ users: [{ id: "42", username: "freshfan", followers_count: 10 }] }));
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => { provider.closeAllConnections(); provider.close(); });
  const store = followerStore({ tracked: { topFollowersLastSeenAt: now }, primary: { topFollowersLastSeenAt: now - 20 * DAY } }, {
    tracked: completedFollowerScan(now - 8 * DAY), primary: completedFollowerScan(now - 8 * DAY),
  });
  store.primaryProfiles = { ["b".repeat(64)]: { username: "primary", lastSeenAt: now } };
  let output = "", storePath;
  await startBridge(t, { TWITTERAPIS_API_KEY: "test-key", TEST_MOCK_UPSTREAM: "0", TOP_FOLLOWERS_MAX_CONCURRENT: "1",
    TWITTERAPIS_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/followers`,
  }, { store, onOutput: value => { output += value; }, onStorePath: value => { storePath = value; } });
  await waitFor(() => calls.length >= 2 && output.includes("scan completed for tracked"), 20000);
  assert.deepEqual(calls, ["primary", "tracked"]);
  const saved = JSON.parse(await readFile(storePath, "utf8"));
  assert.equal(saved.topFollowers.primary.kind, "full");
  assert.equal(saved.topFollowers.tracked.kind, "head");
  assert.equal(saved.topFollowers.tracked.fullCompletedAt, now - 8 * DAY);
});

test("a primary request queues behind busy tracked work and starts at the next ten-page yield", async t => {
  const now = Date.now(), calls = [];
  let releaseFirst;
  const provider = http.createServer((req, res) => {
    const url = new URL(req.url, "http://localhost"), username = url.searchParams.get("username");
    const offset = Number(url.searchParams.get("cursor") || 0);
    calls.push(username);
    const respond = () => {
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ users: [{ id: String(offset + 100), username: `fan${offset}`, followers_count: 10 }],
        ...(username === "tracked" ? { next_cursor: String(offset + 1) } : {}) }));
    };
    if (calls.length === 1) releaseFirst = respond;
    else respond();
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => { provider.closeAllConnections(); provider.close(); });
  const store = followerStore({ tracked: { topFollowersEnrolledAt: now }, primary: { topFollowersEnrolledAt: now } }, {
    tracked: { ...completedFollowerScan(now), status: "running", completedAt: 0, cursor: "", pages: 0, scanned: 0, followers: [] },
  });
  store.primaryProfiles = { ["b".repeat(64)]: { username: "primary", lastSeenAt: now } };
  const base = await startBridge(t, { TWITTERAPIS_API_KEY: "test-key", TEST_MOCK_UPSTREAM: "0", TOP_FOLLOWERS_MAX_CONCURRENT: "1",
    TOP_FOLLOWERS_DAILY_REQUEST_LIMIT: "20", TOP_FOLLOWERS_TRACKED_DAILY_REQUEST_LIMIT: "11", EXPENSIVE_RATE_LIMIT_MAX: "100",
    TWITTERAPIS_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/followers`,
  }, { store });
  await fetch(`${base}/history/tracked/top-followers/scan`);
  await waitFor(() => releaseFirst);
  assert.equal((await fetch(`${base}/history/primary/top-followers/scan`, { method: "POST" })).status, 202);
  releaseFirst();
  await waitFor(() => calls.includes("primary"));
  assert.deepEqual(calls.slice(0, 11), [...Array(10).fill("tracked"), "primary"]);
  await waitFor(async () => (await fetch(`${base}/history/tracked/top-followers/scan`).then(r => r.json())).pages === 11);
  const progress = await fetch(`${base}/history/tracked/top-followers/scan`).then(r => r.json());
  assert.equal(progress.status, "running");
});

test("legacy enrollment verifies the requested ID without assigning old rows to it", async t => {
  const day = Math.floor(Date.now() / DAY) * DAY;
  let storePath;
  const base = await startBridge(t, {}, { store: { version: 4, accounts: { example: [{ ts: day-DAY, followers: 7, src: 'live' }] }, meta: { example: {} } }, onStorePath: value => { storePath = value; } });
  assert.equal((await fetch(`${base}/history/example?userId=999`)).status, 409);
  const response = await fetch(`${base}/history/example?userId=123456`);
  assert.equal(response.status, 200);
  const result = await response.json();
  assert.equal(result.userId, '123456');
  assert.deepEqual(result.history.map(row => row.followers), [1234]);
  assert.equal(result.legacyHistoryIdentity, 'handle-only');
  assert.ok(result.legacyHistory.some(row => row.followers === 7));
  await waitFor(async () => JSON.parse(await readFile(storePath, 'utf8')).meta.example.verifiedUserId === '123456');
  const stored = JSON.parse(await readFile(storePath, 'utf8'));
  assert.equal(stored.meta.example.userId, undefined);
  assert.ok(stored.accounts.example.some(row => row.followers === 7));
});

test("Fx follower scans need no key, use immutable IDs, and publish interval losses", async t => {
  const requests = [];
  const provider = http.createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    requests.push({ path: url.pathname, cursor: url.searchParams.get("cursor"), auth: req.headers.authorization });
    const cursor = url.searchParams.get("cursor");
    const id = cursor ? "3" : "1";
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ code: 200, results: cursor === "fx-end" ? [] : [{ id, screen_name: `fan${id}`, name: "Fan", followers: 10 }],
      cursor: { top: null, bottom: cursor ? "fx-end" : "fx-next" } }));
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => { provider.closeAllConnections(); provider.close(); });
  const now = Date.now();
  const before = { ...completedFollowerScan(now - 8 * DAY), provider: "fxtwitter", userId: "123", kind: "full",
    scanned: 2, followers: ["1", "2"].map(id => ({ id, username: `fan${id}`, name: "Fan", followers: 10, avatar: "", verified: false })) };
  const store = followerStore({ example: { userId: "123" } }, { example: before });
  store.primaryProfiles = { ["a".repeat(64)]: { username: "example", lastSeenAt: now } };
  const base = await startBridge(t, { TOP_FOLLOWERS_PROVIDER: "fxtwitter", TWITTERAPIS_API_KEY: "", TEST_MOCK_UPSTREAM: "0",
    FXTWITTER_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/profile` },
    { store });
  assert.equal((await fetch(`${base}/health`).then(r => r.json())).history.topFollowers.provider, "fxtwitter");
  assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 202);
  await waitFor(async () => (await fetch(`${base}/history/example/top-followers/scan`).then(r => r.json())).status === "complete");
  assert.deepEqual(requests, [
    { path: "/profile/id:123/followers", cursor: null, auth: undefined },
    { path: "/profile/id:123/followers", cursor: "fx-next", auth: undefined },
    { path: "/profile/id:123/followers", cursor: "fx-end", auth: undefined },
  ]);
  const history = await fetch(`${base}/history/example`).then(r => r.json());
  assert.equal(history.followerChanges.length, 1, JSON.stringify(history));
  assert.equal(history.followerChanges[0].lostFollowers, 1);
  assert.equal(history.followerChanges[0].newFollowers, 1);
  assert.equal(history.history[0].unfollows, undefined);
});

test("Fx failure after migration retains the paid snapshot and never reports its followers as lost", async t => {
  const requests = [];
  const provider = http.createServer((req, res) => { requests.push(req.url); res.writeHead(404).end('{}'); });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => { provider.closeAllConnections(); provider.close(); });
  const now = Date.now();
  const old = { ...completedFollowerScan(now - 60_000), userId: "123" };
  const running = { ...old, scanId: "paid-running", status: "running", cursor: "paid-cursor", previous: old };
  const base = await startBridge(t, { TOP_FOLLOWERS_PROVIDER: "fxtwitter", TWITTERAPIS_API_KEY: "", TEST_MOCK_UPSTREAM: "0",
    FXTWITTER_FOLLOWERS_ENDPOINT: `http://127.0.0.1:${provider.address().port}/profile` },
    { store: followerStore({ example: { userId: "123" } }, { example: running }) });
  assert.equal((await fetch(`${base}/history/example/top-followers/scan`, { method: "POST" })).status, 202);
  await waitFor(async () => (await fetch(`${base}/history/example/top-followers/scan`).then(r => r.json())).status === "failed");
  assert.equal(requests.length, 1);
  assert.ok(!requests[0].includes("paid-cursor"));
  const archive = await fetch(`${base}/history/example/top-followers/all`).then(r => r.json());
  assert.equal(archive.total, 1);
  assert.deepEqual((await fetch(`${base}/history/example`).then(r => r.json())).followerChanges, []);
});

test("CSV validation fetches a fresh ID-bound profile and rejects another account", async t => {
  const now = Date.now();
  const yesterday = new Date(now - DAY).toISOString().slice(0, 10);
  const today = new Date(now).toISOString().slice(0, 10);
  const store = followerStore({ example: { userId: "123456" }, wrong: { userId: "999" } });
  for (const key of ["example", "wrong"]) store.accounts[key] = [
    { ts: Date.parse(`${yesterday}T00:00:00Z`), followers: 1234, followersKnown: true, src: "live" },
  ];
  const base = await startBridge(t, {}, { store });
  const payload = JSON.stringify({ movements: [yesterday, today].map(date => ({ date, newFollows: 1, unfollows: 1 })) });
  const send = key => fetch(`${base}/history/${key}/analytics-import`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: payload,
  });
  const valid = await send("example");
  assert.equal(valid.status, 200, await valid.clone().text());
  assert.equal((await valid.json()).checkedAnchors, 1);
  const invalid = await send("wrong");
  assert.equal(invalid.status, 409);
  assert.equal((await invalid.json()).error, "history_identity_mismatch");
});

test("profile refresh, weekly analytics, archive backfill and Banger all prefer known Fx IDs", async t => {
  const paths = [];
  const provider = http.createServer((req, res) => {
    const path = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    paths.push(path);
    res.setHeader("Content-Type", "application/json");
    const id = path.includes("id:456") || path === "/2/profile/fresh" ? "456"
      : path.includes("id:789") ? "789" : path.includes("id:321") ? "321" : path.includes("id:654") ? "654" : "123";
    const name = id === "456" ? "fresh" : id === "789" ? "remote" : id === "321" ? "enroll" : id === "654" ? "moved" : "example";
    if (path.endsWith("/statuses")) {
      assert.match(path, /\/2\/profile\/id:[0-9]+\/statuses$/);
      return res.end(JSON.stringify({ code: 200, results: [{ type: "status", id: "101", url: `https://x.com/${name}/status/101`,
        author: { id, screen_name: name }, created_timestamp: Math.floor(Date.now() / 1000) - 3600,
        text: "Sample post", likes: 5, views: 100, replies: 0, reposts: 0, quotes: 0 }], cursor: { bottom: null } }));
    }
    res.end(JSON.stringify({ code: 200, user: { id, screen_name: name, name, followers: 50,
      following: 10, statuses: 5, likes: 2, protected: false, verification: { verified: false } } }));
  });
  await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
  t.after(() => { provider.closeAllConnections(); provider.close(); });
  const base = await startBridge(t, { TEST_MOCK_UPSTREAM: "0", FXTWITTER_BASE_URL: `http://127.0.0.1:${provider.address().port}` },
    { store: followerStore({ example: { userId: "123" }, oldname: { userId: "654" } }) });
  const profile = await fetch(`${base}/user/example`).then(r => r.json());
  assert.equal(profile.userId, "123");
  for (const [path, method] of [["/analytics/example", "GET"], ["/banger/example", "GET"], ["/analytics/example/backfill", "POST"]]) {
    const response = await fetch(base + path, { method });
    assert.equal(response.status, 200, `${path}: ${await response.clone().text()}`);
  }
  const fresh = await fetch(`${base}/analytics/fresh`).then(r => r.json());
  assert.equal(fresh.postCount, 1, "A just-discovered account passes its ID to the first weekly timeline request");
  const remote = await fetch(`${base}/user/remote?userId=789`).then(r => r.json());
  assert.equal(remote.userId, "789", "An app-provided ID works before the bridge has cached the profile");
  const enrolled = await fetch(`${base}/history/enroll?userId=321`).then(r => r.json());
  assert.equal(enrolled.userId, "321", "First shared-history enrollment uses the app's ID");
  const moved = await fetch(`${base}/history/oldname/handle`, { method: "POST",
    headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username: "moved", userId: "654" }) });
  assert.equal(moved.status, 200, await moved.clone().text());
  assert.ok(paths.includes("/2/profile/id:321"));
  assert.ok(paths.includes("/2/profile/id:654"));
  const wrong = await fetch(`${base}/user/example?userId=999`);
  assert.equal(wrong.status, 502, "A mismatched ID cannot receive another account's cached profile");
  assert.ok(paths.includes("/2/profile/id:123/statuses"));
  assert.ok(paths.includes("/2/profile/id:456/statuses"));
  assert.ok(paths.includes("/2/profile/id:789"));
  assert.ok(!paths.includes("/2/profile/example"));
  assert.ok(!paths.includes("/example"));
  assert.equal((await fetch(`${base}/user/example?userId=invalid`)).status, 400);
});
