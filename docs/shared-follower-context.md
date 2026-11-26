# Shared follower-decline context

The bridge exposes `GET /context/follower-events` and attaches the same object
as `followerContext` to profile and pooled-history responses. Existing auth and
rate limits apply. The endpoint returns aggregate evidence only, never account
handles, IDs or follower lists. It does not enrol accounts or fetch upstream data.

Version 1 includes `schemaVersion`, `assessedAt`, `expiresAt` (Unix milliseconds),
`status`, `eligibleAccounts`, `limitations`, and nullable `event`.
Statuses are `detected`, `no_event`, `insufficient_data`, and `unavailable`.
`no_event` means the detector threshold was not reached, not that a platform
cleanup was disproved. Storage failures return `unavailable` without an event.
Assessments are cached for five minutes and expire after six hours.

Events include stable date-derived ID, `widespread_follower_decline` type,
`startAt`/`endAt` observation bounds, eligible/affected counts and fraction,
historical anomaly fraction and audience-size group count. Confidence is always
`tentative`; cause is always `unknown`. This detector cannot confirm bots, a
purge, genuine unfollows, or a provider fault. The tracked population is not a
representative sample of X. Time bounds describe daily observation intervals,
not the precise time of a removal.

## Initial conservative detection policy

- Only ID-bound accounts and known, non-estimated, live daily follower samples.
- Consecutive UTC days; account deduplication by permanent ID. Missing observations
  are not zero-filled or interpolated.
- At least ten baseline daily changes, taken from the preceding 28 days.
- An account's decline must exceed five followers, a 0.2% floor, and a robust
  deviation threshold based on its own median change and median absolute deviation.
- At least 30 eligible accounts, 15 anomalous accounts and 35% anomalous share;
  the share must also exceed three times its historical anomaly fraction.
- At least two size groups must independently show broad losses. Groups are
  <10,000, 10,000–99,999, and >=100,000 followers. Each account has equal weight.
- Only today's and yesterday's observation windows are assessed. This first
  version does not persist a historical event catalogue.

These are initial guardrails, not calibrated probabilities or proven purge
classification. Source validation is not independent: a common provider artifact
remains possible. Do not automatically upgrade confidence or attribute causes.

## Android interpretation

The app persists the assessment with cached profile data. It applies a caveat
only when fresh shared evidence overlaps a locally observed, non-estimated
follower loss. Unknown fields and expired assessments never activate a warning.
Momentum becomes neutral, the follower graph gets a context heading, and Brief's
follower/momentum descriptions retain the numbers with an uncertainty caveat.
Raw counts, history, post performance and goals are never adjusted.
Direct-only clients without pooled-history sync do not acquire bridge context.

Generated follower/goal claims of moving closer to a goal are rejected when the
current day or week is negative, or the source includes the shared-event caveat.
Safe generated loss descriptions remain available; rejected copy keeps the
deterministic measured description. The shared-event caveat survives rewrites.
Summary goal-progress claims require neither the day nor week to be negative. Existing generated narratives are invalidated with an engine-version
bump. A negative change must not become progress toward a higher follower target.
