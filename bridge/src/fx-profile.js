export function fxUserId(...candidates) {
  return candidates.find(value => typeof value === "string" && /^[0-9]+$/.test(value)) || "";
}

export function fxProfileUrl(username, userId = "", baseUrl = "https://api.fxtwitter.com") {
  const id = fxUserId(userId);
  const identity = id ? `id:${id}` : String(username).trim().replace(/^@+/, "");
  return new URL(`${baseUrl.replace(/\/$/, "")}/2/profile/${encodeURIComponent(identity)}`);
}
