import test from "node:test";
import assert from "node:assert/strict";
import { excludedTopFollowersUsernames, assertTopFollowersAllowed } from "../src/top-followers-policy.js";

test("operator exclusions normalize handles and fail closed on invalid configuration", () => {
  const policy = excludedTopFollowersUsernames("@ElonMusk, APPLE\nApple");
  assert.deepEqual([...policy], ["elonmusk", "apple"]);
  assert.throws(() => assertTopFollowersAllowed(policy, "ELONMUSK"), { message: "top_followers_account_excluded" });
  assert.doesNotThrow(() => assertTopFollowersAllowed(policy, "howtomen"));
  assert.throws(() => excludedTopFollowersUsernames("good,bad-handle"));
  assert.equal(excludedTopFollowersUsernames().size, 0);
});
