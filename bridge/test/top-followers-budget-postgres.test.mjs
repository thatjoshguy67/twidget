import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import pg from 'pg';
import { TopFollowersBudget } from '../src/top-followers-budget.js';

test('Postgres reservations attribute spend atomically across replicas without resetting historical counts', {
  skip: !process.env.TWIDGET_TEST_POSTGRES_URL,
}, async () => {
  const connectionString = process.env.TWIDGET_TEST_POSTGRES_URL;
  const admin = new pg.Pool({ connectionString });
  const schema = `twidget_budget_${crypto.randomBytes(8).toString('hex')}`;
  const pool = new pg.Pool({ connectionString, options: `-c search_path=${schema}` });
  try {
    await admin.query(`CREATE SCHEMA ${schema}`);
    const config = { pool, dailyLimit: 10, now: () => Date.UTC(2026, 9, 18, 12) };
    const first = new TopFollowersBudget(config);
    const second = new TopFollowersBudget(config);
    await first.initialize();
    await pool.query('UPDATE twidget_top_followers_budget SET state = $1::jsonb', [JSON.stringify({ day: '2026-10-18', month: '2026-10', dayRequests: 5, monthRequests: 100 })]);
    const results = await Promise.allSettled(Array.from({ length: 20 }, (_, i) => (i % 2 ? first : second).reserve('HowToMen')));
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 5);
    const before = (await pool.query('SELECT state FROM twidget_top_followers_budget')).rows[0].state;
    const usage = await second.usage();
    assert.equal(usage.day.requests, 10);
    assert.equal(usage.day.byAccount.howtomen, 5);
    assert.equal(usage.day.unattributed, 5);
    assert.equal(usage.month.requests, 105);
    assert.equal(usage.month.unattributed, 100);
    assert.deepEqual((await pool.query('SELECT state FROM twidget_top_followers_budget')).rows[0].state, before);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
    await admin.end();
  }
});

test('Postgres tracked ceiling reserves the rest of the global allowance for primaries across replicas', {
  skip: !process.env.TWIDGET_TEST_POSTGRES_URL,
}, async () => {
  const connectionString = process.env.TWIDGET_TEST_POSTGRES_URL;
  const admin = new pg.Pool({ connectionString });
  const schema = `twidget_priority_${crypto.randomBytes(8).toString('hex')}`;
  const pool = new pg.Pool({ connectionString, options: `-c search_path=${schema}` });
  try {
    await admin.query(`CREATE SCHEMA ${schema}`);
    const config = { pool, dailyLimit: 10, trackedDailyLimit: 3 };
    const first = new TopFollowersBudget(config), second = new TopFollowersBudget(config);
    await first.initialize();
    const results = await Promise.allSettled(Array.from({ length: 20 }, (_, i) => (i % 2 ? first : second).reserve('tracked')));
    assert.equal(results.filter(r => r.status === 'fulfilled').length, 3);
    const primaries = await Promise.allSettled(Array.from({ length: 12 }, (_, i) => (i % 2 ? first : second).reserve('primary', { primary: true })));
    assert.equal(primaries.filter(r => r.status === 'fulfilled').length, 7);
    const usage = await first.usage();
    assert.equal(usage.day.requests, 10);
    assert.equal(usage.tracked.requests, 3);
    assert.equal(usage.primary.requests, 7);
  } finally {
    await pool.end(); await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`); await admin.end();
  }
});
