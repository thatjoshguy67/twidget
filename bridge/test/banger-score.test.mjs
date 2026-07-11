import assert from "node:assert/strict";
import test from "node:test";
import { bangerScore } from "../src/banger-score.js";

test("a viral hit outranks a small post with a hotter engagement rate", () => {
  // Regression: an 11K-view meme with a 5% like rate used to beat
  // genuinely viral posts because rate terms dominated the score.
  const nicheMeme = { views: 11_000, likes: 574, replies: 20, reposts: 30, quotes: 5 };
  const viralHit = { views: 2_000_000, likes: 30_000, replies: 800, reposts: 5_000, quotes: 500 };
  assert.ok(bangerScore(viralHit) > bangerScore(nicheMeme));
});

test("substance still beats hollow reach and tiny high-rate posts", () => {
  const balanced = { views: 10_000, likes: 500, replies: 50, reposts: 50, quotes: 10 };
  const impressionsOnly = { views: 100_000, likes: 500, replies: 1, reposts: 0, quotes: 0 };
  const tinyHighRate = { views: 20, likes: 5, replies: 0, reposts: 0, quotes: 0 };
  const score = bangerScore(balanced);
  assert.ok(score > bangerScore(impressionsOnly));
  assert.ok(score > bangerScore(tinyHighRate));
});

test("posts without views or engagement score zero", () => {
  assert.equal(bangerScore(null), 0);
  assert.equal(bangerScore({ views: 0, likes: 100 }), 0);
  assert.equal(bangerScore({ views: 5_000, likes: 0, replies: 0, reposts: 0, quotes: 0 }), 0);
});
