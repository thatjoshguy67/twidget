const MAX_TOP_FOLLOWERS = 5;
const MAX_SCANNED = 10_000_000;
const MAX_PAGES = 6_250;
const DAY = 24 * 60 * 60 * 1000;

// Membership differences are observations across an interval, not timestamped
// unfollow events: suspensions/deletions can also remove a follower. Never
// derive these gross counts by subtracting the two public follower totals.
export function followerChangeObservation(before, after, counts, ownerId) {
  if (!/^[0-9]+$/.test(ownerId || "") || before?.userId !== ownerId || after?.userId !== ownerId ||
      before.kind === "head" || after.kind === "head" || before.status !== "complete" || after.status !== "complete" ||
      !before.completedAt || after.completedAt <= before.completedAt || before.scanId === after.scanId) return null;
  if (![counts?.newFollowers, counts?.lostFollowers].every(value => Number.isSafeInteger(value) && value >= 0)) return null;
  return {
    userId: ownerId, scanId: after.scanId, from: before.completedAt, to: after.completedAt,
    newFollowers: counts.newFollowers, lostFollowers: counts.lostFollowers,
    source: "complete_follower_scans", semantics: "observed_membership_changes_not_daily_unfollow_events",
  };
}

export function followerChangesFor(meta) {
  const ownerId = meta?.userId || meta?.verifiedUserId;
  return (Array.isArray(meta?.followerChanges) ? meta.followerChanges : []).filter(row => row.userId === ownerId);
}

export function appendFollowerChange(meta, observation) {
  return [...followerChangesFor(meta).filter(row => row.scanId !== observation.scanId), observation]
    .sort((a, b) => a.to - b.to).slice(-366);
}

export function topFollowersScanPlan({ kind, snapshot, followersCount, buffer = 100, overlap = 100 }) {
  const sourceFollowers = Number.isSafeInteger(followersCount) && followersCount >= 0 ? followersCount : null;
  // Migrate older snapshots using their stored full-list size. A run of known
  // followers is required too, even when the net count has not increased.
  const baseline = snapshot?.sourceFollowers ?? snapshot?.total;
  const delta = sourceFollowers !== null && Number.isSafeInteger(baseline) ? Math.max(0, sourceFollowers - baseline) : 0;
  return {
    kind, sourceFollowers, processed: 0, knownStreak: 0,
    targetRows: kind === "head" ? Math.max(buffer, Math.ceil(delta * 1.5) + buffer) : 0,
    overlapRows: overlap,
  };
}

export function topFollowersHeadComplete(scan) {
  return scan?.kind === "head" && Number(scan.processed || 0) >= Number(scan.targetRows || 100) &&
    Number(scan.knownStreak || 0) >= Number(scan.overlapRows || 100);
}

export function canResumeTopFollowersScan(scan, { now = Date.now(), resumeMaxAgeMs = DAY, maxPages = MAX_PAGES } = {}) {
  return scan?.status === "failed" && now >= Number(scan.retryAt || 0) &&
    now - scan.startedAt <= resumeMaxAgeMs && scan.pages < maxPages &&
    ["provider_unavailable", "provider_rate_limited", "provider_credit_empty", "provider_key_rejected", "provider_timeout", "provider_request_budget"].includes(scan.error);
}

export function topFollowersRetryState(scan, error, now = Date.now()) {
  const failureCount = Number(scan.failureCount || 0) + 1;
  const delay = error === "provider_credit_empty" ? 60 * 60 * 1000
    : error === "provider_key_rejected" ? DAY
    : error === "provider_rate_limited" ? 15 * 60 * 1000
    : ["provider_unavailable", "provider_timeout"].includes(error) ? 5 * 60 * 1000 : DAY;
  return { failureCount, retryAt: now + Math.min(DAY, delay * 2 ** Math.min(failureCount - 1, 8)) };
}

export function topFollowersAttemptBlocked(scan, now, refreshMs = 0) {
  return scan?.status === "running" || (scan?.status === "failed" && Number(scan.retryAt || 0) > now) ||
    (scan?.status === "complete" && refreshMs > 0 && now - scan.completedAt < refreshMs);
}

// Reserve the throttle slot before awaiting storage so concurrent polling only
// writes once. Failed writes can be retried by the next request.
export function createTopFollowersActivityRecorder(repository, now = Date.now) {
  const lastWrites = new Map();
  const throttleMs = 60 * 60 * 1000;
  return async function recordActivity(key) {
    const timestamp = now();
    if (timestamp - (lastWrites.get(key) ?? -Infinity) < throttleMs) return;
    lastWrites.set(key, timestamp);
    try {
      if (!(await repository.recordTopFollowersActivity(key, timestamp))) lastWrites.delete(key);
    } catch (error) {
      lastWrites.delete(key);
      throw error;
    } finally {
      for (const [account, writtenAt] of lastWrites) {
        if (timestamp - writtenAt >= throttleMs) lastWrites.delete(account);
      }
    }
  };
}

export function shouldRefreshTopFollowers({
  snapshot,
  scan,
  now = Date.now(),
  refreshMs = 24 * 60 * 60 * 1000,
}) {
  // Repeating a known-incomplete crawl cannot produce a full snapshot. Keep
  // the last result and require an operator to resolve its capacity limit.
  if (scan?.error === "top_followers_page_limit_reached") return false;
  if (scan?.status === "running") return false;
  if (scan?.status === "failed" && scan.retryAt) return now >= scan.retryAt;
  const lastAttemptAt = Math.max(
    Number(snapshot?.completedAt || snapshot?.cachedAt || 0),
    Number(scan?.updatedAt || scan?.startedAt || 0),
  );
  return lastAttemptAt <= 0 || now - lastAttemptAt >= refreshMs;
}

// Full-snapshot age and the last head check are separate clocks. A partial
// merge must never reset the full-scan clock that detects unfollows.
export function topFollowersRefreshKind({
  snapshot,
  scan,
  now = Date.now(),
  refreshMs = 24 * 60 * 60 * 1000,
  fullScanMs = 7 * 24 * 60 * 60 * 1000,
  headCheck = false,
}) {
  if (scan?.status === "running") return null;
  if (scan?.status === "failed" && !shouldRefreshTopFollowers({ scan, now, refreshMs })) return null;
  const fullCompletedAt = Number(snapshot?.fullCompletedAt || snapshot?.lastFullCompletedAt || snapshot?.completedAt || snapshot?.cachedAt || 0);
  if (!fullCompletedAt || now - fullCompletedAt >= fullScanMs) return "full";
  return headCheck && shouldRefreshTopFollowers({ snapshot, scan, now, refreshMs }) ? "head" : null;
}

export function prepareTopFollowersCache(body, now = Date.now()) {
  if (!body || typeof body !== "object" || !Array.isArray(body.top)) return null;
  if (body.top.length < 1 || body.top.length > MAX_TOP_FOLLOWERS) return null;
  const scanned = boundedInteger(body.scanned, body.top.length, MAX_SCANNED);
  const pages = boundedInteger(body.pages, 0, MAX_PAGES);
  if (scanned === null || pages === null) return null;

  const seenIds = new Set();
  const seenUsernames = new Set();
  const top = [];
  for (const value of body.top) {
    const follower = normalizeFollower(value);
    if (!follower) return null;
    const usernameKey = follower.username.toLowerCase();
    if (seenUsernames.has(usernameKey) || (follower.id && seenIds.has(follower.id))) return null;
    seenUsernames.add(usernameKey);
    if (follower.id) seenIds.add(follower.id);
    top.push(follower);
  }

  return {
    version: 1,
    cachedAt: now,
    scanned,
    pages,
    top,
  };
}

function normalizeFollower(value) {
  if (!value || typeof value !== "object") return null;
  const username = cleanUsername(value.username);
  const id = boundedString(value.id, 80);
  const name = boundedString(value.name, 100);
  const avatar = boundedString(value.avatar, 2048);
  const followers = boundedInteger(value.followers, 0, Number.MAX_SAFE_INTEGER);
  if (!username || id === null || name === null || avatar === null || followers === null) return null;
  if (avatar && !isHttpsUrl(avatar)) return null;
  return {
    id,
    username,
    name,
    followers,
    verified: value.verified === true,
    avatar,
  };
}

function cleanUsername(value) {
  const username = typeof value === "string" ? value.trim().replace(/^@+/, "") : "";
  return /^[A-Za-z0-9_]{1,15}$/.test(username) ? username : "";
}

function boundedString(value, maxLength) {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string") return null;
  const result = value.trim();
  return Array.from(result).length <= maxLength ? result : null;
}

function boundedInteger(value, min, max) {
  const result = Number(value);
  return Number.isSafeInteger(result) && result >= min && result <= max ? result : null;
}

function isHttpsUrl(value) {
  try {
    return new URL(value).protocol === "https:";
  } catch {
    return false;
  }
}
