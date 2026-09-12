import assert from "node:assert/strict";
import test from "node:test";
import {
  fetchTwitterApisFollowersPage,
  parseTwitterApisFollowersPage,
  TopFollowersProviderError,
} from "../src/top-followers-provider.js";

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
