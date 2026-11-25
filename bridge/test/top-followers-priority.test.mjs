import test from "node:test";
import assert from "node:assert/strict";
import { TopFollowersPriority } from "../src/top-followers-priority.js";
const DAY = 86400000;

test("recent reported primaries get daily/weekly cadence; other handles get weekly/monthly cadence", async () => {
  const now = 60 * DAY;
  const policy = new TopFollowersPriority({ now: () => now, repository: { primaryProfileCounts: async () => [
    { username: "PRIMARY", active30d: 1 }, { username: "oldprimary", active30d: 0 },
  ] } });
  await policy.refresh();
  assert.equal(policy.isPrimary("primary"), true);
  assert.equal(policy.isPrimary("oldprimary"), false);
  const snapshot = { completedAt: now - 2 * DAY, fullCompletedAt: now - 8 * DAY };
  assert.equal(policy.refreshKind("primary", snapshot), "full");
  assert.equal(policy.refreshKind("tracked", snapshot), null);
  assert.equal(policy.refreshKind("tracked", { ...snapshot, completedAt: now - 7 * DAY }), "head");
  assert.equal(policy.refreshKind("tracked", { ...snapshot, fullCompletedAt: now - 30 * DAY }), "full");
  assert.equal(policy.refreshKind("primary", { ...snapshot, fullCompletedAt: now - 3 * DAY }), "head");
  assert.equal(policy.refreshKind("tracked", null, { status: "failed", updatedAt: now - DAY, retryAt: now - 1 }), null);
});

test("primary full scans precede tracked head checks; head checks win within each tier and yields rotate fairly", async () => {
  const policy = new TopFollowersPriority({ repository: { primaryProfileCounts: async () => [
    { username: "primary", active30d: 1 }, { username: "primaryhead", active30d: 1 },
  ] } });
  await policy.refresh();
  const queue = new Map([
    ["trackedhead", { scan: { kind: "head" } }], ["primary", { scan: { kind: "full" } }],
    ["trackedfull", { scan: { kind: "full" } }], ["primaryhead", { scan: { kind: "head" } }],
  ]);
  const order = [];
  while (queue.size) { const [key] = policy.next(queue); order.push(key); queue.delete(key); }
  assert.deepEqual(order, ["primaryhead", "primary", "trackedhead", "trackedfull"]);
  const round = new Map([["one", { scan: { kind: "full" } }], ["two", { scan: { kind: "full" } }]]);
  const [key, value] = policy.next(round);
  round.delete(key); round.set(key, value);
  assert.equal(policy.next(round)[0], "two");
});

test("report changes invalidate cached priorities and stale reports expire on the next refresh", async () => {
  let now = DAY;
  let rows = [];
  let calls = 0;
  const policy = new TopFollowersPriority({ now: () => now, repository: { primaryProfileCounts: async () => { calls++; return rows; } } });
  await Promise.all([policy.refresh(), policy.refresh()]);
  assert.equal(calls, 1);
  rows = [{ username: "newprimary", active30d: 1 }];
  policy.invalidate();
  await policy.refresh();
  assert.equal(policy.isPrimary("newprimary"), true);
  rows = [];
  now += 61000;
  await policy.refresh();
  assert.equal(policy.isPrimary("newprimary"), false);
});
