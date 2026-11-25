import test from 'node:test';
import assert from 'node:assert/strict';
import { postCounters, dashboardStats } from '../src/dashboard-stats.js';
test('bookmarks distinguish observed zero from missing data; shares are not exposed', () => {
  const counters = postCounters({ bookmarks: 0, reposts: 2, quotes: 3, views: -1 });
  assert.equal(counters.bookmarksKnown, true);
  assert.equal(Object.hasOwn(counters, 'shares'), false);
  assert.equal(Object.hasOwn(counters, 'sharesKnown'), false);
  assert.equal(counters.viewsKnown, false);
  assert.equal(postCounters({}).bookmarksKnown, false);
});
test('dashboard summary counts originals separately and does not invent missing totals', () => {
  const post = { ...postCounters({ likes: 4, bookmarks: 2 }), ts: 1000 };
  const stats = dashboardStats([post], [post, { isReply: true }], false);
  assert.equal(stats.originalPostCount, 1);
  assert.equal(stats.replyPostCount, 1);
  assert.equal(stats.totals.bookmarks, 2);
  assert.equal(stats.totals.views, null);
  assert.equal(Object.hasOwn(stats.totals, 'shares'), false);
  assert.equal(stats.likesPerPost, 4);
  assert.deepEqual(stats.originalPostTimestamps, [1000]);
  assert.equal(stats.complete, false);
});
