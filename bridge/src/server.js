import express from "express";
import crypto from "node:crypto";
import { Rettiwt } from "rettiwt-api";

const app = express();
const port = Number(process.env.PORT || 8787);
const cacheTtlMs = Number(process.env.CACHE_TTL_SECONDS || 900) * 1000;
const rateLimitWindowMs = Number(process.env.RATE_LIMIT_WINDOW_SECONDS || 60) * 1000;
const rateLimitMax = Number(process.env.RATE_LIMIT_MAX || 60);

const cache = new Map();
const hits = new Map();
const oauthStates = new Map();
const oauthSessions = new Map();

app.disable("x-powered-by");
// Railway (and most PaaS) put exactly one proxy in front of the app. Trusting a
// single hop keeps req.ip honest; `true` would trust any spoofed X-Forwarded-For.
app.set("trust proxy", 1);

app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Cache-Control", "no-store");
  next();
});

app.use((req, res, next) => {
  const key = req.ip || "unknown";
  const now = Date.now();
  const bucket = hits.get(key);
  if (!bucket || now - bucket.startedAt > rateLimitWindowMs) {
    hits.set(key, { startedAt: now, count: 1 });
    next();
    return;
  }

  bucket.count += 1;
  if (bucket.count > rateLimitMax) {
    res.status(429).json({ error: "rate_limited" });
    return;
  }

  next();
});

// Both in-memory maps would otherwise grow unbounded on a long-lived public
// instance: expired cache entries are only ever overwritten, and each unique
// client IP leaves a hits bucket behind. Sweep both periodically.
const sweepIntervalMs = Math.max(rateLimitWindowMs, cacheTtlMs);
const sweepTimer = setInterval(() => {
  const now = Date.now();
  for (const [key, value] of cache) {
    if (now - value.fetchedAt >= cacheTtlMs) cache.delete(key);
  }
  for (const [key, value] of hits) {
    if (now - value.startedAt > rateLimitWindowMs) hits.delete(key);
  }
}, sweepIntervalMs);
sweepTimer.unref?.();

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    service: "twidget-bridge",
    authMode: process.env.RETTIWT_API_KEY ? "user" : "guest",
    xOAuthConfigured: Boolean(process.env.X_CLIENT_ID),
  });
});

app.get("/oauth/x/start", (req, res) => {
  const clientId = process.env.X_CLIENT_ID;
  if (!clientId) {
    res.status(501).json({ error: "x_oauth_not_configured" });
    return;
  }

  const returnUri = validReturnUri(req.query.return_uri) || process.env.TWIDGET_ANDROID_RETURN_URI || "twidget://oauth/x";
  const redirectUri = xRedirectUri(req);
  const state = base64Url(crypto.randomBytes(24));
  const verifier = base64Url(crypto.randomBytes(32));
  const challenge = base64Url(crypto.createHash("sha256").update(verifier).digest());

  cleanupOAuthMaps();
  oauthStates.set(state, {
    createdAt: Date.now(),
    redirectUri,
    returnUri,
    verifier,
  });

  const url = new URL("https://x.com/i/oauth2/authorize");
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("scope", process.env.X_OAUTH_SCOPES || "users.read tweet.read offline.access");
  url.searchParams.set("state", state);
  url.searchParams.set("code_challenge", challenge);
  url.searchParams.set("code_challenge_method", "S256");
  res.redirect(url.toString());
});

app.get("/oauth/x/callback", async (req, res) => {
  const state = String(req.query.state || "");
  const record = oauthStates.get(state);
  oauthStates.delete(state);

  if (!record) {
    res.redirect(returnUrl(process.env.TWIDGET_ANDROID_RETURN_URI || "twidget://oauth/x", { error: "invalid_state" }));
    return;
  }

  if (req.query.error) {
    res.redirect(returnUrl(record.returnUri, { error: String(req.query.error) }));
    return;
  }

  try {
    const token = await exchangeXCode({
      code: String(req.query.code || ""),
      redirectUri: record.redirectUri,
      verifier: record.verifier,
    });
    const user = await fetchXMe(token.access_token);
    const session = base64Url(crypto.randomBytes(24));
    oauthSessions.set(session, {
      createdAt: Date.now(),
      token,
      user,
    });
    res.redirect(returnUrl(record.returnUri, {
      session,
      username: user.userName,
      name: user.fullName,
      profile_image: user.profileImage,
    }));
  } catch (error) {
    console.error("X OAuth failed:", error);
    res.redirect(returnUrl(record.returnUri, { error: "oauth_failed" }));
  }
});

app.get("/oauth/x/session/:session", (req, res) => {
  cleanupOAuthMaps();
  const session = oauthSessions.get(String(req.params.session || ""));
  if (!session) {
    res.status(404).json({ error: "session_not_found" });
    return;
  }
  res.json(session.user);
});

app.get(["/official/user/:username", "/x/user/:username", "/api/x/user/:username"], async (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }
  if (!process.env.X_BEARER_TOKEN) {
    res.status(501).json({ error: "x_api_not_configured" });
    return;
  }

  try {
    const data = await fetchOfficialXProfile(username);
    res.json(data);
  } catch (error) {
    console.error(`Official X API fetch failed for ${username}:`, error);
    res.status(502).json({
      error: "x_api_fetch_failed",
      message: error instanceof Error ? error.message : "Unknown X API error",
    });
  }
});

app.get(["/user/:username", "/users/:username", "/details/:username"], async (req, res) => {
  await handleUser(req.params.username, res);
});

app.get("/", async (req, res) => {
  const username = String(req.query.username || "");
  if (!username) {
    res.json({
      ok: true,
      service: "twidget-bridge",
      routes: ["/health", "/user/:username", "/?username=:username"],
    });
    return;
  }

  await handleUser(username, res);
});

app.use((_req, res) => {
  res.status(404).json({ error: "not_found" });
});

app.use((error, _req, res, _next) => {
  console.error(error);
  res.status(500).json({ error: "internal_error" });
});

app.listen(port, "0.0.0.0", () => {
  console.log(`Twidget bridge listening on ${port}`);
});

async function handleUser(input, res) {
  const username = cleanUsername(input);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }

  const cached = cache.get(username.toLowerCase());
  if (cached && Date.now() - cached.fetchedAt < cacheTtlMs) {
    res.setHeader("X-Twidget-Cache", "hit");
    res.json(cached.data);
    return;
  }

  try {
    const data = await fetchProfile(username);
    cache.set(username.toLowerCase(), { fetchedAt: Date.now(), data });
    res.setHeader("X-Twidget-Cache", "miss");
    res.json(data);
  } catch (error) {
    console.error(`Failed to fetch ${username}:`, error);
    res.status(502).json({
      error: "profile_fetch_failed",
      message: error instanceof Error ? error.message : "Unknown Rettiwt error",
    });
  }
}

async function fetchProfile(username) {
  const rettiwt = new Rettiwt({
    ...(process.env.RETTIWT_API_KEY ? { apiKey: process.env.RETTIWT_API_KEY } : {}),
    timeout: 10000,
    maxRetries: 1,
  });
  const details = await rettiwt.user.details(username);
  const user = typeof details?.toJSON === "function" ? details.toJSON() : details;

  return normalizeUser(user, username);
}

async function fetchOfficialXProfile(username) {
  const url = new URL(`https://api.x.com/2/users/by/username/${username}`);
  url.searchParams.set("user.fields", "public_metrics,profile_image_url,verified,verified_type,protected");
  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${process.env.X_BEARER_TOKEN}`,
      Accept: "application/json",
    },
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(`X API ${response.status}: ${JSON.stringify(json)}`);
  }
  return normalizeXUser(json.data || {});
}

function normalizeUser(user, fallbackUsername) {
  return {
    fullName: stringValue(user.fullName || user.name || fallbackUsername),
    userName: stringValue(user.userName || user.username || user.screenName || fallbackUsername).replace(/^@+/, ""),
    followersCount: numberValue(user.followersCount ?? user.followers_count ?? user.followerCount),
    followingsCount: numberValue(user.followingsCount ?? user.followingCount ?? user.friends_count),
    statusesCount: numberValue(user.statusesCount ?? user.statuses_count ?? user.tweetCount),
    likeCount: numberValue(user.likeCount ?? user.favouritesCount ?? user.favourites_count),
    profileImage: highResolutionProfileImageUrl(user.profileImage || user.profile_image_url_https || user.profile_image_url),
    isVerified: booleanValue(user.isVerified ?? user.verified ?? user.blueVerified ?? user.isBlueVerified),
    isPrivate: booleanValue(user.isPrivate ?? user.private ?? user.protected ?? user.isProtected),
  };
}

async function exchangeXCode({ code, redirectUri, verifier }) {
  if (!code) throw new Error("Missing OAuth code");
  const clientId = process.env.X_CLIENT_ID;
  const clientSecret = process.env.X_CLIENT_SECRET || "";
  const body = new URLSearchParams();
  body.set("grant_type", "authorization_code");
  body.set("code", code);
  body.set("redirect_uri", redirectUri);
  body.set("code_verifier", verifier);
  if (!clientSecret) body.set("client_id", clientId);

  const headers = {
    "Content-Type": "application/x-www-form-urlencoded",
  };
  if (clientSecret) {
    headers.Authorization = `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString("base64")}`;
  }

  const response = await fetch("https://api.x.com/2/oauth2/token", {
    method: "POST",
    headers,
    body,
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(`Token exchange failed: ${response.status} ${JSON.stringify(json)}`);
  }
  return json;
}

async function fetchXMe(accessToken) {
  const response = await fetch("https://api.x.com/2/users/me?user.fields=public_metrics,profile_image_url,verified,verified_type,protected", {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(`User lookup failed: ${response.status} ${JSON.stringify(json)}`);
  }
  return normalizeXUser(json.data || {});
}

function normalizeXUser(user) {
  const metrics = user.public_metrics || {};
  return {
    fullName: stringValue(user.name || user.username),
    userName: stringValue(user.username).replace(/^@+/, ""),
    followersCount: numberValue(metrics.followers_count),
    followingsCount: numberValue(metrics.following_count),
    statusesCount: numberValue(metrics.tweet_count),
    likeCount: 0,
    profileImage: highResolutionProfileImageUrl(user.profile_image_url),
    isVerified: booleanValue(user.verified) || stringValue(user.verified_type) === "blue",
    isPrivate: booleanValue(user.protected),
  };
}

function xRedirectUri(req) {
  if (process.env.X_CALLBACK_URL) return process.env.X_CALLBACK_URL;
  const proto = req.get("x-forwarded-proto") || req.protocol || "https";
  return `${proto}://${req.get("host")}/oauth/x/callback`;
}

function validReturnUri(value) {
  const uri = String(value || "");
  if (!uri) return "";
  try {
    const parsed = new URL(uri);
    return parsed.protocol === "twidget:" ? uri : "";
  } catch {
    return "";
  }
}

function returnUrl(returnUri, params) {
  const url = new URL(returnUri);
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, String(value));
    }
  });
  return url.toString();
}

function cleanupOAuthMaps() {
  const ttl = 15 * 60 * 1000;
  const now = Date.now();
  for (const [key, value] of oauthStates) {
    if (now - value.createdAt > ttl) oauthStates.delete(key);
  }
  for (const [key, value] of oauthSessions) {
    if (now - value.createdAt > ttl) oauthSessions.delete(key);
  }
}

function base64Url(buffer) {
  return buffer.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function cleanUsername(value) {
  const username = String(value || "").trim().replace(/^@+/, "");
  return /^[A-Za-z0-9_]{1,15}$/.test(username) ? username : "";
}

function stringValue(value) {
  return typeof value === "string" ? value : "";
}

function highResolutionProfileImageUrl(value) {
  return stringValue(value)
    .trim()
    .replace(/_normal(?=\.[A-Za-z0-9]+(?:\?|$))/, "_400x400")
    .replace(/([?&]name=)normal(?=(&|$))/, "$1400x400");
}

function numberValue(value) {
  if (typeof value === "number" && Number.isFinite(value)) return Math.max(0, Math.trunc(value));
  if (typeof value === "string") {
    const parsed = Number.parseInt(value.replace(/[^\d]/g, ""), 10);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function booleanValue(value) {
  if (typeof value === "boolean") return value;
  if (typeof value === "number") return value !== 0;
  if (typeof value === "string") {
    const normalized = value.toLowerCase();
    if (["true", "1", "yes", "blue", "business", "government"].includes(normalized)) return true;
    if (["false", "0", "no", "none"].includes(normalized)) return false;
  }
  return false;
}
