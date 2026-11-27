import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import pg from 'pg';
import { PostgresHistoryRepository } from '../src/infrastructure.js';

// Opt-in test database only. Each run owns an isolated schema; never use the production DATABASE_URL.
test('PostgreSQL overlapping samples, metadata patches and delete guards', {
  skip: !process.env.TWIDGET_TEST_POSTGRES_URL,
}, async () => {
  const connectionString = process.env.TWIDGET_TEST_POSTGRES_URL;
  const admin = new pg.Pool({ connectionString });
  const schema = `twidget_test_${crypto.randomBytes(8).toString('hex')}`;
  const pool = new pg.Pool({ connectionString, options: `-c search_path=${schema}` });
  try {
    await admin.query(`CREATE SCHEMA ${schema}`);
    const repository = new PostgresHistoryRepository({ pool, maxHistoryDays: 365 });
    await repository.initialize();
    const day = Date.UTC(2026, 9, 9);
    const sample = (ts, followers) => ({ ts, followers, following: 2, posts: 3, likes: 4 });
    await repository.registerAccount('audit', [sample(day, 100)], 10);
    await Promise.all([
      repository.storeSamples('audit', [sample(day + 86400000, 101)], { requireExisting: true }),
      repository.storeSamples('audit', [sample(day + 2 * 86400000, 102)], { requireExisting: true }),
      repository.patchMeta('audit', { waybackAt: 1 }),
      repository.patchMeta('audit', { banger: { url: 'https://example.com/post' } }),
    ]);
    assert.deepEqual((await repository.getHistory('audit')).map(s => s.followers), [100, 101, 102]);
    assert.equal((await repository.getMeta('audit')).waybackAt, 1);
    assert.equal((await repository.getMeta('audit')).banger.url, 'https://example.com/post');
    await repository.deleteAccount('audit');
    assert.equal(await repository.patchMeta('audit', { waybackAt: 2 }), false);
    assert.deepEqual(await repository.storeSamples('audit', [sample(day, 999)], { requireExisting: true }), []);
    assert.equal(await repository.hasAccount('audit'), false);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
    await admin.end();
  }
});

test('PostgreSQL handle moves preserve history, scan rows and metadata with rollback on conflicts', {
  skip: !process.env.TWIDGET_TEST_POSTGRES_URL,
}, async () => {
  const admin = new pg.Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL });
  const schema = `twidget_handles_${crypto.randomBytes(8).toString('hex')}`;
  const pool = new pg.Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL, options: `-c search_path=${schema}` });
  try {
    await admin.query(`CREATE SCHEMA ${schema}`);
    const repository = new PostgresHistoryRepository({ pool, maxHistoryDays: 400 });
    await repository.initialize();
    const sample = { ts: Date.now(), followers: 100, following: 2, posts: 3, likes: 4 };
    await repository.registerAccount('before', [sample], 10);
    await repository.patchMeta('before', { userId: '123', banger: { score: 77 }, customSetting: true });
    const scanId = crypto.randomUUID();
    await pool.query("INSERT INTO twidget_top_follower_scans (scan_id, username, status) VALUES ($1, 'before', 'complete')", [scanId]);
    await pool.query("INSERT INTO twidget_top_followers (scan_id, scan_index, follower_username, followers) VALUES ($1, 0, 'follower', 999)", [scanId]);
    await repository.registerAccount('occupied', [sample], 10);
    await repository.patchMeta('occupied', { userId: '999' });
    await assert.rejects(repository.renameAccount('before', 'occupied', '123'), /destination_identity_conflict/);
    assert.equal(await repository.hasAccount('before'), true);
    assert.equal((await repository.getMeta('occupied')).userId, '999');
    await pool.query(`CREATE FUNCTION interrupt_move() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN RAISE EXCEPTION 'simulated_write_failure'; END $$`);
    await pool.query("CREATE TRIGGER interrupt_move BEFORE UPDATE OF username ON twidget_top_follower_scans FOR EACH ROW EXECUTE FUNCTION interrupt_move()");
    await assert.rejects(repository.renameAccount('before', 'after', '123'), /simulated_write_failure/);
    assert.equal(await repository.hasAccount('before'), true);
    assert.equal(await repository.hasAccount('after'), false);
    assert.equal((await repository.getHistory('before')).length, 1);
    await pool.query("DROP TRIGGER interrupt_move ON twidget_top_follower_scans");
    await pool.query("DROP FUNCTION interrupt_move()");
    await repository.renameAccount('before', 'after', '123');
    assert.equal(await repository.hasAccount('before'), false);
    assert.equal((await repository.getHistory('after')).length, 1);
    assert.equal((await repository.getMeta('after')).customSetting, true);
    assert.equal((await pool.query('SELECT username FROM twidget_top_follower_scans WHERE scan_id = $1', [scanId])).rows[0].username, 'after');
    assert.equal((await pool.query('SELECT count(*)::int AS count FROM twidget_top_followers WHERE scan_id = $1', [scanId])).rows[0].count, 1);
    assert.deepEqual(await repository.renameAccount('before', 'after', '123'), { moved: false });
    await repository.registerAccount('legacy', [sample], 10);
    await assert.rejects(repository.renameAccount('legacy', 'verified', '123'), /source_identity_unverified/);
    await repository.renameAccount('legacy', 'verified', '123', { allowLegacy: true });
    assert.equal((await repository.getMeta('verified')).userId, '123');
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
    await admin.end();
  }
});

test('PostgreSQL legacy interactions retain verified ownership across identity changes', {
  skip: !process.env.TWIDGET_TEST_POSTGRES_URL,
}, async () => {
  const admin = new pg.Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL });
  const schema = `twidget_interactions_${crypto.randomBytes(8).toString('hex')}`;
  const pool = new pg.Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL, options: `-c search_path=${schema}` });
  try {
    await admin.query(`CREATE SCHEMA ${schema}`);
    const repo = new PostgresHistoryRepository({ pool, maxHistoryDays: 365 });
    await repo.initialize();
    await repo.registerAccount('legacy', [{ ts: Date.now(), followers: 100 }], 10);
    await repo.patchMeta('legacy', { verifiedUserId: '42' });
    const data = { daily: [{ date: '2026-01-01', likes: 7 }], posts: [] };
    assert.equal(await repo.storeInteractions('legacy', data, { expectedUserId: '43' }), false);
    assert.equal(await repo.storeInteractions('legacy', data, { expectedUserId: '42' }), true);
    assert.equal((await repo.getInteractions('legacy')).daily[0].likes, 7);
    assert.equal((await repo.getMeta('legacy')).userId, undefined);
    await repo.patchMeta('legacy', { verifiedUserId: '43' });
    assert.deepEqual(await repo.getInteractions('legacy'), { daily: [], posts: [] });
    assert.equal(await repo.storeInteractions('legacy', data, { expectedUserId: '42' }), false);
    assert.equal(await repo.storeInteractions('legacy', { daily: [], posts: [] }, { expectedUserId: '43' }), true);
    assert.deepEqual(await repo.getInteractions('legacy'), { daily: [], posts: [] });
    assert.equal((await repo.getHistory('legacy'))[0].followers, 100);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
    await admin.end();
  }
});
