import { fxTimelineUrl } from './fx-timeline.js';
import { ownFxPostEligible } from './post-ranking.js';

export async function fetchTimelinePage(username, cursor = null, fetcher = fetch, mode = 'auto', count = 100) {
  const url = fxTimelineUrl(username, count, cursor);
  if (mode === 'standard') url.searchParams.delete('with_replies');
  const response = await fetcher(url, {
    headers: { Accept: 'application/json', 'User-Agent': 'TwidgetBridge/0.1' },
    signal: AbortSignal.timeout(15000),
  });
  if (response.status === 404 && mode === 'auto' && !cursor) return fetchTimelinePage(username, null, fetcher, 'standard', count);
  const json = await response.json().catch(() => ({}));
  // An error/404 is never evidence that historical coverage is complete.
  if (!response.ok || json.code >= 400 || !Array.isArray(json.results)) {
    throw new Error(`FxTwitter timeline ${response.status}`);
  }
  const next = json.cursor?.bottom;
  return { results: json.results, mode: mode === 'standard' ? 'standard' : 'with_replies', cursor: typeof next === 'string' && next ? next : null };
}

export async function backfillAnalytics({ username, profile, archive, normalize, page = (u, c, mode, id) => fetchTimelinePage(id ? `id:${id}` : u, c, fetch, mode), maxPages = 2, restart = false }) {
  return archive.withBackfillLock(username, async () => {
    const existing = await archive.get(username);
    let state = existing.userId === profile.userId && !restart ? { ...existing.backfill } : {};
    if (state.status === 'exhausted') return existing;
    let cursor = state.cursor || null;
    const seen = new Set(cursor ? [cursor] : []);
    for (let i = 0; i < maxPages; i++) {
      let result;
      try { result = await page(username, cursor, state.timelineMode || 'auto', profile.userId); }
      catch (error) {
        await archive.record(username, profile.userId, [], { ...state, status: 'error', cursor, error: String(error.message), updatedAt: Date.now() });
        return archive.get(username);
      }
      const posts = result.results.filter(status => ownFxPostEligible(status, username, { windowStart: 1, includeReplies: true })).map(normalize);
      const repeated = result.cursor && seen.has(result.cursor);
      state = { status: repeated ? 'stalled' : result.cursor ? 'partial' : 'exhausted',
        cursor: result.cursor, timelineMode: result.mode || state.timelineMode || 'unknown', pagesFetched: (state.pagesFetched || 0) + 1,
        oldestPostAt: [state.oldestPostAt, ...posts.map(post => post.ts)]
          .filter(value => Number.isSafeInteger(value) && value > 0).reduce((oldest, value) => oldest == null ? value : Math.min(oldest, value), null),
        startedAt: state.startedAt || Date.now(), updatedAt: Date.now() };
      await archive.record(username, profile.userId, posts, state);
      if (!result.cursor || repeated) break;
      cursor = result.cursor;
      seen.add(cursor);
    }
    return archive.get(username);
  });
}
