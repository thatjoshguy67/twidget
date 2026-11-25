import assert from "node:assert/strict";
import test from "node:test";
import {
  fetchTwitterApisFollowersPage,
  parseTwitterApisFollowersPage,
  TopFollowersProviderError,
  fetchFxFollowersPage,
  parseFxFollowersPage,
} from "../src/top-followers-provider.js";

const fxUser = { id: "1", screen_name: "fan", name: "Fan", followers: 42,
  avatar_url: "https://pbs.twimg.com/a_normal.jpg", verification: { verified: true } };

test("FxTwitter uses account IDs and its bottom cursor without account credentials", async () => {
  let request;
  const page = await fetchFxFollowersPage({ userId: "123", cursor: "next|value", fetchImpl: async (url, options) => {
    request = { url: new URL(url), options };
    return { ok: true, json: async () => ({ code: 200, results: [fxUser], cursor: { top: "previous", bottom: "next2" } }) };
  } });
  assert.equal(request.url.pathname, "/2/profile/id:123/followers");
  assert.equal(request.url.searchParams.get("cursor"), "next|value");
  assert.equal(request.options.headers.Authorization, undefined);
  assert.equal(page.nextCursor, "next2");
  assert.equal(page.users[0].username, "fan");
  assert.equal(page.users[0].verified, true);
  assert.equal(page.users[0].avatar, "https://pbs.twimg.com/a_400x400.jpg");
  assert.equal(page.users[0].mutual, null);
});

test("unavailable, malformed and incomplete Fx lists cannot masquerade as terminal pages", async () => {
  await assert.rejects(fetchFxFollowersPage({ userId: "123", fetchImpl: async () => ({ ok: false, status: 404 }) }),
    error => error instanceof TopFollowersProviderError && error.status === 404);
  await assert.rejects(fetchFxFollowersPage({ userId: "handle" }), /identity_required/);
  for (const body of [
    { code: 404, results: [], cursor: { bottom: null } },
    { code: 200, results: [] },
    { code: 200, results: [{ ...fxUser, id: "" }], cursor: { bottom: null } },
  ]) assert.throws(() => parseFxFollowersPage(body));
  assert.deepEqual(parseFxFollowersPage({ code: 200, results: [], cursor: { bottom: null } }), { users: [], nextCursor: "" });
  assert.deepEqual(parseFxFollowersPage({ code: 200, results: [], cursor: { bottom: "terminal" } }), { users: [], nextCursor: "terminal" });
});

test("TwitterAPIs follower pages are normalized and deduplicated", () => {
  const page = parseTwitterApisFollowersPage({
    users: [
      {
        id: "1",
        username: "one",
        name: "One",
        followers_count: 42,
        is_blue_verified: true,
        profile_image_url_https: "https://pbs.twimg.com/a_normal.jpg",
        following: true,
      },
      { id: "1", username: "duplicate", name: "Duplicate", followers_count: 2 },
      { id: "bad", username: "not valid", followers_count: 1 },
    ],
    next_cursor: "next-page",
  });
  assert.equal(page.users.length, 1);
  assert.equal(page.users[0].avatar, "https://pbs.twimg.com/a_400x400.jpg");
  assert.equal(page.users[0].mutual, true);
  assert.equal(page.nextCursor, "next-page");
});

test("TwitterAPIs requests keep the provider key server-side and surface status", async () => {
  let request;
  const page = await fetchTwitterApisFollowersPage({
    username: "example",
    cursor: "cursor value",
    apiKey: "secret-key",
    fetchImpl: async (url, options) => {
      request = { url: String(url), options };
      return { ok: true, async json() { return { users: [], next_cursor: "" }; } };
    },
  });
  assert.deepEqual(page, { users: [], nextCursor: "" });
  assert.match(request.url, /username=example/);
  assert.match(request.url, /count=100/);
  assert.match(request.url, /cursor=cursor\+value/);
  assert.equal(request.options.headers.Authorization, "Bearer secret-key");

  await assert.rejects(
    fetchTwitterApisFollowersPage({
      username: "example",
      apiKey: "secret-key",
      fetchImpl: async () => ({ ok: false, status: 402 }),
    }),
    (error) => error instanceof TopFollowersProviderError && error.status === 402,
  );
});


test("provider deduplicates case-insensitive handles independently of IDs", () => {
  const result = parseTwitterApisFollowersPage({ users: [
    { id: "1", username: "first", followers_count: 10 },
    { id: "2", username: "FIRST", followers_count: 20 },
    { id: "1", username: "alias", followers_count: 10 },
    { id: "3", username: "third", followers_count: 30 },
  ], next_cursor: "next" });
  assert.deepEqual(result.users.map(user => user.id), ["1", "3"]);
  assert.equal(result.nextCursor, "next");
});
