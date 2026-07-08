import express from "express";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { Rettiwt } from "rettiwt-api";
import { reconstructFollowerHistory } from "./graph.js";

const app = express();
const port = Number(process.env.PORT || 8787);
const cacheTtlMs = Number(process.env.CACHE_TTL_SECONDS || 900) * 1000;
const rateLimitWindowMs = Number(process.env.RATE_LIMIT_WINDOW_SECONDS || 60) * 1000;
const rateLimitMax = Number(process.env.RATE_LIMIT_MAX || 60);
const maxHistoryDays = Number(process.env.HISTORY_MAX_DAYS || 400);
const historyRefreshMs = Number(process.env.HISTORY_REFRESH_MINUTES || 60) * 60 * 1000;
const waybackEnabled = process.env.WAYBACK_BACKFILL !== "0";
const waybackMaxSnapshots = Number(process.env.WAYBACK_MAX_SNAPSHOTS || 12);
const graphEnabled = process.env.GRAPH_BACKFILL !== "0";
const graphMaxFollowers = Number(process.env.GRAPH_MAX_FOLLOWERS || 25000);
const historyStorePath = process.env.HISTORY_STORE_PATH ||
  (fs.existsSync("/data") ? "/data/twidget-history.json" : path.resolve(process.cwd(), "data", "twidget-history.json"));

const cache = new Map();
const hits = new Map();
const oauthStates = new Map();
const oauthSessions = new Map();
const historyStore = loadHistoryStore();

// One-time cleanup (2026-07-08): earlier graph backfills anchored partial
// follower enumerations at zero, storing wildly low estimates (an account's
// newest ~1k follows mapped onto its first days). Drop every graph sample
// and the per-account markers so the hourly loop regenerates them with
// baseCount anchoring.
if (!historyStore.graphPurgedAt) {
  let purged = 0;
  for (const key of Object.keys(historyStore.accounts)) {
    const samples = historyStore.accounts[key] || [];
    const kept = samples.filter((sample) => sample.src !== "graph");
    purged += samples.length - kept.length;
    historyStore.accounts[key] = kept;
    if (historyStore.meta[key]) {
      delete historyStore.meta[key].graphAt;
      delete historyStore.meta[key].graphSamples;
      delete historyStore.meta[key].graphSkipped;
    }
  }
  historyStore.graphPurgedAt = Date.now();
  saveHistoryStore();
  if (purged) console.log(`Purged ${purged} stale graph samples`);
}

app.disable("x-powered-by");
// Railway (and most PaaS) put exactly one proxy in front of the app. Trusting a
// single hop keeps req.ip honest; `true` would trust any spoofed X-Forwarded-For.
app.set("trust proxy", 1);

app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Cache-Control", "no-store");
  next();
});

app.use(express.json({ limit: "128kb" }));

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
    upstream: "fxtwitter",
    authMode: process.env.RETTIWT_API_KEY ? "user" : "guest",
    xOAuthConfigured: Boolean(process.env.X_CLIENT_ID),
    history: {
      enabled: true,
      maxDays: maxHistoryDays,
      accounts: Object.keys(historyStore.accounts).length,
      waybackBackfilled: Object.values(historyStore.meta).filter((meta) => meta?.waybackAt).length,
      graph: {
        // Follower enumeration needs an authenticated Rettiwt session.
        enabled: graphEnabled && Boolean(process.env.RETTIWT_API_KEY),
        backfilled: Object.values(historyStore.meta).filter((meta) => meta?.graphAt).length,
      },
    },
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

// ---- Shared history pool ---------------------------------------------------
// Opt-in clients register the accounts they track and read back the pooled
// per-day history. The server sources every ongoing number itself (FxTwitter),
// so there is nothing for a hostile client to poison; client uploads only
// backfill days the server never observed, bounded by the server's own
// real anchors.

app.get("/history/:username", async (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }
  const key = username.toLowerCase();
  if (!historyStore.accounts[key]?.length) {
    try {
      const profile = await fetchProfile(username);
      recordHistorySample(profile);
      cache.set(key, { fetchedAt: Date.now(), data: profile });
      void backfillFromWayback(username);
      void backfillFromFollowerGraph(username);
      console.log(`Pool registered ${key}`);
    } catch (error) {
      console.error(`Pool registration failed for ${username}:`, error);
      res.status(502).json({ error: "profile_fetch_failed" });
      return;
    }
  }
  res.json({ userName: username, history: historyFor(username) });
});

app.post("/history/:username/backfill", (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }
  const key = username.toLowerCase();
  const existing = historyStore.accounts[key];
  if (!existing?.length) {
    res.status(409).json({ error: "account_not_registered" });
    return;
  }
  const submitted = Array.isArray(req.body?.samples) ? req.body.samples.slice(0, maxHistoryDays) : [];
  const accepted = acceptableClientSamples(existing, submitted);
  if (accepted.length) {
    storeSamples(key, accepted, { preferExisting: true });
    console.log(`Pool backfill for ${key}: accepted ${accepted.length}/${submitted.length}`);
  }
  res.json({ accepted: accepted.length, rejected: submitted.length - accepted.length });
});

// Gap days only (server samples always win), never today, never estimates,
// and follower counts capped relative to the server's own real anchors.
// Worst case is a fabricated value on a day nobody observed, bracketed ever
// more tightly as real samples accumulate around it.
function acceptableClientSamples(existing, submitted) {
  const real = existing.filter((sample) => !sample.est);
  if (!real.length) return [];
  const knownDays = new Set(existing.filter((sample) => !sample.est).map((sample) => sample.ts));
  const today = startOfDay(Date.now());
  const followerCap = Math.max(...real.map((sample) => sample.followers)) * 2 + 100;
  const accepted = new Map();
  for (const item of submitted) {
    const sample = normalizeHistorySample(item);
    if (!sample || sample.est) continue;
    if (sample.ts >= today || knownDays.has(sample.ts) || accepted.has(sample.ts)) continue;
    if (sample.followers > followerCap) continue;
    accepted.set(sample.ts, { ...sample, src: "client" });
  }
  return Array.from(accepted.values());
}

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
  // Body-parser rejections (bad JSON, oversize payload) carry their own status.
  const status = Number(error?.status || error?.statusCode);
  res.status(status >= 400 && status < 600 ? status : 500).json({ error: "internal_error" });
});

const server = app.listen(port, "0.0.0.0", () => {
  console.log(`Twidget bridge listening on ${port}`);
});

// Exit 0 on deploy teardown so npm doesn't report the SIGTERM as a crash.
for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    console.log(`${signal} received, shutting down`);
    saveHistoryStore();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 4000).unref();
  });
}

// Record a daily sample for every account the store has ever seen, whether or
// not any client asks for it today — history accumulates while phones are off.
const refreshTimer = setInterval(refreshKnownAccounts, historyRefreshMs);
refreshTimer.unref?.();
const bootRefreshTimer = setTimeout(refreshKnownAccounts, 15_000);
bootRefreshTimer.unref?.();

let refreshing = false;
async function refreshKnownAccounts() {
  if (refreshing) return;
  refreshing = true;
  try {
    const today = startOfDay(Date.now());
    for (const key of Object.keys(historyStore.accounts)) {
      const samples = historyStore.accounts[key] || [];
      const latest = samples[samples.length - 1];
      if (latest && latest.ts >= today) continue;
      try {
        const profile = await fetchProfile(key);
        recordHistorySample(profile);
        cache.set(key, { fetchedAt: Date.now(), data: profile });
        console.log(`Daily refresh recorded ${key}`);
      } catch (error) {
        console.warn(`Daily refresh failed for ${key}:`, error);
      }
      await sleep(3000);
    }
    for (const key of Object.keys(historyStore.accounts)) {
      if (!historyStore.meta[key]?.waybackAt) await backfillFromWayback(key);
      if (!historyStore.meta[key]?.graphAt) await backfillFromFollowerGraph(key);
    }
  } finally {
    refreshing = false;
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function handleUser(input, res) {
  const username = cleanUsername(input);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }

  const cached = cache.get(username.toLowerCase());
  if (cached && Date.now() - cached.fetchedAt < cacheTtlMs) {
    res.setHeader("X-Twidget-Cache", "hit");
    res.json(withHistory(cached.data));
    return;
  }

  try {
    const profile = await fetchProfile(username);
    recordHistorySample(profile);
    cache.set(username.toLowerCase(), { fetchedAt: Date.now(), data: profile });
    res.setHeader("X-Twidget-Cache", "miss");
    res.json(withHistory(profile));
    void backfillFromWayback(username);
    void backfillFromFollowerGraph(username);
  } catch (error) {
    console.error(`Failed to fetch ${username}:`, error);
    res.status(502).json({
      error: "profile_fetch_failed",
      message: error instanceof Error ? error.message : "Unknown Rettiwt error",
    });
  }
}

// FxTwitter is the primary upstream: free, no credentials, and it reports
// verified/protected directly. Rettiwt stays as the fallback for the days
// FxTwitter is down or blocked.
async function fetchProfile(username) {
  try {
    return await fetchFxProfile(username);
  } catch (error) {
    console.warn(`FxTwitter fetch failed for ${username}, falling back to Rettiwt:`, error?.message ?? error);
  }
  return fetchRettiwtProfile(username);
}

async function fetchFxProfile(username) {
  const response = await fetch(`https://api.fxtwitter.com/${encodeURIComponent(username)}`, {
    headers: { Accept: "application/json", "User-Agent": "TwidgetBridge/0.1" },
    signal: AbortSignal.timeout(10000),
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(`FxTwitter ${response.status}: ${stringValue(json.message) || "request failed"}`);
  }
  const user = json.user;
  if (!user || typeof user !== "object") {
    throw new Error(`FxTwitter returned no user (${stringValue(json.message) || "unknown"})`);
  }
  return {
    fullName: stringValue(user.name) || username,
    userName: (stringValue(user.screen_name) || username).replace(/^@+/, ""),
    followersCount: numberValue(user.followers),
    followingsCount: numberValue(user.following),
    statusesCount: numberValue(user.tweets),
    likeCount: numberValue(user.likes),
    profileImage: highResolutionProfileImageUrl(user.avatar_url),
    isVerified: user.verification && typeof user.verification === "object"
      ? Boolean(user.verification.verified)
      : booleanValue(user.verified),
    isPrivate: user.protected !== undefined ? booleanValue(user.protected) : undefined,
  };
}

async function fetchRettiwtProfile(username) {
  const rettiwt = new Rettiwt({
    ...(process.env.RETTIWT_API_KEY ? { apiKey: process.env.RETTIWT_API_KEY } : {}),
    timeout: 10000,
    maxRetries: 1,
  });
  const details = await rettiwt.user.details(username);
  const user = typeof details?.toJSON === "function" ? details.toJSON() : details;
  const normalized = normalizeUser(user, username);

  if (process.env.X_BEARER_TOKEN && (normalized.isPrivate === undefined || normalized.isVerified === undefined)) {
    try {
      const official = await fetchOfficialXProfile(username);
      return {
        ...normalized,
        isVerified: normalized.isVerified ?? official.isVerified,
        isPrivate: normalized.isPrivate ?? official.isPrivate,
      };
    } catch (error) {
      console.warn(`Official X enrichment failed for ${username}:`, error);
    }
  }

  if (normalized.isPrivate === undefined) {
    const webPrivacy = await fetchWebProfilePrivacy(username);
    if (webPrivacy !== undefined) {
      return { ...normalized, isPrivate: webPrivacy };
    }
  }

  return normalized;
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
  const verified = firstDefined(
    findDeep(user, ["isVerified", "verified", "blueVerified", "blue_verified", "is_blue_verified"]),
    findDeep(user, ["verifiedType", "verified_type"]),
  );
  return {
    fullName: stringValue(firstDefined(findDeep(user, ["fullName", "name", "displayName", "display_name"]), fallbackUsername)),
    userName: stringValue(firstDefined(findDeep(user, ["userName", "username", "screenName", "screen_name", "handle"]), fallbackUsername)).replace(/^@+/, ""),
    followersCount: numberValue(findDeep(user, ["followersCount", "followers_count", "followerCount", "follower_count"])),
    followingsCount: numberValue(findDeep(user, ["followingsCount", "followingCount", "friends_count", "following_count"])),
    statusesCount: numberValue(findDeep(user, ["statusesCount", "statusCount", "tweetCount", "tweetsCount", "statuses_count", "listed_count"])),
    likeCount: numberValue(findDeep(user, ["likeCount", "likesCount", "favouritesCount", "favoritesCount", "favourites_count", "favorites_count"])),
    profileImage: highResolutionProfileImageUrl(findDeep(user, ["profileImage", "profile_image_url_https", "profile_image_url", "avatar", "avatarUrl", "avatar_url", "imageUrl", "image_url"])),
    isVerified: booleanValue(verified),
    isPrivate: booleanValue(findDeep(user, ["isPrivate", "private", "protected", "isProtected", "is_protected", "protectedProfile"])),
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
  const normalized = {
    fullName: stringValue(user.name || user.username),
    userName: stringValue(user.username).replace(/^@+/, ""),
    followersCount: numberValue(metrics.followers_count),
    followingsCount: numberValue(metrics.following_count),
    statusesCount: numberValue(metrics.tweet_count),
    likeCount: 0,
    profileImage: highResolutionProfileImageUrl(user.profile_image_url),
    isVerified: user.verified !== undefined || user.verified_type !== undefined
      ? Boolean(booleanValue(user.verified) || verifiedTypeValue(user.verified_type))
      : undefined,
    isPrivate: user.protected !== undefined ? booleanValue(user.protected) : undefined,
  };
  return Object.fromEntries(Object.entries(normalized).filter(([, value]) => value !== undefined));
}

function withHistory(profile) {
  const username = cleanUsername(profile.userName);
  return {
    ...profile,
    syncedAt: Date.now(),
    history: historyFor(username),
  };
}

function recordHistorySample(profile) {
  const username = cleanUsername(profile.userName);
  if (!username) return [];
  return storeSamples(username.toLowerCase(), [historySampleFor(profile, Date.now())]);
}

// Merge new samples into an account's history, one per calendar day. New
// samples win by default; preferExisting keeps recorded daily closes intact
// when merging coarser data such as Wayback anchors.
function storeSamples(key, samples, { preferExisting = false } = {}) {
  const existing = Array.isArray(historyStore.accounts[key]) ? historyStore.accounts[key] : [];
  const ordered = preferExisting ? [...samples, ...existing] : [...existing, ...samples];
  const byDay = new Map();
  for (const item of ordered) {
    const normalized = normalizeHistorySample(item);
    if (!normalized) continue;
    // A real measurement on a day always beats an estimate for that day.
    const current = byDay.get(normalized.ts);
    if (current && !current.est && normalized.est) continue;
    byDay.set(normalized.ts, normalized);
  }
  historyStore.accounts[key] = capSamples(
    Array.from(byDay.values()).sort((left, right) => left.ts - right.ts),
  );
  saveHistoryStore();
  return historyStore.accounts[key];
}

// Daily resolution for the recent window; older samples (Wayback anchors)
// thin to one per month instead of being evicted by a hard cap.
function capSamples(samples) {
  const cutoff = startOfDay(Date.now()) - maxHistoryDays * 24 * 60 * 60 * 1000;
  const recent = samples.filter((sample) => sample.ts >= cutoff);
  const older = samples.filter((sample) => sample.ts < cutoff);
  if (!older.length) return recent;
  const monthly = new Map();
  for (const sample of older) {
    const date = new Date(sample.ts);
    monthly.set(date.getFullYear() * 12 + date.getMonth(), sample);
  }
  return [...monthly.values(), ...recent];
}

function historyFor(username) {
  const key = cleanUsername(username).toLowerCase();
  return (historyStore.accounts[key] || [])
    .map(normalizeHistorySample)
    .filter(Boolean)
    .sort((left, right) => left.ts - right.ts);
}

function historySampleFor(profile, now) {
  const ts = startOfDay(now);
  return {
    dayLabel: dayLabel(ts),
    followers: numberValue(profile.followersCount),
    following: numberValue(profile.followingsCount),
    posts: numberValue(profile.statusesCount),
    likes: numberValue(profile.likeCount),
    ts,
  };
}

function normalizeHistorySample(sample) {
  if (!sample || typeof sample !== "object") return null;
  const ts = startOfDay(numberValue(firstDefined(sample.ts, sample.timestamp, sample.syncedAt)));
  if (!ts) return null;
  return {
    dayLabel: stringValue(sample.dayLabel) || dayLabel(ts),
    followers: numberValue(sample.followers),
    following: numberValue(firstDefined(sample.following, sample.followings)),
    posts: numberValue(sample.posts),
    likes: numberValue(sample.likes),
    ts,
    ...(sample.src ? { src: stringValue(sample.src) } : {}),
    ...(sample.est ? { est: true } : {}),
  };
}

function startOfDay(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 0;
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

function dayLabel(ts) {
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" }).format(new Date(ts));
}

function loadHistoryStore() {
  try {
    const raw = fs.readFileSync(historyStorePath, "utf8");
    const parsed = JSON.parse(raw);
    return {
      version: 1,
      accounts: parsed && typeof parsed.accounts === "object" && parsed.accounts ? parsed.accounts : {},
      meta: parsed && typeof parsed.meta === "object" && parsed.meta ? parsed.meta : {},
      graphPurgedAt: Number(parsed?.graphPurgedAt) || undefined,
    };
  } catch (error) {
    if (error.code !== "ENOENT") {
      console.warn(`Unable to read history store at ${historyStorePath}:`, error);
    }
    return { version: 1, accounts: {}, meta: {} };
  }
}

function saveHistoryStore() {
  try {
    fs.mkdirSync(path.dirname(historyStorePath), { recursive: true });
    const tempPath = `${historyStorePath}.tmp`;
    fs.writeFileSync(tempPath, JSON.stringify(historyStore, null, 2));
    fs.renameSync(tempPath, historyStorePath);
  } catch (error) {
    console.warn(`Unable to write history store at ${historyStorePath}:`, error);
  }
}

// ---- Follower-graph backfill ---------------------------------------------
// Reconstructs an estimated followers-over-time curve from the follow order
// and follower account-creation dates (see graph.js). Works best for exactly
// the small accounts archives miss, but needs an authenticated Rettiwt
// session (RETTIWT_API_KEY) to enumerate followers — dormant without one.
// Every sample is flagged est so clients render it as an estimate.

const graphInFlight = new Set();

async function backfillFromFollowerGraph(username) {
  const key = cleanUsername(username).toLowerCase();
  if (!graphEnabled || !process.env.RETTIWT_API_KEY || !key || graphInFlight.has(key)) return;
  if (historyStore.meta[key]?.graphAt) return;
  graphInFlight.add(key);
  try {
    const rettiwt = new Rettiwt({ apiKey: process.env.RETTIWT_API_KEY, timeout: 15000, maxRetries: 1 });
    const details = await rettiwt.user.details(key);
    const total = numberValue(details?.followersCount);
    const accountCreatedAt = new Date(details?.createdAt ?? 0).getTime() || 0;
    if (!details?.id || !total || !accountCreatedAt || total > graphMaxFollowers) {
      historyStore.meta[key] = {
        ...historyStore.meta[key],
        graphAt: Date.now(),
        graphSamples: 0,
        graphSkipped: total > graphMaxFollowers ? "too_many_followers" : "missing_details",
      };
      saveHistoryStore();
      return;
    }

    const followerCreatedAts = [];
    let cursor;
    for (let page = 0; page < Math.ceil(graphMaxFollowers / 100); page += 1) {
      const result = await rettiwt.user.followers(details.id, 100, cursor);
      const list = result?.list ?? [];
      for (const follower of list) {
        const created = new Date(follower?.createdAt ?? 0).getTime();
        if (created > 0) followerCreatedAts.push(created);
      }
      cursor = result?.next?.value;
      if (!cursor || !list.length) break;
      await sleep(400);
    }
    followerCreatedAts.reverse(); // API pages newest follow first

    // Pagination often ends before the full list (rate limits, API caps), so
    // the window covers only the newest follows; everything before it counts
    // toward the anchor base or the curve collapses onto the account's start.
    const baseCount = Math.max(0, total - followerCreatedAts.length);
    const points = reconstructFollowerHistory({
      followerCreatedAts,
      accountCreatedAt,
      currentCount: total,
      now: Date.now(),
      baseCount,
    });
    const samples = points.map((point) => ({
      dayLabel: dayLabel(point.ts),
      followers: point.followers,
      following: 0,
      posts: 0,
      likes: 0,
      ts: point.ts,
      est: true,
      src: "graph",
    }));
    if (samples.length) storeSamples(key, samples, { preferExisting: true });
    historyStore.meta[key] = {
      ...historyStore.meta[key],
      graphAt: Date.now(),
      graphSamples: samples.length,
      graphEnumerated: followerCreatedAts.length,
      graphTotal: total,
    };
    saveHistoryStore();
    console.log(`Follower-graph backfill for ${key}: ${samples.length} estimated samples from ${followerCreatedAts.length}/${total} followers`);
  } catch (error) {
    // No marker on failure: refreshKnownAccounts retries next cycle, so the
    // feature self-activates once a valid key appears.
    console.warn(`Follower-graph backfill failed for ${key}:`, error?.message ?? error);
  } finally {
    graphInFlight.delete(key);
  }
}

// ---- Wayback Machine backfill -------------------------------------------
// archive.org snapshots of profile pages are real, dated follower counts.
// Fetched once per account (marker in historyStore.meta), fire-and-forget.

const waybackInFlight = new Set();

async function backfillFromWayback(username) {
  const key = cleanUsername(username).toLowerCase();
  if (!waybackEnabled || !key || waybackInFlight.has(key)) return;
  if (historyStore.meta[key]?.waybackAt) return;
  waybackInFlight.add(key);
  try {
    const snapshots = await waybackSnapshots(key);
    const samples = [];
    for (const [timestamp, original] of snapshots) {
      const sample = await waybackSample(timestamp, original);
      if (sample) samples.push(sample);
      await sleep(500);
    }
    if (samples.length) storeSamples(key, samples, { preferExisting: true });
    historyStore.meta[key] = { ...historyStore.meta[key], waybackAt: Date.now(), waybackSamples: samples.length };
    saveHistoryStore();
    console.log(`Wayback backfill for ${key}: ${samples.length} of ${snapshots.length} snapshots usable`);
  } catch (error) {
    console.warn(`Wayback backfill failed for ${key}:`, error);
    historyStore.meta[key] = { ...historyStore.meta[key], waybackAt: Date.now(), waybackSamples: 0 };
    saveHistoryStore();
  } finally {
    waybackInFlight.delete(key);
  }
}

async function waybackSnapshots(username) {
  const rows = [];
  for (const domain of ["twitter.com", "x.com"]) {
    const url = new URL("https://web.archive.org/cdx/search/cdx");
    url.searchParams.set("url", `${domain}/${username}`);
    url.searchParams.set("output", "json");
    url.searchParams.set("fl", "timestamp,original");
    url.searchParams.set("filter", "statuscode:200");
    url.searchParams.set("collapse", "timestamp:6");
    // x.com only exists since mid-2023; bounding the scan keeps CDX fast.
    if (domain === "x.com") url.searchParams.set("from", "20230601");
    try {
      const response = await fetch(url, {
        headers: { "User-Agent": "TwidgetBridge/0.1" },
        signal: AbortSignal.timeout(15000),
      });
      if (!response.ok) continue;
      const json = await response.json();
      if (Array.isArray(json)) rows.push(...json.slice(1));
    } catch (error) {
      console.warn(`Wayback CDX lookup failed for ${domain}/${username}:`, error);
    }
  }
  rows.sort((left, right) => String(left[0]).localeCompare(String(right[0])));
  return spreadEvenly(rows, waybackMaxSnapshots);
}

function spreadEvenly(rows, max) {
  if (rows.length <= max) return rows;
  const picked = [];
  for (let index = 0; index < max; index += 1) {
    picked.push(rows[Math.round((index * (rows.length - 1)) / (max - 1))]);
  }
  return [...new Set(picked)];
}

async function waybackSample(timestamp, original) {
  const ts = waybackDayTs(timestamp);
  if (!ts || ts >= startOfDay(Date.now())) return null;
  try {
    // id_ serves the original captured bytes without the Wayback toolbar.
    const response = await fetch(`https://web.archive.org/web/${timestamp}id_/${original}`, {
      headers: { "User-Agent": "TwidgetBridge/0.1" },
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) return null;
    const html = await response.text();
    const followers = matchCount(html, [
      /"followers_count"\s*:\s*(\d+)/,
      /followers_count&quot;\s*:\s*(\d+)/,
      /ProfileNav-item--followers[\s\S]{0,600}?data-count="(\d+)"/,
      /data-nav="followers"[\s\S]{0,300}?title="([\d.,  ]+)\s+Follower/i,
      /title="([\d.,  ]+)\s+Followers?"/i,
    ]);
    if (!followers) return null;
    return {
      dayLabel: dayLabel(ts),
      followers,
      following: matchCount(html, [/"friends_count"\s*:\s*(\d+)/, /friends_count&quot;\s*:\s*(\d+)/]),
      posts: matchCount(html, [/"statuses_count"\s*:\s*(\d+)/, /statuses_count&quot;\s*:\s*(\d+)/]),
      likes: matchCount(html, [/"favourites_count"\s*:\s*(\d+)/, /favourites_count&quot;\s*:\s*(\d+)/]),
      ts,
      src: "wayback",
    };
  } catch (error) {
    console.warn(`Wayback snapshot fetch failed (${timestamp}):`, error);
    return null;
  }
}

function waybackDayTs(timestamp) {
  const match = /^(\d{4})(\d{2})(\d{2})/.exec(String(timestamp));
  if (!match) return 0;
  const time = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])).getTime();
  return Number.isNaN(time) ? 0 : time;
}

function matchCount(html, patterns) {
  for (const pattern of patterns) {
    const match = pattern.exec(html);
    if (!match) continue;
    const value = Number(String(match[1]).replace(/\D/g, ""));
    if (Number.isFinite(value) && value > 0) return value;
  }
  return 0;
}

async function fetchWebProfilePrivacy(username) {
  try {
    const response = await fetch(`https://x.com/${encodeURIComponent(username)}`, {
      headers: {
        Accept: "text/html",
        "User-Agent": "Mozilla/5.0 TwidgetBridge/0.1",
      },
      signal: AbortSignal.timeout(4000),
    });
    if (!response.ok) return undefined;
    const body = (await response.text()).toLowerCase();
    return body.includes("these posts are protected") ||
      body.includes("only approved followers can see");
  } catch (error) {
    console.warn(`X web privacy check failed for ${username}:`, error);
    return undefined;
  }
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

function firstDefined(...values) {
  return values.find((value) => value !== undefined && value !== null);
}

function findDeep(value, keys, depth = 0) {
  if (!value || typeof value !== "object" || depth > 4) return undefined;
  for (const key of keys) {
    if (value[key] !== undefined && value[key] !== null && value[key] !== "") return value[key];
  }
  for (const child of Object.values(value)) {
    if (Array.isArray(child)) {
      for (const item of child) {
        const found = findDeep(item, keys, depth + 1);
        if (found !== undefined) return found;
      }
    } else if (child && typeof child === "object") {
      const found = findDeep(child, keys, depth + 1);
      if (found !== undefined) return found;
    }
  }
  return undefined;
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
  return undefined;
}

function verifiedTypeValue(value) {
  const normalized = stringValue(value).toLowerCase();
  if (!normalized) return undefined;
  return ["blue", "business", "government"].includes(normalized);
}
