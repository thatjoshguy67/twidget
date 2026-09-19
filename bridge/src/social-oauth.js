import crypto from "node:crypto";
import express from "express";

const TTL = 10 * 60 * 1000;
const TICKET_TTL = 2 * 60 * 1000;
const PREFIX = "twidget:oauth:v1:";
const proof = /^[A-Za-z0-9_-]{43}$/;
const randomId = () => crypto.randomBytes(32).toString("base64url");
export const challengeFor = (verifier) => crypto.createHash("sha256").update(verifier).digest("base64url");

/** Bounded, expiring, single-use state shared by bridge replicas when Redis is configured. */
export class OAuthExchangeStore {
  constructor({ redis = null, key, now = Date.now, capacity = 1000 } = {}) {
    this.redis = redis; this.key = key; this.now = now; this.capacity = capacity; this.local = new Map();
  }
  seal(value, id) {
    const nonce = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", this.key, nonce);
    cipher.setAAD(Buffer.from(id));
    const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value)), cipher.final()]);
    return Buffer.concat([nonce, cipher.getAuthTag(), ciphertext]).toString("base64");
  }
  unseal(value, id) {
    const bytes = Buffer.from(value, "base64");
    const decipher = crypto.createDecipheriv("aes-256-gcm", this.key, bytes.subarray(0, 12));
    decipher.setAAD(Buffer.from(id)); decipher.setAuthTag(bytes.subarray(12, 28));
    return JSON.parse(Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]).toString());
  }
  async put(kind, value, ttl, challenge = "") {
    const id = randomId(); const name = PREFIX + kind + ":" + id;
    const payload = JSON.stringify({ challenge, sealed: this.seal(value, name) });
    const now = this.now();
    if (this.redis) {
      const accepted = await this.redis.eval(`
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
        redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
        redis.call('ZADD', KEYS[1], ARGV[5], KEYS[2])
        return 1`, { keys: [PREFIX + "capacity", name], arguments: [String(now), String(this.capacity), payload, String(ttl), String(now + ttl)] });
      if (!accepted) throw new Error("capacity");
    } else {
      for (const [k, v] of this.local) if (v.until <= now) this.local.delete(k);
      if (this.local.size >= this.capacity) throw new Error("capacity");
      this.local.set(name, { payload, until: now + ttl });
    }
    return id;
  }
  async take(kind, id, challenge = "") {
    if (!proof.test(id)) return null;
    const name = PREFIX + kind + ":" + id;
    let payload;
    if (this.redis) {
      payload = await this.redis.eval(`
        local value = redis.call('GET', KEYS[1])
        if not value then return nil end
        if cjson.decode(value).challenge ~= ARGV[1] then return nil end
        redis.call('DEL', KEYS[1]); redis.call('ZREM', KEYS[2], KEYS[1]); return value`,
      { keys: [name, PREFIX + "capacity"], arguments: [challenge] });
    } else {
      const entry = this.local.get(name);
      if (!entry) return null;
      if (entry.until <= this.now()) { this.local.delete(name); return null; }
      if (JSON.parse(entry.payload).challenge !== challenge) return null;
      this.local.delete(name); payload = entry.payload;
    }
    return payload ? this.unseal(JSON.parse(payload).sealed, name) : null;
  }
}

function configFor(provider, env) {
  const key = Buffer.from(env.SOCIAL_OAUTH_TICKET_KEY || "", "base64");
  const origin = env.SOCIAL_OAUTH_ORIGIN || "https://twidget-bridge-production.up.railway.app";
  let url; try { url = new URL(origin); } catch { return null; }
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash || key.length !== 32) return null;
  const prefix = provider === "github" ? "GITHUB" : provider === "instagram" ? "INSTAGRAM" : null;
  if (!prefix || !env[`${prefix}_OAUTH_CLIENT_ID`] || !env[`${prefix}_OAUTH_CLIENT_SECRET`]) return null;
  return { key, clientId: env[`${prefix}_OAUTH_CLIENT_ID`], clientSecret: env[`${prefix}_OAUTH_CLIENT_SECRET`],
    redirect: `${url.origin}/oauth/${provider}/callback` };
}

function githubTokens(data) {
  if (typeof data.access_token !== "string" || !data.access_token || data.error) throw new Error("exchange");
  if (data.expires_in == null) return { accessToken: data.access_token, expiresAt: null };
  if (!Number.isSafeInteger(data.expires_in) || data.expires_in <= 0 || data.expires_in > 86400 ||
      typeof data.refresh_token !== "string" || !data.refresh_token ||
      !Number.isSafeInteger(data.refresh_token_expires_in) || data.refresh_token_expires_in <= 0) throw new Error("exchange");
  return { accessToken: data.access_token, expiresAt: Date.now() + data.expires_in * 1000,
    refreshToken: data.refresh_token, refreshExpiresAt: Date.now() + data.refresh_token_expires_in * 1000 };
}

class TokenExchangeError extends Error {
  constructor(stage, reason, status = null, data = null) {
    super("exchange");
    // Only fixed labels and numeric codes are safe to log. Never retain provider text or tokens.
    this.diagnostic = { stage, reason, status,
      code: Number.isSafeInteger(data?.error?.code) ? data.error.code : null,
      subcode: Number.isSafeInteger(data?.error?.error_subcode) ? data.error.error_subcode : null };
  }
}

async function tokenResponse(request, url, options, stage) {
  let response;
  try { response = await request(url, options); }
  catch { throw new TokenExchangeError(stage, "network"); }
  let data;
  try { data = await response.json(); }
  catch { throw new TokenExchangeError(stage, "invalid_json", response.status); }
  if (!response.ok || data?.error) throw new TokenExchangeError(stage, "provider_rejected", response.status, data);
  if (typeof data?.access_token !== "string" || !data.access_token) throw new TokenExchangeError(stage, "missing_token", response.status);
  return data;
}

async function providerToken(provider, code, config, request) {
  const params = new URLSearchParams({ client_id: config.clientId, client_secret: config.clientSecret,
    redirect_uri: config.redirect, code });
  if (provider === "instagram") params.set("grant_type", "authorization_code");
  const data = await tokenResponse(request, provider === "github" ? "https://github.com/login/oauth/access_token" : "https://api.instagram.com/oauth/access_token", {
    method: "POST", redirect: "error", signal: AbortSignal.timeout(15000),
    headers: { Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded" }, body: params.toString(),
  }, "authorization_code");
  if (provider === "github") return githubTokens(data);
  // Instagram's short token lasts one hour; exchange it before returning to the device.
  const longUrl = new URL("https://graph.instagram.com/access_token");
  longUrl.search = new URLSearchParams({ grant_type: "ig_exchange_token", client_secret: config.clientSecret, access_token: data.access_token }).toString();
  const longToken = await tokenResponse(request, longUrl, { redirect: "error", signal: AbortSignal.timeout(15000) }, "long_lived_token");
  if (!Number.isSafeInteger(longToken.expires_in) || longToken.expires_in <= 0) throw new TokenExchangeError("long_lived_token", "invalid_expiry");
  return { accessToken: longToken.access_token, expiresAt: Date.now() + longToken.expires_in * 1000 };
}

/** No provider tokens or error text enter redirect URLs, application logs, or profile caches. */
export function createSocialOAuthRouter({ env = process.env, redis = null, request = fetch, store = null,
  diagnostic = entry => console.info(JSON.stringify({ event: "social_oauth_callback", ...entry })) } = {}) {
  const router = express.Router();
  const exchange = store || new OAuthExchangeStore({ redis, key: Buffer.from(env.SOCIAL_OAUTH_TICKET_KEY || "", "base64") });
  router.use((req, res, next) => /^(\/github|\/instagram)\//.test(req.path) ? next() : next("router"));
  router.use(express.json({ limit: "2kb", strict: true }));
  router.get("/:provider/status", (req, res) => res.json({ available: configFor(req.params.provider, env) !== null }));
  router.post("/:provider/start", async (req, res) => {
    const provider = req.params.provider; const config = configFor(provider, env);
    if (!config) return res.status(503).json({ error: "not_configured" });
    const challenge = req.body?.challenge;
    if (typeof challenge !== "string" || !proof.test(challenge)) return res.status(400).json({ error: "invalid_request" });
    try {
      const state = await exchange.put(`state:${provider}`, { challenge }, TTL);
      const authorize = new URL(provider === "github" ? "https://github.com/login/oauth/authorize" : "https://www.instagram.com/oauth/authorize");
      authorize.search = new URLSearchParams({ client_id: config.clientId, redirect_uri: config.redirect, state,
        ...(provider === "instagram" ? { response_type: "code", scope: "instagram_business_basic", enable_fb_login: "0", force_authentication: "1" } : {}) }).toString();
      return res.json({ authorizationUrl: authorize.toString(), state });
    } catch { return res.status(503).json({ error: "temporarily_unavailable" }); }
  });
  router.get("/:provider/callback", async (req, res) => {
    const provider = req.params.provider; const config = configFor(provider, env);
    if (!config) return res.status(503).send("This connection is not configured.");
    if (typeof req.query.state !== "string") return res.status(400).send("This connection has expired. Return to Twidget and try again.");
    try {
      const pending = await exchange.take(`state:${provider}`, req.query.state);
      if (!pending) return res.status(400).send("This connection has expired. Return to Twidget and try again.");
      const returnUrl = new URL(`twidget://oauth/${provider}`);
      returnUrl.searchParams.set("state", req.query.state);
      if (req.query.error || typeof req.query.code !== "string" || req.query.code.length > 2048) {
        returnUrl.searchParams.set("error", "cancelled");
      } else {
        try {
          const tokens = await providerToken(provider, req.query.code, config, request);
          const ticket = await exchange.put(`ticket:${provider}`, tokens, TICKET_TTL, pending.challenge);
          returnUrl.searchParams.set("ticket", ticket);
          diagnostic({ provider, result: "ticket_issued" });
        } catch (error) {
          diagnostic({ provider, result: "failed", ...(error instanceof TokenExchangeError ? error.diagnostic : { stage: "ticket", reason: "unavailable" }) });
          returnUrl.searchParams.set("error", "connection_failed");
        }
      }
      return res.redirect(302, returnUrl.toString());
    } catch { return res.status(503).send("Unable to connect. Return to Twidget and try again."); }
  });
  router.post("/github/refresh", async (req, res) => {
    const config = configFor("github", env);
    if (!config) return res.status(503).json({ error: "not_configured" });
    const token = req.body?.refreshToken;
    if (typeof token !== "string" || token.length < 20 || token.length > 1024 || !/^[A-Za-z0-9_]+$/.test(token)) return res.status(400).json({ error: "invalid_request" });
    try {
      const response = await request("https://github.com/login/oauth/access_token", {
        method: "POST", redirect: "error", signal: AbortSignal.timeout(15000),
        headers: { Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ client_id: config.clientId, client_secret: config.clientSecret, grant_type: "refresh_token", refresh_token: token }).toString(),
      });
      if (!response.ok) return res.status(401).json({ error: "reauthorization_required" });
      return res.json(githubTokens(await response.json()));
    } catch { return res.status(401).json({ error: "reauthorization_required" }); }
  });
  router.post("/:provider/redeem", async (req, res) => {
    const provider = req.params.provider;
    if (!configFor(provider, env)) return res.status(503).json({ error: "not_configured" });
    const { ticket, verifier } = req.body || {};
    if (typeof ticket !== "string" || !proof.test(ticket) || typeof verifier !== "string" || !proof.test(verifier)) return res.status(400).json({ error: "invalid_request" });
    try {
      const tokens = await exchange.take(`ticket:${provider}`, ticket, challengeFor(verifier));
      return tokens ? res.json(tokens) : res.status(400).json({ error: "expired_connection" });
    } catch { return res.status(503).json({ error: "temporarily_unavailable" }); }
  });
  return router;
}
