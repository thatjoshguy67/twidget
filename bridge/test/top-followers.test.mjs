import assert from "node:assert/strict";
import test from "node:test";
import { prepareTopFollowersCache } from "../src/top-followers.js";

test("normalizes a bounded completed Top Followers result", () => {
  const cached = prepareTopFollowersCache({
    scanned: 250,
    pages: 5,
    top: [{
      id: "42",
      username: "Example",
      name: "Example User",
      followers: 1234,
      verified: true,
      avatar: "https://example.com/avatar.jpg",
    }],
  }, 123456);

  assert.deepEqual(cached, {
    version: 1,
    cachedAt: 123456,
    scanned: 250,
    pages: 5,
    top: [{
      id: "42",
      username: "Example",
      name: "Example User",
      followers: 1234,
      verified: true,
      avatar: "https://example.com/avatar.jpg",
    }],
  });
});

test("rejects malformed, duplicate, or unbounded shared rankings", () => {
  const valid = { id: "1", username: "Example", name: "Example", followers: 1, avatar: "" };
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [{ ...valid, username: "not valid" }] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [{ ...valid, avatar: "http://example.com/a" }] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 2, pages: 1, top: [valid, valid] }), null);
});
