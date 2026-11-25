import test from 'node:test';
import assert from 'node:assert/strict';
import { profileResponse } from '../src/profile-contract.js';

test('profile contract preserves observed zero and distinguishes missing metrics', () => {
  const profile = { followersCount: 0, followersCountKnown: true, likeCount: 0, likeCountKnown: false, profileSource: 'fxtwitter' };
  const result = profileResponse(profile, { fetchedAt: 1000, ttlMs: 15000, cacheStatus: 'hit', now: 2000 });
  assert.equal(result.followersCount, 0);
  assert.equal(result.profileMetadata.fields.followersCount, true);
  assert.equal(result.profileMetadata.fields.likeCount, false);
  assert.equal(result.profileMetadata.source, 'fxtwitter');
  assert.equal(result.profileMetadata.stale, false);
  assert.equal(result.profileMetadata.ageSeconds, 1);
});

test('stale fallback keeps original observation time and legacy cache source is unknown', () => {
  const result = profileResponse({}, { fetchedAt: 1000, ttlMs: 1000, cacheStatus: 'stale', now: 5000 });
  assert.equal(result.profileMetadata.fetchedAt, '1970-01-01T00:00:01.000Z');
  assert.equal(result.profileMetadata.stale, true);
  assert.equal(result.profileMetadata.source, 'unknown');
});

import { profileDetails, analyticsResponse } from '../src/profile-contract.js';
test('optional profile details preserve empty values and reject unsafe links', () => {
  assert.deepEqual(profileDetails({ description: '', location: 'London', website: { url: 'https://example.com' }, banner_url: 'javascript:alert(1)' }), {
    bio: '', location: 'London', website: 'https://example.com/', bannerImage: null, accountCreatedAt: null,
  });
});
test('analytics stale fallback preserves sample and retrieval time', () => {
  const result = analyticsResponse({ recentPosts: [{ id: '1' }], activityComplete: false }, { fetchedAt: 1000, ttlMs: 2000, cacheStatus: 'stale', now: 5000 });
  assert.equal(result.recentPosts[0].id, '1');
  assert.equal(result.analyticsMetadata.complete, false);
  assert.equal(result.analyticsMetadata.stale, true);
  assert.equal(result.analyticsMetadata.fetchedAt, '1970-01-01T00:00:01.000Z');
});
