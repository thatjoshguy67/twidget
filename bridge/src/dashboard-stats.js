const metrics = ['likes', 'replies', 'reposts', 'quotes', 'views', 'bookmarks'];
export function postCounters(status) {
  const result = {};
  for (const field of metrics) {
    const raw = status[field];
    const value = raw == null ? NaN : Number(raw);
    const known = Number.isSafeInteger(value) && value >= 0;
    result[field] = known ? value : 0;
    result[`${field}Known`] = known;
  }
  return result;
}

// Sample totals, never daily movements or full-account totals. Unknown counters
// make the total null instead of silently counting missing observations as zero.
export function dashboardStats(posts, candidates = posts, complete = false) {
  const totals = {};
  for (const field of metrics) {
    const known = posts.length > 0 && posts.every(post => post[`${field}Known`] === true);
    totals[field] = known ? posts.reduce((sum, post) => sum + post[field], 0) : null;
  }
  const replies = candidates.filter(post => post.isReply).length;
  return {
    windowDays: 7, complete, originalPostCount: posts.length, replyPostCount: replies,
    postsPerDay: posts.length / 7,
    likesPerPost: totals.likes == null || !posts.length ? null : totals.likes / posts.length,
    totals,
    // UTC instants allow clients to calculate streaks in their own timezone.
    originalPostTimestamps: posts.map(post => post.ts),
  };
}
