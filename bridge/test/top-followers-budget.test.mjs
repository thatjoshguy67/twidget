import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { TopFollowersBudget } from "../src/top-followers-budget.js";

async function ledger(t, options = {}) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "followers-budget-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  let now = Date.UTC(2026, 9, 17, 12);
  const config = { filePath: path.join(directory, "budget.json"), now: () => now, ...options };
  const budget = new TopFollowersBudget(config);
  await budget.initialize();
  return { budget, config, advance: (ms) => { now += ms; } };
}

test("concurrent reservations never exceed the daily budget, including after restart", async t => {
  const { budget, config, advance } = await ledger(t, { dailyLimit: 3 });
  const results = await Promise.allSettled(Array.from({ length: 20 }, () => budget.reserve()));
  assert.equal(results.filter(r => r.status === "fulfilled").length, 3);
  const restarted = new TopFollowersBudget(config);
  await restarted.initialize();
  await assert.rejects(restarted.reserve(), { message: "top_followers_daily_budget_reached" });
  advance(86_400_000);
  await restarted.reserve();
  assert.equal((await restarted.status()).dayRequests, 1);
  assert.equal((await restarted.status()).monthRequests, 4);
});

test("monthly ceiling survives daily resets and resets only in the next calendar month", async t => {
  const { budget, advance } = await ledger(t, { dailyLimit: 2, monthlyLimit: 3 });
  await budget.reserve();
  await budget.reserve();
  advance(86_400_000);
  await budget.reserve();
  await assert.rejects(budget.reserve(), { message: "top_followers_monthly_budget_reached" });
  advance(86_400_000);
  await assert.rejects(budget.reserve(), { message: "top_followers_monthly_budget_reached" });
  advance(31 * 86_400_000);
  await budget.reserve();
  assert.equal((await budget.status()).monthRequests, 1);
});

test("credit failures pause all accounts without spending more request units", async t => {
  const { budget, config, advance } = await ledger(t);
  await budget.reserve();
  await budget.pause("provider_credit_empty");
  const restarted = new TopFollowersBudget(config);
  await restarted.initialize();
  await assert.rejects(restarted.reserve(), { message: "provider_credit_empty" });
  advance(3_600_000);
  await restarted.reserve();
  assert.equal((await restarted.status()).dayRequests, 2);
});

test("zero budget disables paid requests and corrupt accounting fails closed", async t => {
  const { budget, config } = await ledger(t, { dailyLimit: 0 });
  await assert.rejects(budget.reserve(), { message: "top_followers_daily_budget_reached" });
  await writeFile(config.filePath, "corrupt");
  await assert.rejects(budget.reserve(), SyntaxError);
});

test("operator benchmark retry clears a credit cooldown once without resetting spend", async t => {
  const { budget } = await ledger(t, { dailyLimit: 2 });
  await budget.reserve();
  await budget.pause("provider_credit_empty");
  await budget.retryBenchmark("topup-one");
  assert.equal((await budget.status()).dayRequests, 1);
  await budget.pause("provider_credit_empty");
  await budget.retryBenchmark("topup-one");
  assert.equal((await budget.status()).reason, "provider_credit_empty", "the same ID cannot repeatedly clear the circuit");
  await budget.retryBenchmark("topup-two");
  await budget.reserve();
  await budget.retryBenchmark("topup-three");
  await assert.rejects(budget.reserve(), { message: "top_followers_daily_budget_reached" });
});

test("account attribution is atomic, survives restart, and preserves pre-existing spend", async t => {
  const { budget, config, advance } = await ledger(t, { dailyLimit: 5 });
  await budget.reserve(); // Spend from before account attribution was available.
  const attempts = await Promise.allSettled(Array.from({ length: 10 }, () => budget.reserve("HowToMen")));
  assert.equal(attempts.filter(value => value.status === "fulfilled").length, 4);
  const restarted = new TopFollowersBudget(config);
  await restarted.initialize();
  let usage = await restarted.usage();
  assert.equal(usage.day.requests, 5);
  assert.equal(usage.day.byAccount.howtomen, 4);
  assert.equal(usage.day.unattributed, 1);
  advance(86_400_000);
  usage = await restarted.usage();
  assert.deepEqual(usage.day.byAccount, {});
  assert.equal(usage.month.byAccount.howtomen, 4);
  await restarted.reserve("__proto__");
  assert.equal((await restarted.usage()).day.byAccount.__proto__, 1);
  advance(31 * 86_400_000);
  assert.deepEqual((await restarted.usage()).month.byAccount, {});
  await assert.rejects(restarted.reserve("bad-handle"));
  assert.equal((await restarted.usage()).day.requests, 0);
});

test("tracked reservations cannot consume the protected primary allowance, including after restart and reset", async t => {
  const { budget, config, advance } = await ledger(t, { dailyLimit: 10, trackedDailyLimit: 3 });
  const results = await Promise.allSettled(Array.from({ length: 15 }, () => budget.reserve("tracked")));
  assert.equal(results.filter(r => r.status === "fulfilled").length, 3);
  assert.equal((await budget.status({ primary: false })).reason, "top_followers_tracked_daily_budget_reached");
  assert.equal((await budget.status({ primary: true })).allowed, true);
  const restarted = new TopFollowersBudget(config);
  await restarted.initialize();
  await assert.rejects(restarted.reserve("tracked"), { message: "top_followers_tracked_daily_budget_reached" });
  for (let i = 0; i < 7; i++) await restarted.reserve("primary", { primary: true });
  await assert.rejects(restarted.reserve("primary", { primary: true }), { message: "top_followers_daily_budget_reached" });
  const usage = await restarted.usage();
  assert.equal(usage.day.requests, 10);
  assert.deepEqual(usage.tracked, { requests: 3, limit: 3 });
  assert.equal(usage.primary.requests, 7);
  advance(86400000);
  await restarted.reserve("tracked");
  assert.equal((await restarted.usage()).tracked.requests, 1);
  assert.equal((await restarted.usage()).month.requests, 11);
});

test("introducing tracked limits counts previous spend conservatively without resetting either ceiling", async t => {
  const { budget, config } = await ledger(t, { dailyLimit: 10, trackedDailyLimit: 3 });
  await writeFile(config.filePath, JSON.stringify({ day: "2026-10-17", month: "2026-10", dayRequests: 5, monthRequests: 100 }));
  await assert.rejects(budget.reserve("tracked"), { message: "top_followers_tracked_daily_budget_reached" });
  await budget.reserve("primary", { primary: true });
  const usage = await budget.usage();
  assert.equal(usage.day.requests, 6);
  assert.equal(usage.month.requests, 101);
  assert.equal(usage.tracked.requests, 5);
  assert.equal(usage.primary.requests, 1);
});
