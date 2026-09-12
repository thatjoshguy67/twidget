const DEFAULT_ENDPOINT = "https://api.twitterapis.com/twitter/user/followers_v2";

export class TopFollowersProviderError extends Error {
  constructor(status, message = "TwitterAPIs request failed") {
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
  fetchImpl = fetch,
}) {
  if (!apiKey) throw new TopFollowersProviderError(0, "TwitterAPIs is not configured");
  const url = new URL(endpoint);
  url.searchParams.set("username", username);
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
  const seen = new Set();
  const users = [];
  for (const value of values) {
    const follower = normalizeProviderFollower(value);
    if (!follower) continue;
    const identity = String(follower.id || follower.username).toLowerCase();
    if (seen.has(identity)) continue;
    seen.add(identity);
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
