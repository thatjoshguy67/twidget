// Additive contract: retain the original Android fields and their known flags.
export function profileResponse(profile, { fetchedAt, ttlMs, cacheStatus, now = Date.now() }) {
  const fields = {};
  for (const field of ['followersCount', 'followingsCount', 'statusesCount', 'likeCount']) {
    fields[field] = profile[`${field}Known`] === true;
  }
  for (const field of ["bio", "bannerImage", "location", "website", "accountCreatedAt"]) fields[field] = profile[field] != null;
  return {
    ...profile,
    profileMetadata: {
      schemaVersion: 1,
      fetchedAt: new Date(fetchedAt).toISOString(),
      expiresAt: new Date(fetchedAt + ttlMs).toISOString(),
      ageSeconds: Math.max(0, Math.floor((now - fetchedAt) / 1000)),
      stale: cacheStatus === 'stale' || now >= fetchedAt + ttlMs,
      cacheStatus,
      source: profile.profileSource || 'unknown',
      fields,
    },
  };
}

// Missing provider fields stay null; an observed empty bio remains an empty string.
export function profileDetails(user) {
  const text = (...values) => values.find(value => typeof value === 'string') ?? null;
  const web = (...values) => {
    const value = text(...values);
    if (value === '') return '';
    try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? url.href : null; } catch { return null; }
  };
  return {
    bio: text(user.description, user.bio),
    bannerImage: web(user.banner_url, user.profile_banner_url, user.profileBannerUrl),
    location: text(user.location),
    website: web(user.website?.url, user.website, user.url),
    accountCreatedAt: text(user.joined, user.created_at, user.createdAt),
  };
}

export function analyticsResponse(data, { fetchedAt, ttlMs, cacheStatus, now = Date.now() }) {
  return { ...data, analyticsMetadata: {
    schemaVersion: 1, source: 'fxtwitter', cacheStatus,
    fetchedAt: new Date(fetchedAt).toISOString(),
    expiresAt: new Date(fetchedAt + ttlMs).toISOString(),
    ageSeconds: Math.max(0, Math.floor((now - fetchedAt) / 1000)),
    stale: cacheStatus === 'stale' || now >= fetchedAt + ttlMs,
    // This is a bounded seven-day sample, not a complete account archive.
    complete: data.activityComplete === true,
  } };
}
