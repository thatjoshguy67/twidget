import fs from 'node:fs/promises';

// Retains the latest observation of each post, not daily engagement movements.
export class AnalyticsArchive {
  constructor(repository) { this.pool = repository.pool; this.path = `${repository.filePath}.analytics.json`; this.queue = Promise.resolve(); }
  async initialize() {
    if (this.pool) await this.pool.query(`CREATE TABLE IF NOT EXISTS twidget_analytics_archive (
      username varchar(15) PRIMARY KEY, user_id text NOT NULL, posts jsonb NOT NULL,
      updated_at timestamptz NOT NULL DEFAULT now())`);
    if (this.pool) await this.pool.query(`ALTER TABLE twidget_analytics_archive ADD COLUMN IF NOT EXISTS backfill jsonb NOT NULL DEFAULT '{}'::jsonb`);
  }
  async record(username, userId, posts, backfill = null) {
    if (!userId) return;
    const values = Object.fromEntries(posts.filter(p => /^\d+$/.test(p.id || '')).map(p => [p.id, p]));
    if (this.pool) {
      await this.pool.query(`INSERT INTO twidget_analytics_archive(username,user_id,posts,backfill) VALUES ($1,$2,$3::jsonb,COALESCE($4::jsonb,'{}'::jsonb))
        ON CONFLICT(username) DO UPDATE SET user_id=EXCLUDED.user_id,
        posts=CASE WHEN twidget_analytics_archive.user_id=EXCLUDED.user_id
          THEN twidget_analytics_archive.posts || EXCLUDED.posts ELSE EXCLUDED.posts END, backfill=CASE WHEN twidget_analytics_archive.user_id=EXCLUDED.user_id THEN COALESCE($4::jsonb,twidget_analytics_archive.backfill) ELSE EXCLUDED.backfill END, updated_at=now()`, [username, userId, JSON.stringify(values), backfill == null ? null : JSON.stringify(backfill)]);
      return;
    }
    const pending = this.queue.then(async () => {
      const data = await this.readFile();
      const old = data[username];
      data[username] = { userId, posts: { ...(old?.userId === userId ? old.posts : {}), ...values }, updatedAt: Date.now(), backfill: backfill ?? (old?.userId === userId ? old.backfill : {}) };
      await fs.writeFile(`${this.path}.tmp`, JSON.stringify(data));
      await fs.rename(`${this.path}.tmp`, this.path);
    });
    this.queue = pending.catch(() => {});
    await pending;
  }
  async withBackfillLock(username, run) {
    if (this.pool) {
      const client = await this.pool.connect();
      try {
        const result = await client.query("SELECT pg_try_advisory_lock(hashtext('analytics_backfill'), hashtext($1)) AS locked", [username]);
        if (!result.rows[0].locked) return null;
        try { return await run(); }
        finally { await client.query("SELECT pg_advisory_unlock(hashtext('analytics_backfill'), hashtext($1))", [username]); }
      } finally { client.release(); }
    }
    this.locks ??= new Set();
    if (this.locks.has(username)) return null;
    this.locks.add(username);
    try { return await run(); } finally { this.locks.delete(username); }
  }
  async readFile() { try { return JSON.parse(await fs.readFile(this.path, 'utf8')); } catch (e) { if (e.code === 'ENOENT') return {}; throw e; } }
  async get(username) {
    let entry;
    if (this.pool) {
      const result = await this.pool.query('SELECT user_id,posts,updated_at,backfill FROM twidget_analytics_archive WHERE username=$1', [username]);
      const row = result.rows[0];
      if (row) entry = { userId: row.user_id, posts: row.posts, backfill: row.backfill, updatedAt: new Date(row.updated_at).getTime() };
    } else { await this.queue; entry = (await this.readFile())[username]; }
    const posts = Object.values(entry?.posts || {}).sort((a,b) => a.ts-b.ts);
    return { postCount: posts.filter(post => !post.isReply).length, replyCount: posts.filter(post => post.isReply).length, userName: username, userId: entry?.userId || '', scope: 'all_stored', complete: false,
      updatedAt: entry?.updatedAt ?? null, posts,
      backfill: entry?.backfill || { status: "not_started" },
      coverage: { earliestPostAt: posts[0]?.ts ?? null, latestPostAt: posts.at(-1)?.ts ?? null,
        sourceExhausted: entry?.backfill?.status === "exhausted", replyCoverage: entry?.backfill?.timelineMode === 'with_replies' ? 'reply_inclusive_timeline' : 'incidental_or_unknown',
        semantics: "observed_post_creation_dates_not_guaranteed_continuous_coverage" },
      semantics: 'latest_lifetime_counters_per_post_not_daily_movements' };
  }
}
