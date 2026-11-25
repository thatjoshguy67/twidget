import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { TopFollowersBudget } from "../src/top-followers-budget.js";
import { benchmarkTopFollowersPageSize } from "../src/top-followers-benchmark.js";

for (const honorsCount of [false, true]) {
  test(`benchmark measures actual rows when count=100 is ${honorsCount ? "honored" : "ignored"}`, async t => {
    const dir = await mkdtemp(path.join(os.tmpdir(), "followers-benchmark-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const budget = new TopFollowersBudget({ filePath: path.join(dir, "budget.json") });
    await budget.initialize();
    let calls = 0;
    const fetchPage = async (cursor, count) => {
      await budget.reserve();
      calls += 1;
      return { users: Array.from({ length: honorsCount && count === 100 ? 100 : 50 }), nextCursor: String(Number(cursor || 0) + 1) };
    };
    const result = await benchmarkTopFollowersPageSize({ budget, fetchPage });
    assert.equal(result.status, "complete");
    assert.deepEqual(result.baseline, [50, 50, 50]);
    assert.deepEqual(result.count100, honorsCount ? [100, 100, 100] : [50, 50, 50]);
    assert.equal(result.observedRequestReduction, honorsCount ? 0.5 : 0);
    assert.equal(calls, 6);
    assert.equal((await budget.status()).dayRequests, 6);
    await benchmarkTopFollowersPageSize({ budget, fetchPage });
    assert.equal(calls, 6, "completed benchmark is not billed again on restart");
  });
}

test("benchmark stops at the shared budget and resumes its samples after reset", async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "followers-benchmark-pause-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  let now = Date.UTC(2026, 9, 17, 23);
  const budget = new TopFollowersBudget({ filePath: path.join(dir, "budget.json"), dailyLimit: 2, now: () => now });
  await budget.initialize();
  let calls = 0;
  const fetchPage = async cursor => {
    await budget.reserve();
    calls += 1;
    return { users: Array.from({ length: 50 }), nextCursor: String(Number(cursor || 0) + 1) };
  };
  const run = () => benchmarkTopFollowersPageSize({ budget, fetchPage, now: () => now });
  const first = await run();
  assert.equal(first.status, "waiting");
  assert.equal(first.reason, "top_followers_daily_budget_reached");
  assert.equal(calls, 2);
  await run();
  assert.equal(calls, 2);
  now += 86_400_000;
  await run();
  assert.equal(calls, 4);
  now += 86_400_000;
  assert.equal((await run()).status, "complete");
  assert.equal(calls, 6);
});
