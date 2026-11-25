import { topFollowersRefreshKind } from "./top-followers.js";

const DAY = 86400000;

// Reports identify the install's selected default, not verified ownership.
// Unreported/older clients keep working at the lower paid-refresh cadence.
export class TopFollowersPriority {
  constructor({ repository, now = Date.now, cacheMs = 60000,
    primaryRefreshMs = DAY, primaryFullScanMs = 7 * DAY,
    trackedRefreshMs = 7 * DAY, trackedFullScanMs = 30 * DAY } = {}) {
    Object.assign(this, { repository, now, cacheMs, primaryRefreshMs, primaryFullScanMs, trackedRefreshMs, trackedFullScanMs });
    this.primary = new Set();
    this.expiresAt = 0;
    this.version = 0;
    this.pending = null;
  }

  invalidate() { this.expiresAt = 0; this.version += 1; }

  async refresh() {
    if (this.now() < this.expiresAt) return;
    if (!this.pending) {
      const version = this.version;
      this.pending = this.repository.primaryProfileCounts().then(rows => {
        this.primary = new Set(rows.filter(row => Number(row.active30d) > 0).map(row => row.username.toLowerCase()));
        this.expiresAt = version === this.version ? this.now() + this.cacheMs : 0;
      }).finally(() => { this.pending = null; });
    }
    await this.pending;
  }

  isPrimary(key) { return this.primary.has(key.toLowerCase()); }

  cadence(key) {
    return this.isPrimary(key)
      ? { refreshMs: this.primaryRefreshMs, fullScanMs: this.primaryFullScanMs }
      : { refreshMs: this.trackedRefreshMs, fullScanMs: this.trackedFullScanMs };
  }

  refreshKind(key, snapshot, scan, now = this.now(), headCheck = true) {
    const cadence = this.cadence(key);
    // A failed lower-priority attempt must not bypass its slower cadence.
    if (!this.isPrimary(key) && scan?.status === "failed" &&
      now - Number(scan.updatedAt || scan.startedAt || 0) < cadence.refreshMs) return null;
    return topFollowersRefreshKind({ snapshot, scan, now, headCheck, ...cadence });
  }

  next(queue) {
    let best;
    let bestRank = Infinity;
    for (const entry of queue) {
      const [key, { scan }] = entry;
      const rank = (this.isPrimary(key) ? 0 : 2) + (scan.kind === "head" ? 0 : 1);
      if (rank < bestRank) { best = entry; bestRank = rank; }
    }
    return best;
  }
}
