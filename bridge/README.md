# Twidget Bridge

The bridge is an optional Node service for public X/Twitter profile lookups,
weekly post analytics, and opt-in pooled daily history. FxTwitter is the primary
upstream; Rettiwt guest lookup is the profile fallback.

Twidget's FxTwitter mode does not require this service. Both profile data and
weekly analytics can be fetched directly by the Android app.

Profile, weekly analytics, archive backfill and Banger requests use numeric
account IDs whenever available from the account record, cached profile, or
the Android request's `userId` query parameter. Profile requests with an explicit
ID cannot reuse another account's cached response. New accounts use the
current `/2/profile/{handle}` discovery route; the old `/{handle}` profile API
is no longer used. `FXTWITTER_BASE_URL` overrides the public API origin for
self-hosted deployments and provider integration tests.

## Local development

```bash
npm ci
npm run dev
```

```bash
curl http://localhost:8787/health
curl http://localhost:8787/user/thatjoshguy
npm test
```

## Routes

- `GET /health` — small unauthenticated readiness response; returns `503` if the configured history backend is unavailable. Includes `publicMode: true` when `BRIDGE_API_TOKEN` is unset.
- `GET /user/:username` — normalized public profile data.
- `GET /analytics/:username` — recent original-post analytics, cached for 45 minutes.
- `GET /banger/:username` — opt-in, resumable Hall of Fame scan for the account's best balanced post.
- `GET /history/:username` — opt-in pooled daily history registration/read.
- `POST /installation/primary-profile` — opt-in installation default-profile report; `X-Twidget-Install-Key` is a random 64-character lowercase hex capability. `{ "username": "handle" }` replaces the report; `{ "username": null }` removes it. This does not enroll an account or start a scan.
- `GET /admin/audience` — operator-authenticated counts of reported primary profiles and installs active in the last 7/30 days. Accounts without a report remain unclassified.
- `GET /history/:username/top-followers` — reads a completed shared ranking.
- `GET /history/:username/top-followers/all` — paginates the latest completed server-owned follower list.
- `GET /history/:username/top-followers/scan` — reads durable server-scan progress.
- `POST /history/:username/top-followers/scan` — starts or resumes a bounded server-owned scan for an already opted-in account, or reuses a fresh snapshot. Current clients use this only for the first user-requested ranking; subsequent refreshes are bridge-scheduled.
- `POST /history/:username/top-followers` — legacy trusted-publisher compatibility route; requires `TOP_FOLLOWERS_PUBLISH_TOKEN` on a public bridge or `BRIDGE_API_TOKEN` on a private bridge.
- `POST /history/:username/analytics-import` — reconstructs X Analytics movements and admits gap days only when live and stored follower anchors match within a tight margin.
- `DELETE /admin/history/:username` — operator-only permanent deletion; hidden unless `HISTORY_ADMIN_TOKEN` is configured.
- `GET /official/user/:username` — disabled publicly by default, even with an X bearer configured.
- `/oauth/x/*` — disabled by default.

## Production security model

The service validates X usernames, rate-limits before body parsing, applies a
smaller budget to expensive routes, caps tracked IPs and history accounts,
coalesces duplicate upstream requests, limits concurrent upstream work, and
sets short HTTP header/request/keep-alive limits. Ordinary profile lookups no
longer create persistent history records. Private accounts are rejected from
the pool and removed if a previously public pooled account becomes private.

Wayback archive fetching and client history uploads are off by default. Both
can amplify work or weaken data trust and should stay off on a public instance
unless there is a concrete operational reason to enable them.

`BRIDGE_API_TOKEN` protects all data routes for private/self-hosted instances.
Self-hosted deployments should always set this token. The maintainer-operated
shared bridge intentionally remains token-free; check `/health` for
`publicMode: true` when evaluating exposure.
Android sends the configured bridge token as both a Bearer token and the legacy
`X-Rettiwt-Api-Key` header. Do not ship a private token inside a publicly
distributed APK.

Top Followers defaults to public FxTwitter (`TOP_FOLLOWERS_PROVIDER=fxtwitter`).
It uses `/2/profile/id:<account-id>/followers`, since handle-based resolution
can fail even while numeric-ID lists work. No X login cookies or provider key
are needed. A self-hoster can explicitly select `twitterapis` to keep the old
server-side provider; there is no automatic paid fallback. FxTwitter request
limits use a separate ledger, so old paid usage or credit pauses cannot block
free scans. Existing concurrency, daily/monthly request ceilings and activity
limits still bound public-source traffic. The page-size benchmark applies only
to the paid provider.

Active enrolled FxTwitter accounts now attempt a full reconciliation once per
UTC calendar day, using the same priority queue, concurrency limits and shared
request ceilings. This detects losses as well as gains; a newest-follower head
check cannot detect departures. Set `FX_FOLLOWERS_DAILY_RECONCILIATION=0` to use
the older head-check/weekly cadence. Android plots only short consecutive-day
observations, marks them with `~` and an interval in the tooltip, and keeps
CSV day counts authoritative when both are available. Longer intervals remain
summary observations rather than invented daily bars.

Only complete, ID-bound scans from the same provider produce observed losses.
Switching providers starts a new full baseline and discards old in-progress
cursors, preserving the last completed ranking if a request fails. Snapshot
losses include deletions/suspensions and cover the interval between scans;
they are not daily unfollow events or a historical backfill. `followerChanges`
in the history response carries these intervals. Android shows a dated lost-
followers total on the expanded followers card when a recent observation exists;
CSV unfollows remain a separate daily series.

When a shared-history
user first asks to find their top follower, that request enrolls the account in
server-owned full scans once per `TOP_FOLLOWERS_FULL_SCAN_DAYS` (default 7,
allowed 1–90 days), including while phones are offline, as long as a client has used Top
Followers within `TOP_FOLLOWERS_ACTIVE_DAYS` (default 7, allowed 1–365 days).
Preview, archive, scan-status, and scan-start requests record activity, with
metadata writes throttled to once per hour per account in each bridge process.
Dormant accounts keep their stale ranking but stop starting scans; scans
already running still resume. A returning preview reader receives that stale
ranking immediately and triggers a refresh when due through the same capacity, global
daily, and per-IP daily limits as an explicit scan request. Legacy accounts use
their enrollment time as activity; those without it receive a persisted grace
period starting on the first scheduler run after deployment.
Accounts that only participate in pooled metric history are not scanned until
that explicit first request.

Daily recent-follower checks run every `TOP_FOLLOWERS_REFRESH_HOURS` (24 by
default). They use the latest free daily profile/history sample to measure
positive growth since the last completed scan. The target is
`max(HEAD_ROWS, ceil(growth * 1.5) + HEAD_ROWS)`, with `HEAD_ROWS=100` by default.
A +200 change therefore scans at least 400 actual returned rows. Requested
`count=100` is a page-size hint, never a limit on the number of followers read.
The scan must also encounter `TOP_FOLLOWERS_OVERLAP_ROWS` consecutive followers
from the previous snapshot (default 100); it continues paging if that overlap
has not yet been reached, even with zero or negative net growth.

Daily scans preserve the entire existing ranked archive and add discovered
followers atomically. Cached followers' profile counts and unfollows are
reconciled by the weekly full scan (`TOP_FOLLOWERS_FULL_SCAN_DAYS=7`). Daily
merges never reset the full-scan clock. Weekly crawls yield every ten pages,
and queued daily checks take priority, to avoid one large account consuming
the shared allowance before daily updates can run.

Recent-follower checks assume newest-first provider ordering. Net growth can
hide follow/unfollow churn, and provider ordering is not guaranteed by this
adapter. Daily results are best-effort between full reconciliations. Set
`TOP_FOLLOWERS_HEAD_CHECK=0` to opt out. Unknown/stale profile counts use the
minimum target and require overlap instead of inventing a growth delta.

`completedAt` and `cachedAt` identify the latest published snapshot, including
partial merges. `fullCompletedAt` and `scanKind` distinguish weekly freshness.
Old snapshots migrate their stored list size as the count baseline. `/health`
exposes `headCheck`, `headRows`, `overlapRows`, `fullScanDays`, and the request
ceilings. The old `TOP_FOLLOWERS_HEAD_PAGES` setting is retired.

Set `TOP_FOLLOWERS_BENCHMARK_USERNAME` to a public account with at least a few
hundred followers to compare three default-size pages with three `count=100`
pages. The benchmark runs server-side with the same budget reservation and
provider circuit as scans; it never exports credentials. Samples and results
persist in the shared budget ledger, so restarts do not repeat successful
samples. If the daily/monthly budget or provider credits are exhausted, it
waits until the provider cooldown or budget window resets. `/health` and runtime logs report actual rows per
page and the observed request reduction; a requested page size alone is not
evidence of savings. Completed results are reused across deployments. After a credit top-up, an
operator can change `TOP_FOLLOWERS_BENCHMARK_RETRY_ID` to a fresh nonce to retry
immediately. Each nonce is applied only once; it clears only the credit-error
cooldown and benchmark wait, never the daily or monthly request counters.

Production benchmark on 17 October 2026: the default and `count=100` each
returned 69, 50, and 50 usable followers across three pages (169 each).
There was no observed request reduction in this sample. The six successful
sample calls were reserved against the same daily/monthly budget as scans.

Activity updates patch only the last-seen timestamp, preserve concurrent metadata,
and cannot recreate a deleted or renamed account. The timestamp never moves backward.

Postgres page writes adjust the scan count by actual inserted and removed rows,
without counting updates as new followers, in the same transaction as the cursor.
Head/full state uses the existing `scan_state` JSONB column; no parallel scan schema is added. The single-instance JSON backend keeps its existing coalesced atomic writes. Expired provider cursors
can fail the attempt, which will restart after the retry cooldown. Head scans
stage a copy of the archive and publish atomically, so they still incur local
archive-copy/storage costs even though provider calls are bounded.

Ordinary app installs cannot submit follower rows. Client scan starts have
global and per-IP daily-start limits; scheduled work remains bounded by the
enrolled-account cap, activity window, refresh cadence, upstream concurrency, page cost, and
retention. The legacy ranking write remains fail-closed behind
`TOP_FOLLOWERS_PUBLISH_TOKEN` for compatibility and must never be exposed to an
app install.

Application middleware cannot absorb a volumetric or distributed DDoS attack.
Put a public production domain behind an edge WAF/DDoS service and monitor
Railway HTTP metrics. Railway's emergency Under Attack Mode can also be used,
but it may block native API clients and should not be the normal operating mode.

The default JSON history store is intentionally single-instance. PostgreSQL and
Redis adapters are included for multi-replica deployments; do not increase the
replica count until both are configured. PostgreSQL is the history authority,
while Redis shares fixed-window request budgets, history-registration limits,
profile/analytics caches, and scheduled-job locks across replicas.

### Multi-replica storage

Set these variables before adding replicas:

```bash
HISTORY_BACKEND=postgres
HISTORY_DATABASE_URL=postgresql://user:password@host/database?sslmode=require
REDIS_URL=redis://user:password@host:6379
```

The bridge creates its `twidget_history_accounts`, `twidget_history_samples`,
`twidget_top_follower_scans`, and `twidget_top_followers` tables and indexes at startup. An explicitly selected
PostgreSQL backend fails startup instead of falling back to a local JSON file;
this prevents a database outage from silently creating divergent history.
Likewise, a configured but unavailable Redis instance fails startup, and live
rate-limit failures return `503` rather than multiplying the budget per replica.

Every history sample carries `followersKnown`, `followingKnown`, `postsKnown`,
and `likesKnown`. Consumers must check the relevant flag before treating a
numeric value as observed. This matters for older Wayback captures, where an
absent field was historically serialized as zero. Startup schema migration
marks legacy Wayback zero fields unknown, keeps positive archive values known,
and preserves genuine observed zero values from live/client samples. Sample
`src` records `live`, `client`, or `wayback` provenance where available.

For participating clients, the bridge owns the provider scan and stages each
validated page in durable storage. Readers only see the latest scan after an
atomic completion transition; an interrupted scan can resume from its stored
cursor without replacing the previous completed snapshot. The original
top-five response remains available for older clients, while current clients
page through the full ranked list. These routes do not create history-pool
accounts on their own.

For the first deployment onto an existing PostgreSQL store, keep the bridge at
one replica and take a database backup. Startup takes a PostgreSQL advisory
transaction lock, adds the four nullable columns, backfills them, and only then
makes them `NOT NULL`; subsequent startups are no-ops. After readiness returns
200, verify there are no null flags before adding replicas:

```sql
SELECT count(*) AS incomplete
FROM twidget_history_samples
WHERE followers_known IS NULL OR following_known IS NULL
   OR posts_known IS NULL OR likes_known IS NULL;
```

To migrate an existing JSON store, back it up and run the idempotent importer
once before switching the backend:

```bash
HISTORY_DATABASE_URL='postgresql://…' npm run history:migrate -- /data/twidget-history.json
```

The importer never deletes or rewrites the source JSON file. Existing database
samples win on conflicts, except that a real sample can replace an estimate.

Retention is opt-in to avoid surprising deletion of existing history:

- `HISTORY_SAMPLE_RETENTION_DAYS` permanently deletes older samples.
- `HISTORY_INACTIVE_ACCOUNT_DAYS` deletes an account and its samples after no
  client history access or registration for that period. Scheduled collection
  does not keep an otherwise inactive account alive.
- `HISTORY_PRUNE_HOURS` controls cleanup frequency; cleanup also runs at boot.

Private-account detection still deletes pooled history immediately regardless
of the retention settings. PostgreSQL uses cascading deletion so samples and
metadata are removed together. Operators can also permanently delete an account
with `DELETE /admin/history/:username` and the separate `HISTORY_ADMIN_TOKEN`;
this credential must never be included in an app build.

## Environment

Updated Android clients report the existing Settings → Accounts default profile
only while shared history is enabled and the cached profile is known public.
The bridge stores a hash of the install capability in `twidget_primary_profiles`
(or the JSON history store), with one current handle per install and 90-day
expiry. Reports are client claims, not proof of account ownership; old clients
are never inferred to be primary users. The reporting store is capped at 10,000
installs, and the endpoint performs no upstream lookup or paid scan. The
administrator view never exposes installation hashes or capabilities. SQL can
join `twidget_primary_profiles.username` to `twidget_history_accounts.username`
for operator analysis. Handle transfers preserve reports and account deletion
removes them. Clearing an installation report does not delete pooled history.

The safe reference values are in [`.env.example`](.env.example). Important
controls include:

- `BRIDGE_API_TOKEN` — optional token for private/self-hosted data routes.
- `TOP_FOLLOWERS_PUBLISH_TOKEN` — trusted shared-ranking writer credential for an otherwise public bridge.
- `TWITTERAPIS_API_KEY` — server-only key used for opted-in bridge-owned follower scans.
- `TOP_FOLLOWERS_FRESH_HOURS`, `TOP_FOLLOWERS_REFRESH_HOURS`, `TOP_FOLLOWERS_RETENTION_DAYS` — immediate request reuse, head-check/retry cadence, and permanent deletion windows (see cadence details above).
- `TOP_FOLLOWERS_FULL_SCAN_DAYS` — full-scan interval (default `7`, range `1–90`).
- `TOP_FOLLOWERS_HEAD_CHECK`, `TOP_FOLLOWERS_HEAD_ROWS`, `TOP_FOLLOWERS_OVERLAP_ROWS` — daily partial checks (`1` by default), growth buffer/minimum rows (`100`), and consecutive known-follower overlap (`100`).
- `TOP_FOLLOWERS_BENCHMARK_USERNAME` — optional public account for the metered, durable default-vs-100 page-size benchmark.
- `TOP_FOLLOWERS_ACTIVE_DAYS` — client activity window for scheduled scans (default `7`, range `1–365`); exposed as `history.topFollowers.activeDays` in `/health`.
- `TOP_FOLLOWERS_DAILY_SCAN_LIMIT`, `TOP_FOLLOWERS_DAILY_SCAN_LIMIT_PER_IP` — explicit and returning-preview client scan-start budgets.
- `TOP_FOLLOWERS_MAX_CONCURRENT`, `TOP_FOLLOWERS_MAX_PAGES` — scheduled and requested provider-work bounds. A scan that reaches the page limit is not automatically restarted; investigate it before clearing its failed state or raising the limit.
- `TOP_FOLLOWERS_DAILY_REQUEST_LIMIT` (default `1250`) and `TOP_FOLLOWERS_MONTHLY_REQUEST_LIMIT` (default `37500`) — durable request reservations across every account, trigger and replica. At $0.0008 per read these bound future Top Followers spend to $1 per UTC day and $30 per UTC calendar month, including failed calls. Calls before the guard was deployed and usage of the key outside this bridge are not included. Set either to `0` to disable paid requests. Review the limits if provider prices change.

The request ledger lives in PostgreSQL (`twidget_top_followers_budget`) when
PostgreSQL history is enabled, or alongside the JSON history file as
`*.top-followers-budget.json` for a single-instance bridge. Preserve that file
on the same persistent volume as history. Reservations are committed before
provider calls, survive deployments, and fail closed during storage failures.
At a ceiling, running scans retain their cursor and the last completed list
remains available; the scheduler resumes work when the UTC window resets.
Full-scan and head-check schedules still apply, but cannot exceed the budget.
Credit/key errors and provider rate limits also pause work globally (one hour,
24 hours and 15 minutes respectively). Requests already in flight may finish.
- `TRUST_PROXY_HOPS` — exact reverse-proxy hop count; Railway uses `1`.
- `RATE_LIMIT_*`, `EXPENSIVE_RATE_LIMIT_MAX` — request budgets; shared when Redis is configured.
- `REDIS_URL` — shared rate limits, response caches, and scheduled-job locks.
- `MAX_CACHE_ENTRIES`, `OAUTH_MAX_PENDING` — hard memory bounds for public-input maps.
- `MAX_CONCURRENT_UPSTREAM` — process-wide upstream concurrency ceiling.
- `HISTORY_MAX_ACCOUNTS`, `HISTORY_REGISTRATIONS_PER_HOUR` — persistent-work caps.
- `BANGER_PAGES_PER_REQUEST`, `BANGER_MAX_POSTS` — bound each resumable historical scan and its total scope.
- `HISTORY_STORE_PATH` — defaults to `/data/twidget-history.json` when `/data` exists.
- `HISTORY_BACKEND=postgres` and `HISTORY_DATABASE_URL` — shared history for multiple replicas.
- `HISTORY_SAMPLE_RETENTION_DAYS`, `HISTORY_INACTIVE_ACCOUNT_DAYS` — optional permanent deletion policies.
- `HISTORY_ADMIN_TOKEN` — separate operator credential for explicit history deletion.
- `WAYBACK_BACKFILL=1` — explicitly enables archive fetching.
- `X_BEARER_TOKEN` and `PUBLIC_OFFICIAL_API=1` — official endpoint credentials and public exposure opt-in.
- `X_OAUTH_ENABLED=1`, `X_CLIENT_ID`, and `X_CALLBACK_URL` — OAuth opt-in and fixed callback.
- `CORS_ALLOW_ORIGIN` — exact browser origin; absent means no CORS header.

### Account handle changes

Profile responses include `userId`; pooled history includes its stored `userId`, and profile-attached history includes `historyUserId`. ID-aware clients must not merge history with a missing or different account ID.

Legacy accounts with no stored ID continue to serve their handle-keyed history and record daily observations, including through existing clients and repeat registration. Their metadata stays unbound and `historyUserId` stays empty; a current lookup does not establish who owned the historical observations. A known stored ID must match the live profile before its history is attached or updated. Normal profile writes recheck identity atomically, so a concurrent ownership verification cannot admit a stale mismatched write. New accounts are ID-bound on enrollment. Legacy records require independent operator verification before binding or migration.

`POST /history/:oldHandle/handle` accepts `{ "username": "newHandle", "userId": "permanentXId" }`. The bridge verifies the live destination, then moves only ID-bound source records. A destination with a different or unknown ID is rejected. Transfers are atomic in PostgreSQL and atomically published by the JSON backend, preserve history, metadata and follower scans, and can be retried safely. No client history samples are uploaded by this operation.

For independently verified legacy records only, operators can use the admin-authenticated equivalent `POST /admin/history/:oldHandle/handle` with `attestLegacyIdentity: true`. This credential and attestation are never available to Android clients. The administrator must independently establish that both handles identify the same person. Keep a scoped backup before an operator transfer.

### Paid scan exclusions and attribution

`TOP_FOLLOWERS_EXCLUDED_USERNAMES` accepts comma or whitespace separated handles (case insensitive, optional `@`). Excluded handles cannot start or resume paid scans through clients, scheduled refreshes or benchmarks. Existing running scans are marked failed at startup; saved snapshots and profile history remain available. Configure explicit test handles rather than a follower-count threshold that might block real users.

The operator-only `GET /admin/top-followers/usage` endpoint reports daily/monthly request totals, per-account reservations, unattributed earlier requests, limits and exclusions. It requires `HISTORY_ADMIN_TOKEN`. Attribution starts with this version; earlier usage remains unattributed. Reservations include failed provider requests and never refund or reset spent allowance.

### Primary-profile priority and tracked cadence

Paid follower scans prioritise handles reported as an installation's primary profile within the last 30 days. These reports describe the selected default; they are not verified ownership. Older/nonparticipating clients remain usable and receive the tracked/unclassified policy until they report their primary.

- Primary profiles: `TOP_FOLLOWERS_REFRESH_HOURS` (24 by default) between head checks and `TOP_FOLLOWERS_FULL_SCAN_DAYS` (7) between full scans.
- Tracked/unclassified accounts: `TOP_FOLLOWERS_TRACKED_REFRESH_HOURS` (168) between head checks and `TOP_FOLLOWERS_TRACKED_FULL_SCAN_DAYS` (30) between full scans. Failed attempts also respect their slower cadence.
- Tracked/unclassified scans share `TOP_FOLLOWERS_TRACKED_DAILY_REQUEST_LIMIT` (250) requests per UTC day, within the existing global daily and monthly ceilings. Primaries may use the remaining global allowance. This includes client-triggered work, resumes and benchmarks; changing triggers cannot bypass it. Earlier reservations without a tier are conservatively counted against the tracked share until the next UTC day, without resetting global counters.

Primary jobs precede tracked jobs, including primary full scans ahead of tracked head checks. Head checks win within each tier; long scans yield every ten pages. In-progress scans preserve their checkpoints when a tier or global budget pauses them, and cached results remain readable. Free profile/history sampling retains its existing cadence. Reporting changes invalidate the priority cache immediately on that replica; other replicas refresh within a minute.

The authenticated `/admin/top-followers/usage` endpoint includes `tracked.requests`, `tracked.limit` and `primary.requests` for the current UTC day. Expected tracked-share pauses are not global allowance exhaustion or provider failures.

### Shared profile contract

`GET /user/:username` retains all existing profile fields and adds
`profileMetadata` (schema version `1`). All clients share the same profile cache.
It includes `fetchedAt` and `expiresAt` as UTC ISO timestamps, `ageSeconds`,
`cacheStatus` (`hit`, `miss`, or `stale`), `stale`, and `source`
(`fxtwitter`, `rettiwt`, or `unknown` for older cache entries).
`fetchedAt` is the bridge retrieval time, not an upstream measurement timestamp.
Rettiwt results may include official-X verification/privacy enrichment.

`profileMetadata.fields` maps `followersCount`, `followingsCount`,
`statusesCount`, and `likeCount` to availability booleans. Only display a count
as observed when its flag is true; unknown counts may retain a legacy zero
placeholder. Clients should retain stale data with a visible freshness indicator
and use `userId` when associating cached profiles with accounts. Cache hits and
upstream-error fallbacks preserve the original retrieval timestamp. Profile
lookups do not enroll accounts in pooled history. This contract adds no new
upstream requests and uses the existing configurable profile cache lifetime.

### Using shared data from forks

Forks can use the same read routes without registering an installation or
joining pooled history. Public deployments require no bridge token; private
instances require their configured token. Existing rate limits apply to all
clients. Do not embed operator credentials in a client.

- `/user/:username` additionally caches `bio`, `bannerImage`, `location`,
  `website`, and `accountCreatedAt` when supplied by the profile provider.
  Missing fields are null (older cache entries may omit them); empty strings
  are observed empty values. Availability is included in `profileMetadata.fields`.
  `accountCreatedAt` preserves the provider's date string; it is not guaranteed
  to be ISO formatted. Website and banner URLs accept only HTTP(S).
- `/analytics/:username` shares the existing bounded seven-day post sample,
  per-post engagement, recent replies, reach/engagement aggregates, benchmarks,
  best/worst posts and activity timestamps. This is not a full timeline archive.
  It now includes `analyticsMetadata` with version, provider, retrieval/expiry
  timestamps, age, cache status, stale status and sample completeness. Existing
  response fields are preserved. The default cache freshness is 45 minutes
  (`ANALYTICS_TTL_SECONDS`). When refresh fails, a retained cached result can
  be served explicitly marked stale. Cache retention is bounded by the existing
  cache backend and eviction policy; stale fallback is not guaranteed.

These caches are account-scoped, not installation-scoped. Redis shares them
between replicas when configured; otherwise they are held in process memory.
No client-supplied API credentials or private local analytics imports are
published through these additions. Profile details reuse the existing provider
response and introduce no extra lookup. Clients must tolerate unsupported fields
and incomplete post samples rather than interpreting them as account-wide totals.

### Dashboard card data for forks

Analytics post objects now include `bookmarks` and `bookmarksKnown`, alongside the existing counters and
availability flags. Unsupported bookmarks remain unknown, not observed zero.
`dashboardStats` on `/analytics/:username` exposes seven-day sample totals for
likes, replies, reposts, quotes, views and bookmarks; a total is null
when any sampled original post lacks that counter (or the sample is empty).
It also includes `originalPostCount`, `replyPostCount`, `postsPerDay`,
`likesPerPost`, `originalPostTimestamps`, `windowDays`, and `complete`.
Reply counts refer to sampled reply posts authored by the account; the replies
counter measures replies received on its original posts. Sample totals are
lifetime counters on posts created in the window, not daily received movements.
`statusesInspected` and `isSampled` describe the bounded timeline fetch.

Dashboard coverage:

| Card data | Shared source |
| --- | --- |
| Followers, following, posts, profile likes; follower ratio, audience balance | `/user/:username` |
| Growth pace, best growth day, momentum, milestone progress, account health inputs | `/history/:username` plus profile counts; clients retain their display formulas and milestone targets |
| Engagement mix, originals/replies, post rate, likes per post | `/analytics/:username` `dashboardStats` |
| Average/total views, engagement rate, average/median engagements, best/worst posts | Existing `/analytics/:username` fields |
| Daily streak | Original-post timestamps; calculate in the client's timezone; bounded samples may only establish a lower bound |
| Top followers | `/history/:username/top-followers` |
| All-time post | Existing `/banger/:username` route; scan completeness still applies |
| Interaction graphs | `/history/:username/interactions`; missing metrics remain null |

Imported X Analytics cards (impressions, engagements, profile visits and likes
received) are private import data, not inferred from public post counters. These
changes do not automatically publish local imports. Client-specific goals,
settings and calculated scores are not uploaded; forks can derive their cards
from the same shared inputs without treating those preferences as public stats.

Shares are not available from the supported public analytics sources. Analytics
responses omit `shares`, `sharesKnown`, and `benchmarks.medianShares`; reposts
and quotes remain separate metrics. The analytics cache namespace was advanced
to prevent older cached responses from exposing the removed calculated metric.

### Full stored profile history

`GET /history/:username?all=true` (or `all=1`) returns every retained daily
profile sample without the default monthly thinning for older dates. Omit the
flag or use `all=false` for the default response. Invalid flag values return
400. Account scope, privacy checks, retention policies and identity fields are
unchanged. This does not fetch older upstream posts, restore deleted samples,
or expose historical analytics snapshots: analytics retains only its latest
cached seven-day sample. Older JSON-store samples may already have been thinned
on write and cannot be reconstructed.

### All stored analytics posts

`GET /analytics/:username?all=true` (also `all=1`) reads the persistent post
archive without making an upstream request. This mode returns a separate shape:
`{ userName, userId, scope: "all_stored", complete: false, updatedAt, posts, semantics }`.
`posts` contains all retained normalized posts in ascending creation-time order,
including sampled replies; use `isReply` to distinguish them. Each post retains
its latest observed lifetime counters, not daily interaction movements.
`updatedAt` is the archive's last write time in Unix milliseconds, or null.
An account without archived posts returns an empty list, not fabricated history.
The archive is populated by successful normal analytics refreshes after this
feature was enabled; it does not backfill older posts or resurrect expired cache
entries. It uses PostgreSQL when configured, otherwise a sibling JSON file to
the history store. Account ID changes replace the handle's previous archive.
No shares metrics are provided. The regular seven-day endpoint is unchanged.

Analytics responses now include top-level `postCount` (original posts) and
`replyCount` (reply posts authored by the account, not replies received). The
normal response counts the fetched seven-day sample; `?all=true` counts all
retained unique posts. These are sample/archive counts, not full-account totals.

### Resumable historical analytics backfill

`POST /analytics/:username/backfill` retrieves up to two historical timeline
pages per call (configurable with `ANALYTICS_BACKFILL_PAGES`, 1–5), archives
originals and authored replies regardless of age, and returns the same archive
shape as `GET /analytics/:username?all=true`. It requires no request body.
Repeat the POST to resume the durable cursor. Read-only `GET ...?all=true`
does not start a backfill. Use `?restart=true` on POST to restart from the head
and revisit already observed counters. Posts are deduplicated by post ID.

`backfill.status` is `partial`, `exhausted`, `stalled`, or `error` (and
`not_started` before the first run). `exhausted` means the source returned no
continuation cursor, not proof that X exposed every historical post. A provider
error preserves the last successful cursor. `stalled` means a cursor repeated;
restart or investigate rather than repeatedly resuming it. Concurrent requests
for an account return 409 `backfill_in_progress`. Existing API authentication
and expensive-route rate limits apply. Only known-public accounts can be filled.

`coverage.earliestPostAt` and `latestPostAt` are creation timestamps in Unix
milliseconds, not proof of continuous daily coverage. `sourceExhausted` describes
the provider cursor. `replyCoverage` identifies a reply-inclusive timeline versus
incidental/unknown replies; some accounts' reply endpoint fails and the bridge
falls back to their ordinary timeline. Pagination stays on the selected timeline.
`complete` remains false because provider limits/deleted/private posts prevent a
full-account completeness guarantee. Counts cover observed originals and authored
replies only. Engagement counters remain the latest lifetime observations;
backfill cannot reconstruct interactions received on past calendar days.

Normal seven-day analytics now archives every eligible post in each fetched page,
including older ones, before applying the seven-day display window. Its completeness
uses the provider cursor, not page size. Profile post totals read FxTwitter's
`statuses` field with compatibility fallback to `tweets`. Profile and analytics
cache namespaces were advanced to avoid serving old placeholders or coverage flags.
