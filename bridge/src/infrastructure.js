import fs from "node:fs";
import path from "node:path";

const DAY_MS = 24 * 60 * 60 * 1000;

export class LocalFixedWindowLimiter {
  constructor({ windowMs, maxKeys }) {
    this.windowMs = windowMs;
    this.maxKeys = maxKeys;
    this.buckets = new Map();
  }

  async take(key) {
    const now = Date.now();
    let bucket = this.buckets.get(key);
    if (!bucket || now - bucket.startedAt >= this.windowMs) {
      if (!bucket && this.buckets.size >= this.maxKeys) {
        this.sweep(now);
        if (this.buckets.size >= this.maxKeys) return { capacityReached: true, retryMs: this.windowMs };
      }
      bucket = { startedAt: now, count: 0 };
      this.buckets.set(key, bucket);
    }
    bucket.count += 1;
    return {
      count: bucket.count,
      retryMs: Math.max(1, bucket.startedAt + this.windowMs - now),
    };
  }

  sweep(now = Date.now()) {
    for (const [key, bucket] of this.buckets) {
      if (now - bucket.startedAt >= this.windowMs) this.buckets.delete(key);
    }
  }
}

export class RedisFixedWindowLimiter {
  constructor({ client, prefix, windowMs }) {
    this.client = client;
    this.prefix = prefix;
    this.windowMs = windowMs;
  }

  async take(key) {
    const redisKey = `${this.prefix}:${key}`;
    const result = await this.client.eval(
      "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; return {n,redis.call('PTTL',KEYS[1])}",
      { keys: [redisKey], arguments: [String(this.windowMs)] },
    );
    return {
      count: Number(result[0]),
      retryMs: Math.max(1, Number(result[1]) || this.windowMs),
    };
  }
}

export class SharedJsonCache {
  constructor({ client = null, prefix, maxEntries }) {
    this.client = client;
    this.prefix = prefix;
    this.maxEntries = maxEntries;
    this.local = new Map();
  }

  async get(key) {
    if (this.client) {
      try {
        const raw = await this.client.get(`${this.prefix}:${key}`);
        return raw ? JSON.parse(raw) : undefined;
      } catch (error) {
        console.warn(`Redis cache read failed (${this.prefix}):`, error?.message || error);
      }
    }
    return this.local.get(key);
  }

  async set(key, value, ttlMs) {
    if (this.client) {
      try {
        await this.client.set(`${this.prefix}:${key}`, JSON.stringify(value), {
          PX: Math.max(1000, Math.trunc(ttlMs)),
        });
      } catch (error) {
        console.warn(`Redis cache write failed (${this.prefix}):`, error?.message || error);
      }
    }
    if (this.local.has(key)) this.local.delete(key);
    while (this.local.size >= this.maxEntries) this.local.delete(this.local.keys().next().value);
    this.local.set(key, value);
  }

  sweep(maxAgeMs, timestampField) {
    const now = Date.now();
    for (const [key, value] of this.local) {
      if (now - Number(value?.[timestampField] || 0) >= maxAgeMs) this.local.delete(key);
    }
  }
}

export class LocalHistoryRepository {
  constructor({ filePath, maxHistoryDays, sampleRetentionDays = 0, inactiveAccountDays = 0, now = Date.now }) {
    this.filePath = filePath;
    this.maxHistoryDays = maxHistoryDays;
    this.sampleRetentionDays = sampleRetentionDays;
    this.inactiveAccountDays = inactiveAccountDays;
    this.now = now;
    this.store = { version: 3, accounts: {}, meta: {}, access: {} };
  }

  async initialize() {
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, "utf8"));
      this.store = {
        version: Number(parsed?.version) || 1,
        accounts: objectValue(parsed?.accounts),
        meta: objectValue(parsed?.meta),
        access: objectValue(parsed?.access),
      };
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    const migratedUtc = this.migrateToUtc();
    const migratedMetricKnowledge = this.migrateMetricKnowledge();
    if (migratedUtc || migratedMetricKnowledge) await this.persist();
  }

  backendName() { return "json"; }
  async healthCheck() { return true; }
  async close() { await this.persist(); }
  async countAccounts() { return Object.keys(this.store.accounts).length; }
  async hasAccount(key) { return Object.hasOwn(this.store.accounts, key); }
  async listAccounts() { return Object.keys(this.store.accounts).sort(); }
  async getHistory(key, { touch = false } = {}) {
    if (touch && Object.hasOwn(this.store.accounts, key)) {
      const now = this.now();
      if (now - Number(this.store.access[key] || 0) >= DAY_MS) {
        this.store.access[key] = now;
        await this.persist();
      }
    }
    return normalizeStoredSamples(this.store.accounts[key], this.maxHistoryDays, this.sampleRetentionDays, this.now());
  }
  async getMeta(key) { return { ...(this.store.meta[key] || {}) }; }
  async setMeta(key, meta) {
    this.store.meta[key] = { ...meta };
    await this.persist();
  }
  async storeSamples(key, samples, { preferExisting = false, touchAccess = true } = {}) {
    const existing = await this.getHistory(key);
    const previousAccess = Number(this.store.access[key] || 0);
    const next = mergeSamples(existing, samples, {
      preferExisting,
      maxHistoryDays: this.maxHistoryDays,
      sampleRetentionDays: this.sampleRetentionDays,
      now: this.now(),
    });
    this.store.accounts[key] = next;
    if (touchAccess) this.store.access[key] = this.now();
    if (JSON.stringify(existing) !== JSON.stringify(next) || (touchAccess && this.now() - previousAccess >= DAY_MS)) await this.persist();
    return next;
  }
  async registerAccount(key, samples, maxAccounts) {
    const existed = Object.hasOwn(this.store.accounts, key);
    if (!existed && Object.keys(this.store.accounts).length >= maxAccounts) return false;
    if (!existed) this.store.accounts[key] = [];
    try {
      await this.storeSamples(key, samples);
      return true;
    } catch (error) {
      if (!existed) delete this.store.accounts[key];
      throw error;
    }
  }
  async deleteAccount(key) {
    const existed = Object.hasOwn(this.store.accounts, key);
    delete this.store.accounts[key];
    delete this.store.meta[key];
    delete this.store.access[key];
    if (existed) await this.persist();
    return existed;
  }
  async prune() {
    const now = this.now();
    let deletedAccounts = 0;
    let deletedSamples = 0;
    if (this.inactiveAccountDays > 0) {
      const cutoff = now - this.inactiveAccountDays * DAY_MS;
      for (const key of Object.keys(this.store.accounts)) {
        const samples = this.store.accounts[key] || [];
        const lastSample = samples.at(-1)?.ts || 0;
        const lastAccess = Number(this.store.access[key] || lastSample);
        if (lastAccess < cutoff) {
          deletedSamples += samples.length;
          await this.deleteAccount(key);
          deletedAccounts += 1;
        }
      }
    }
    if (this.sampleRetentionDays > 0) {
      const cutoff = startOfDay(now) - this.sampleRetentionDays * DAY_MS;
      for (const key of Object.keys(this.store.accounts)) {
        const existing = this.store.accounts[key] || [];
        const kept = existing.filter((sample) => sample.ts >= cutoff);
        deletedSamples += existing.length - kept.length;
        this.store.accounts[key] = kept;
      }
    }
    if (deletedAccounts || deletedSamples) await this.persist();
    return { deletedAccounts, deletedSamples };
  }
  async importLegacyStore(parsed) {
    for (const [key, samples] of Object.entries(objectValue(parsed?.accounts))) {
      await this.storeSamples(key, samples, { preferExisting: true });
    }
    for (const [key, meta] of Object.entries(objectValue(parsed?.meta))) await this.setMeta(key, meta);
  }
  migrateToUtc() {
    if (this.store.version >= 2) return false;
    for (const samples of Object.values(this.store.accounts)) {
      if (!Array.isArray(samples)) continue;
      for (const sample of samples) {
        const corrected = utcDayFromLabel(sample?.dayLabel, numberValue(sample?.ts));
        if (corrected) sample.ts = corrected;
      }
    }
    this.store.version = 2;
    return true;
  }
  migrateMetricKnowledge() {
    if (this.store.version >= 3) return false;
    for (const [key, samples] of Object.entries(this.store.accounts)) {
      this.store.accounts[key] = (Array.isArray(samples) ? samples : [])
        .map(normalizeHistorySample)
        .filter(Boolean);
    }
    this.store.version = 3;
    return true;
  }
  async persist() {
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true, mode: 0o700 });
    const tempPath = `${this.filePath}.tmp`;
    fs.writeFileSync(tempPath, JSON.stringify(this.store, null, 2), { mode: 0o600 });
    fs.renameSync(tempPath, this.filePath);
  }
}

export class PostgresHistoryRepository {
  constructor({ pool, maxHistoryDays, sampleRetentionDays = 0, inactiveAccountDays = 0 }) {
    this.pool = pool;
    this.maxHistoryDays = maxHistoryDays;
    this.sampleRetentionDays = sampleRetentionDays;
    this.inactiveAccountDays = inactiveAccountDays;
  }

  backendName() { return "postgres"; }
  async healthCheck() {
    await this.pool.query("SELECT 1");
    return true;
  }
  async initialize() {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query("SELECT pg_advisory_xact_lock(hashtext('twidget_history_schema'))");
      await client.query(`
      CREATE TABLE IF NOT EXISTS twidget_history_accounts (
        username varchar(15) PRIMARY KEY,
        metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
        created_at timestamptz NOT NULL DEFAULT now(),
        last_accessed_at timestamptz NOT NULL DEFAULT now()
      );
      CREATE TABLE IF NOT EXISTS twidget_history_samples (
        username varchar(15) NOT NULL REFERENCES twidget_history_accounts(username) ON DELETE CASCADE,
        sample_date date NOT NULL,
        followers bigint NOT NULL CHECK (followers >= 0),
        followers_known boolean NOT NULL DEFAULT true,
        following bigint NOT NULL CHECK (following >= 0),
        following_known boolean NOT NULL DEFAULT true,
        posts bigint NOT NULL CHECK (posts >= 0),
        posts_known boolean NOT NULL DEFAULT true,
        likes bigint NOT NULL CHECK (likes >= 0),
        likes_known boolean NOT NULL DEFAULT true,
        source varchar(32),
        estimated boolean NOT NULL DEFAULT false,
        observed_at timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (username, sample_date)
      );
      CREATE INDEX IF NOT EXISTS twidget_history_samples_username_date_idx
        ON twidget_history_samples (username, sample_date DESC);
      CREATE INDEX IF NOT EXISTS twidget_history_accounts_last_accessed_idx
        ON twidget_history_accounts (last_accessed_at);

      ALTER TABLE twidget_history_samples ADD COLUMN IF NOT EXISTS followers_known boolean;
      ALTER TABLE twidget_history_samples ADD COLUMN IF NOT EXISTS following_known boolean;
      ALTER TABLE twidget_history_samples ADD COLUMN IF NOT EXISTS posts_known boolean;
      ALTER TABLE twidget_history_samples ADD COLUMN IF NOT EXISTS likes_known boolean;

      UPDATE twidget_history_samples SET
        followers_known = COALESCE(followers_known, NOT estimated AND (source IS DISTINCT FROM 'wayback' OR followers > 0)),
        following_known = COALESCE(following_known, NOT estimated AND (source IS DISTINCT FROM 'wayback' OR following > 0)),
        posts_known = COALESCE(posts_known, NOT estimated AND (source IS DISTINCT FROM 'wayback' OR posts > 0)),
        likes_known = COALESCE(likes_known, NOT estimated AND (source IS DISTINCT FROM 'wayback' OR likes > 0))
      WHERE followers_known IS NULL OR following_known IS NULL OR posts_known IS NULL OR likes_known IS NULL;

      UPDATE twidget_history_samples SET source = 'live'
      WHERE source IS NULL AND NOT estimated;

      ALTER TABLE twidget_history_samples ALTER COLUMN followers_known SET DEFAULT true;
      ALTER TABLE twidget_history_samples ALTER COLUMN followers_known SET NOT NULL;
      ALTER TABLE twidget_history_samples ALTER COLUMN following_known SET DEFAULT true;
      ALTER TABLE twidget_history_samples ALTER COLUMN following_known SET NOT NULL;
      ALTER TABLE twidget_history_samples ALTER COLUMN posts_known SET DEFAULT true;
      ALTER TABLE twidget_history_samples ALTER COLUMN posts_known SET NOT NULL;
      ALTER TABLE twidget_history_samples ALTER COLUMN likes_known SET DEFAULT true;
      ALTER TABLE twidget_history_samples ALTER COLUMN likes_known SET NOT NULL;
      `);
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }
  async close() { await this.pool.end(); }
  async countAccounts() {
    const result = await this.pool.query("SELECT count(*)::int AS count FROM twidget_history_accounts");
    return Number(result.rows[0]?.count || 0);
  }
  async hasAccount(key) {
    const result = await this.pool.query("SELECT 1 FROM twidget_history_accounts WHERE username = $1", [key]);
    return result.rowCount > 0;
  }
  async listAccounts() {
    const result = await this.pool.query("SELECT username FROM twidget_history_accounts ORDER BY username");
    return result.rows.map((row) => row.username);
  }
  async getHistory(key, { touch = false } = {}) {
    if (touch) {
      await this.pool.query(`
        UPDATE twidget_history_accounts SET last_accessed_at = now()
        WHERE username = $1 AND last_accessed_at < now() - interval '1 day'
      `, [key]);
    }
    const params = [key];
    let retention = "";
    if (this.sampleRetentionDays > 0) {
      params.push(this.sampleRetentionDays);
      retention = "AND sample_date >= (CURRENT_DATE - $2::int)";
    }
    const result = await this.pool.query(`
      SELECT sample_date::text, followers::text, followers_known,
             following::text, following_known, posts::text, posts_known,
             likes::text, likes_known, source, estimated
      FROM twidget_history_samples
      WHERE username = $1 ${retention}
      ORDER BY sample_date
    `, params);
    return capSamples(result.rows.map(sampleFromRow), {
      maxHistoryDays: this.maxHistoryDays,
      sampleRetentionDays: this.sampleRetentionDays,
      now: Date.now(),
    });
  }
  async getMeta(key) {
    const result = await this.pool.query("SELECT metadata FROM twidget_history_accounts WHERE username = $1", [key]);
    return objectValue(result.rows[0]?.metadata);
  }
  async setMeta(key, meta) {
    await this.pool.query(`
      INSERT INTO twidget_history_accounts (username, metadata)
      VALUES ($1, $2::jsonb)
      ON CONFLICT (username) DO UPDATE SET metadata = EXCLUDED.metadata
    `, [key, JSON.stringify(meta)]);
  }
  async storeSamples(key, samples, { preferExisting = false, touchAccess = true } = {}) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query(touchAccess ? `
        INSERT INTO twidget_history_accounts (username) VALUES ($1)
        ON CONFLICT (username) DO UPDATE SET last_accessed_at = now()
      ` : `
        INSERT INTO twidget_history_accounts (username) VALUES ($1)
        ON CONFLICT (username) DO NOTHING
      `, [key]);
      for (const sample of samples) {
        const normalized = normalizeHistorySample(sample);
        if (!normalized) continue;
        const update = `DO UPDATE SET
               followers=CASE WHEN EXCLUDED.followers_known THEN EXCLUDED.followers ELSE twidget_history_samples.followers END,
               followers_known=twidget_history_samples.followers_known OR EXCLUDED.followers_known,
               following=CASE WHEN EXCLUDED.following_known THEN EXCLUDED.following ELSE twidget_history_samples.following END,
               following_known=twidget_history_samples.following_known OR EXCLUDED.following_known,
               posts=CASE WHEN EXCLUDED.posts_known THEN EXCLUDED.posts ELSE twidget_history_samples.posts END,
               posts_known=twidget_history_samples.posts_known OR EXCLUDED.posts_known,
               likes=CASE WHEN EXCLUDED.likes_known THEN EXCLUDED.likes ELSE twidget_history_samples.likes END,
               likes_known=twidget_history_samples.likes_known OR EXCLUDED.likes_known,
               source=EXCLUDED.source,
               estimated=EXCLUDED.estimated, observed_at=now()
             WHERE ${preferExisting
               ? "twidget_history_samples.estimated AND NOT EXCLUDED.estimated"
               : "twidget_history_samples.estimated OR NOT EXCLUDED.estimated"}`;
        await client.query(`
          INSERT INTO twidget_history_samples
            (username, sample_date, followers, followers_known, following, following_known,
             posts, posts_known, likes, likes_known, source, estimated)
          VALUES ($1, (to_timestamp($2 / 1000.0) AT TIME ZONE 'UTC')::date,
                  $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
          ON CONFLICT (username, sample_date) ${update}
        `, [key, normalized.ts, normalized.followers, normalized.followersKnown,
          normalized.following, normalized.followingKnown, normalized.posts, normalized.postsKnown,
          normalized.likes, normalized.likesKnown, normalized.src || null, Boolean(normalized.est)]);
      }
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
    return this.getHistory(key);
  }
  async registerAccount(key, samples, maxAccounts) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query("SELECT pg_advisory_xact_lock(hashtext('twidget_history_registration'))");
      const exists = await client.query("SELECT 1 FROM twidget_history_accounts WHERE username = $1", [key]);
      if (!exists.rowCount) {
        const count = await client.query("SELECT count(*)::int AS count FROM twidget_history_accounts");
        if (Number(count.rows[0]?.count || 0) >= maxAccounts) {
          await client.query("ROLLBACK");
          return false;
        }
      }
      await client.query(`
        INSERT INTO twidget_history_accounts (username) VALUES ($1)
        ON CONFLICT (username) DO UPDATE SET last_accessed_at = now()
      `, [key]);
      for (const sample of samples) {
        const normalized = normalizeHistorySample(sample);
        if (!normalized) continue;
        await client.query(`
          INSERT INTO twidget_history_samples
            (username, sample_date, followers, followers_known, following, following_known,
             posts, posts_known, likes, likes_known, source, estimated)
          VALUES ($1, (to_timestamp($2 / 1000.0) AT TIME ZONE 'UTC')::date,
                  $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
          ON CONFLICT (username, sample_date) DO UPDATE SET
            followers=CASE WHEN EXCLUDED.followers_known THEN EXCLUDED.followers ELSE twidget_history_samples.followers END,
            followers_known=twidget_history_samples.followers_known OR EXCLUDED.followers_known,
            following=CASE WHEN EXCLUDED.following_known THEN EXCLUDED.following ELSE twidget_history_samples.following END,
            following_known=twidget_history_samples.following_known OR EXCLUDED.following_known,
            posts=CASE WHEN EXCLUDED.posts_known THEN EXCLUDED.posts ELSE twidget_history_samples.posts END,
            posts_known=twidget_history_samples.posts_known OR EXCLUDED.posts_known,
            likes=CASE WHEN EXCLUDED.likes_known THEN EXCLUDED.likes ELSE twidget_history_samples.likes END,
            likes_known=twidget_history_samples.likes_known OR EXCLUDED.likes_known,
            source=EXCLUDED.source, estimated=EXCLUDED.estimated, observed_at=now()
        `, [key, normalized.ts, normalized.followers, normalized.followersKnown,
          normalized.following, normalized.followingKnown, normalized.posts, normalized.postsKnown,
          normalized.likes, normalized.likesKnown, normalized.src || null, Boolean(normalized.est)]);
      }
      await client.query("COMMIT");
      return true;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }
  async deleteAccount(key) {
    const result = await this.pool.query("DELETE FROM twidget_history_accounts WHERE username = $1", [key]);
    return result.rowCount > 0;
  }
  async prune() {
    let deletedAccounts = 0;
    let deletedSamples = 0;
    if (this.inactiveAccountDays > 0) {
      const result = await this.pool.query(
        "DELETE FROM twidget_history_accounts WHERE last_accessed_at < now() - ($1::int * interval '1 day')",
        [this.inactiveAccountDays],
      );
      deletedAccounts = result.rowCount;
    }
    if (this.sampleRetentionDays > 0) {
      const result = await this.pool.query(
        "DELETE FROM twidget_history_samples WHERE sample_date < CURRENT_DATE - $1::int",
        [this.sampleRetentionDays],
      );
      deletedSamples = result.rowCount;
    }
    return { deletedAccounts, deletedSamples };
  }
  async importLegacyStore(parsed) {
    for (const [key, samples] of Object.entries(objectValue(parsed?.accounts))) {
      await this.storeSamples(key, samples, { preferExisting: true });
    }
    for (const [key, meta] of Object.entries(objectValue(parsed?.meta))) await this.setMeta(key, meta);
  }
}

export function normalizeHistorySample(sample) {
  if (!sample || typeof sample !== "object") return null;
  const rawTimestamp = numberValue(sample.ts ?? sample.timestamp ?? sample.syncedAt);
  const ts = utcDayFromLabel(sample.dayLabel, rawTimestamp) || startOfDay(rawTimestamp);
  if (!ts) return null;
  const suppliedSrc = stringValue(sample.src);
  const estimated = Boolean(sample.est);
  const src = suppliedSrc || (estimated ? "" : "live");
  const normalized = {
    dayLabel: stringValue(sample.dayLabel) || dayLabel(ts),
    followers: numberValue(sample.followers),
    followersKnown: metricKnown(sample, "followers", src, estimated),
    following: numberValue(sample.following ?? sample.followings),
    followingKnown: metricKnown(sample, Object.hasOwn(sample, "following") ? "following" : "followings", src, estimated),
    posts: numberValue(sample.posts),
    postsKnown: metricKnown(sample, "posts", src, estimated),
    likes: numberValue(sample.likes),
    likesKnown: metricKnown(sample, "likes", src, estimated),
    ts,
    ...(src ? { src } : {}),
    ...(estimated ? { est: true } : {}),
  };
  return normalized;
}

export function mergeSamples(existing, samples, { preferExisting, maxHistoryDays, sampleRetentionDays, now }) {
  const ordered = preferExisting ? [...samples, ...existing] : [...existing, ...samples];
  const byDay = new Map();
  for (const item of ordered) {
    const normalized = normalizeHistorySample(item);
    if (!normalized) continue;
    const current = byDay.get(normalized.ts);
    if (current && !current.est && normalized.est) continue;
    if (current) preserveKnownMetrics(normalized, current);
    byDay.set(normalized.ts, normalized);
  }
  return capSamples([...byDay.values()].sort((a, b) => a.ts - b.ts), {
    maxHistoryDays,
    sampleRetentionDays,
    now,
  });
}

function normalizeStoredSamples(samples, maxHistoryDays, sampleRetentionDays, now) {
  return capSamples((Array.isArray(samples) ? samples : []).map(normalizeHistorySample).filter(Boolean), {
    maxHistoryDays,
    sampleRetentionDays,
    now,
  });
}

function capSamples(samples, { maxHistoryDays, sampleRetentionDays, now }) {
  const today = startOfDay(now);
  const retentionCutoff = sampleRetentionDays > 0 ? today - sampleRetentionDays * DAY_MS : 0;
  const retained = retentionCutoff ? samples.filter((sample) => sample.ts >= retentionCutoff) : samples;
  const dailyCutoff = today - maxHistoryDays * DAY_MS;
  const recent = retained.filter((sample) => sample.ts >= dailyCutoff);
  const older = retained.filter((sample) => sample.ts < dailyCutoff);
  const monthly = new Map();
  for (const sample of older) {
    const date = new Date(sample.ts);
    monthly.set(date.getUTCFullYear() * 12 + date.getUTCMonth(), sample);
  }
  return [...monthly.values(), ...recent];
}

function sampleFromRow(row) {
  const ts = Date.parse(`${row.sample_date}T00:00:00Z`);
  return {
    dayLabel: dayLabel(ts),
    followers: numberValue(row.followers),
    followersKnown: Boolean(row.followers_known),
    following: numberValue(row.following),
    followingKnown: Boolean(row.following_known),
    posts: numberValue(row.posts),
    postsKnown: Boolean(row.posts_known),
    likes: numberValue(row.likes),
    likesKnown: Boolean(row.likes_known),
    ts,
    ...(row.source ? { src: row.source } : {}),
    ...(row.estimated ? { est: true } : {}),
  };
}

function metricKnown(sample, field, src, estimated) {
  if (estimated) return false;
  const knownField = field === "followings" ? "followingKnown" : `${field}Known`;
  const explicit = sample[knownField];
  if (typeof explicit === "boolean") return explicit && Object.hasOwn(sample, field);
  if (!Object.hasOwn(sample, field)) return false;
  const value = numberValue(sample[field]);
  // Legacy Wayback rows wrote zero when a field was absent. Other stored
  // sources wrote zero only after observing/submitting the metric, so their
  // zero remains a genuine known value during migration.
  return src !== "wayback" || value > 0;
}

function preserveKnownMetrics(target, previous) {
  for (const field of ["followers", "following", "posts", "likes"]) {
    const knownField = `${field}Known`;
    if (!target[knownField] && previous[knownField]) {
      target[field] = previous[field];
      target[knownField] = true;
    }
  }
}

function utcDayFromLabel(label, approximateTimestamp) {
  const match = /^([A-Z][a-z]{2})\s+(\d{1,2})$/.exec(stringValue(label).trim());
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const month = match ? months.indexOf(match[1]) : -1;
  const day = match ? Number(match[2]) : 0;
  if (month < 0 || day < 1 || day > 31 || !approximateTimestamp) return 0;
  const approximate = new Date(approximateTimestamp);
  if (Number.isNaN(approximate.getTime())) return 0;
  const year = approximate.getUTCFullYear();
  return [year - 1, year, year + 1]
    .map((candidateYear) => Date.UTC(candidateYear, month, day))
    .filter((candidate) => new Date(candidate).getUTCMonth() === month && new Date(candidate).getUTCDate() === day)
    .sort((a, b) => Math.abs(a - approximateTimestamp) - Math.abs(b - approximateTimestamp))[0] || 0;
}

function startOfDay(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 0;
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
}

function dayLabel(ts) {
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" }).format(new Date(ts));
}

function numberValue(value) {
  if (typeof value === "number" && Number.isFinite(value)) return Math.max(0, Math.trunc(value));
  if (typeof value === "string" && /^\d+$/.test(value)) return Number(value);
  return 0;
}

function stringValue(value) { return typeof value === "string" ? value : ""; }
function objectValue(value) { return value && typeof value === "object" && !Array.isArray(value) ? value : {}; }
