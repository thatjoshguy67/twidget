// Fetch the bounded timeline without a provider-side date filter: `since`
// currently returns 404. Ownership and date filtering happen after retrieval.
export function fxTimelineUrl(username, count, cursor = null) {
  const url = new URL(`https://api.fxtwitter.com/2/profile/${encodeURIComponent(username)}/statuses`);
  url.searchParams.set('count', String(Math.min(100, Math.max(1, count))));
  url.searchParams.set('with_replies', 'true');
  if (cursor) url.searchParams.set('cursor', cursor);
  return url;
}
