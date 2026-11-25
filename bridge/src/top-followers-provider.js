const DEFAULT_ENDPOINT = "https://api.twitterapis.com/twitter/user/followers_v2";

// Use the immutable ID: the public handle-resolution path can return 404
// even while the ID-based follower list is available.
export async function fetchFxFollowersPage({ userId, cursor = "", count = 100, fetchImpl = fetch,
  endpoint = "https://api.fxtwitter.com/2/profile" }) {
  if (!/^[0-9]+$/.test(userId || "")) throw new Error("followers_identity_required");
  if (!Number.isInteger(count) || count < 1 || count > 100) throw new Error("invalid_follower_page_size");
  const url = new URL(`${endpoint.replace(/\/$/, "")}/id:${userId}/followers`);
  url.searchParams.set("count", String(count));
  if (cursor) url.searchParams.set("cursor", cursor);
  const response = await fetchImpl(url, {
    headers: { "User-Agent": "Twidget Bridge" }, signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new TopFollowersProviderError(response.status, "FxTwitter follower list unavailable");
  return parseFxFollowersPage(await response.json());
}

export function parseFxFollowersPage(body) {
  // A missing/malformed list is never an empty terminal page. Reject malformed
  // rows too, rather than silently dropping identities and inventing losses.
  if (body?.code !== 200 || !Array.isArray(body.results) || !body.cursor ||
      !(body.cursor.bottom === null || typeof body.cursor.bottom === "string")) {
    throw new Error("invalid_fx_followers_page");
  }
  const users = body.results.map(value => {
    if (!/^[0-9]+$/.test(value?.id || "") || !Number.isSafeInteger(value.followers) || value.followers < 0) {
      throw new Error("invalid_fx_follower");
    }
    const user = normalizeProviderFollower({ ...value, username: value.screen_name,
      avatar: value.avatar_url, verified: value.verification?.verified === true });
    if (!user) throw new Error("invalid_fx_follower");
    return user;
  });
  // Empty pages with a continuation cursor do not prove the list is complete.
  if (!users.length && body.cursor.bottom) throw new Error("fx_followers_empty_continuation");
  const nextCursor = boundedString(body.cursor.bottom, 4096);
  if (nextCursor === null) throw new Error("invalid_fx_followers_cursor");
  return { users, nextCursor };
}

export class TopFollowersProviderError extends Error {
  constructor(status, message = "Follower provider request failed") {
    super(message);
    this.name = "TopFollowersProviderError";
    this.status = Number(status || 0);
  }
}

export async function fetchTwitterApisFollowersPage({
  username,
  cursor = "",
  apiKey,
  endpoint = DEFAULT_ENDPOINT,
  count = 100,
  fetchImpl = fetch,
}) {
  if (!apiKey) throw new TopFollowersProviderError(0, "TwitterAPIs is not configured");
  const url = new URL(endpoint);
  url.searchParams.set("username", username);
  if (count !== null) {
    if (!Number.isInteger(count) || count < 1 || count > 100) throw new Error("invalid_follower_page_size");
    url.searchParams.set("count", String(count));
  }
  if (cursor) url.searchParams.set("cursor", cursor);
  const response = await fetchImpl(url, {
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "User-Agent": "Twidget Bridge",
    },
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new TopFollowersProviderError(response.status);
  return parseTwitterApisFollowersPage(await response.json());
}

export function parseTwitterApisFollowersPage(body) {
  const root = body && typeof body === "object" ? body : {};
  const values = Array.isArray(root.users) ? root.users : [];
  const seenIds = new Set();
  const seenUsernames = new Set();
  const users = [];
  for (const value of values) {
    const follower = normalizeProviderFollower(value);
    if (!follower) continue;
    const username = follower.username.toLowerCase();
    if (seenUsernames.has(username) || (follower.id && seenIds.has(follower.id))) continue;
    seenUsernames.add(username);
    if (follower.id) seenIds.add(follower.id);
    users.push(follower);
  }
  return {
    users,
    nextCursor: boundedString(root.next_cursor ?? root.nextCursor, 4096) || "",
  };
}

function normalizeProviderFollower(value) {
  if (!value || typeof value !== "object") return null;
  const username = cleanUsername(value.username);
  if (!username) return null;
  const followers = Number(value.followers_count ?? value.followers ?? 0);
  if (!Number.isSafeInteger(followers) || followers < 0) return null;
  const id = boundedString(value.id, 80);
  const name = boundedString(value.name, 100);
  const avatar = highResolutionAvatar([
    value.profile_image_url_https,
    value.profile_image_url,
    value.avatar_url,
    value.avatar,
    value.profile_image,
  ].find((candidate) => typeof candidate === "string" && candidate.trim()) || "");
  if (id === null || name === null || avatar === null || (avatar && !isHttpsUrl(avatar))) return null;
  const mutualValue = value.following ?? value.is_following ?? value.follows_back;
  return {
    id,
    username,
    name: name || username,
    followers,
    verified: value.is_blue_verified === true || value.verified === true,
    avatar,
    mutual: typeof mutualValue === "boolean" ? mutualValue : null,
  };
}

function highResolutionAvatar(value) {
  const clean = String(value || "").trim()
    .replace(/^http:\/\/pbs\.twimg\.com\//i, "https://pbs.twimg.com/")
    .replace("_normal.", "_400x400.")
    .replace("name=normal", "name=400x400");
  return boundedString(clean.startsWith("//") ? `https:${clean}` : clean, 2048);
}

function cleanUsername(value) {
  const clean = typeof value === "string" ? value.trim().replace(/^@+/, "") : "";
  return /^[A-Za-z0-9_]{1,15}$/.test(clean) ? clean : "";
}

function boundedString(value, maxLength) {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string") return null;
  const clean = value.trim();
  return Array.from(clean).length <= maxLength ? clean : null;
}

function isHttpsUrl(value) {
  try {
    return new URL(value).protocol === "https:";
  } catch {
    return false;
  }
}
