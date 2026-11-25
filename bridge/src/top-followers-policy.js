// Explicit operator exclusions affect paid follower scans only.
export function excludedTopFollowersUsernames(value = "") {
  const handles = String(value).split(/[,\s]+/).filter(Boolean).map(value => value.replace(/^@/, "").toLowerCase());
  if (handles.some(value => !/^[a-z0-9_]{1,15}$/.test(value))) throw new Error("invalid_top_followers_excluded_username");
  return new Set(handles);
}

export function assertTopFollowersAllowed(excluded, username) {
  if (excluded.has(String(username).toLowerCase())) throw new Error("top_followers_account_excluded");
}
