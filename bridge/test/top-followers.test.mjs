import assert from "node:assert/strict";
import test from "node:test";
import { createTopFollowersActivityRecorder, prepareTopFollowersCache, shouldRefreshTopFollowers, topFollowersRefreshKind, topFollowersScanPlan, topFollowersHeadComplete } from "../src/top-followers.js";

test("growth scales rows instead of assuming the provider honors count=100", () => {
  const plan = topFollowersScanPlan({ kind: "head", snapshot: { sourceFollowers: 1000 }, followersCount: 1200 });
  assert.equal(plan.targetRows, 400);
  assert.equal(topFollowersHeadComplete({ ...plan, processed: 399, knownStreak: 200 }), false);
  assert.equal(topFollowersHeadComplete({ ...plan, processed: 400, knownStreak: 99 }), false);
  assert.equal(topFollowersHeadComplete({ ...plan, processed: 400, knownStreak: 100 }), true);
  for (const followersCount of [1000, 950, null]) {
    const churn = topFollowersScanPlan({ kind: "head", snapshot: { sourceFollowers: 1000 }, followersCount });
    assert.equal(churn.targetRows, 100);
    assert.equal(topFollowersHeadComplete({ ...churn, processed: 300, knownStreak: 0 }), false);
  }
  assert.equal(topFollowersScanPlan({ kind: "head", snapshot: { total: 500 }, followersCount: 700 }).targetRows, 400);
});

test("page-limited full crawls are not automatically purchased again", () => {
  const scan = { status: "failed", error: "top_followers_page_limit_reached", retryAt: 1 };
  assert.equal(shouldRefreshTopFollowers({ scan, now: 1e12 }), false);
  assert.equal(topFollowersRefreshKind({ scan, now: 1e12 }), null);
});

test("Top Followers activity writes are hourly, per account, and preserve metadata", async () => {
  const hour = 60 * 60 * 1000;
  let now = 10 * hour;
  const metadata = new Map([["example", { topFollowersEnrolledAt: 1, banger: { score: 42 } }], ["other", {}]]);
  const writes = [];
  const recordActivity = createTopFollowersActivityRecorder({
    async hasAccount(key) { return metadata.has(key); },
    async getMeta(key) { return metadata.get(key); },
    async recordTopFollowersActivity(key, timestamp) {
      if (!metadata.has(key)) return false;
      writes.push(key);
      metadata.set(key, { ...metadata.get(key), topFollowersLastSeenAt: timestamp });
      return true;
    },
  }, () => now);

  await Promise.all([recordActivity("example"), recordActivity("example")]);
  assert.deepEqual(writes, ["example"]);
  assert.deepEqual(metadata.get("example"), {
    topFollowersEnrolledAt: 1, banger: { score: 42 }, topFollowersLastSeenAt: now,
  });
  now += hour / 4;
  await recordActivity("example");
  await recordActivity("other");
  await recordActivity("missing");
  assert.deepEqual(writes, ["example", "other"]);
  now += hour * 3 / 4;
  await recordActivity("example");
  assert.deepEqual(writes, ["example", "other", "example"]);
  assert.equal(metadata.get("example").topFollowersLastSeenAt, now);
});

test("failed activity writes can be retried", async () => {
  let attempts = 0;
  const recordActivity = createTopFollowersActivityRecorder({
    async hasAccount() { return true; },
    async getMeta() { return {}; },
    async recordTopFollowersActivity() { if (++attempts === 1) throw new Error("storage unavailable"); return true; },
  });
  await assert.rejects(recordActivity("example"), /storage unavailable/);
  await recordActivity("example");
  assert.equal(attempts, 2);
});

test("normalizes a bounded completed Top Followers result", () => {
  const cached = prepareTopFollowersCache({
    scanned: 250,
    pages: 5,
    top: [{
      id: "42",
      username: "Example",
      name: "Example User",
      followers: 1234,
      verified: true,
      avatar: "https://example.com/avatar.jpg",
    }],
  }, 123456);

  assert.deepEqual(cached, {
    version: 1,
    cachedAt: 123456,
    scanned: 250,
    pages: 5,
    top: [{
      id: "42",
      username: "Example",
      name: "Example User",
      followers: 1234,
      verified: true,
      avatar: "https://example.com/avatar.jpg",
    }],
  });
});

test("rejects malformed, duplicate, or unbounded shared rankings", () => {
  const valid = { id: "1", username: "Example", name: "Example", followers: 1, avatar: "" };
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [{ ...valid, username: "not valid" }] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [{ ...valid, avatar: "http://example.com/a" }] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 2, pages: 1, top: [valid, valid] }), null);
});

test("daily refreshes run once per cadence and do not duplicate running scans", () => {
  const day = 24 * 60 * 60 * 1000;
  const now = 10 * day;
  assert.equal(shouldRefreshTopFollowers({ snapshot: null, scan: null, now, refreshMs: day }), true);
  assert.equal(shouldRefreshTopFollowers({
    snapshot: { completedAt: now - day + 1 }, scan: null, now, refreshMs: day,
  }), false);
  assert.equal(shouldRefreshTopFollowers({
    snapshot: { completedAt: now - day }, scan: null, now, refreshMs: day,
  }), true);
  assert.equal(shouldRefreshTopFollowers({
    snapshot: null, scan: { status: "failed", startedAt: now - 1000 }, now, refreshMs: day,
  }), false);
  assert.equal(shouldRefreshTopFollowers({
    snapshot: null, scan: { status: "running", startedAt: now - day * 2 }, now, refreshMs: day,
  }), false);
});


test("weekly full scans are the default for scheduled, explicit, and returning work", () => {
  const day = 24 * 60 * 60 * 1000;
  const now = 100 * day;
  for (const age of [0, 1, 2, 6]) {
    assert.equal(topFollowersRefreshKind({ snapshot: { completedAt: now - age * day }, now }), null);
  }
  assert.equal(topFollowersRefreshKind({ snapshot: { completedAt: now - 7 * day }, now }), "full");
  assert.equal(topFollowersRefreshKind({ snapshot: { cachedAt: now - 6 * day }, now }), null);
  assert.equal(topFollowersRefreshKind({ snapshot: { cachedAt: now - 8 * day }, now }), "full");
  assert.equal(topFollowersRefreshKind({ now }), "full");
  for (const days of [1, 90]) {
    const fullScanMs = days * day;
    assert.equal(topFollowersRefreshKind({ snapshot: { completedAt: now - fullScanMs + 1 }, now, fullScanMs }), null);
    assert.equal(topFollowersRefreshKind({ snapshot: { completedAt: now - fullScanMs }, now, fullScanMs }), "full");
  }
});

test("opt-in head checks follow the legacy refresh cadence without postponing full scans", () => {
  const day = 24 * 60 * 60 * 1000;
  const now = 100 * day;
  const decide = (options) => topFollowersRefreshKind({ now, headCheck: true, ...options });
  assert.equal(decide({ snapshot: { completedAt: now - day + 1 } }), null);
  assert.equal(decide({ snapshot: { completedAt: now - day } }), "head");
  assert.equal(decide({ snapshot: { completedAt: now - day }, refreshMs: 2 * day }), null);
  assert.equal(decide({ snapshot: { completedAt: now - 2 * day }, refreshMs: 2 * day }), "head");
  assert.equal(decide({ snapshot: { completedAt: now - day, fullCompletedAt: now - 7 * day, kind: "head" } }), "full");
  assert.equal(decide({ snapshot: { completedAt: now, fullCompletedAt: now - 7 * day, kind: "head" } }), "full");
  assert.equal(decide({ snapshot: { completedAt: now - day }, fullScanMs: day, refreshMs: 7 * day }), "full");
  assert.equal(decide({}), "full", "initial work always builds a complete snapshot");
});

test("running scans suppress starts and failed attempts keep their retry cooldown", () => {
  const day = 24 * 60 * 60 * 1000;
  const now = 100 * day;
  for (const headCheck of [false, true]) {
    const snapshot = { completedAt: now - 8 * day };
    const decide = (scan) => topFollowersRefreshKind({ snapshot, scan, now, headCheck });
    assert.equal(decide({ status: "running", startedAt: now - 8 * day }), null);
    assert.equal(decide({ status: "failed", startedAt: now - day + 1 }), null);
    assert.equal(decide({ status: "failed", startedAt: now - day }), "full");
    assert.equal(topFollowersRefreshKind({ scan: { status: "failed", startedAt: now - 1000 }, now, headCheck }), null);
  }
});
