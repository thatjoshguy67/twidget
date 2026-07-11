import express from "express";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { Pool } from "pg";
import { createClient as createRedisClient } from "redis";
import { Rettiwt } from "rettiwt-api";
import { bangerScore } from "./banger-score.js";
import { prepareAnalyticsImport } from "./analytics-import.js";
import {
  LocalFixedWindowLimiter,
  LocalHistoryRepository,
  PostgresHistoryRepository,
  RedisFixedWindowLimiter,
  SharedJsonCache,
} from "./infrastructure.js";

const app = express();
const port = envInteger("PORT", 8787, 1, 65535);
const cacheTtlMs = envInteger("CACHE_TTL_SECONDS", 900, 30, 86400) * 1000;
const rateLimitWindowMs = envInteger("RATE_LIMIT_WINDOW_SECONDS", 60, 1, 3600) * 1000;
const rateLimitMax = envInteger("RATE_LIMIT_MAX", 60, 1, 10000);
const expensiveRateLimitMax = envInteger("EXPENSIVE_RATE_LIMIT_MAX", 12, 1, 1000);
const maxTrackedIps = envInteger("RATE_LIMIT_MAX_IPS", 10000, 100, 100000);
const maxCacheEntries = envInteger("MAX_CACHE_ENTRIES", 1000, 10, 100000);
const maxConcurrentUpstream = envInteger("MAX_CONCURRENT_UPSTREAM", 8, 1, 100);
const maxPendingOAuth = envInteger("OAUTH_MAX_PENDING", 1000, 10, 10000);
const maxHistoryDays = envInteger("HISTORY_MAX_DAYS", 400, 7, 3650);
const maxHistoryAccounts = envInteger("HISTORY_MAX_ACCOUNTS", 250, 1, 100000);
const historyRegistrationsPerHour = envInteger("HISTORY_REGISTRATIONS_PER_HOUR", 30, 1, 10000);
const historyRefreshMs = envInteger("HISTORY_REFRESH_MINUTES", 60, 15, 1440) * 60 * 1000;
const historySampleRetentionDays = envInteger("HISTORY_SAMPLE_RETENTION_DAYS", 0, 0, 36500);
const historyInactiveAccountDays = envInteger("HISTORY_INACTIVE_ACCOUNT_DAYS", 0, 0, 36500);
const historyPruneMs = envInteger("HISTORY_PRUNE_HOURS", 24, 1, 168) * 60 * 60 * 1000;
const bangerPagesPerRequest = envInteger("BANGER_PAGES_PER_REQUEST", 5, 1, 20);
const bangerMaxPosts = envInteger("BANGER_MAX_POSTS", 75000, 20, 100000);
const bangerScoringVersion = 2;
// Archive lookups fan one request out into many remote fetches, so self-hosters
// must opt in deliberately. It is unsafe as a public-instance default.
const waybackEnabled = process.env.WAYBACK_BACKFILL === "1";
const waybackMaxSnapshots = envInteger("WAYBACK_MAX_SNAPSHOTS", 12, 1, 50);
const maxWaybackConcurrent = envInteger("WAYBACK_MAX_CONCURRENT", 1, 1, 5);
const bridgeApiToken = String(process.env.BRIDGE_API_TOKEN || "").trim();
const historyAdminToken = String(process.env.HISTORY_ADMIN_TOKEN || "").trim();
const xOAuthEnabled = process.env.X_OAUTH_ENABLED === "1";
const publicOfficialApi = process.env.PUBLIC_OFFICIAL_API === "1";
const historyStorePath = process.env.HISTORY_STORE_PATH ||
  (fs.existsSync("/data") ? "/data/twidget-history.json" : path.resolve(process.cwd(), "data", "twidget-history.json"));
const historyBackend = String(process.env.HISTORY_BACKEND || "json").trim().toLowerCase();
const redisUrl = String(process.env.REDIS_URL || "").trim();

const redisClient = await connectRedis(redisUrl);
const cache = new SharedJsonCache({ client: redisClient, prefix: "twidget:profile", maxEntries: maxCacheEntries });
const analyticsCache = new SharedJsonCache({ client: redisClient, prefix: "twidget:analytics", maxEntries: maxCacheEntries });
const requestLimiter = redisClient
  ? new RedisFixedWindowLimiter({ client: redisClient, prefix: "twidget:rate:all", windowMs: rateLimitWindowMs })
  : new LocalFixedWindowLimiter({ windowMs: rateLimitWindowMs, maxKeys: maxTrackedIps });
const expensiveLimiter = redisClient
  ? new RedisFixedWindowLimiter({ client: redisClient, prefix: "twidget:rate:expensive", windowMs: rateLimitWindowMs })
  : new LocalFixedWindowLimiter({ windowMs: rateLimitWindowMs, maxKeys: maxTrackedIps });
const historyRegistrationLimiter = redisClient
  ? new RedisFixedWindowLimiter({ client: redisClient, prefix: "twidget:rate:history-registration", windowMs: 60 * 60 * 1000 })
  : new LocalFixedWindowLimiter({ windowMs: 60 * 60 * 1000, maxKeys: 1 });
const historyRepository = await createHistoryRepository();
const oauthStates = new Map();
const oauthSessions = new Map();
const profileInFlight = new Map();
const bangerInFlight = new Map();
let upstreamActive = 0;

// The follower-graph reconstruction feature is gone (removed 2026-07-08:
// Rettiwt enumeration only ever returned ~1 page and the weekly-only app UI
// never shows long-range estimates). Strip any legacy graph samples and
// markers left in the store.
{
  let purged = 0;
  for (const key of await historyRepository.listAccounts()) {
    const samples = await historyRepository.getHistory(key);
    const meta = await historyRepository.getMeta(key);
    const kept = samples.filter((sample) => sample.src !== "graph");
    purged += samples.length - kept.length;
    if (samples.length !== kept.length) {
      await historyRepository.deleteAccount(key);
      if (kept.length) await historyRepository.storeSamples(key, kept, { touchAccess: false });
    }
    for (const marker of ["graphAt", "graphSamples", "graphSkipped", "graphEnumerated", "graphTotal"]) {
      delete meta[marker];
    }
    if (kept.length) await historyRepository.setMeta(key, meta);
  }
  if (purged) {
    console.log(`Purged ${purged} legacy graph samples`);
  }
}

app.disable("x-powered-by");
// Railway puts one proxy in front of the service. Self-hosters with a different
// topology must set the exact hop count; Express warns against blanket `true`.
app.set("trust proxy", envInteger("TRUST_PROXY_HOPS", 1, 0, 10));

app.use((req, res, next) => {
  res.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
  res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
  res.setHeader("Cross-Origin-Resource-Policy", "cross-origin");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  const corsOrigin = String(process.env.CORS_ALLOW_ORIGIN || "").trim();
  if (corsOrigin) res.setHeader("Access-Control-Allow-Origin", corsOrigin);
  res.setHeader("Cache-Control", "no-store");
  next();
});

// Reject abusive requests before parsing any body. Expensive endpoints also
// get a smaller budget because they can trigger remote API work.
app.use(rateLimit(requestLimiter, rateLimitMax));
app.use((req, res, next) => {
  if (/^\/(admin|analytics|banger|history|official|oauth)\b/.test(req.path)) {
    return rateLimit(expensiveLimiter, expensiveRateLimitMax)(req, res, next);
  }
  next();
});

// Administrative deletion has a separate credential that must never be
// distributed to app clients. Protect the namespace centrally so future
// operator routes cannot accidentally inherit public bridge access.
app.use("/admin", (req, res, next) => {
  if (!historyAdminToken) {
    res.status(404).json({ error: "not_found" });
    return;
  }
  if (!tokenMatches(requestToken(req), historyAdminToken)) {
    res.setHeader("WWW-Authenticate", "Bearer");
    res.status(401).json({ error: "unauthorized" });
    return;
  }
  next();
});

// Optional protection for self-hosted bridges. Health and OAuth browser
// callbacks remain public; every data endpoint requires the configured token.
app.use((req, res, next) => {
  if (!bridgeApiToken || req.path === "/health" || req.path === "/" || req.path.startsWith("/oauth/") || req.path.startsWith("/admin/")) {
    next();
    return;
  }
  if (tokenMatches(requestToken(req), bridgeApiToken)) {
    next();
    return;
  }
  res.setHeader("WWW-Authenticate", "Bearer");
  res.status(401).json({ error: "unauthorized" });
});

const historyJsonBody = express.json({ limit: "64kb", strict: true });

// Both in-memory maps would otherwise grow unbounded on a long-lived public
// instance: expired cache entries are only ever overwritten, and each unique
// client IP leaves a hits bucket behind. Sweep both periodically.
const sweepIntervalMs = Math.max(10_000, Math.min(rateLimitWindowMs, cacheTtlMs));
const sweepTimer = setInterval(() => {
  cache.sweep(cacheTtlMs, "fetchedAt");
  analyticsCache.sweep(analyticsTtlMs, "cachedAt");
  requestLimiter.sweep?.();
  expensiveLimiter.sweep?.();
  cleanupOAuthMaps();
}, sweepIntervalMs);
sweepTimer.unref?.();

let storageHealth = { checkedAt: 0, ok: true };
app.get("/health", async (_req, res) => {
  if (Date.now() - storageHealth.checkedAt >= 5000) {
    try {
      await historyRepository.healthCheck();
      storageHealth = { checkedAt: Date.now(), ok: true };
    } catch (error) {
      console.warn("History readiness check failed:", error?.message || error);
      storageHealth = { checkedAt: Date.now(), ok: false };
    }
  }
  res.status(storageHealth.ok ? 200 : 503).json({
    ok: storageHealth.ok,
    service: "twidget-bridge",
    upstream: "fxtwitter",
    authMode: bridgeApiToken ? "bearer" : "public",
    xOAuthConfigured: xOAuthEnabled && Boolean(process.env.X_CLIENT_ID),
    history: {
      enabled: true,
      backend: historyRepository.backendName(),
      maxDays: maxHistoryDays,
      sampleRetentionDays: historySampleRetentionDays || null,
      inactiveAccountDays: historyInactiveAccountDays || null,
      analyticsImport: true,
      archiveBackfill: waybackEnabled,
    },
    sharedState: redisClient ? "redis" : "local",
  });
});

app.get("/oauth/x/start", (req, res) => {
  const clientId = process.env.X_CLIENT_ID;
  if (!xOAuthEnabled || !clientId || !process.env.X_CALLBACK_URL) {
    res.status(501).json({ error: "x_oauth_not_configured" });
    return;
  }

  const returnUri = validReturnUri(req.query.return_uri) || process.env.TWIDGET_ANDROID_RETURN_URI || "twidget://oauth/x";
  const redirectUri = xRedirectUri(req);
  const state = base64Url(crypto.randomBytes(24));
  const verifier = base64Url(crypto.randomBytes(32));
  const challenge = base64Url(crypto.createHash("sha256").update(verifier).digest());

  cleanupOAuthMaps();
  if (oauthStates.size >= maxPendingOAuth) {
    res.setHeader("Retry-After", "60");
    res.status(503).json({ error: "oauth_capacity_reached" });
    return;
  }
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
  if (!xOAuthEnabled) {
    res.status(404).json({ error: "not_found" });
    return;
  }
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
    const user = await withUpstreamSlot(async () => {
      const token = await exchangeXCode({
        code: String(req.query.code || ""),
        redirectUri: record.redirectUri,
        verifier: record.verifier,
      });
      return fetchXMe(token.access_token);
    });
    cleanupOAuthMaps();
    if (oauthSessions.size >= maxPendingOAuth) {
      res.redirect(returnUrl(record.returnUri, { error: "oauth_capacity_reached" }));
      return;
    }
    const session = base64Url(crypto.randomBytes(24));
    oauthSessions.set(session, {
      createdAt: Date.now(),
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
  if (!xOAuthEnabled) {
    res.status(404).json({ error: "not_found" });
    return;
  }
  cleanupOAuthMaps();
  const key = String(req.params.session || "");
  const session = oauthSessions.get(key);
  if (!session) {
    res.status(404).json({ error: "session_not_found" });
    return;
  }
  // The deep-link handoff is single-use, limiting exposure if a session URL is
  // copied from browser history or logs.
  oauthSessions.delete(key);
  res.json(session.user);
});

app.get(["/official/user/:username", "/x/user/:username", "/api/x/user/:username"], async (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }
  if ((!publicOfficialApi && !bridgeApiToken) || !process.env.X_BEARER_TOKEN) {
    res.status(501).json({ error: "x_api_not_configured" });
    return;
  }

  try {
    const data = await withUpstreamSlot(() => fetchOfficialXProfile(username));
    res.json(data);
  } catch (error) {
    console.error(`Official X API fetch failed for ${username}:`, error);
    sendUpstreamFailure(res, "x_api_fetch_failed", error);
  }
});

// ---- Shared history pool ---------------------------------------------------
// Opt-in clients register the accounts they track and read back the pooled
// per-day history. Ordinary samples remain server-sourced. Explicit X
// Analytics imports submit movements rather than totals and are admitted only
// after reconstruction matches independent live and stored follower anchors.

app.get("/history/:username", async (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }
  const key = username.toLowerCase();
  const existing = await historyRepository.getHistory(key, { touch: true });
  if (!existing.length) {
    const accountExists = await historyRepository.hasAccount(key);
    const accountCount = await historyRepository.countAccounts();
    if (!accountExists && accountCount >= maxHistoryAccounts) {
      res.status(503).json({ error: "history_capacity_reached" });
      return;
    }
    if (!accountExists && !(await takeHistoryRegistrationSlot())) {
      res.setHeader("Retry-After", "3600");
      res.status(429).json({ error: "history_registration_limited" });
      return;
    }
    try {
      const profile = await fetchProfileLimited(username);
      if (profile.isPrivate === true) {
        res.status(403).json({ error: "private_account_not_pooled" });
        return;
      }
      const registered = await recordHistorySample(profile, { allowNew: true });
      if (registered === null) {
        res.status(503).json({ error: "history_capacity_reached" });
        return;
      }
      await cache.set(key, { fetchedAt: Date.now(), data: profile }, cacheTtlMs * 2);
      void backfillFromWayback(username);
      console.log(`Pool registered ${key}`);
    } catch (error) {
      console.error(`Pool registration failed for ${username}:`, error);
      sendUpstreamFailure(res, "profile_fetch_failed", error);
      return;
    }
  }
  res.json({ userName: username, history: await historyFor(username, { touch: true }) });
});

app.post("/history/:username/analytics-import", historyJsonBody, async (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }
  const key = username.toLowerCase();
  const existing = await historyRepository.getHistory(key, { touch: true });
  if (!existing.length) {
    res.status(409).json({ error: "insufficient_trusted_history" });
    return;
  }
  try {
    const profile = await fetchProfileLimited(username);
    if (profile.isPrivate === true) {
      res.status(403).json({ error: "private_account_not_pooled" });
      return;
    }
    if (!profileMetricKnown(profile, "followersCount")) {
      res.status(503).json({ error: "current_followers_unavailable" });
      return;
    }
    const validation = prepareAnalyticsImport({
      movements: req.body?.movements,
      currentFollowers: numberValue(profile.followersCount),
      existing,
    });
    if (!validation.ok) {
      const status = validation.error === "insufficient_trusted_history" ? 409 :
        validation.error === "analytics_trend_mismatch" ? 422 : 400;
      res.status(status).json({ error: validation.error, ...(validation.detail ? { detail: validation.detail } : {}) });
      return;
    }
    await recordHistorySample(profile);
    await cache.set(key, { fetchedAt: Date.now(), data: profile }, cacheTtlMs * 2);
    if (validation.samples.length) {
      await storeSamples(key, validation.samples, { preferExisting: true });
    }
    console.log(`X Analytics import for ${key}: accepted ${validation.samples.length}, checked ${validation.checkedAnchors} anchors`);
    res.json({
      accepted: validation.samples.length,
      checkedAnchors: validation.checkedAnchors,
      history: await historyFor(username, { touch: true }),
    });
  } catch (error) {
    console.error(`X Analytics import failed for ${username}:`, error);
    sendUpstreamFailure(res, "profile_fetch_failed", error);
  }
});

app.delete("/admin/history/:username", async (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }
  await historyRepository.deleteAccount(username.toLowerCase());
  res.status(204).end();
});

// ---- Post analytics --------------------------------------------------------
// Reach + engagement over the recent timeline, plus the 7-day best/worst posts.
// Timeline fetches are heavier than a profile lookup, so results are cached.

app.get("/banger/:username", async (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) return res.status(400).json({ error: "invalid_username" });
  const key = username.toLowerCase();
  try {
    let pending = bangerInFlight.get(key);
    if (!pending) {
      pending = withUpstreamSlot(() => refreshBanger(username))
        .finally(() => bangerInFlight.delete(key));
      bangerInFlight.set(key, pending);
    }
    const state = await pending;
    res.json(publicBangerState(state));
  } catch (error) {
    console.error(`Banger scan failed for ${username}:`, error?.message ?? error);
    try {
      const stale = normalizeBangerState((await historyRepository.getMeta(key)).banger);
      if (stale.post) {
        res.setHeader("Warning", '110 - "Response is stale"');
        res.json(publicBangerState(stale));
        return;
      }
    } catch (storageError) {
      console.warn(`Unable to read stale banger for ${username}:`, storageError?.message ?? storageError);
    }
    sendUpstreamFailure(res, "banger_failed", error);
  }
});

const analyticsTtlMs = envInteger("ANALYTICS_TTL_SECONDS", 2700, 60, 86400) * 1000; // 45 min
const analyticsPostCount = envInteger("ANALYTICS_POST_COUNT", 100, 1, 200);
const analyticsInFlight = new Map();

app.get("/analytics/:username", async (req, res) => {
  const username = cleanUsername(req.params.username);
  if (!username) {
    res.status(400).json({ error: "invalid_username" });
    return;
  }
  const key = username.toLowerCase();
  const cached = await analyticsCache.get(key);
  if (cached && Date.now() - cached.cachedAt < analyticsTtlMs) {
    res.setHeader("X-Twidget-Cache", "hit");
    setPublicCache(res, 900);
    res.json(cached.data);
    return;
  }
  try {
    // Collapse concurrent requests for the same account onto one fetch.
    let pending = analyticsInFlight.get(key);
    if (!pending) {
      pending = withUpstreamSlot(() => computeAnalytics(username))
        .finally(() => analyticsInFlight.delete(key));
      analyticsInFlight.set(key, pending);
    }
    const data = await pending;
    await analyticsCache.set(key, { cachedAt: Date.now(), data }, analyticsTtlMs * 2);
    res.setHeader("X-Twidget-Cache", "miss");
    setPublicCache(res, 900);
    res.json(data);
  } catch (error) {
    console.error(`Analytics failed for ${username}:`, error?.message ?? error);
    sendUpstreamFailure(res, "analytics_failed", error);
  }
});

async function computeAnalytics(username) {
  const profile = await fetchFxProfile(username);
  const weekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
  const posts = await fetchFxWeeklyPosts(username, weekAgo);

  const postCount = posts.length;
  const views = posts.map((post) => post.views);
  const engagements = posts.map((post) => post.engagements);
  const totalViews = sum(views);
  const totalEngagements = sum(engagements);
  const best = maxBy(posts, (post) => post.engagements);
  const worst = minBy(posts, (post) => post.engagements);

  return {
    userName: username,
    followers: profile.followersCount,
    postsAnalyzed: postCount,
    windowDays: 7,
    reach: {
      totalViews,
      avgViews: mean(views),
      medianViews: median(views),
      avgViewsPerFollower: profile.followersCount > 0 && postCount > 0 ? totalViews / postCount / profile.followersCount : 0,
    },
    engagement: {
      totalEngagements,
      avgEngagements: mean(engagements),
      medianEngagements: median(engagements),
      avgEngagementsPerFollower: profile.followersCount > 0 && postCount > 0 ? totalEngagements / postCount / profile.followersCount : 0,
      engagementRate: totalViews > 0 ? totalEngagements / totalViews : 0,
    },
    // With only one post, best and worst would be identical — show just the one.
    best: posts.length ? best : null,
    worst: posts.length >= 2 ? worst : null,
    cachedAt: Date.now(),
  };
}

async function refreshBanger(username) {
  const key = username.toLowerCase();
  const profile = await fetchFxProfile(username);
  if (profile.isPrivate === true) throw new Error("private_account_not_pooled");
  if (!(await historyRepository.hasAccount(key))) {
    if (await historyRepository.countAccounts() >= maxHistoryAccounts) throw new Error("history_capacity_reached");
    if (!(await takeHistoryRegistrationSlot())) throw new Error("history_registration_limited");
    const registered = await recordHistorySample(profile, { allowNew: true });
    if (registered === null) throw new Error("history_capacity_reached");
  }
  const meta = await historyRepository.getMeta(key);
  let state = normalizeBangerState(meta.banger);
  if (state.version !== bangerScoringVersion) state = normalizeBangerState(null);
  // A raised BANGER_MAX_POSTS lets a previously capped scan resume from its cursor.
  if (state.capped && state.postsScanned < bangerMaxPosts) state.capped = false;
  let cursor = state.complete || state.capped ? "" : state.cursor;
  const pages = state.complete || state.capped ? 1 : bangerPagesPerRequest;
  const seenCursors = new Set();
  for (let pageIndex = 0; pageIndex < pages; pageIndex += 1) {
    let page;
    try {
      page = await fetchFxStatusPage(username, cursor);
    } catch (error) {
      if (!state.post) throw error;
      break;
    }
    for (const status of page.results) {
      if (!isOwnOriginalPost(status, key)) continue;
      const post = normalizeFxStatus(status);
      if (post.views <= 0) continue;
      if (!state.complete && !state.capped) state.postsScanned += 1;
      const score = bangerScore(post);
      if (!state.post || score > state.score || post.url === state.post.url) {
        state.post = post;
        state.score = score;
      }
      if (state.postsScanned >= bangerMaxPosts) break;
    }
    if (state.postsScanned >= bangerMaxPosts) state.capped = true;
    if (state.complete || state.capped) break;
    if (!page.results.length) {
      state.complete = true;
      state.cursor = "";
      break;
    }
    if (!page.cursor || seenCursors.has(page.cursor)) {
      state.complete = !page.cursor;
      state.cursor = "";
      break;
    }
    seenCursors.add(page.cursor);
    state.cursor = page.cursor;
    cursor = page.cursor;
  }
  state.version = bangerScoringVersion;
  state.updatedAt = Date.now();
  meta.banger = state;
  await historyRepository.setMeta(key, meta);
  return state;
}

async function fetchFxStatusPage(username, cursor = "") {
  const url = new URL(`https://api.fxtwitter.com/2/profile/${encodeURIComponent(username)}/statuses`);
  url.searchParams.set("count", "100");
  if (cursor) url.searchParams.set("cursor", cursor);
  const response = await fetch(url, {
    headers: { Accept: "application/json", "User-Agent": "TwidgetBridge/0.1" },
    signal: AbortSignal.timeout(15000),
  });
  if (response.status === 204) return { results: [], cursor: "" };
  const json = await response.json().catch(() => ({}));
  if (!response.ok || numberValue(json.code) >= 400) {
    throw new Error(`FxTwitter statuses ${response.status}: ${stringValue(json.message) || "request failed"}`);
  }
  return {
    results: Array.isArray(json.results) ? json.results : [],
    cursor: stringValue(json.cursor?.bottom),
  };
}

function isOwnOriginalPost(status, requestedUsername) {
  if (!status || status.type !== "status") return false;
  if (stringValue(status.author?.screen_name).toLowerCase() !== requestedUsername) return false;
  if (status.reposted_by || status.replying_to || status.in_reply_to_status_id || status.in_reply_to_status_id_str) return false;
  const id = stringValue(status.id);
  if (status.conversation_id != null && id && stringValue(status.conversation_id) !== id) return false;
  const ts = timestampForStatus(status);
  return ts > 0 && ts <= Date.now() + 5 * 60 * 1000 &&
    stringValue(status.url).toLowerCase().includes(`/${requestedUsername}/status/`);
}

function normalizeBangerState(value) {
  const state = value && typeof value === "object" ? value : {};
  return {
    version: numberValue(state.version),
    post: state.post && typeof state.post === "object" ? state.post : null,
    score: Number.isFinite(Number(state.score)) ? Number(state.score) : 0,
    complete: Boolean(state.complete),
    capped: Boolean(state.capped),
    postsScanned: numberValue(state.postsScanned),
    cursor: stringValue(state.cursor),
    updatedAt: numberValue(state.updatedAt),
  };
}

function publicBangerState(state) {
  return {
    post: state.post,
    score: state.score,
    complete: state.complete,
    capped: state.capped,
    postsScanned: state.postsScanned,
    updatedAt: state.updatedAt,
  };
}

async function fetchFxWeeklyPosts(username, weekAgo) {
  const url = new URL(`https://api.fxtwitter.com/2/profile/${encodeURIComponent(username)}/statuses`);
  url.searchParams.set("count", String(analyticsPostCount));
  url.searchParams.set("since", String(weekAgo));
  const response = await fetch(url, {
    headers: { Accept: "application/json", "User-Agent": "TwidgetBridge/0.1" },
    signal: AbortSignal.timeout(15000),
  });
  if (response.status === 204) return [];
  const json = await response.json().catch(() => ({}));
  if (!response.ok || numberValue(json.code) >= 400) {
    throw new Error(`FxTwitter statuses ${response.status}: ${stringValue(json.message) || "request failed"}`);
  }
  const requested = username.toLowerCase();
  return (Array.isArray(json.results) ? json.results : [])
    .filter((status) => isWeeklyOwnPost(status, requested, weekAgo))
    .map(normalizeFxStatus)
    .sort((a, b) => b.ts - a.ts);
}

function normalizeFxStatus(status) {
  const likes = numberValue(status.likes);
  const reposts = numberValue(status.reposts);
  const replies = numberValue(status.replies);
  const quotes = numberValue(status.quotes);
  const textParts = displayTextAndLinks(status);
  return {
    id: stringValue(status.id),
    url: stringValue(status.url),
    text: textParts.text,
    links: textParts.links,
    views: numberValue(status.views),
    likes,
    replies,
    reposts,
    quotes,
    engagements: likes + reposts + replies + quotes,
    ts: timestampForStatus(status),
    createdAt: stringValue(status.created_at),
    authorName: stringValue(status.author?.name),
    authorUserName: stringValue(status.author?.screen_name),
    authorAvatar: highResolutionProfileImageUrl(status.author?.avatar_url),
    media: mediaForStatus(status),
  };
}

function isWeeklyOwnPost(status, requestedUsername, weekAgo) {
  if (!status || status.type !== "status") return false;
  if (stringValue(status.author?.screen_name).toLowerCase() !== requestedUsername) return false;
  if (status.reposted_by) return false;
  if (status.replying_to || status.in_reply_to_status_id || status.in_reply_to_status_id_str) return false;

  const id = stringValue(status.id);
  if (stringValue(status.url).toLowerCase().includes(`/${requestedUsername}/status/`) === false) return false;

  const ts = timestampForStatus(status);
  return ts >= weekAgo && ts <= Date.now() + 5 * 60 * 1000 && (!id || status.conversation_id == null || stringValue(status.conversation_id) === id);
}

function timestampForStatus(status) {
  return numberValue(status.created_timestamp) * 1000 || new Date(status.created_at || 0).getTime() || 0;
}

function displayTextAndLinks(status) {
  const raw = status.raw_text && typeof status.raw_text === "object" ? status.raw_text : null;
  let text = stringValue(status.text).trim() || stringValue(raw?.text).trim();
  const facets = Array.isArray(raw?.facets) ? raw.facets : [];
  const links = facets
    .map((facet) => ({
      type: stringValue(facet.type),
      original: stringValue(facet.original),
      display: stringValue(facet.display),
      replacement: stringValue(facet.replacement),
    }))
    .filter((facet) => facet.type === "url" && facet.display && (facet.replacement || facet.original))
    .map((facet) => ({ display: facet.display, url: facet.replacement || facet.original }));

  for (const link of links) {
    if (!text.includes(link.display)) {
      text = `${text} ${link.display}`.trim();
    }
  }
  return { text: collapseText(text), links };
}

function mediaForStatus(status) {
  const all = Array.isArray(status.media?.all) ? status.media.all : [];
  return all
    .map((item) => ({
      type: stringValue(item.type),
      url: stringValue(item.thumbnail_url) || stringValue(item.url),
      alt: stringValue(item.altText),
      width: numberValue(item.width),
      height: numberValue(item.height),
    }))
    .filter((item) => item.url)
    .slice(0, 4);
}

function collapseText(value) {
  return Array.from(stringValue(value).replace(/\s+/g, " ").trim()).slice(0, 180).join("");
}

function sum(values) {
  return values.reduce((total, value) => total + value, 0);
}

function mean(values) {
  return values.length ? sum(values) / values.length : 0;
}

function median(values) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

function maxBy(items, selector) {
  return items.reduce((best, item) => (best === null || selector(item) > selector(best) ? item : best), null);
}

function minBy(items, selector) {
  return items.reduce((worst, item) => (worst === null || selector(item) < selector(worst) ? item : worst), null);
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
server.requestTimeout = envInteger("REQUEST_TIMEOUT_MS", 30_000, 1_000, 300_000);
server.headersTimeout = envInteger("HEADERS_TIMEOUT_MS", 15_000, 1_000, server.requestTimeout);
server.keepAliveTimeout = envInteger("KEEP_ALIVE_TIMEOUT_MS", 5_000, 1_000, 60_000);
server.maxHeadersCount = envInteger("MAX_HEADERS_COUNT", 100, 20, 1000);
server.maxRequestsPerSocket = envInteger("MAX_REQUESTS_PER_SOCKET", 100, 1, 10000);

// Exit 0 on deploy teardown so npm doesn't report the SIGTERM as a crash.
for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    console.log(`${signal} received, shutting down`);
    server.closeIdleConnections?.();
    server.close(async () => {
      await Promise.allSettled([
        historyRepository.close(),
        redisClient?.quit(),
      ]);
      process.exit(0);
    });
    setTimeout(() => process.exit(0), 4000).unref();
  });
}

// Record a daily sample for every account the store has ever seen, whether or
// not any client asks for it today — history accumulates while phones are off.
const refreshTimer = setInterval(refreshKnownAccounts, historyRefreshMs);
refreshTimer.unref?.();
const bootRefreshTimer = setTimeout(refreshKnownAccounts, 15_000);
bootRefreshTimer.unref?.();
const pruneTimer = setInterval(pruneHistory, historyPruneMs);
pruneTimer.unref?.();

let refreshing = false;
async function refreshKnownAccounts() {
  if (refreshing) return;
  const releaseLock = await acquireJobLock("history-refresh", Math.max(historyRefreshMs, 10 * 60 * 1000));
  if (!releaseLock) return;
  refreshing = true;
  try {
    const today = startOfDay(Date.now());
    const accounts = await historyRepository.listAccounts();
    for (const key of accounts) {
      const samples = await historyRepository.getHistory(key);
      const latest = samples[samples.length - 1];
      if (latest && latest.ts >= today) continue;
      try {
        const profile = await fetchProfileLimited(key);
        await recordHistorySample(profile);
        await cache.set(key, { fetchedAt: Date.now(), data: profile }, cacheTtlMs * 2);
        console.log(`Daily refresh recorded ${key}`);
      } catch (error) {
        console.warn(`Daily refresh failed for ${key}:`, error);
      }
      await sleep(3000);
    }
    for (const key of accounts) {
      if (!(await historyRepository.getMeta(key)).waybackAt) await backfillFromWayback(key);
    }
  } finally {
    refreshing = false;
    await releaseLock();
  }
}

async function pruneHistory() {
  const releaseLock = await acquireJobLock("history-prune", historyPruneMs);
  if (!releaseLock) return;
  try {
    const result = await historyRepository.prune();
    if (result.deletedAccounts || result.deletedSamples) {
      console.log(`History retention pruned ${result.deletedAccounts} accounts and ${result.deletedSamples} samples`);
    }
  } catch (error) {
    console.warn("History retention failed:", error);
  } finally {
    await releaseLock();
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

  const cached = await cache.get(username.toLowerCase());
  if (cached && Date.now() - cached.fetchedAt < cacheTtlMs) {
    res.setHeader("X-Twidget-Cache", "hit");
    setPublicCache(res, 300);
    res.json(await withHistory(cached.data));
    return;
  }

  try {
    const profile = await fetchProfileLimited(username);
    // Ordinary profile lookups must not opt users into persistent pooled
    // history. Existing opt-in accounts still receive their daily update.
    await recordHistorySample(profile);
    await cache.set(username.toLowerCase(), { fetchedAt: Date.now(), data: profile }, cacheTtlMs * 2);
    res.setHeader("X-Twidget-Cache", "miss");
    setPublicCache(res, 300);
    res.json(await withHistory(profile));
  } catch (error) {
    console.error(`Failed to fetch ${username}:`, error);
    if (cached) {
      res.setHeader("Warning", '110 - "Response is stale"');
      res.setHeader("X-Twidget-Cache", "stale");
      setPublicCache(res, 60);
      res.json(await withHistory(cached.data));
      return;
    }
    sendUpstreamFailure(res, "profile_fetch_failed", error);
  }
}

async function fetchProfileLimited(username) {
  const key = cleanUsername(username).toLowerCase();
  const existing = profileInFlight.get(key);
  if (existing) return existing;
  const pending = withUpstreamSlot(() => fetchProfile(username))
    .finally(() => profileInFlight.delete(key));
  profileInFlight.set(key, pending);
  return pending;
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
    followersCountKnown: user.followers !== undefined && user.followers !== null,
    followingsCount: numberValue(user.following),
    followingsCountKnown: user.following !== undefined && user.following !== null,
    statusesCount: numberValue(user.tweets),
    statusesCountKnown: user.tweets !== undefined && user.tweets !== null,
    likeCount: numberValue(user.likes),
    likeCountKnown: user.likes !== undefined && user.likes !== null,
    profileImage: highResolutionProfileImageUrl(user.avatar_url),
    isVerified: user.verification && typeof user.verification === "object"
      ? Boolean(user.verification.verified)
      : booleanValue(user.verified),
    isPrivate: user.protected !== undefined ? booleanValue(user.protected) : undefined,
  };
}

async function fetchRettiwtProfile(username) {
  // Guest mode: profile details need no auth, and the account-suspension
  // risk lived entirely in follower enumeration, which is gone.
  const rettiwt = new Rettiwt({ timeout: 10000, maxRetries: 1 });
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
    signal: AbortSignal.timeout(10000),
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
  const followers = findDeep(user, ["followersCount", "followers_count", "followerCount", "follower_count"]);
  const following = findDeep(user, ["followingsCount", "followingCount", "friends_count", "following_count"]);
  const statuses = findDeep(user, ["statusesCount", "statusCount", "tweetCount", "tweetsCount", "statuses_count", "listed_count"]);
  const likes = findDeep(user, ["likeCount", "likesCount", "favouritesCount", "favoritesCount", "favourites_count", "favorites_count"]);
  return {
    fullName: stringValue(firstDefined(findDeep(user, ["fullName", "name", "displayName", "display_name"]), fallbackUsername)),
    userName: stringValue(firstDefined(findDeep(user, ["userName", "username", "screenName", "screen_name", "handle"]), fallbackUsername)).replace(/^@+/, ""),
    followersCount: numberValue(followers),
    followersCountKnown: followers !== undefined && followers !== null,
    followingsCount: numberValue(following),
    followingsCountKnown: following !== undefined && following !== null,
    statusesCount: numberValue(statuses),
    statusesCountKnown: statuses !== undefined && statuses !== null,
    likeCount: numberValue(likes),
    likeCountKnown: likes !== undefined && likes !== null,
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
    signal: AbortSignal.timeout(10000),
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
    signal: AbortSignal.timeout(10000),
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
    followersCountKnown: metrics.followers_count !== undefined && metrics.followers_count !== null,
    followingsCount: numberValue(metrics.following_count),
    followingsCountKnown: metrics.following_count !== undefined && metrics.following_count !== null,
    statusesCount: numberValue(metrics.tweet_count),
    statusesCountKnown: metrics.tweet_count !== undefined && metrics.tweet_count !== null,
    // X user public_metrics has no profile-wide likes count. Omit it rather
    // than returning a fabricated zero.
    likeCount: undefined,
    likeCountKnown: false,
    profileImage: highResolutionProfileImageUrl(user.profile_image_url),
    isVerified: user.verified !== undefined || user.verified_type !== undefined
      ? Boolean(booleanValue(user.verified) || verifiedTypeValue(user.verified_type))
      : undefined,
    isPrivate: user.protected !== undefined ? booleanValue(user.protected) : undefined,
  };
  return Object.fromEntries(Object.entries(normalized).filter(([, value]) => value !== undefined));
}

async function withHistory(profile) {
  const username = cleanUsername(profile.userName);
  return {
    ...profile,
    syncedAt: Date.now(),
    history: await historyFor(username),
  };
}

async function recordHistorySample(profile, { allowNew = false } = {}) {
  const username = cleanUsername(profile.userName);
  if (!username) return [];
  const key = username.toLowerCase();
  if (profile.isPrivate === true) {
    // The pool is explicitly for public accounts. If an existing account turns
    // private, stop serving and refreshing its previously collected history.
    await historyRepository.deleteAccount(key);
    return [];
  }
  if (!allowNew && !(await historyRepository.hasAccount(key))) return [];
  if (allowNew) {
    const registered = await historyRepository.registerAccount(
      key,
      [historySampleFor(profile, Date.now())],
      maxHistoryAccounts,
    );
    return registered ? historyRepository.getHistory(key) : null;
  }
  return storeSamples(key, [historySampleFor(profile, Date.now())], { touchAccess: false });
}

// Merge new samples into an account's history, one per calendar day. New
// samples win by default; preferExisting keeps recorded daily closes intact
// when merging coarser data such as Wayback anchors.
async function storeSamples(key, samples, { preferExisting = false, touchAccess = true } = {}) {
  return historyRepository.storeSamples(key, samples, { preferExisting, touchAccess });
}

async function historyFor(username, { touch = false } = {}) {
  const key = cleanUsername(username).toLowerCase();
  return historyRepository.getHistory(key, { touch });
}

function historySampleFor(profile, now) {
  const ts = startOfDay(now);
  return {
    dayLabel: dayLabel(ts),
    followers: numberValue(profile.followersCount),
    followersKnown: profileMetricKnown(profile, "followersCount"),
    following: numberValue(profile.followingsCount),
    followingKnown: profileMetricKnown(profile, "followingsCount"),
    posts: numberValue(profile.statusesCount),
    postsKnown: profileMetricKnown(profile, "statusesCount"),
    likes: numberValue(profile.likeCount),
    likesKnown: profileMetricKnown(profile, "likeCount"),
    ts,
    src: "live",
  };
}

function profileMetricKnown(profile, field) {
  const explicit = profile[`${field}Known`];
  if (typeof explicit === "boolean") return explicit;
  return Object.hasOwn(profile, field) && profile[field] !== undefined && profile[field] !== null;
}

function startOfDay(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 0;
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
}

function dayLabel(ts) {
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" }).format(new Date(ts));
}

// ---- Wayback Machine backfill -------------------------------------------
// archive.org snapshots of profile pages are real, dated follower counts.
// Fetched once per account (marker in historyStore.meta), fire-and-forget.

const waybackInFlight = new Set();

async function backfillFromWayback(username) {
  const key = cleanUsername(username).toLowerCase();
  if (!waybackEnabled || !key || waybackInFlight.has(key)) return;
  if (waybackInFlight.size >= maxWaybackConcurrent) return;
  const existingMeta = await historyRepository.getMeta(key);
  if (existingMeta.waybackAt) return;
  const releaseLock = await acquireJobLock(`wayback:${key}`, 10 * 60 * 1000);
  if (!releaseLock) return;
  waybackInFlight.add(key);
  try {
    const snapshots = await waybackSnapshots(key);
    const samples = [];
    for (const [timestamp, original] of snapshots) {
      const sample = await waybackSample(timestamp, original);
      if (sample) samples.push(sample);
      await sleep(500);
    }
    if (samples.length) await storeSamples(key, samples, { preferExisting: true, touchAccess: false });
    await historyRepository.setMeta(key, { ...existingMeta, waybackAt: Date.now(), waybackSamples: samples.length });
    console.log(`Wayback backfill for ${key}: ${samples.length} of ${snapshots.length} snapshots usable`);
  } catch (error) {
    console.warn(`Wayback backfill failed for ${key}:`, error);
    await historyRepository.setMeta(key, { ...existingMeta, waybackAt: Date.now(), waybackSamples: 0 });
  } finally {
    waybackInFlight.delete(key);
    await releaseLock();
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
    const followers = matchOptionalCount(html, [
      /"followers_count"\s*:\s*(\d+)/,
      /followers_count&quot;\s*:\s*(\d+)/,
      /ProfileNav-item--followers[\s\S]{0,600}?data-count="(\d+)"/,
      /data-nav="followers"[\s\S]{0,300}?title="([\d.,  ]+)\s+Follower/i,
      /title="([\d.,  ]+)\s+Followers?"/i,
    ]);
    if (followers === null) return null;
    const following = matchOptionalCount(html, [
      /"friends_count"\s*:\s*(\d+)/,
      /friends_count&quot;\s*:\s*(\d+)/,
    ]);
    const posts = matchOptionalCount(html, [
      /"statuses_count"\s*:\s*(\d+)/,
      /statuses_count&quot;\s*:\s*(\d+)/,
    ]);
    const likes = matchOptionalCount(html, [
      /"favourites_count"\s*:\s*(\d+)/,
      /favourites_count&quot;\s*:\s*(\d+)/,
    ]);
    return {
      dayLabel: dayLabel(ts),
      followers,
      followersKnown: true,
      following: following ?? 0,
      followingKnown: following !== null,
      posts: posts ?? 0,
      postsKnown: posts !== null,
      likes: likes ?? 0,
      likesKnown: likes !== null,
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
  const time = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(time) ? 0 : time;
}

function matchOptionalCount(html, patterns) {
  for (const pattern of patterns) {
    const match = pattern.exec(html);
    if (!match) continue;
    const value = Number(String(match[1]).replace(/\D/g, ""));
    if (Number.isFinite(value) && value >= 0) return value;
  }
  return null;
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
  const configured = String(process.env.X_CALLBACK_URL || "").trim();
  if (configured) return configured;
  throw new Error("X_CALLBACK_URL is required when X OAuth is enabled");
}

function validReturnUri(value) {
  const uri = String(value || "");
  const allowed = String(process.env.TWIDGET_ANDROID_RETURN_URI || "twidget://oauth/x");
  return uri === allowed ? uri : "";
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

function rateLimit(limiter, max) {
  return async (req, res, next) => {
    const key = req.ip || req.socket?.remoteAddress || "unknown";
    try {
      const result = await limiter.take(key);
      if (result.capacityReached) {
        res.setHeader("Retry-After", String(Math.ceil(result.retryMs / 1000)));
        res.status(503).json({ error: "rate_limiter_capacity_reached" });
        return;
      }
      const resetSeconds = Math.max(1, Math.ceil(result.retryMs / 1000));
      res.setHeader("RateLimit-Limit", String(max));
      res.setHeader("RateLimit-Remaining", String(Math.max(0, max - result.count)));
      res.setHeader("RateLimit-Reset", String(resetSeconds));
      if (result.count > max) {
        res.setHeader("Retry-After", String(resetSeconds));
        res.status(429).json({ error: "rate_limited" });
        return;
      }
      next();
    } catch (error) {
      // A configured shared limiter must fail closed. Falling back locally
      // during a Redis outage would multiply the request budget per replica.
      console.warn("Rate limiter unavailable:", error?.message || error);
      res.setHeader("Retry-After", "5");
      res.status(503).json({ error: "rate_limiter_unavailable" });
    }
  };
}

function requestToken(req) {
  const authorization = String(req.get("authorization") || "");
  const bearer = /^Bearer\s+(.+)$/i.exec(authorization)?.[1];
  return String(bearer || req.get("x-rettiwt-api-key") || "").trim();
}

function tokenMatches(received, expected) {
  const left = Buffer.from(String(received));
  const right = Buffer.from(String(expected));
  return left.length === right.length && left.length > 0 && crypto.timingSafeEqual(left, right);
}

async function takeHistoryRegistrationSlot() {
  const result = await historyRegistrationLimiter.take("all");
  return !result.capacityReached && result.count <= historyRegistrationsPerHour;
}

class ServiceBusyError extends Error {}

async function withUpstreamSlot(task) {
  if (upstreamActive >= maxConcurrentUpstream) {
    throw new ServiceBusyError("Upstream concurrency limit reached");
  }
  upstreamActive += 1;
  try {
    return await task();
  } finally {
    upstreamActive -= 1;
  }
}

function sendUpstreamFailure(res, code, error) {
  if (error instanceof ServiceBusyError) {
    res.setHeader("Retry-After", "5");
    res.status(503).json({ error: "service_busy" });
    return;
  }
  res.status(502).json({ error: code });
}

function setPublicCache(res, sharedSeconds) {
  if (bridgeApiToken) return;
  res.setHeader(
    "Cache-Control",
    `public, max-age=30, s-maxage=${sharedSeconds}, stale-while-revalidate=${sharedSeconds}`,
  );
}

async function connectRedis(url) {
  if (!url) return null;
  const client = createRedisClient({
    url,
    disableOfflineQueue: true,
    socket: {
      connectTimeout: envInteger("REDIS_CONNECT_TIMEOUT_MS", 3000, 250, 30000),
      reconnectStrategy: (retries) => Math.min(100 * 2 ** Math.min(retries, 5), 3000),
    },
  });
  client.on("error", (error) => console.warn("Redis connection error:", error?.message || error));
  await client.connect();
  console.log("Shared rate limits and response caches use Redis");
  return client;
}

async function createHistoryRepository() {
  let repository;
  if (historyBackend === "postgres") {
    const connectionString = String(process.env.HISTORY_DATABASE_URL || "").trim();
    if (!connectionString) throw new Error("HISTORY_DATABASE_URL is required when HISTORY_BACKEND=postgres");
    const pool = new Pool({
      connectionString,
      max: envInteger("HISTORY_DATABASE_POOL_MAX", 10, 1, 50),
      connectionTimeoutMillis: envInteger("HISTORY_DATABASE_CONNECT_TIMEOUT_MS", 5000, 250, 30000),
      idleTimeoutMillis: envInteger("HISTORY_DATABASE_IDLE_TIMEOUT_MS", 30000, 1000, 300000),
      statement_timeout: envInteger("HISTORY_DATABASE_STATEMENT_TIMEOUT_MS", 10000, 1000, 60000),
      application_name: "twidget-bridge",
    });
    pool.on("error", (error) => console.error("PostgreSQL pool error:", error));
    repository = new PostgresHistoryRepository({
      pool,
      maxHistoryDays,
      sampleRetentionDays: historySampleRetentionDays,
      inactiveAccountDays: historyInactiveAccountDays,
    });
  } else if (historyBackend === "json") {
    repository = new LocalHistoryRepository({
      filePath: historyStorePath,
      maxHistoryDays,
      sampleRetentionDays: historySampleRetentionDays,
      inactiveAccountDays: historyInactiveAccountDays,
    });
  } else {
    throw new Error(`Unsupported HISTORY_BACKEND: ${historyBackend}`);
  }
  await repository.initialize();
  const pruned = await repository.prune();
  if (pruned.deletedAccounts || pruned.deletedSamples) {
    console.log(`History retention pruned ${pruned.deletedAccounts} accounts and ${pruned.deletedSamples} samples at startup`);
  }
  console.log(`History backend: ${repository.backendName()}`);
  return repository;
}

async function acquireJobLock(name, ttlMs) {
  if (!redisClient) return async () => {};
  const key = `twidget:lock:${name}`;
  const token = base64Url(crypto.randomBytes(18));
  try {
    const acquired = await redisClient.set(key, token, { NX: true, PX: Math.max(1000, Math.trunc(ttlMs)) });
    if (acquired !== "OK") return null;
  } catch (error) {
    console.warn(`Unable to acquire ${name} lock:`, error?.message || error);
    return null;
  }
  return async () => {
    try {
      await redisClient.eval(
        "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",
        { keys: [key], arguments: [token] },
      );
    } catch (error) {
      console.warn(`Unable to release ${name} lock:`, error?.message || error);
    }
  };
}

function envInteger(name, fallback, min, max) {
  const raw = process.env[name];
  if (raw === undefined || raw === "") return fallback;
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) && parsed >= min && parsed <= max ? parsed : fallback;
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
