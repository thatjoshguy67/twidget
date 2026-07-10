import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  LocalFixedWindowLimiter,
  LocalHistoryRepository,
  PostgresHistoryRepository,
  RedisFixedWindowLimiter,
  SharedJsonCache,
  mergeSamples,
  normalizeHistorySample,
} from "../src/infrastructure.js";

const DAY = 24 * 60 * 60 * 1000;

test("local history persists UTC samples and applies configured retention", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-history-test-"));
  const filePath = path.join(temp, "history.json");
  t.after(() => rm(temp, { recursive: true, force: true }));
  let now = Date.UTC(2026, 3, 1);
  const repository = new LocalHistoryRepository({
    filePath,
    maxHistoryDays: 400,
    sampleRetentionDays: 30,
    inactiveAccountDays: 60,
    now: () => now,
  });
  await repository.initialize();
  assert.equal(await repository.registerAccount("old", [sample(now, 5)], 2), true);
  now = Date.UTC(2026, 6, 10);
  assert.equal(await repository.registerAccount("active", [
    sample(now - 45 * DAY, 8),
    sample(now - DAY, 10),
  ], 2), true);
  assert.equal(await repository.registerAccount("full", [sample(now, 1)], 2), false);
  assert.deepEqual((await repository.getHistory("active")).map((item) => item.followers), [10]);

  const result = await repository.prune();
  assert.deepEqual(result, { deletedAccounts: 1, deletedSamples: 1 });
  assert.deepEqual((await repository.getHistory("active")).map((item) => item.followers), [10]);
  assert.equal(await repository.hasAccount("old"), false);

  const stored = JSON.parse(await readFile(filePath, "utf8"));
  assert.equal(stored.version, 3);
  assert.equal(stored.accounts.active[0].ts, now - DAY);
});

test("history merge never lets an estimate replace a real daily sample", () => {
  const now = Date.UTC(2026, 6, 10);
  const merged = mergeSamples(
    [{ ...sample(now, 100), est: false }],
    [{ ...sample(now, 999), est: true }],
    { preferExisting: false, maxHistoryDays: 400, sampleRetentionDays: 0, now },
  );
  assert.equal(merged.length, 1);
  assert.equal(merged[0].followers, 100);
  assert.equal(merged[0].est, undefined);
});

test("legacy Wayback zero fields migrate to unknown while observed client zero remains known", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-history-known-test-"));
  const filePath = path.join(temp, "history.json");
  t.after(() => rm(temp, { recursive: true, force: true }));
  const ts = Date.UTC(2026, 6, 10);
  await writeFile(filePath, JSON.stringify({
    version: 2,
    accounts: {
      example: [
        { ...sample(ts - DAY, 10), following: 0, posts: 0, likes: 0, src: "wayback" },
        { ...sample(ts, 11), following: 0, posts: 0, likes: 0, src: "client" },
      ],
    },
    meta: {},
  }));
  const repository = new LocalHistoryRepository({ filePath, maxHistoryDays: 400, now: () => ts });
  await repository.initialize();
  const [wayback, client] = await repository.getHistory("example");
  assert.deepEqual(
    [wayback.followersKnown, wayback.followingKnown, wayback.postsKnown, wayback.likesKnown],
    [true, false, false, false],
  );
  assert.deepEqual(
    [client.followersKnown, client.followingKnown, client.postsKnown, client.likesKnown],
    [true, true, true, true],
  );
  assert.equal(JSON.parse(await readFile(filePath, "utf8")).version, 3);
});

test("same-day merge preserves a previously known metric when a later source omits it", () => {
  const ts = Date.UTC(2026, 6, 10);
  const merged = mergeSamples(
    [{ ...sample(ts, 100), following: 7, followingKnown: true }],
    [{ ...sample(ts, 101), following: 0, followingKnown: false, src: "wayback" }],
    { preferExisting: false, maxHistoryDays: 400, sampleRetentionDays: 0, now: ts },
  );
  assert.equal(merged[0].followers, 101);
  assert.equal(merged[0].following, 7);
  assert.equal(merged[0].followingKnown, true);
  assert.equal(normalizeHistorySample({ ...sample(ts, 0), followers: 0, src: "client" }).followersKnown, true);
  assert.equal(normalizeHistorySample({
    ...sample(ts, 0),
    following: 0,
    followingKnown: true,
    src: "wayback",
  }).followingKnown, true);
});

test("bounded local limiter rejects new keys at capacity without weakening a bucket", async () => {
  const limiter = new LocalFixedWindowLimiter({ windowMs: 60_000, maxKeys: 1 });
  assert.equal((await limiter.take("first")).count, 1);
  assert.equal((await limiter.take("first")).count, 2);
  assert.equal((await limiter.take("second")).capacityReached, true);
});

test("Redis limiter and JSON cache use shared atomic/TTL commands", async () => {
  const calls = [];
  const client = {
    async eval(_script, options) {
      calls.push(["eval", options]);
      return [3, 2500];
    },
    async get(key) {
      calls.push(["get", key]);
      return JSON.stringify({ fetchedAt: 1, data: { followers: 7 } });
    },
    async set(key, value, options) {
      calls.push(["set", key, JSON.parse(value), options]);
      return "OK";
    },
  };
  const limiter = new RedisFixedWindowLimiter({ client, prefix: "test:rate", windowMs: 60_000 });
  assert.deepEqual(await limiter.take("127.0.0.1"), { count: 3, retryMs: 2500 });

  const cache = new SharedJsonCache({ client, prefix: "test:cache", maxEntries: 2 });
  assert.equal((await cache.get("user")).data.followers, 7);
  await cache.set("user", { fetchedAt: 2, data: { followers: 8 } }, 5000);
  assert.deepEqual(calls.at(-1).at(-1), { PX: 5000 });
});

test("PostgreSQL repository creates cascading schema and stores dates in UTC", async () => {
  const calls = [];
  const client = {
    async query(sql, params) {
      calls.push({ sql, params });
      return { rows: [], rowCount: 0 };
    },
    release() { calls.push({ sql: "RELEASE" }); },
  };
  const pool = {
    async query(sql, params) {
      calls.push({ sql, params });
      return { rows: [], rowCount: 0 };
    },
    async connect() { return client; },
    async end() {},
  };
  const repository = new PostgresHistoryRepository({ pool, maxHistoryDays: 400 });
  await repository.initialize();
  const schema = calls.find((call) => /CREATE TABLE IF NOT EXISTS twidget_history_accounts/.test(call.sql));
  assert.ok(calls.some((call) => /twidget_history_schema/.test(call.sql)));
  assert.match(schema.sql, /ON DELETE CASCADE/);
  assert.match(schema.sql, /last_accessed_at/);
  assert.match(schema.sql, /ADD COLUMN IF NOT EXISTS following_known/);
  assert.match(schema.sql, /NOT estimated AND \(source IS DISTINCT FROM 'wayback' OR following > 0\)/);
  assert.equal(await repository.healthCheck(), true);
  assert.ok(calls.some((call) => call.sql === "SELECT 1"));

  const ts = Date.UTC(2026, 6, 10);
  await repository.storeSamples("example", [sample(ts, 10)], { touchAccess: false });
  const sampleInsert = calls.find((call) => /INSERT INTO twidget_history_samples/.test(call.sql));
  assert.match(sampleInsert.sql, /AT TIME ZONE 'UTC'/);
  assert.match(sampleInsert.sql, /CASE WHEN EXCLUDED.following_known/);
  assert.deepEqual(sampleInsert.params.slice(2), [10, true, 1, true, 2, true, 3, true, "live", false]);
  assert.ok(calls.some((call) => call.sql === "COMMIT"));
  assert.ok(calls.some((call) => call.sql === "RELEASE"));
});

function sample(ts, followers) {
  return {
    dayLabel: new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" }).format(new Date(ts)),
    followers,
    following: 1,
    posts: 2,
    likes: 3,
    ts,
  };
}
