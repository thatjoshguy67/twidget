import test from 'node:test';
import assert from 'node:assert/strict';
import { mergeInteractionData, normalizeInteractionData } from '../src/interactions.js';

test('snapshots merge monotonic counters and preserve missing values', () => {
  const post = { id: '123', createdAt: 1, capturedAt: 2, isReply: false, likes: 3 };
  const first = normalizeInteractionData({ daily: [{ date: '2026-01-01', likes: 0 }], posts: [post] });
  const second = normalizeInteractionData({ daily: [{ date: '2026-01-01', replies: 2 }], posts: [{ ...post, capturedAt: 3, likes: 1, reposts: 4 }] });
  const result = mergeInteractionData(first, second);
  assert.equal(result.daily[0].likes, 0);
  assert.equal(result.daily[0].shares, null);
  assert.equal(result.posts[0].likes, 3);
  assert.equal(result.posts[0].reposts, 4);
  assert.equal(result.posts[0].capturedAt, 3);
  assert.throws(() => normalizeInteractionData({ daily: [{ date: '2026-02-30' }], posts: [] }));
});

test('local repository persists interaction rows across restarts', async t => {
  const { mkdtemp, rm } = await import('node:fs/promises');
  const { default: os } = await import('node:os');
  const { default: path } = await import('node:path');
  const { LocalHistoryRepository } = await import('../src/infrastructure.js');
  const dir = await mkdtemp(path.join(os.tmpdir(), 'twidget-interactions-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const options = { filePath: path.join(dir, 'history.json'), maxHistoryDays: 366 };
  const repo = new LocalHistoryRepository(options);
  await repo.initialize();
  await repo.registerAccount('example', [{ ts: Date.now(), followers: 1 }], 2);
  await repo.storeInteractions('example', normalizeInteractionData({ daily: [{ date: '2026-01-01', shares: 0 }], posts: [] }));
  const reopened = new LocalHistoryRepository(options);
  await reopened.initialize();
  assert.equal((await reopened.getInteractions('example')).daily[0].shares, 0);
  assert.equal((await reopened.getInteractions('example')).daily[0].likes, null);
});

test('public snapshot preserves absent counters and marks replies', async () => {
  const { fxInteractionSnapshot } = await import('../src/interactions.js');
  const snapshot = fxInteractionSnapshot({ id: '456', created_timestamp: 1720000000, likes: 0, replies: 4, replying_to: 'x' }, 1720000123000);
  assert.equal(snapshot.likes, 0);
  assert.equal(snapshot.reposts, null);
  assert.equal(snapshot.shares, null);
  assert.equal(snapshot.isReply, true);
  assert.equal(snapshot.createdAt, 1720000000000);
});

test('local writes require the expected account identity and merge on rename', async t => {
  const { mkdtemp, rm } = await import('node:fs/promises');
  const { default: os } = await import('node:os');
  const { default: path } = await import('node:path');
  const { LocalHistoryRepository } = await import('../src/infrastructure.js');
  const dir = await mkdtemp(path.join(os.tmpdir(), 'twidget-interactions-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const repo = new LocalHistoryRepository({ filePath: path.join(dir, 'data'), maxHistoryDays: 366 });
  await repo.initialize();
  await repo.registerAccount('example', [{ ts: Date.now(), followers: 1 }], 4, '42');
  const data = normalizeInteractionData({ daily: [], posts: [{ id: '1', createdAt: 1, capturedAt: 2, isReply: false, likes: 4 }] });
  assert.equal(await repo.storeInteractions('example', data, { expectedUserId: '43' }), false);
  assert.equal(await repo.storeInteractions('example', data, { expectedUserId: '42' }), true);
  await repo.renameAccount('example', 'renamed', '42');
  assert.equal((await repo.getInteractions('renamed')).posts[0].likes, 4);
  assert.deepEqual(await repo.getInteractions('example'), { daily: [], posts: [] });
});


test('verified legacy interaction ownership survives restart and excludes recycled handles', async t => {
  const { mkdtemp, rm } = await import('node:fs/promises');
  const { default: os } = await import('node:os');
  const { default: path } = await import('node:path');
  const { LocalHistoryRepository } = await import('../src/infrastructure.js');
  const dir = await mkdtemp(path.join(os.tmpdir(), 'twidget-interactions-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const options = { filePath: path.join(dir, 'data'), maxHistoryDays: 366 };
  const repo = new LocalHistoryRepository(options);
  await repo.initialize();
  await repo.registerAccount('example', [{ ts: Date.now(), followers: 1 }], 4);
  await repo.patchMeta('example', { verifiedUserId: '42' });
  const data = normalizeInteractionData({ daily: [{ date: '2026-01-01', likes: 7 }], posts: [] });
  assert.equal(await repo.storeInteractions('example', data, { expectedUserId: '43' }), false);
  assert.equal(await repo.storeInteractions('example', data, { expectedUserId: '42' }), true);
  const reopened = new LocalHistoryRepository(options);
  await reopened.initialize();
  assert.equal((await reopened.getInteractions('example')).daily[0].likes, 7);
  assert.equal((await reopened.getMeta('example')).userId, undefined);
  await reopened.patchMeta('example', { verifiedUserId: '43' });
  assert.deepEqual(await reopened.getInteractions('example'), { daily: [], posts: [] });
  assert.equal(await reopened.storeInteractions('example', data, { expectedUserId: '42' }), false);
  assert.equal(await reopened.storeInteractions('example', normalizeInteractionData({ daily: [], posts: [] }), { expectedUserId: '43' }), true);
  assert.deepEqual(await reopened.getInteractions('example'), { daily: [], posts: [] });
});
