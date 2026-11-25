import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import pg from 'pg';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { LocalHistoryRepository, PostgresHistoryRepository } from '../src/infrastructure.js';
import { createTopFollowersActivityRecorder, topFollowersScanPlan } from '../src/top-followers.js';

async function repositoryFor(t, backend) {
  if (backend === 'json') {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'twidget-activity-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const repository = new LocalHistoryRepository({ filePath: path.join(directory, 'history.json'), maxHistoryDays: 365 });
    await repository.initialize();
    return repository;
  }
  const admin = new pg.Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL });
  const schema = 'twidget_activity_' + crypto.randomBytes(8).toString('hex');
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new pg.Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL, options: `-c search_path=${schema}` });
  t.after(async () => { await pool.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end(); });
  const repository = new PostgresHistoryRepository({ pool, maxHistoryDays: 365 });
  await repository.initialize();
  return repository;
}

for (const backend of ['json', 'postgres']) {
  const options = { skip: backend === 'postgres' && !process.env.TWIDGET_TEST_POSTGRES_URL };
  test(`${backend}: activity preserves concurrent metadata and permanent identity`, options, async t => {
    const repository = await repositoryFor(t, backend);
    await repository.registerAccount('example', [], 10, '123456');
    const patch = { userId: '123456', banger: { score: 42 }, topFollowers: { cachedAt: 99 } };
    // Interleave another writer immediately before whichever write the recorder uses.
    const proxy = {
      hasAccount: repository.hasAccount.bind(repository),
      getMeta: repository.getMeta.bind(repository),
      async setMeta(key, meta) { await repository.patchMeta(key, patch); return repository.setMeta(key, meta); },
      async recordTopFollowersActivity(key, timestamp) { await repository.patchMeta(key, patch); return repository.recordTopFollowersActivity(key, timestamp); },
    };
    await createTopFollowersActivityRecorder(proxy, () => 2000)('example');
    assert.deepEqual(await repository.getMeta('example'), { ...patch, topFollowersLastSeenAt: 2000 });
    await repository.recordTopFollowersActivity('example', 1000);
    assert.equal((await repository.getMeta('example')).topFollowersLastSeenAt, 2000);
  });
  test(`${backend}: legacy history keeps recording without claiming ownership`, options, async t => {
    const r = await repositoryFor(t, backend);
    const day = Date.now() - 3 * 86400000;
    const sample = (ts, followers) => ({ ts, followers, following: 1, posts: 2, likes: 3 });
    await r.registerAccount('legacy', [sample(day, 10), sample(day + 86400000, 11)], 1);
    assert.equal(await r.registerAccount('legacy', [sample(day + 2 * 86400000, 12)], 1, '123456'), true);
    assert.deepEqual((await r.getHistory('legacy')).map(row => row.followers), [10, 11, 12]);
    assert.equal((await r.getMeta('legacy')).userId, undefined);
    await r.storeSamples('legacy', [sample(Date.now(), 13)], { requireExisting: true, expectedUserId: '123456' });
    assert.equal((await r.getHistory('legacy')).length, 4);
    await assert.rejects(r.renameAccount('legacy', 'renamed', '123456'), /source_identity_unverified/);
    // Independent operator verification can bind the legacy history. Stale
    // writers are checked again atomically against the now-known owner.
    await r.patchMeta('legacy', { userId: '999' });
    assert.equal(await r.registerAccount('legacy', [sample(Date.now(), 9999)], 1, '123456'), false);
    assert.equal(await r.registerAccount('legacy', [sample(Date.now(), 9999)], 1), false);
    assert.deepEqual(await r.storeSamples('legacy', [sample(Date.now(), 9999)], { requireExisting: true, expectedUserId: '123456' }), []);
    assert.equal((await r.getHistory('legacy')).at(-1).followers, 13);
    await r.storeSamples('legacy', [sample(Date.now(), 14)], { requireExisting: true, expectedUserId: '999' });
    assert.equal((await r.getHistory('legacy')).at(-1).followers, 14);
  });
  test(`${backend}: pages deduplicate handles and IDs before writing`, options, async t => {
    const r = await repositoryFor(t, backend);
    await r.registerAccount('example', [], 10, '123456');
    const scanId = crypto.randomUUID();
    await r.startTopFollowersScan('example', scanId);
    const page = [follower('1'), { ...follower('2'), username: 'FAN1' },
      { ...follower('1'), username: 'alias' }, follower('3')];
    const first = await r.appendTopFollowersPage('example', scanId, page, 'next');
    assert.equal(first.scanned, 2);
    const second = await r.appendTopFollowersPage('example', scanId,
      [{ ...follower('3'), followers: 300 }, follower('4'), { ...follower('5'), username: 'FAN4' }], '');
    assert.equal(second.scanned, 3);
    await r.completeTopFollowersScan('example', scanId);
    const snapshot = await r.getTopFollowersSnapshot('example');
    assert.equal(snapshot.total, 3);
    assert.deepEqual(snapshot.followers.map(value => value.id), ['3', '4', '1']);
    const head = crypto.randomUUID();
    await r.startTopFollowersScan('example', head, Date.now(), 'head');
    const appended = await r.appendTopFollowersPage('example', head, page, '');
    assert.equal(appended.scanned, 3);
    await r.completeTopFollowersScan('example', head);
    assert.equal((await r.getTopFollowersSnapshot('example')).total, 3);
  });
  test(`${backend}: activity cannot resurrect a deleted or renamed account`, options, async t => {
    const repository = await repositoryFor(t, backend);
    for (const mutation of ['delete', 'rename']) {
      await repository.registerAccount('example', [], 10, '123456');
      const remove = () => mutation === 'delete' ? repository.deleteAccount('example') : repository.renameAccount('example', 'renamed', '123456');
      const proxy = {
        hasAccount: repository.hasAccount.bind(repository), getMeta: repository.getMeta.bind(repository),
        async setMeta(key, meta) { await remove(); return repository.setMeta(key, meta); },
        async recordTopFollowersActivity(key, timestamp) { await remove(); return repository.recordTopFollowersActivity(key, timestamp); },
      };
      await createTopFollowersActivityRecorder(proxy)('example');
      assert.equal(await repository.hasAccount('example'), false);
      assert.deepEqual(await repository.getMeta('example'), {});
      if (mutation === 'rename') assert.equal((await repository.getMeta('renamed')).userId, '123456');
    }
  });
}

const follower = id => ({ id, username: 'fan' + id, name: 'Fan', followers: Number(id), verified: false, avatar: '', mutual: null });

for (const backend of ['json', 'postgres']) {
  test(`${backend}: delta plans and overlap survive a checkpoint without resetting weekly counts`, {
    skip: backend === 'postgres' && !process.env.TWIDGET_TEST_POSTGRES_URL,
  }, async t => {
    const r = await repositoryFor(t, backend);
    await r.registerAccount('example', [], 10, '123');
    const full = crypto.randomUUID();
    await r.startTopFollowersScan('example', full, Date.now(), { plan: { sourceFollowers: 1000 } });
    await r.appendTopFollowersPage('example', full, [follower('1'), follower('2')], '');
    await r.completeTopFollowersScan('example', full);
    const before = await r.getTopFollowersSnapshot('example');
    const plan = topFollowersScanPlan({ kind: 'head', snapshot: before, followersCount: 1200 });
    const head = crypto.randomUUID();
    await r.startTopFollowersScan('example', head, Date.now(), { kind: 'head', plan });
    await r.appendTopFollowersPage('example', head, [follower('3'), { ...follower('1'), followers: 999 }, follower('2')], 'next');
    const restarted = backend === 'json'
      ? new LocalHistoryRepository({ filePath: r.filePath, maxHistoryDays: 400 })
      : new PostgresHistoryRepository({ pool: r.pool, maxHistoryDays: 400 });
    if (backend === 'json') await restarted.initialize();
    const saved = await restarted.getTopFollowersScan('example');
    assert.equal(saved.targetRows, 400);
    assert.equal(saved.processed, 3);
    assert.equal(saved.knownStreak, 2);
    assert.equal(saved.sourceFollowers, 1200);
    assert.equal(saved.cursor, 'next');
    assert.equal((await restarted.getTopFollowersSnapshot('example')).total, 2);
    await restarted.completeTopFollowersScan('example', head);
    const after = await restarted.getTopFollowersSnapshot('example');
    assert.equal(after.total, 3);
    assert.equal(after.followers.find(v => v.id === '1').followers, 1);
    assert.equal(after.fullCompletedAt, before.fullCompletedAt);
  });
}
test('PostgreSQL: exact page counts, atomic head publication, weekly clock and rollback', { skip: !process.env.TWIDGET_TEST_POSTGRES_URL }, async t => {
  const r = await repositoryFor(t, 'postgres');
  await r.registerAccount('example', [], 10, '123456');
  const full = crypto.randomUUID();
  await r.startTopFollowersScan('example', full);
  await r.appendTopFollowersPage('example', full, [follower('1'), follower('2')], 'next');
  const overlap = await r.appendTopFollowersPage('example', full, [follower('2'), follower('3')], '');
  assert.equal(overlap.scanned, 3, 'an update must not increment the row count');
  const renamed = await r.appendTopFollowersPage('example', full, [{ ...follower('3'), username: 'changedfan' }], '');
  assert.equal(renamed.scanned, 3, 'a renamed follower replaces one row');
  const empty = await r.appendTopFollowersPage('example', full, [], '');
  assert.equal(empty.scanned, 3, 'empty pages leave the count intact');
  const published = await r.completeTopFollowersScan('example', full);
  const head = crypto.randomUUID();
  const started = await r.startTopFollowersScan('example', head, Date.now(), 'head');
  assert.equal(started.scanned, 3);
  assert.equal(started.fullCompletedAt, published.fullCompletedAt);
  await r.appendTopFollowersPage('example', head, [follower('3'), follower('4')], 'next');
  assert.equal((await r.getTopFollowersSnapshot('example')).total, 3);
  await r.pool.query(`CREATE FUNCTION fail_scan_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'review write failure'; END $$`);
  await r.pool.query(`CREATE TRIGGER fail_scan BEFORE UPDATE ON twidget_top_follower_scans FOR EACH ROW EXECUTE FUNCTION fail_scan_update()`);
  await assert.rejects(r.appendTopFollowersPage('example', head, [follower('5')], 'failed'), /review write failure/);
  await r.pool.query('DROP TRIGGER fail_scan ON twidget_top_follower_scans');
  assert.equal((await r.getTopFollowersScan('example')).scanned, 4);
  assert.equal(Number((await r.pool.query('SELECT count(*) AS total FROM twidget_top_followers WHERE scan_id = $1', [head])).rows[0].total), 4);
  const completed = await r.completeTopFollowersScan('example', head);
  assert.equal(completed.fullCompletedAt, published.fullCompletedAt);
  assert.equal(completed.lastFullCompletedAt, published.lastFullCompletedAt);
  assert.equal((await r.getTopFollowersSnapshot('example')).total, 4);
  const reconciled = crypto.randomUUID();
  await r.startTopFollowersScan('example', reconciled);
  await r.appendTopFollowersPage('example', reconciled, [follower('4')], '');
  await r.completeTopFollowersScan('example', reconciled);
  assert.equal((await r.getTopFollowersSnapshot('example')).total, 1);
});
