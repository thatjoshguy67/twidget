const DAY_MS = 24 * 60 * 60 * 1000;

export function prepareAnalyticsImport({ movements, currentFollowers, existing, now = Date.now() }) {
  const normalized = normalizeMovements(movements);
  if (!normalized.ok) return normalized;
  if (!Number.isSafeInteger(currentFollowers) || currentFollowers < 0) {
    return failure("current_followers_unavailable");
  }
  const today = startOfUtcDay(now);
  if (normalized.movements.at(-1).ts !== today) {
    return failure("analytics_export_not_current");
  }

  let followers = currentFollowers;
  const samplesDescending = normalized.movements.toReversed().map((movement) => {
    const sample = {
      dayLabel: dayLabel(movement.ts),
      followers,
      followersKnown: true,
      followingKnown: false,
      postsKnown: false,
      likesKnown: false,
      ts: movement.ts,
      src: "x_analytics",
    };
    followers -= movement.newFollows - movement.unfollows;
    return sample;
  });
  if (followers < 0) return failure("analytics_impossible_followers");
  const samples = samplesDescending.toReversed();
  const byDay = new Map(samples.map((sample) => [sample.ts, sample]));
  const trusted = (Array.isArray(existing) ? existing : [])
    .filter((sample) => sample && !sample.est && sample.followersKnown !== false)
    .filter((sample) => !["client", "x_analytics"].includes(String(sample.src || "")))
    .filter((sample) => Number.isFinite(Number(sample.followers)) && byDay.has(Number(sample.ts)))
    .sort((a, b) => Number(a.ts) - Number(b.ts));

  const historical = trusted.filter((sample) => Number(sample.ts) < today);
  if (!historical.length) return failure("insufficient_trusted_history");
  for (const anchor of trusted) {
    const reconstructed = byDay.get(Number(anchor.ts));
    const days = Math.max(0, Math.round((today - Number(anchor.ts)) / DAY_MS));
    const tolerance = trendTolerance(days, currentFollowers);
    const difference = Math.abs(Number(anchor.followers) - reconstructed.followers);
    if (difference > tolerance) {
      return failure("analytics_trend_mismatch", {
        date: isoDay(Number(anchor.ts)),
        expected: Number(anchor.followers),
        reconstructed: reconstructed.followers,
        difference,
        tolerance,
      });
    }
  }

  return {
    ok: true,
    checkedAnchors: trusted.length,
    tolerance: trendTolerance(Math.round((today - historical[0].ts) / DAY_MS), currentFollowers),
    // Today's independently fetched live value is already in the repository;
    // the CSV fills only older gaps and never replaces trusted observations.
    samples: samples.filter((sample) => sample.ts < today),
  };
}

export function trendTolerance(days, currentFollowers = 0) {
  return Math.max(3, Math.ceil(currentFollowers * 0.001), Math.ceil(Math.max(0, days) / 30) * 2);
}

function normalizeMovements(input) {
  if (!Array.isArray(input) || !input.length || input.length > 366) {
    return failure("invalid_analytics_rows");
  }
  const byDay = new Map();
  for (const item of input) {
    const date = typeof item?.date === "string" && /^\d{4}-\d{2}-\d{2}$/.test(item.date)
      ? Date.parse(`${item.date}T00:00:00Z`)
      : Number.NaN;
    const newFollows = Number(item?.newFollows);
    const unfollows = Number(item?.unfollows);
    if (!Number.isFinite(date) || isoDay(date) !== item.date ||
        !Number.isSafeInteger(newFollows) || newFollows < 0 ||
        !Number.isSafeInteger(unfollows) || unfollows < 0 || byDay.has(date)) {
      return failure("invalid_analytics_rows");
    }
    byDay.set(date, { ts: date, newFollows, unfollows });
  }
  const movements = [...byDay.values()].sort((a, b) => a.ts - b.ts);
  for (let index = 1; index < movements.length; index += 1) {
    if (movements[index].ts - movements[index - 1].ts !== DAY_MS) {
      return failure("analytics_date_gap");
    }
  }
  return { ok: true, movements };
}

function failure(error, detail = undefined) {
  return { ok: false, error, ...(detail ? { detail } : {}) };
}

function startOfUtcDay(value) {
  const date = new Date(value);
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
}

function isoDay(value) {
  return new Date(value).toISOString().slice(0, 10);
}

function dayLabel(value) {
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" })
    .format(new Date(value));
}
