import assert from "node:assert/strict";
import test from "node:test";
import { bangerScore, compareBangers } from "../src/banger-score.js";

test("thatjoshguy's older hit beats the newer post despite its lower engagement rate", () => {
  const older = { views: 253559, likes: 3663, replies: 115, reposts: 123, quotes: 48 };
  const newer = { views: 171876, likes: 3422, replies: 70, reposts: 120, quotes: 16 };
  assert.equal(bangerScore(older), 3961.75);
  assert.equal(bangerScore(newer), 3687.5);
  assert.ok(compareBangers(older, newer) > 0);
});

test("missing views never excludes a well-liked historical post", () => {
  const historical = { likes: 1000, reposts: 50 };
  assert.equal(bangerScore(historical), 1100);
  assert.equal(bangerScore({ ...historical, views: 0 }), 1100);
  assert.ok(compareBangers(historical, { likes: 900, views: 1000000 }) > 0);
});

test("more impressions never lowers the audience-response score", () => {
  const post = { likes: 500, reposts: 50, replies: 20, quotes: 10 };
  assert.equal(bangerScore({ ...post, views: 1000 }), bangerScore({ ...post, views: 1000000 }));
});

test("reposts support likes; replies and quotes have a modest, bounded contribution", () => {
  assert.equal(bangerScore({ likes: 100, reposts: 2, replies: 4, quotes: 2 }), 106);
  const discussion = { likes: 100, replies: 100000, quotes: 100000, reposts: 100000 };
  assert.equal(bangerScore(discussion), 125);
  assert.ok(compareBangers({ likes: 126 }, discussion) > 0);
});

test("impressions break exact engagement ties only", () => {
  const post = { likes: 100, reposts: 2 };
  assert.ok(compareBangers({ ...post, views: 2000 }, { ...post, views: 1000 }) > 0);
  assert.equal(compareBangers(post, { ...post, views: 0 }), 0);
  assert.ok(compareBangers({ likes: 101 }, { likes: 100, views: 1000000 }) > 0);
});

test("invalid or absent counters cannot corrupt the score", () => {
  assert.equal(bangerScore(null), 0);
  assert.equal(bangerScore({ likes: -1, replies: Infinity, quotes: "bad" }), 0);
  assert.equal(bangerScore({ likes: "100", reposts: "2" }), 104);
  assert.equal(bangerScore({ likes: 100, replies: -100 }), 100);
});
