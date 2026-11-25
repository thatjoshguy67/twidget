import { mergeInteractionData } from "./interactions.js";
import fs from "node:fs";
import path from "node:path";
import { setImmediate as nextTurn } from "node:timers/promises";
import { appendFollowerChange, followerChangeObservation, canResumeTopFollowersScan, topFollowersAttemptBlocked, topFollowersRetryState } from "./top-followers.js";
import { PRIMARY_PROFILE_RETENTION_DAYS, primaryProfileSummary } from "./primary-profiles.js";

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
    this.store = { version: 4, accounts: {}, meta: {}, access: {}, topFollowers: {}, interactions: {}, primaryProfiles: {} };
    this.revision = 0;
    this.persistedRevision = 0;
    this.flushPromise = null;
    this.rankedSnapshots = new WeakMap();
    this.scanIdentities = new WeakMap();
  }

  async initialize() {
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, "utf8"));
      this.store = {
        version: Number(parsed?.version) || 1,
        accounts: objectValue(parsed?.accounts),
        meta: objectValue(parsed?.meta),
        access: objectValue(parsed?.access),
        topFollowers: objectValue(parsed?.topFollowers),
        interactions: objectValue(parsed?.interactions),
        primaryProfiles: objectValue(parsed?.primaryProfiles),
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
  async recordPrimaryProfile(hash, username, maxInstallations) {
    const reports = this.store.primaryProfiles;
    if (username === null) {
      delete reports[hash];
    } else {
      if (!Object.hasOwn(this.store.accounts, username)) return "account_missing";
      this.prunePrimaryProfiles();
      if (!Object.hasOwn(reports, hash) && Object.keys(reports).length >= maxInstallations) return "capacity";
      reports[hash] = { username, lastSeenAt: this.now() };
    }
    await this.persist();
    return "ok";
  }
  prunePrimaryProfiles() {
    let deleted = 0;
    for (const [hash, report] of Object.entries(this.store.primaryProfiles)) {
      if (report.lastSeenAt < this.now() - PRIMARY_PROFILE_RETENTION_DAYS * DAY_MS || !Object.hasOwn(this.store.accounts, report.username)) {
        delete this.store.primaryProfiles[hash];
        deleted += 1;
      }
    }
    return deleted;
  }
  async primaryProfileCounts() { return primaryProfileSummary(Object.values(this.store.primaryProfiles), this.now()); }
  async getHistory(key, { touch = false, all = false } = {}) {
    if (touch && Object.hasOwn(this.store.accounts, key)) {
      const now = this.now();
      if (now - Number(this.store.access[key] || 0) >= DAY_MS) {
        this.store.access[key] = now;
        await this.persist();
      }
    }
    return normalizeStoredSamples(this.store.accounts[key], all ? Number.MAX_SAFE_INTEGER : this.maxHistoryDays, this.sampleRetentionDays, this.now());
  }
  async getInteractions(key) { return this.store.interactions[key] || { daily: [], posts: [] }; }
  async storeInteractions(key, data, { expectedUserId } = {}) {
    if (!Object.hasOwn(this.store.accounts, key) || (expectedUserId !== undefined && this.store.meta[key]?.userId !== expectedUserId)) return false;
    this.store.interactions[key] = mergeInteractionData(this.store.interactions[key], data);
    await this.persist();
    return true;
  }
  async getMeta(key) { return { ...(this.store.meta[key] || {}) }; }
  async setMeta(key, meta) {
    const lastSeenAt = this.store.meta[key]?.topFollowersLastSeenAt || 0;
    this.store.meta[key] = { ...meta };
    if (lastSeenAt > (meta.topFollowersLastSeenAt || 0)) this.store.meta[key].topFollowersLastSeenAt = lastSeenAt;
    await this.persist();
  }
  async recordTopFollowersActivity(key, timestamp) {
    if (!Object.hasOwn(this.store.accounts, key)) return false;
    const meta = this.store.meta[key] || {};
    this.store.meta[key] = { ...meta, topFollowersLastSeenAt: Math.max(meta.topFollowersLastSeenAt || 0, timestamp) };
    await this.persist();
    return true;
  }
  async patchMeta(key, patch) {
    // Patches never enroll an account or recreate one deleted during a fetch.
    if (!Object.hasOwn(this.store.accounts, key)) return false;
    this.store.meta[key] = { ...this.store.meta[key], ...patch };
    await this.persist();
    return true;
  }
  async storeSamples(key, samples, { preferExisting = false, touchAccess = true, requireExisting = false, expectedUserId } = {}) {
    if (requireExisting && !Object.hasOwn(this.store.accounts, key)) return [];
    const ownerId = this.store.meta[key]?.userId;
    if (expectedUserId !== undefined && ownerId && ownerId !== expectedUserId) return [];
    // No await between reading and replacing the account: the complete merge is atomic.
    const existing = normalizeStoredSamples(this.store.accounts[key], this.maxHistoryDays, this.sampleRetentionDays, this.now());
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
  async registerAccount(key, samples, maxAccounts, userId = "") {
    const existed = Object.hasOwn(this.store.accounts, key);
    // Legacy histories keep working, but only new enrollments acquire an ID.
    if (existed && this.store.meta[key]?.userId && this.store.meta[key].userId !== userId) return false;
    if (!existed && Object.keys(this.store.accounts).length >= maxAccounts) return false;
    if (!existed) {
      this.store.accounts[key] = [];
      if (userId) this.store.meta[key] = { userId };
    }
    try {
      await this.storeSamples(key, samples);
      return true;
    } catch (error) {
      if (!existed) delete this.store.accounts[key];
      throw error;
    }
  }

  async renameAccount(oldKey, newKey, userId, { allowLegacy = false } = {}) {
    const source = this.store.accounts[oldKey];
    const sourceMeta = this.store.meta[oldKey] || {};
    const targetMeta = this.store.meta[newKey] || {};
    validateHandleMove({ source: !!source, destination: !!this.store.accounts[newKey], sourceMeta, targetMeta, userId, allowLegacy });
    if (!source || oldKey === newKey) { await this.persist(); return { moved: false }; }
    // No await until all state is replaced; publication uses the existing atomic file writer.
    const target = this.store.accounts[newKey] || [];
    this.store.accounts[newKey] = mergeSamples(source, target, {
      // Moving records must not apply age-based compaction as a side effect.
      maxHistoryDays: Number.MAX_SAFE_INTEGER, sampleRetentionDays: 0, now: this.now(),
    });
    this.store.meta[newKey] = { ...sourceMeta, ...targetMeta, userId };
    this.store.access[newKey] = Math.max(this.store.access[oldKey] || 0, this.store.access[newKey] || 0);
    if (!this.store.topFollowers[newKey] && this.store.topFollowers[oldKey]) this.store.topFollowers[newKey] = this.store.topFollowers[oldKey];
    this.store.interactions[newKey] = mergeInteractionData(this.store.interactions[newKey], this.store.interactions[oldKey] || { daily: [], posts: [] });
    for (const report of Object.values(this.store.primaryProfiles)) {
      if (report.username === oldKey) report.username = newKey;
    }
    this.removeAccountState(oldKey);
    await this.persist();
    return { moved: true };
  }
  async deleteAccount(key) {
    const existed = this.removeAccountState(key);
    if (existed) await this.persist();
    return existed;
  }
  removeAccountState(key) {
    const existed = Object.hasOwn(this.store.accounts, key);
    delete this.store.accounts[key];
    delete this.store.meta[key];
    delete this.store.access[key];
    delete this.store.topFollowers[key];
    delete this.store.interactions[key];
    for (const [hash, report] of Object.entries(this.store.primaryProfiles)) {
      if (report.username === key) delete this.store.primaryProfiles[hash];
    }
    return existed;
  }
  async getTopFollowersScan(key) {
    const scan = this.store.topFollowers[key];
    return scan ? topFollowersScanSummary(scan) : null;
  }
  async startTopFollowersScan(key, scanId, now = this.now(), options = {}) {
    options = typeof options === "string" ? { kind: options } : options;
    const existing = this.store.topFollowers[key];
    if ((!options.plan?.provider || (existing?.provider || "twitterapis") === options.plan.provider) && topFollowersAttemptBlocked(existing, now, options.refreshMs)) return topFollowersScanSummary(existing);
    if ((!options.plan?.provider || (existing?.provider || "twitterapis") === options.plan.provider) && canResumeTopFollowersScan(existing, { ...options, now })) {
      existing.status = "running";
      existing.error = "";
      existing.updatedAt = now;
      await this.persist();
      return topFollowersScanSummary(existing);
    }
    const previous = existing?.status === "complete" ? existing : existing?.previous;
    const kind = options.kind === "head" && previous ? "head" : "full";
    const scan = {
      ...options.plan,
      userId: this.store.meta[key]?.userId || this.store.meta[key]?.verifiedUserId || "",
      kind,
      baseScanId: kind === "head" ? previous.scanId : "",
      fullCompletedAt: kind === "head" ? Number(previous.fullCompletedAt || previous.lastFullCompletedAt || previous.completedAt || 0) : 0,
      scanId, status: "running", cursor: "", pages: 0, scanned: 0,
      startedAt: now, updatedAt: now, completedAt: 0, error: "",
      followers: kind === "head" ? previous.followers.map(value => ({ ...value })) : [],
      ...(kind === "head" && previous ? { fullBaseline: previous.kind === "head" ? previous.fullBaseline : {
        ...topFollowersScanSummary(previous), followers: previous.followers.map(value => ({ id: value.id })),
      } } : {}),
      ...(previous?.status === "complete" ? { previous } : {}),
    };
    scan.scanned = scan.followers.length;
    this.store.topFollowers[key] = scan;
    await this.persist();
    return topFollowersScanSummary(scan);
  }
  async appendTopFollowersPage(key, scanId, followers, cursor, now = this.now()) {
    followers = uniqueTopFollowerPage(followers);
    const scan = this.store.topFollowers[key];
    if (!scan || scan.scanId !== scanId || scan.status !== "running") return null;
    let index = this.scanIdentities.get(scan);
    if (!index) {
      index = followerIndex(scan.followers);
      this.scanIdentities.set(scan, index);
    }
    let previousIndex;
    if (scan.kind === "head" && scan.previous) {
      previousIndex = this.scanIdentities.get(scan.previous);
      if (!previousIndex) {
        previousIndex = followerIndex(scan.previous.followers);
        this.scanIdentities.set(scan.previous, previousIndex);
      }
    }
    for (const value of followers) {
      if (scan.kind === "head") scan.knownStreak = indexedFollower(previousIndex, value) ? Number(scan.knownStreak || 0) + 1 : 0;
      if (!topFollowerIdentity(value)) continue;
      const row = indexedFollower(index, value);
      if (row) { if (scan.kind !== "head") Object.assign(row, value); }
      else {
        const added = { ...value, scanIndex: scan.followers.length };
        scan.followers.push(added);
        indexFollower(index, added);
      }
    }
    scan.processed = Number(scan.processed || 0) + followers.length;
    scan.failureCount = 0;
    scan.retryAt = 0;
    scan.cursor = cursor;
    scan.pages += 1;
    scan.scanned = scan.followers.length;
    scan.updatedAt = now;
    await this.persist();
    return topFollowersScanSummary(scan);
  }
  async completeTopFollowersScan(key, scanId, now = this.now()) {
    const scan = this.store.topFollowers[key];
    if (!scan || scan.scanId !== scanId || scan.status !== "running") return null;
    scan.scanned = scan.followers.length;
    scan.status = "complete";
    scan.cursor = "";
    scan.completedAt = now;
    scan.updatedAt = now;
    if (scan.kind !== "head") scan.lastFullCompletedAt = now;
    scan.fullCompletedAt = scan.kind === "head" ? scan.fullCompletedAt : scan.lastFullCompletedAt;
    if (scan.kind !== "head" && scan.previous) {
      const before = scan.previous.kind === "head" ? scan.previous.fullBaseline : scan.previous;
      const ids = values => {
        if (!values || values.some(row => !/^[0-9]+$/.test(row.id || ""))) return null;
        const result = new Set(values.map(row => row.id));
        return result.size === values.length ? result : null;
      };
      const oldIds = ids(before?.followers), newIds = ids(scan.followers);
      const meta = this.store.meta[key] || {};
      const observation = oldIds && newIds ? followerChangeObservation(before, scan, {
        newFollowers: [...newIds].filter(id => !oldIds.has(id)).length,
        lostFollowers: [...oldIds].filter(id => !newIds.has(id)).length,
      }, meta.userId || meta.verifiedUserId) : null;
      if (observation) this.store.meta[key] = { ...meta, followerChanges: appendFollowerChange(meta, observation) };
    }
    delete scan.previous;
    await this.persist();
    return topFollowersScanSummary(scan);
  }
  async failTopFollowersScan(key, scanId, error, now = this.now()) {
    const scan = this.store.topFollowers[key];
    if (!scan || scan.scanId !== scanId || scan.status !== "running") return null;
    Object.assign(scan, topFollowersRetryState(scan, error, now));
    scan.status = "failed";
    scan.error = String(error || "scan_failed").slice(0, 160);
    scan.updatedAt = now;
    await this.persist();
    return topFollowersScanSummary(scan);
  }
  async getTopFollowersSnapshot(key, { offset = 0, limit = 250 } = {}) {
    const current = this.store.topFollowers[key];
    const scan = current?.status === "complete" ? current : current?.previous;
    if (!scan || scan.status !== "complete" || !scan.followers.length) return null;
    let ordered = this.rankedSnapshots.get(scan);
    if (!ordered) {
      ordered = [...scan.followers].sort(topFollowersRankOrder);
      this.rankedSnapshots.set(scan, ordered);
    }
    return {
      ...topFollowersScanSummary(scan), total: ordered.length,
      followers: ordered.slice(offset, offset + limit),
    };
  }
  async pruneTopFollowers(retentionDays, now = this.now()) {
    if (retentionDays <= 0) return 0;
    const cutoff = now - retentionDays * DAY_MS;
    let deleted = 0;
    for (const [key, scan] of Object.entries(this.store.topFollowers)) {
      if (Number(scan.completedAt || scan.updatedAt || 0) < cutoff) {
        delete this.store.topFollowers[key];
        deleted += 1;
      }
    }
    if (deleted) await this.persist();
    return deleted;
  }
  async prune() {
    const now = this.now();
    const deletedPrimaryProfiles = this.prunePrimaryProfiles();
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
          // Retention is one atomic mutation and one snapshot publication.
          this.removeAccountState(key);
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
    if (deletedAccounts || deletedSamples || deletedPrimaryProfiles) await this.persist();
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
  persist() {
    const requestedRevision = ++this.revision;
    const previous = this.flushPromise || Promise.resolve();
    const write = previous.catch(() => {}).then(async () => {
      if (this.persistedRevision < requestedRevision) await this.flush();
    });
    this.flushPromise = write;
    return write;
  }
  async flush() {
    // Coalesce mutations from the same turn, but acknowledge only after atomic publication.
    await nextTurn();
    await fs.promises.mkdir(path.dirname(this.filePath), { recursive: true, mode: 0o700 });
    const tempPath = `${this.filePath}.tmp`;
    while (this.persistedRevision < this.revision) {
      const revision = this.revision;
      const snapshot = JSON.stringify(this.store);
      await fs.promises.writeFile(tempPath, snapshot, { mode: 0o600 });
      await fs.promises.rename(tempPath, this.filePath);
      this.persistedRevision = revision;
    }
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
      CREATE TABLE IF NOT EXISTS twidget_interactions (
        username varchar(15) PRIMARY KEY REFERENCES twidget_history_accounts(username) ON DELETE CASCADE,
        data jsonb NOT NULL DEFAULT '{"daily":[],"posts":[]}'::jsonb
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

      CREATE TABLE IF NOT EXISTS twidget_primary_profiles (
        installation_hash char(64) PRIMARY KEY,
        username varchar(15) NOT NULL REFERENCES twidget_history_accounts(username) ON DELETE CASCADE,
        last_seen_at timestamptz NOT NULL DEFAULT now()
      );
      CREATE INDEX IF NOT EXISTS twidget_primary_profiles_username_idx ON twidget_primary_profiles(username);
      CREATE INDEX IF NOT EXISTS twidget_primary_profiles_last_seen_idx ON twidget_primary_profiles(last_seen_at);

      CREATE TABLE IF NOT EXISTS twidget_top_follower_scans (
        scan_id uuid PRIMARY KEY,
        username varchar(15) NOT NULL REFERENCES twidget_history_accounts(username) ON DELETE CASCADE,
        status varchar(16) NOT NULL CHECK (status IN ('running', 'complete', 'failed')),
        cursor text NOT NULL DEFAULT '',
        pages integer NOT NULL DEFAULT 0 CHECK (pages >= 0),
        scanned integer NOT NULL DEFAULT 0 CHECK (scanned >= 0),
        error varchar(160) NOT NULL DEFAULT '',
        started_at timestamptz NOT NULL DEFAULT now(),
        updated_at timestamptz NOT NULL DEFAULT now(),
        completed_at timestamptz
      );
      ALTER TABLE twidget_top_follower_scans ADD COLUMN IF NOT EXISTS scan_state jsonb NOT NULL DEFAULT '{}'::jsonb;
      CREATE TABLE IF NOT EXISTS twidget_top_followers (
        scan_id uuid NOT NULL REFERENCES twidget_top_follower_scans(scan_id) ON DELETE CASCADE,
        scan_index integer NOT NULL CHECK (scan_index >= 0),
        follower_id varchar(80) NOT NULL DEFAULT '',
        follower_username varchar(15) NOT NULL,
        display_name varchar(100) NOT NULL DEFAULT '',
        followers bigint NOT NULL CHECK (followers >= 0),
        verified boolean NOT NULL DEFAULT false,
        avatar text NOT NULL DEFAULT '',
        mutual boolean,
        PRIMARY KEY (scan_id, scan_index)
      );
      CREATE UNIQUE INDEX IF NOT EXISTS twidget_top_followers_scan_username_idx
        ON twidget_top_followers (scan_id, lower(follower_username));
      CREATE INDEX IF NOT EXISTS twidget_top_follower_scans_username_completed_idx
        ON twidget_top_follower_scans (username, completed_at DESC);
      CREATE INDEX IF NOT EXISTS twidget_top_followers_identity_idx
        ON twidget_top_followers (scan_id, follower_id) WHERE follower_id <> '';
      CREATE INDEX IF NOT EXISTS twidget_top_followers_rank_idx
        ON twidget_top_followers (scan_id, followers DESC, follower_username);

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
  async recordPrimaryProfile(hash, username, maxInstallations) {
    if (username === null) {
      await this.pool.query("DELETE FROM twidget_primary_profiles WHERE installation_hash = $1", [hash]);
      return "ok";
    }
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      // Bound cardinality across replicas, and prevent deletion between validation and insertion.
      await client.query("SELECT pg_advisory_xact_lock(hashtext('twidget_primary_profiles'))");
      const account = await client.query("SELECT username FROM twidget_history_accounts WHERE username = $1 FOR KEY SHARE", [username]);
      if (!account.rowCount) { await client.query("ROLLBACK"); return "account_missing"; }
      await client.query("DELETE FROM twidget_primary_profiles WHERE last_seen_at < now() - ($1::int * interval '1 day')", [PRIMARY_PROFILE_RETENTION_DAYS]);
      const capacity = await client.query(`SELECT
        EXISTS(SELECT 1 FROM twidget_primary_profiles WHERE installation_hash = $1) AS existing,
        (SELECT count(*) FROM twidget_primary_profiles)::int AS total`, [hash]);
      if (!capacity.rows[0].existing && capacity.rows[0].total >= maxInstallations) {
        await client.query("ROLLBACK"); return "capacity";
      }
      await client.query(`INSERT INTO twidget_primary_profiles (installation_hash, username) VALUES ($1, $2)
        ON CONFLICT (installation_hash) DO UPDATE SET username = EXCLUDED.username, last_seen_at = now()`, [hash, username]);
      await client.query("COMMIT");
      return "ok";
    } catch (error) { await client.query("ROLLBACK"); throw error; }
    finally { client.release(); }
  }
  async primaryProfileCounts() {
    const result = await this.pool.query(`SELECT username, count(*)::int AS "primaryInstalls",
      count(*) FILTER (WHERE last_seen_at >= now() - interval '7 days')::int AS "active7d",
      count(*) FILTER (WHERE last_seen_at >= now() - interval '30 days')::int AS "active30d"
      FROM twidget_primary_profiles WHERE last_seen_at >= now() - ($1::int * interval '1 day')
      GROUP BY username ORDER BY "primaryInstalls" DESC, username`, [PRIMARY_PROFILE_RETENTION_DAYS]);
    return result.rows;
  }
  async getHistory(key, { touch = false, all = false } = {}) {
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
      maxHistoryDays: all ? Number.MAX_SAFE_INTEGER : this.maxHistoryDays,
      sampleRetentionDays: this.sampleRetentionDays,
      now: Date.now(),
    });
  }
  async getInteractions(key) {
    const result = await this.pool.query("SELECT data FROM twidget_interactions WHERE username = $1", [key]);
    return result.rows[0]?.data || { daily: [], posts: [] };
  }
  async storeInteractions(key, data, { expectedUserId } = {}) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const account = await client.query("SELECT metadata FROM twidget_history_accounts WHERE username = $1 FOR UPDATE", [key]);
      if (!account.rowCount || (expectedUserId !== undefined && account.rows[0].metadata?.userId !== expectedUserId)) { await client.query("ROLLBACK"); return false; }
      const old = await client.query("SELECT data FROM twidget_interactions WHERE username = $1", [key]);
      const merged = mergeInteractionData(old.rows[0]?.data, data);
      await client.query("INSERT INTO twidget_interactions (username, data) VALUES ($1, $2::jsonb) ON CONFLICT (username) DO UPDATE SET data = EXCLUDED.data", [key, JSON.stringify(merged)]);
      await client.query("COMMIT");
      return true;
    } catch (error) { await client.query("ROLLBACK"); throw error; }
    finally { client.release(); }
  }
  async getMeta(key) {
    const result = await this.pool.query("SELECT metadata FROM twidget_history_accounts WHERE username = $1", [key]);
    return objectValue(result.rows[0]?.metadata);
  }
  async setMeta(key, meta) {
    await this.pool.query(`
      INSERT INTO twidget_history_accounts (username, metadata)
      VALUES ($1, $2::jsonb)
      ON CONFLICT (username) DO UPDATE SET metadata = CASE
        WHEN (twidget_history_accounts.metadata->>'topFollowersLastSeenAt')::bigint
          > COALESCE((EXCLUDED.metadata->>'topFollowersLastSeenAt')::bigint, 0)
        THEN EXCLUDED.metadata || jsonb_build_object('topFollowersLastSeenAt', twidget_history_accounts.metadata->'topFollowersLastSeenAt')
        ELSE EXCLUDED.metadata END
    `, [key, JSON.stringify(meta)]);
  }
  async recordTopFollowersActivity(key, timestamp) {
    const result = await this.pool.query(`
      UPDATE twidget_history_accounts SET metadata = metadata || jsonb_build_object(
        'topFollowersLastSeenAt', GREATEST(COALESCE((metadata->>'topFollowersLastSeenAt')::bigint, 0), $2::bigint))
      WHERE username = $1
    `, [key, timestamp]);
    return result.rowCount > 0;
  }
  async patchMeta(key, patch) {
    const result = await this.pool.query(`
      UPDATE twidget_history_accounts SET metadata = metadata || $2::jsonb
      WHERE username = $1
    `, [key, JSON.stringify(patch)]);
    return result.rowCount > 0;
  }
  async getTopFollowersScan(key) {
    const result = await this.pool.query(`
      SELECT scan_id::text, status, cursor, pages, scanned, error, scan_state,
             extract(epoch from started_at) * 1000 AS started_at_ms,
             extract(epoch from updated_at) * 1000 AS updated_at_ms,
             extract(epoch from completed_at) * 1000 AS completed_at_ms
      FROM twidget_top_follower_scans
      WHERE username = $1
      ORDER BY (status = 'running') DESC, updated_at DESC
      LIMIT 1
    `, [key]);
    return result.rows[0] ? topFollowersScanFromRow(result.rows[0]) : null;
  }
  async startTopFollowersScan(key, scanId, now = Date.now(), options = {}) {
    options = typeof options === "string" ? { kind: options } : options;
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query("SELECT pg_advisory_xact_lock(hashtext('twidget_top_followers:' || $1))", [key]);
      const current = await client.query(`
        SELECT scan_id::text, status, cursor, pages, scanned, error, scan_state,
               extract(epoch from started_at) * 1000 AS started_at_ms,
               extract(epoch from updated_at) * 1000 AS updated_at_ms,
               extract(epoch from completed_at) * 1000 AS completed_at_ms
        FROM twidget_top_follower_scans WHERE username = $1
        ORDER BY (status = 'running') DESC, updated_at DESC LIMIT 1 FOR UPDATE
      `, [key]);
      const existing = current.rows[0] ? topFollowersScanFromRow(current.rows[0]) : null;
      if ((!options.plan?.provider || (existing?.provider || "twitterapis") === options.plan.provider) && topFollowersAttemptBlocked(existing, now, options.refreshMs)) {
        await client.query("COMMIT");
        return existing;
      }
      if ((!options.plan?.provider || (existing?.provider || "twitterapis") === options.plan.provider) && canResumeTopFollowersScan(existing, { ...options, now })) {
        await client.query("UPDATE twidget_top_follower_scans SET status = 'running', error = '', updated_at = now() WHERE scan_id = $1::uuid", [existing.scanId]);
        await client.query("COMMIT");
        return { ...existing, status: "running", error: "", updatedAt: now };
      }
      const previous = options.kind === "head" ? await client.query(`
        SELECT scan_id::text, scanned, scan_state,
               extract(epoch from completed_at) * 1000 AS completed_at_ms
        FROM twidget_top_follower_scans WHERE username = $1 AND status = 'complete'
        ORDER BY completed_at DESC LIMIT 1
      `, [key]) : { rows: [] };
      const source = previous.rows[0];
      const account = await client.query("SELECT metadata FROM twidget_history_accounts WHERE username = $1", [key]);
      const owner = account.rows[0]?.metadata || {};
      const plan = { ...options.plan, userId: owner.userId || owner.verifiedUserId || "", kind: source ? "head" : "full", baseScanId: source?.scan_id || "" };
      if (source) plan.fullCompletedAt = Number(source.scan_state?.fullCompletedAt || source.scan_state?.lastFullCompletedAt || source.completed_at_ms || 0);
      const inserted = await client.query(`
        INSERT INTO twidget_top_follower_scans (scan_id, username, status, scan_state, scanned)
        VALUES ($1::uuid, $2, 'running', $3::jsonb, $4::int)
        RETURNING scan_id::text, status, cursor, pages, scanned, error, scan_state,
                  extract(epoch from started_at) * 1000 AS started_at_ms,
                  extract(epoch from updated_at) * 1000 AS updated_at_ms,
                  NULL::numeric AS completed_at_ms
      `, [scanId, key, JSON.stringify(plan), Number(source?.scanned || 0)]);
      if (source) await client.query(`
        INSERT INTO twidget_top_followers
          (scan_id, scan_index, follower_id, follower_username, display_name, followers, verified, avatar, mutual)
        SELECT $1::uuid, scan_index, follower_id, follower_username, display_name, followers, verified, avatar, mutual
        FROM twidget_top_followers WHERE scan_id = $2::uuid
      `, [scanId, source.scan_id]);
      await client.query("COMMIT");
      return topFollowersScanFromRow(inserted.rows[0]);
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally { client.release(); }
  }
  async appendTopFollowersPage(key, scanId, followers, cursor) {
    followers = uniqueTopFollowerPage(followers);
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const current = await client.query(
        "SELECT scanned, scan_state FROM twidget_top_follower_scans WHERE scan_id = $1::uuid AND username = $2 AND status = 'running' FOR UPDATE",
        [scanId, key],
      );
      if (!current.rows[0]) {
        await client.query("ROLLBACK");
        return null;
      }
      const state = { ...current.rows[0].scan_state };
      if (state.kind === "head" && state.baseScanId) {
        const known = await client.query(`
          SELECT follower_id, lower(follower_username) AS username FROM twidget_top_followers
          WHERE scan_id = $1::uuid AND
            ((follower_id <> '' AND follower_id = ANY($2::text[])) OR lower(follower_username) = ANY($3::text[]))
        `, [state.baseScanId, followers.map(v => v.id || ""), followers.map(v => v.username.toLowerCase())]);
        const ids = new Set(known.rows.map(v => v.follower_id).filter(Boolean));
        const names = new Set(known.rows.map(v => v.username));
        for (const value of followers) state.knownStreak = ids.has(value.id) || names.has(value.username.toLowerCase()) ? Number(state.knownStreak || 0) + 1 : 0;
      }
      state.processed = Number(state.processed || 0) + followers.length;
      state.failureCount = 0;
      state.retryAt = 0;
      const lastIndex = await client.query("SELECT COALESCE(max(scan_index), -1) + 1 AS next_index FROM twidget_top_followers WHERE scan_id = $1::uuid", [scanId]);
      const base = Number(lastIndex.rows[0].next_index);
      const payload = followers.map((value, index) => ({ ...value, scanIndex: base + index }));
      let countDelta = 0;
      if (payload.length) {
        const removed = await client.query(`
          DELETE FROM twidget_top_followers WHERE scan_id = $1::uuid AND follower_id <> ''
            AND follower_id = ANY($2::text[]) AND lower(follower_username) <> ALL($3::text[])
        `, [scanId, payload.map(v => v.id || ""), payload.map(v => v.username.toLowerCase())]);
        const inserted = await client.query(`
          INSERT INTO twidget_top_followers
            (scan_id, scan_index, follower_id, follower_username, display_name, followers, verified, avatar, mutual)
          SELECT $1::uuid, value.scan_index, value.id, value.username, value.name,
                 value.followers, value.verified, value.avatar, value.mutual
          FROM jsonb_to_recordset($2::jsonb) AS value(
            scan_index integer, id varchar(80), username varchar(15), name varchar(100),
            followers bigint, verified boolean, avatar text, mutual boolean
          )
          ${state.kind === "head" ? "ON CONFLICT DO NOTHING" : `ON CONFLICT (scan_id, lower(follower_username)) DO UPDATE SET
            follower_id = EXCLUDED.follower_id, display_name = EXCLUDED.display_name,
            followers = EXCLUDED.followers, verified = EXCLUDED.verified,
            avatar = EXCLUDED.avatar, mutual = EXCLUDED.mutual`}
          RETURNING (xmax = 0) AS inserted
        `, [scanId, JSON.stringify(payload.map((value) => ({
          scan_index: value.scanIndex,
          id: value.id,
          username: value.username,
          name: value.name,
          followers: value.followers,
          verified: value.verified,
          avatar: value.avatar,
          mutual: value.mutual,
        })))]);
        countDelta = inserted.rows.filter(row => row.inserted).length - removed.rowCount;
      }
      const updated = await client.query(`
        UPDATE twidget_top_follower_scans SET
          cursor = $3, scan_state = $4::jsonb,
          pages = pages + 1,
          scanned = scanned + $5::int,
          updated_at = now()
        WHERE scan_id = $1::uuid AND username = $2 AND status = 'running'
        RETURNING scan_id::text, status, cursor, pages, scanned, error, scan_state,
                  extract(epoch from started_at) * 1000 AS started_at_ms,
                  extract(epoch from updated_at) * 1000 AS updated_at_ms,
                  extract(epoch from completed_at) * 1000 AS completed_at_ms
      `, [scanId, key, cursor, JSON.stringify(state), countDelta]);
      await client.query("COMMIT");
      return updated.rows[0] ? topFollowersScanFromRow(updated.rows[0]) : null;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }
  async completeTopFollowersScan(key, scanId) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const current = await client.query("SELECT scan_state FROM twidget_top_follower_scans WHERE scan_id = $1::uuid AND username = $2 AND status = 'running' FOR UPDATE", [scanId, key]);
      if (!current.rows[0]) { await client.query("ROLLBACK"); return null; }
      const state = { ...current.rows[0].scan_state };
      if (state.kind !== "head") state.lastFullCompletedAt = Date.now();
      state.fullCompletedAt = state.kind === "head" ? state.fullCompletedAt : state.lastFullCompletedAt;
      const updated = await client.query(`
        UPDATE twidget_top_follower_scans SET status = 'complete', cursor = '', completed_at = now(), updated_at = now(),
          scan_state = $3::jsonb,
          scanned = (SELECT count(*)::int FROM twidget_top_followers WHERE scan_id = $1::uuid)
        WHERE scan_id = $1::uuid AND username = $2 AND status = 'running'
        RETURNING scan_id::text, status, cursor, pages, scanned, error, scan_state,
                  extract(epoch from started_at) * 1000 AS started_at_ms,
                  extract(epoch from updated_at) * 1000 AS updated_at_ms,
                  extract(epoch from completed_at) * 1000 AS completed_at_ms
      `, [scanId, key, JSON.stringify(state)]);
      if (updated.rows[0]) {
        if (state.kind !== "head") {
          const previous = await client.query(`
            SELECT scan_id::text, scan_state, extract(epoch from completed_at) * 1000 AS completed_at_ms
            FROM twidget_top_follower_scans WHERE username = $1 AND status = 'complete' AND scan_id <> $2::uuid
              AND scan_state->>'kind' IS DISTINCT FROM 'head' ORDER BY completed_at DESC LIMIT 1
          `, [key, scanId]);
          if (previous.rows[0]) {
            const before = previous.rows[0];
            const differences = await client.query(`
              WITH old_ids AS (SELECT follower_id FROM twidget_top_followers WHERE scan_id = $1::uuid),
                   new_ids AS (SELECT follower_id FROM twidget_top_followers WHERE scan_id = $2::uuid)
              SELECT (SELECT count(*) FROM (SELECT follower_id FROM new_ids EXCEPT SELECT follower_id FROM old_ids) n)::int AS gained,
                     (SELECT count(*) FROM (SELECT follower_id FROM old_ids EXCEPT SELECT follower_id FROM new_ids) n)::int AS lost,
                     (SELECT count(*) = count(DISTINCT follower_id) AND bool_and(follower_id ~ '^[0-9]+$') FROM old_ids)
                     AND (SELECT count(*) = count(DISTINCT follower_id) AND bool_and(follower_id ~ '^[0-9]+$') FROM new_ids) AS valid
            `, [before.scan_id, scanId]);
            const account = await client.query("SELECT metadata FROM twidget_history_accounts WHERE username = $1 FOR UPDATE", [key]);
            const meta = account.rows[0]?.metadata || {};
            const counts = differences.rows[0];
            const observation = counts?.valid ? followerChangeObservation({
              ...before.scan_state, status: "complete", scanId: before.scan_id, completedAt: Number(before.completed_at_ms),
            }, topFollowersScanFromRow(updated.rows[0]), { newFollowers: counts.gained, lostFollowers: counts.lost },
            meta.userId || meta.verifiedUserId) : null;
            if (observation) await client.query(`
              UPDATE twidget_history_accounts SET metadata = metadata || jsonb_build_object('followerChanges', $2::jsonb) WHERE username = $1
            `, [key, JSON.stringify(appendFollowerChange(meta, observation))]);
          }
        }
        // Head checks cannot detect losses. Keep the last complete full scan
        // as their baseline until the next full scan has recorded its changes.
        await client.query(`DELETE FROM twidget_top_follower_scans WHERE username = $1 AND scan_id <> $2::uuid
          AND scan_id <> (SELECT scan_id FROM twidget_top_follower_scans WHERE username = $1 AND status = 'complete'
            AND scan_state->>'kind' IS DISTINCT FROM 'head' ORDER BY completed_at DESC LIMIT 1)`, [key, scanId]);
      }
      await client.query("COMMIT");
      return updated.rows[0] ? topFollowersScanFromRow(updated.rows[0]) : null;
    } catch (error) { await client.query("ROLLBACK"); throw error; }
    finally { client.release(); }
  }
  async failTopFollowersScan(key, scanId, error) {
    const result = await this.pool.query(`
      UPDATE twidget_top_follower_scans SET status = 'failed', error = $3, updated_at = now(),
        scan_state = scan_state || jsonb_build_object(
          'failureCount', COALESCE((scan_state->>'failureCount')::int, 0) + 1,
          'retryAt', $4::bigint + LEAST($5::bigint, $6::bigint * power(2, LEAST(COALESCE((scan_state->>'failureCount')::int, 0), 8)))::bigint)
      WHERE scan_id = $1::uuid AND username = $2 AND status = 'running'
      RETURNING scan_id::text, status, cursor, pages, scanned, error, scan_state,
                extract(epoch from started_at) * 1000 AS started_at_ms,
                extract(epoch from updated_at) * 1000 AS updated_at_ms,
                extract(epoch from completed_at) * 1000 AS completed_at_ms
    `, [scanId, key, String(error || "scan_failed").slice(0, 160), Date.now(), DAY_MS,
      topFollowersRetryState({}, error, 0).retryAt]);
    return result.rows[0] ? topFollowersScanFromRow(result.rows[0]) : null;
  }
  async getTopFollowersSnapshot(key, { offset = 0, limit = 250 } = {}) {
    const scan = await this.pool.query(`
      SELECT scan_id::text, status, cursor, pages, scanned, error, scan_state,
             extract(epoch from started_at) * 1000 AS started_at_ms,
             extract(epoch from updated_at) * 1000 AS updated_at_ms,
             extract(epoch from completed_at) * 1000 AS completed_at_ms
      FROM twidget_top_follower_scans
      WHERE username = $1 AND status = 'complete'
      ORDER BY completed_at DESC LIMIT 1
    `, [key]);
    if (!scan.rows[0]) return null;
    const followers = await this.pool.query(`
      SELECT follower_id AS id, follower_username AS username, display_name AS name,
             followers::text, verified, avatar, mutual, scan_index
      FROM twidget_top_followers
      WHERE scan_id = $1::uuid
      ORDER BY twidget_top_followers.followers DESC, lower(display_name), lower(follower_username), follower_id, scan_index
      OFFSET $2 LIMIT $3
    `, [scan.rows[0].scan_id, offset, limit]);
    return {
      ...topFollowersScanFromRow(scan.rows[0]),
      total: Number(scan.rows[0].scanned || 0),
      followers: followers.rows.map((value) => ({
        id: value.id || "",
        username: value.username,
        name: value.name || value.username,
        followers: Number(value.followers || 0),
        verified: Boolean(value.verified),
        avatar: value.avatar || "",
        mutual: value.mutual === null ? null : Boolean(value.mutual),
        scanIndex: Number(value.scan_index || 0),
      })),
    };
  }
  async pruneTopFollowers(retentionDays) {
    if (retentionDays <= 0) return 0;
    const result = await this.pool.query(`
      DELETE FROM twidget_top_follower_scans
      WHERE COALESCE(completed_at, updated_at) < now() - ($1::int * interval '1 day')
    `, [retentionDays]);
    return result.rowCount;
  }
  async storeSamples(key, samples, { preferExisting = false, touchAccess = true, requireExisting = false, expectedUserId } = {}) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      if (requireExisting || expectedUserId !== undefined) {
        const account = await client.query("SELECT metadata FROM twidget_history_accounts WHERE username = $1 FOR UPDATE", [key]);
        const ownerId = account.rows[0]?.metadata?.userId;
        if ((requireExisting && !account.rowCount) || (expectedUserId !== undefined && ownerId && ownerId !== expectedUserId)) {
          await client.query("COMMIT");
          return [];
        }
      }
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
  async registerAccount(key, samples, maxAccounts, userId = "") {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query("SELECT pg_advisory_xact_lock(hashtext('twidget_history_registration'))");
      const exists = await client.query("SELECT metadata FROM twidget_history_accounts WHERE username = $1 FOR UPDATE", [key]);
      // An existing unbound row must not inherit the current handle owner's ID.
      if (exists.rowCount && exists.rows[0].metadata?.userId && exists.rows[0].metadata.userId !== userId) {
        await client.query("ROLLBACK");
        return false;
      }
      if (!exists.rowCount) {
        const count = await client.query("SELECT count(*)::int AS count FROM twidget_history_accounts");
        if (Number(count.rows[0]?.count || 0) >= maxAccounts) {
          await client.query("ROLLBACK");
          return false;
        }
      }
      await client.query(`
        INSERT INTO twidget_history_accounts (username, metadata) VALUES ($1, $2::jsonb)
        ON CONFLICT (username) DO UPDATE SET last_accessed_at = now()
      `, [key, JSON.stringify(userId ? { userId } : {})]);
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

  async renameAccount(oldKey, newKey, userId, { allowLegacy = false } = {}) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      // Serialize moves, enrollment and concurrent sample writes, including absent destination rows.
      await client.query("LOCK TABLE twidget_history_accounts IN SHARE ROW EXCLUSIVE MODE");
      const rows = await client.query("SELECT username, metadata FROM twidget_history_accounts WHERE username = ANY($1::text[]) ORDER BY username FOR UPDATE", [[oldKey, newKey]]);
      const source = rows.rows.find(row => row.username === oldKey);
      const destination = rows.rows.find(row => row.username === newKey);
      validateHandleMove({ source: !!source, destination: !!destination, sourceMeta: source?.metadata || {}, targetMeta: destination?.metadata || {}, userId, allowLegacy });
      if (!source || oldKey === newKey) { await client.query("COMMIT"); return { moved: false }; }
      await client.query(`INSERT INTO twidget_history_accounts (username, metadata, created_at, last_accessed_at)
        SELECT $2, metadata || jsonb_build_object('userId', $3::text), created_at, last_accessed_at FROM twidget_history_accounts WHERE username = $1
        ON CONFLICT (username) DO UPDATE SET metadata = EXCLUDED.metadata || twidget_history_accounts.metadata || jsonb_build_object('userId', $3::text)`, [oldKey, newKey, userId]);
      const overlap = ["followers", "following", "posts", "likes"].flatMap(metric => [
        `${metric} = CASE WHEN twidget_history_samples.${metric}_known THEN twidget_history_samples.${metric} ELSE EXCLUDED.${metric} END`,
        `${metric}_known = twidget_history_samples.${metric}_known OR EXCLUDED.${metric}_known`,
      ]).join(", ");
      await client.query(`INSERT INTO twidget_history_samples
        (username, sample_date, followers, followers_known, following, following_known, posts, posts_known, likes, likes_known, source, estimated, observed_at)
        SELECT $2, sample_date, followers, followers_known, following, following_known, posts, posts_known, likes, likes_known, source, estimated, observed_at
        FROM twidget_history_samples WHERE username = $1 ON CONFLICT (username, sample_date) DO UPDATE SET ${overlap}`, [oldKey, newKey]);
      const interactionRows = await client.query("SELECT username, data FROM twidget_interactions WHERE username = ANY($1::text[])", [[oldKey, newKey]]);
      const sourceInteractions = interactionRows.rows.find(row => row.username === oldKey)?.data;
      if (sourceInteractions) {
        const targetInteractions = interactionRows.rows.find(row => row.username === newKey)?.data;
        await client.query("INSERT INTO twidget_interactions (username, data) VALUES ($1, $2::jsonb) ON CONFLICT (username) DO UPDATE SET data = EXCLUDED.data", [newKey, JSON.stringify(mergeInteractionData(targetInteractions, sourceInteractions))]);
      }
      await client.query("UPDATE twidget_top_follower_scans SET username = $2 WHERE username = $1", [oldKey, newKey]);
      await client.query("UPDATE twidget_primary_profiles SET username = $2 WHERE username = $1", [oldKey, newKey]);
      await client.query("DELETE FROM twidget_history_accounts WHERE username = $1", [oldKey]);
      await client.query("COMMIT");
      return { moved: true };
    } catch (error) { await client.query("ROLLBACK"); throw error; }
    finally { client.release(); }
  }
  async deleteAccount(key) {
    const result = await this.pool.query("DELETE FROM twidget_history_accounts WHERE username = $1", [key]);
    return result.rowCount > 0;
  }
  async prune() {
    let deletedAccounts = 0;
    let deletedSamples = 0;
    await this.pool.query("DELETE FROM twidget_primary_profiles WHERE last_seen_at < now() - ($1::int * interval '1 day')", [PRIMARY_PROFILE_RETENTION_DAYS]);
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
    for (const [key, data] of Object.entries(objectValue(parsed?.interactions))) await this.storeInteractions(key, data);
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

function topFollowersScanSummary(scan) {
  return {
    userId: String(scan.userId || ""),
    provider: scan.provider || "twitterapis",
    kind: scan.kind === "head" ? "head" : "full",
    baseScanId: String(scan.baseScanId || ""),
    sourceFollowers: Number.isSafeInteger(scan.sourceFollowers) ? scan.sourceFollowers : null,
    targetRows: Number(scan.targetRows || 100), overlapRows: Number(scan.overlapRows || 100),
    processed: Number(scan.processed || 0), knownStreak: Number(scan.knownStreak || 0),
    fullCompletedAt: Number(scan.fullCompletedAt || scan.lastFullCompletedAt || (scan.kind !== "head" ? scan.completedAt : 0) || 0),
    lastFullCompletedAt: Number(scan.fullCompletedAt || scan.lastFullCompletedAt || (scan.kind !== "head" && scan.status === "complete" ? scan.completedAt : 0) || 0),
    failureCount: Number(scan.failureCount || 0), retryAt: Number(scan.retryAt || 0),
    scanId: String(scan.scanId || ""),
    status: String(scan.status || ""),
    cursor: String(scan.cursor || ""),
    pages: Number(scan.pages || 0),
    scanned: Number(scan.scanned || 0),
    error: String(scan.error || ""),
    startedAt: Number(scan.startedAt || 0),
    updatedAt: Number(scan.updatedAt || 0),
    completedAt: Number(scan.completedAt || 0),
  };
}

function topFollowersScanFromRow(row) {
  return topFollowersScanSummary({
    ...objectValue(row.scan_state),
    scanId: row.scan_id,
    status: row.status,
    cursor: row.cursor,
    pages: row.pages,
    scanned: row.scanned,
    error: row.error,
    startedAt: row.started_at_ms,
    updatedAt: row.updated_at_ms,
    completedAt: row.completed_at_ms,
  });
}

// Provider pages can repeat a handle under different IDs, or an ID under aliases.
// Keep the first row for either identity so PostgreSQL never upserts one row twice.
function uniqueTopFollowerPage(followers) {
  const ids = new Set();
  const usernames = new Set();
  return followers.filter(value => {
    const username = String(value.username || "").toLowerCase();
    const id = String(value.id || "");
    if (!username || usernames.has(username) || (id && ids.has(id))) return false;
    usernames.add(username);
    if (id) ids.add(id);
    return true;
  });
}

function followerIndex(followers) {
  const index = new Map();
  for (const value of followers) indexFollower(index, value);
  return index;
}
function indexFollower(index, value) {
  if (value.id) index.set(`id:${value.id}`, value);
  index.set(`name:${value.username.toLowerCase()}`, value);
}
function indexedFollower(index, value) {
  return index?.get(`id:${value.id}`) || index?.get(`name:${value.username.toLowerCase()}`);
}

function topFollowerIdentity(value) {
  return String(value?.id || value?.username || "").trim().toLowerCase();
}

function topFollowersRankOrder(left, right) {
  return Number(right.followers || 0) - Number(left.followers || 0) ||
    String(left.name || "").localeCompare(String(right.name || ""), "en", { sensitivity: "base" }) ||
    String(left.username || "").localeCompare(String(right.username || ""), "en", { sensitivity: "base" });
}


export function validateHandleMove({ source, destination, sourceMeta, targetMeta, userId, allowLegacy = false }) {
  if (!/^[0-9]+$/.test(userId)) throw new Error("invalid_account_id");
  if (source && sourceMeta.userId !== userId && !(allowLegacy && !sourceMeta.userId)) throw new Error("source_identity_unverified");
  if (destination && targetMeta.userId !== userId && !(allowLegacy && !targetMeta.userId)) throw new Error("destination_identity_conflict");
}
