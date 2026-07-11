import assert from "node:assert/strict";
import test from "node:test";
import { bangerScore } from "../src/banger-score.js";

test("balanced reach and interaction beats one-dimensional posts", () => {
  const balanced = { views: 10_000, likes: 500, replies: 50, reposts: 50, quotes: 10 };
  const impressionsOnly = { views: 100_000, likes: 500, replies: 1, reposts: 0, quotes: 0 };
  const tinyHighRate = { views: 20, likes: 5, replies: 0, reposts: 0, quotes: 0 };
  const score = bangerScore(balanced, 1_000);
  assert.ok(score > bangerScore(impressionsOnly, 1_000));
  assert.ok(score > bangerScore(tinyHighRate, 1_000));
});
