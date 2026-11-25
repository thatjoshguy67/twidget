import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import pg from 'pg';
import { LocalHistoryRepository, PostgresHistoryRepository } from '../src/infrastructure.js';
import { followerChangeObservation, followerChangesFor } from '../src/top-followers.js';

const follower = (id, username = `user${id}`) => ({ id, username, name: username, followers: 5, verified: false, avatar: '', mutual: null });
const sample = { ts: Date.UTC(2026, 10, 25), followers: 3, following: 1, posts: 1, likes: 1 };

async function exercise(repository, restart = async () => repository) {
  await repository.initialize();
  await repository.registerAccount('example', [sample], 10, '123');
  let tick = Date.now() - 60_000;
  async function scan(ids, kind = 'full') {
    tick += 1000;
    const id = crypto.randomUUID();
    await repository.startTopFollowersScan('example', id, tick, { kind });
    await repository.appendTopFollowersPage('example', id, ids.map(value => typeof value === 'string' ? follower(value) : value), '', tick + 1);
    await repository.completeTopFollowersScan('example', id, tick + 2);
    return id;
  }
  await scan(['1', '2', '3']);
  assert.deepEqual(followerChangesFor(await repository.getMeta('example')), []);
  await scan(['4'], 'head');
  assert.deepEqual(followerChangesFor(await repository.getMeta('example')), []);
  repository = await restart();
  // Same public total, but one old follower left and one new one arrived.
  const completed = await scan([follower('1', 'renamed'), '3', '4']);
  let rows = followerChangesFor(await repository.getMeta('example'));
  assert.equal(rows.length, 1);
  assert.equal(rows[0].lostFollowers, 1);
  assert.equal(rows[0].newFollowers, 1);
  assert.equal(rows[0].source, 'complete_follower_scans');
  assert.ok(rows[0].to > rows[0].from);
  // Repeated completion is idempotent, and missing IDs cannot create losses.
  await repository.completeTopFollowersScan('example', completed);
  await scan([follower('', 'missingid')]);
  rows = followerChangesFor(await repository.getMeta('example'));
  assert.equal(rows.length, 1);
  await repository.patchMeta('example', { userId: '999' });
  assert.deepEqual(followerChangesFor(await repository.getMeta('example')), []);
}

test('completed follower scans persist gross membership changes across head checks and restart', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'twidget-follower-changes-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const config = { filePath: path.join(directory, 'history.json'), maxHistoryDays: 400 };
  const repository = new LocalHistoryRepository(config);
  await exercise(repository, async () => {
    const next = new LocalHistoryRepository(config);
    await next.initialize();
    return next;
  });
});

test('Postgres records changes atomically before deleting old scans', { skip: !process.env.TWIDGET_TEST_POSTGRES_URL }, async () => {
  const connectionString = process.env.TWIDGET_TEST_POSTGRES_URL;
  const admin = new pg.Pool({ connectionString });
  const schema = `twidget_follow_changes_${crypto.randomBytes(8).toString('hex')}`;
  const pool = new pg.Pool({ connectionString, options: `-c search_path=${schema}` });
  try {
    await admin.query(`CREATE SCHEMA ${schema}`);
    await exercise(new PostgresHistoryRepository({ pool, maxHistoryDays: 400 }));
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
    await admin.end();
  }
});

test('Postgres scan comparison SQL also runs in isolated PGlite', { skip: !process.env.TWIDGET_TEST_PGLITE_MODULE }, async () => {
  const { PGlite } = await import(process.env.TWIDGET_TEST_PGLITE_MODULE);
  const db = new PGlite();
  const query = async (sql, parameters) => {
    const result = !parameters && sql.split(';').filter(value => value.trim()).length > 1
      ? (await db.exec(sql)).at(-1) : await db.query(sql, parameters);
    return { ...result, rowCount: result.affectedRows ?? result.rows.length };
  };
  const pool = { query, connect: async () => ({ query, release() {} }) };
  try { await exercise(new PostgresHistoryRepository({ pool, maxHistoryDays: 400 })); }
  finally { await db.close(); }
});

test('unverified, recycled, partial and backwards scan comparisons remain unknown', () => {
  const before = { scanId: 'a', kind: 'full', status: 'complete', completedAt: 1, userId: '123' };
  const after = { ...before, scanId: 'b', completedAt: 2 };
  const counts = { newFollowers: 0, lostFollowers: 0 };
  assert.equal(followerChangeObservation(before, after, counts, '123').lostFollowers, 0);
  for (const invalid of [{ userId: '' }, { userId: '456' }, { kind: 'head' }, { status: 'failed' }, { completedAt: 0 }]) {
    assert.equal(followerChangeObservation({ ...before, ...invalid }, after, counts, '123'), null);
  }
  assert.equal(followerChangeObservation(after, before, counts, '123'), null);
  assert.equal(followerChangeObservation(before, { ...after, provider: 'fxtwitter' }, counts, '123'), null);
  assert.equal(followerChangeObservation(before, after, { newFollowers: 0, lostFollowers: -1 }, '123'), null);
});
