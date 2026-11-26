const DAY = 86400000;
const median = values => { const s = [...values].sort((a,b) => a-b); const m = Math.floor(s.length/2); return s.length % 2 ? s[m] : (s[m-1]+s[m])/2; };
const bucket = n => n < 10000 ? 'small' : n < 100000 ? 'medium' : 'large';

// One equally weighted account per UTC day. This detects a shared count anomaly,
// never the identity or cause of removed followers. Thresholds are deliberately
// conservative initial policy, to be calibrated against historical false alarms.
export function assessFollowerEvents(rows, now = Date.now()) {
  const today = Math.floor(now / DAY) * DAY;
  const base = { schemaVersion: 1, assessedAt: now, expiresAt: now + 6*3600000,
    status: 'insufficient_data', event: null, limitations: ['daily_resolution', 'nonrepresentative_cohort', 'provider_artifact_not_ruled_out', 'cause_unconfirmed'] };
  const accounts = new Map();
  for (const row of rows) {
    const day = Number(row.ts);
    if (!row.accountId || row.known !== true || row.source !== 'live' || row.estimated || !Number.isSafeInteger(row.followers) || row.followers < 0 || !Number.isFinite(day) || day % DAY !== 0 || day > today || day < today-35*DAY) continue;
    const samples = accounts.get(row.accountId) || new Map();
    samples.set(day, row.followers); accounts.set(row.accountId, samples);
  }
  const candidates = [];
  for (const day of [today, today-DAY]) {
    const observations = [];
    for (const samples of accounts.values()) {
      const current = samples.get(day), previous = samples.get(day-DAY);
      if (current == null || !previous) continue;
      const baseline = [];
      for (let offset=1; offset<=28; offset++) {
        const b = samples.get(day-offset*DAY), a = samples.get(day-(offset+1)*DAY);
        if (b != null && a > 0) baseline.push((b-a)/a);
      }
      if (baseline.length < 10) continue;
      const center = median(baseline), mad = median(baseline.map(v => Math.abs(v-center)));
      const threshold = Math.min(-0.002, center - Math.max(0.002, 4*1.4826*mad));
      const rate = (current-previous)/previous;
      observations.push({ affected: current-previous <= -5 && rate < threshold,
        baselineRate: baseline.filter(v => v < threshold).length/baseline.length,
        bucket: bucket(previous) });
    }
    const count = observations.length, affected = observations.filter(o => o.affected).length;
    const fraction = count ? affected/count : 0;
    const usual = count ? observations.reduce((s,o)=>s+o.baselineRate,0)/count : 0;
    const broadBuckets = ['small','medium','large'].filter(b => {
      const group = observations.filter(o=>o.bucket===b);
      return group.length >= 5 && group.filter(o=>o.affected).length >= 3 && group.filter(o=>o.affected).length/group.length >= 0.25;
    }).length;
    candidates.push({ day, count, affected, fraction, usual, broadBuckets });
  }
  const event = candidates.find(c=>c.count>=30 && c.affected>=15 && c.fraction>=Math.max(0.35,3*c.usual) && c.broadBuckets>=2);
  if (!event) return { ...base, status: candidates.some(c=>c.count>=30) ? 'no_event' : 'insufficient_data', eligibleAccounts: Math.max(...candidates.map(c=>c.count)) };
  return { ...base, status: 'detected', eligibleAccounts: event.count, event: {
    id: `follower-decline-${new Date(event.day).toISOString().slice(0,10)}`, type: 'widespread_follower_decline',
    confidence: 'tentative', cause: 'unknown', startAt: event.day-DAY, endAt: event.day+DAY,
    eligibleAccounts: event.count, affectedAccounts: event.affected, affectedFraction: event.fraction,
    baselineAnomalyFraction: event.usual, affectedSizeGroups: event.broadBuckets,
  } };
}

export function followerEventService(repository, { now = Date.now, ttlMs = 300000 } = {}) {
  let cached, pending;
  return async () => {
    if (cached && now()-cached.assessedAt < ttlMs) return cached;
    if (!pending) pending = repository.followerEventObservations().then(rows => cached = assessFollowerEvents(rows, now()))
      .catch(() => ({ schemaVersion: 1, status: 'unavailable', assessedAt: now(), expiresAt: now()+60000, event: null }))
      .finally(()=>pending=null);
    return pending;
  };
}
