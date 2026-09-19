import assert from "node:assert/strict";
import crypto from "node:crypto";
import express from "express";
import test from "node:test";
import { OAuthExchangeStore, challengeFor, createSocialOAuthRouter } from "../src/social-oauth.js";

const key = crypto.randomBytes(32);
const verifier = crypto.randomBytes(32).toString("base64url");
const config = { SOCIAL_OAUTH_TICKET_KEY: key.toString("base64"), GITHUB_OAUTH_CLIENT_ID: "test-id", GITHUB_OAUTH_CLIENT_SECRET: "test-secret" };
async function server(t, options = {}) {
  const app = express(); app.use("/oauth", createSocialOAuthRouter(options));
  const s = app.listen(0, "127.0.0.1"); await new Promise(r => s.once("listening", r));
  t.after(() => new Promise(r => s.close(r)));
  return `http://127.0.0.1:${s.address().port}/oauth`;
}
const post = (url, body) => fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

test("tickets are encrypted, proof-bound and consumed exactly once", async () => {
  const store = new OAuthExchangeStore({ key });
  const ticket = await store.put("ticket:github", { accessToken: "never-plaintext" }, 1000, challengeFor(verifier));
  assert.ok(!JSON.stringify([...store.local]).includes("never-plaintext"));
  assert.equal(await store.take("ticket:github", ticket, "wrong"), null);
  assert.equal(await store.take("ticket:instagram", ticket, challengeFor(verifier)), null);
  const results = await Promise.all([store.take("ticket:github", ticket, challengeFor(verifier)), store.take("ticket:github", ticket, challengeFor(verifier))]);
  assert.equal(results.filter(Boolean).length, 1);
  assert.equal(results.find(Boolean).accessToken, "never-plaintext");
});
test("expired state and capacity are bounded", async () => {
  let now = 1; const store = new OAuthExchangeStore({ key, capacity: 1, now: () => now });
  const id = await store.put("state:github", {}, 10);
  await assert.rejects(() => store.put("state:github", {}, 10));
  now = 11; assert.equal(await store.take("state:github", id), null);
  assert.ok(await store.put("state:github", {}, 10));
});
test("encrypted records cannot be swapped between identities", async () => {
  const store = new OAuthExchangeStore({ key }); const payload = store.seal({ secret: "token" }, "one");
  assert.throws(() => store.unseal(payload, "two"));
});
test("unconfigured apps and insecure callback origins fail closed", async t => {
  for (const env of [{}, { ...config, SOCIAL_OAUTH_ORIGIN: "http://example.com" }, { ...config, SOCIAL_OAUTH_ORIGIN: "https://example.com/evil" }]) {
    const base = await server(t, { env });
    assert.deepEqual(await (await fetch(`${base}/github/status`)).json(), { available: false });
    assert.equal((await post(`${base}/github/start`, { challenge: challengeFor(verifier) })).status, 503);
  }
});
test("GitHub browser callback and device redemption are replay protected", async t => {
  let calls = 0;
  const base = await server(t, { env: config, request: async (url, options) => {
    calls++; assert.equal(url, "https://github.com/login/oauth/access_token");
    assert.equal(new URLSearchParams(options.body).get("client_secret"), "test-secret");
    return Response.json({ access_token: "provider-token" });
  } });
  assert.equal((await post(`${base}/github/start`, { challenge: "bad" })).status, 400);
  const start = await (await post(`${base}/github/start`, { challenge: challengeFor(verifier), return_uri: "https://evil.test" })).json();
  const auth = new URL(start.authorizationUrl);
  assert.equal(auth.origin, "https://github.com"); assert.equal(auth.searchParams.get("scope"), null);
  assert.equal(auth.searchParams.get("redirect_uri"), "https://twidget-bridge-production.up.railway.app/oauth/github/callback");
  const callback = `${base}/github/callback?code=test-code&state=${start.state}`;
  const result = await fetch(callback, { redirect: "manual" });
  const location = new URL(result.headers.get("location"));
  assert.equal(location.protocol, "twidget:"); assert.equal(location.pathname, "/github");
  assert.ok(!location.toString().includes("provider-token"));
  assert.equal((await fetch(callback, { redirect: "manual" })).status, 400); assert.equal(calls, 1);
  const ticket = location.searchParams.get("ticket");
  assert.equal((await post(`${base}/github/redeem`, { ticket, verifier: crypto.randomBytes(32).toString("base64url") })).status, 400);
  assert.equal((await (await post(`${base}/github/redeem`, { ticket, verifier })).json()).accessToken, "provider-token");
  assert.equal((await post(`${base}/github/redeem`, { ticket, verifier })).status, 400);
});
test("provider cancellation consumes state without contacting provider", async t => {
  const base = await server(t, { env: config, request: () => { throw Error("must not call"); } });
  const { state } = await (await post(`${base}/github/start`, { challenge: challengeFor(verifier) })).json();
  const r = await fetch(`${base}/github/callback?error=access_denied&state=${state}`, { redirect: "manual" });
  const url = new URL(r.headers.get("location")); assert.equal(url.searchParams.get("error"), "cancelled");
  assert.equal(url.searchParams.get("ticket"), null);
});
test("provider errors never expose credentials or upstream messages", async t => {
  const base = await server(t, { env: config, request: () => { throw Error("sensitive-provider-body"); } });
  const { state } = await (await post(`${base}/github/start`, { challenge: challengeFor(verifier) })).json();
  const r = await fetch(`${base}/github/callback?code=code&state=${state}`, { redirect: "manual" });
  assert.ok(!r.headers.get("location").includes("sensitive"));
  assert.equal(new URL(r.headers.get("location")).searchParams.get("error"), "connection_failed");
});
test("Instagram exchanges the short token before issuing a ticket", async t => {
  const calls = [];
  const base = await server(t, { env: { ...config, INSTAGRAM_OAUTH_CLIENT_ID: "ig", INSTAGRAM_OAUTH_CLIENT_SECRET: "ig-secret" },
    request: async (url) => { calls.push(String(url)); return Response.json(calls.length === 1 ? { access_token: "short" } : { access_token: "long", expires_in: 5184000 }); } });
  const start = await (await post(`${base}/instagram/start`, { challenge: challengeFor(verifier) })).json();
  assert.equal(new URL(start.authorizationUrl).searchParams.get("scope"), "instagram_business_basic");
  const r = await fetch(`${base}/instagram/callback?code=code&state=${start.state}`, { redirect: "manual" });
  const ticket = new URL(r.headers.get("location")).searchParams.get("ticket");
  const redeemed = await (await post(`${base}/instagram/redeem`, { ticket, verifier })).json();
  assert.equal(redeemed.accessToken, "long"); assert.ok(redeemed.expiresAt > Date.now());
  assert.equal(calls.length, 2);
});
test("social router leaves legacy X OAuth routes alone", async t => {
  const base = await server(t, { env: config }); assert.equal((await fetch(`${base}/x/start`)).status, 404);
});

test("GitHub expiring grants preserve both expiries and rotate through the bridge", async t => {
  let calls = 0;
  const base = await server(t, { env: config, request: async (_, options) => {
    calls++;
    if (calls === 2) assert.equal(new URLSearchParams(options.body).get("grant_type"), "refresh_token");
    return Response.json({ access_token: `access_${calls}`, expires_in: 28800,
      refresh_token: `ghr_${"r".repeat(40)}_${calls}`, refresh_token_expires_in: 15897600 });
  } });
  const { state } = await (await post(`${base}/github/start`, { challenge: challengeFor(verifier) })).json();
  const callback = await fetch(`${base}/github/callback?code=code&state=${state}`, { redirect: "manual" });
  const ticket = new URL(callback.headers.get("location")).searchParams.get("ticket");
  const first = await (await post(`${base}/github/redeem`, { ticket, verifier })).json();
  assert.ok(first.expiresAt > Date.now()); assert.ok(first.refreshExpiresAt > first.expiresAt);
  const next = await (await post(`${base}/github/refresh`, { refreshToken: first.refreshToken })).json();
  assert.notEqual(first.refreshToken, next.refreshToken); assert.equal(next.accessToken, "access_2");
});

test("refresh rejects missing proof of token possession and failed grants", async t => {
  let called = 0;
  const base = await server(t, { env: config, request: async () => { called++; return Response.json({ error: "bad_refresh_token", error_description: "sensitive" }); } });
  assert.equal((await post(`${base}/github/refresh`, { refreshToken: "" })).status, 400);
  assert.equal(called, 0);
  const response = await post(`${base}/github/refresh`, { refreshToken: "ghr_" + "r".repeat(40) });
  assert.equal(response.status, 401); assert.deepEqual(await response.json(), { error: "reauthorization_required" });
});
