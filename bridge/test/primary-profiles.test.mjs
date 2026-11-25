import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { Pool } from "pg";
import { LocalHistoryRepository, PostgresHistoryRepository } from "../src/infrastructure.js";
import { installationHash } from "../src/primary-profiles.js";

const DAY = 86400000;
const hash = installationHash("a".repeat(64));
const other = installationHash("b".repeat(64));

test("installation capabilities are validated and only their hash is stored", () => {
  assert.notEqual(hash, "a".repeat(64));
  for (const invalid of [null, {}, "a".repeat(63), "A".repeat(64), "a".repeat(65)]) assert.equal(installationHash(invalid), null);
});

async function exercise(repository) {
  await repository.registerAccount("first", [{ ts: Date.now(), followers: 1 }], 10, "123");
  await repository.registerAccount("second", [{ ts: Date.now(), followers: 2 }], 10, "456");
  assert.equal(await repository.recordPrimaryProfile(hash, "missing", 1), "account_missing");
  assert.equal(await repository.hasAccount("missing"), false);
  assert.equal(await repository.recordPrimaryProfile(hash, "first", 1), "ok");
  assert.equal(await repository.recordPrimaryProfile(other, "first", 1), "capacity");
  assert.equal(await repository.recordPrimaryProfile(hash, "second", 1), "ok");
  assert.deepEqual(await repository.primaryProfileCounts(), [{ username: "second", primaryInstalls: 1, active7d: 1, active30d: 1 }]);
  await repository.renameAccount("second", "renamed", "456");
  assert.equal((await repository.primaryProfileCounts())[0].username, "renamed");
  await repository.recordPrimaryProfile(hash, null, 1);
  assert.deepEqual(await repository.primaryProfileCounts(), []);
  await repository.recordPrimaryProfile(hash, "first", 1);
  await repository.deleteAccount("first");
  assert.deepEqual(await repository.primaryProfileCounts(), []);
}

test("local default reports replace, move, clear and cascade without enrolling accounts", async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "twidget-primary-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const repository = new LocalHistoryRepository({ filePath: path.join(dir, "history.json"), maxHistoryDays: 400 });
  await repository.initialize();
  await exercise(repository);
  await repository.recordPrimaryProfile(hash, "renamed", 1);
  const restored = new LocalHistoryRepository({ filePath: repository.filePath, maxHistoryDays: 400 });
  await restored.initialize();
  assert.equal((await restored.primaryProfileCounts())[0].primaryInstalls, 1);
  restored.now = () => Date.now() + 91 * DAY;
  assert.deepEqual(await restored.primaryProfileCounts(), []);
  await restored.prune();
  assert.equal(Object.keys(restored.store.primaryProfiles).length, 0);
});

test("Postgres default reporting is atomic, bounded and survives migrations/deletions", { skip: !process.env.TWIDGET_TEST_POSTGRES_URL }, async t => {
  const pool = new Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL });
  const schema = `primary_${Date.now()}`;
  await pool.query(`CREATE SCHEMA ${schema}`);
  const scoped = new Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL, options: `-c search_path=${schema}` });
  t.after(async () => { await scoped.end(); await pool.query(`DROP SCHEMA ${schema} CASCADE`); await pool.end(); });
  const repository = new PostgresHistoryRepository({ pool: scoped, maxHistoryDays: 400 });
  await repository.initialize();
  await exercise(repository);
  // Racing reports cannot exceed the shared admission ceiling.
  const result = await Promise.all([hash, other].map(key => repository.recordPrimaryProfile(key, "renamed", 1)));
  assert.deepEqual(result.sort(), ["capacity", "ok"]);
  await scoped.query("UPDATE twidget_primary_profiles SET last_seen_at = now() - interval '91 days'");
  assert.deepEqual(await repository.primaryProfileCounts(), []);
  await repository.prune();
  assert.equal((await scoped.query("SELECT count(*)::int AS n FROM twidget_primary_profiles")).rows[0].n, 0);
});
