import test from 'node:test';
import assert from 'node:assert/strict';
import { PostgresHistoryRepository } from '../src/infrastructure.js';
test('all returns old daily observations while default thins by month', async () => {
  const rows = ['2020-01-01','2020-01-02'].map(sample_date => ({ sample_date, followers: '1', following: '2', posts: '3', likes: '4', followers_known: true }));
  const repository = new PostgresHistoryRepository({ pool: { query: async () => ({ rows }) }, maxHistoryDays: 400 });
  assert.equal((await repository.getHistory('example')).length, 1);
  assert.equal((await repository.getHistory('example', { all: true })).length, 2);
});

import { LocalHistoryRepository } from '../src/infrastructure.js';
test('local all preserves stored daily detail and still honors retention', async () => {
  const now = Date.UTC(2026, 10, 25);
  const repository = new LocalHistoryRepository({ filePath: '/tmp/unused-full-history-test.json', maxHistoryDays: 7, now: () => now });
  repository.store = { accounts: { example: [1,2].map(day => ({ ts: Date.UTC(2026, 9, day), followers: day, following: 1, posts: 1, likes: 1 })) } };
  assert.equal((await repository.getHistory('example')).length, 1);
  assert.equal((await repository.getHistory('example', { all: true })).length, 2);
  repository.sampleRetentionDays = 10;
  assert.equal((await repository.getHistory('example', { all: true })).length, 0);
});
