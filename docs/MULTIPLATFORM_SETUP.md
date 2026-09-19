# Developer-app setup for multi-platform Twidget

Start these registrations while the feature is built. Client IDs may be shared
in the task. Keep client secrets in a password manager until the backend exchange
is ready; they will be configured on the server, never in the APK or Git history.

The maintainer chose the existing Twidget bridge hostname on 19 September 2026.
These callback paths are live as of 19 September 2026:

| Provider | HTTPS callback to register |
| --- | --- |
| GitHub | `https://twidget-bridge-production.up.railway.app/oauth/github/callback` |
| Instagram | `https://twidget-bridge-production.up.railway.app/oauth/instagram/callback` |

The existing Buffer callback remains unchanged. These callbacks must exchange
authorization server-side and return a one-use, installation-bound completion
ticket to Android, not expose provider tokens in a browser redirect.

## Google / YouTube

### Registered development configuration (19 September 2026)

Project: **Twidget** (`twidget-509111`). Both Android clients were confirmed
created in the maintainer's Google Cloud credentials screen:

| Client name | Android OAuth client ID |
| --- | --- |
| Twidget OAuth - Debug | `290048267551-id2rqp7fpk8t075705n9marphh772uoe.apps.googleusercontent.com` |
| Twidget OAuth - GitHub & CI | `290048267551-d92e5ttjqhjv5u60ilt0cqtru35hppf6.apps.googleusercontent.com` |

Both registrations use `com.tjg.twidget`. The maintainer supplied the Play app
signing SHA-1, which matches the production fingerprint below, so the GitHub/CI
client also covers Play distribution; a third client is unnecessary.

The maintainer reports YouTube Data API v3, External/Testing audience, test-user
access and the `youtube.readonly` scope configured. Those settings have not been
independently inspected. The console registrations are ready for development;
successful authorization and a YouTube API call in Twidget remain unverified.
These client IDs and certificate fingerprints are public configuration, not
secrets. No Google client secret or bridge callback is required for the planned
on-device `AuthorizationClient` flow.

### Registration reference

1. Create/select a **Twidget** project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable **YouTube Data API v3** in the API Library. The initial read scope is
   `https://www.googleapis.com/auth/youtube.readonly`. Owner analytics will use
   YouTube Analytics API and `https://www.googleapis.com/auth/yt-analytics.readonly`
   when that integration is ready; no upload or channel-management scopes are
   needed for the analytics feature.
3. Configure Google Auth Platform branding, support/developer contact, audience
   and the real app homepage/privacy-policy URLs. Keep the app in testing and
   add the Google account(s) you will use for development as test users.
4. Create Android OAuth clients for package **`com.tjg.twidget`**, one per signing
   certificate used for testing/distribution. Google requires the **SHA-1**, not
   SHA-256. The checked-in debug certificate differs from trusted CI/release
   signing. Play App Signing may use another certificate; copy it from Play
   Console's App integrity page, not the upload certificate.
5. Record the project ID and Android OAuth client IDs. Use an account that has a
   YouTube channel; channel identity is distinct from the Google login identity.
6. If we add a backend Google exchange, create a separate Web application client
   at that point. The Android authorization path does not require copying a
   Google client secret into Twidget.

The optional local `.sesl9` application ID needs its own Android registration
only if it will be used for YouTube OAuth testing. It is not the package used by
normal feature-branch CI builds.

To reproduce local certificate details:

```sh
JAVA_HOME=/path/to/jdk25-or-newer ./gradlew :app:signingReport
```

Verified from this checkout's signing report on 19 September 2026:

| Certificate | SHA-1 for Google Android registration |
| --- | --- |
| Checked-in debug key (local/contributor builds) | `65:12:D7:58:C8:A1:7F:52:E7:E6:01:DF:21:DB:E0:E9:48:F5:0E:EF` |
| Production key (release/beta and trusted feature-branch CI) | `F1:12:10:48:CB:16:BE:49:71:9A:90:C1:1C:3D:95:0F:B5:38:D9:9B` |
| Play-distributed build (maintainer-confirmed) | `F1:12:10:48:CB:16:BE:49:71:9A:90:C1:1C:3D:95:0F:B5:38:D9:9B` (same client as production) |

These are public certificate fingerprints. The APK/AAB certificate from
[feature CI run 35439107834](https://github.com/thatjoshguy67/twidget/actions/runs/35439107834)
matches the local production key by SHA-256. Recheck if signing configuration changes.

Source: [Google's Android authorization setup](https://developer.android.com/identity/authorization).

## GitHub

OAuth App client ID supplied by the maintainer on 19 September 2026:
`Ov23liPX2kvl57lZ7ehI`.

The bridge expects `GITHUB_OAUTH_CLIENT_ID` and `GITHUB_OAUTH_CLIENT_SECRET`.
After the maintainer set both with `--skip-deploys`, a read-back on 19 September
2026 confirmed the matching client ID and a nonempty secret with no surrounding
whitespace in Railway's `twidget-bridge` service, `production` environment. The
verification did not display or persist secret values. This verifies stored
configuration only; secret validity and live GitHub authorization remain untested.

1. Open [Developer settings → OAuth Apps](https://github.com/settings/developers)
   under the account/organization that should own Twidget's registration.
2. Register an OAuth app such as **Twidget development**. Set its homepage to the
   actual Twidget site/repository and its callback to the GitHub URL in the table.
3. Copy the client ID. Generate a client secret and save it privately.
4. We will use browser authorization plus the server-side code exchange. Device
   flow does not need to be enabled for this design.
   Keep **Expire user access tokens** enabled if offered. Current GitHub docs
   describe expiring OAuth App tokens as the default. Before live testing, the
   bridge/device integration now preserves both token expiries and the rotating
   refresh token. `/oauth/github/refresh` exchanges it server-side; Android stores
   the replacement in Keystore-backed storage. Fixture tests cover rotation; live
   expiring-grant verification remains required.
5. The first integration reads public account/repository analytics. Do not request
   private `repo` or write permissions just to read follower or public-star totals.
6. Send the client ID and a test username when ready. The secret can be configured
   in the bridge service environment once the exchange routes are implemented.

Source: [GitHub OAuth app registration](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app)
and [authorization/code exchange](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps).

## Meta / Instagram

Created Meta app: **Twidget** (`1085129303901685`), with Instagram and Threads
use cases; Threads is reserved for future work. The Instagram Login setup shows
Instagram app **Twidget-IG**, client ID **`1731035707968772`**. Use this Instagram
client ID for `INSTAGRAM_OAUTH_CLIENT_ID`, not the parent Meta app ID.

On 19 September 2026, Railway read-back confirmed the matching Instagram client
ID and a nonempty `INSTAGRAM_OAUTH_CLIENT_SECRET` with no surrounding whitespace
in `twidget-bridge` / `production`. The maintainer used `--skip-deploys`; this
verifies stored configuration, not a deployed OAuth flow or secret validity.
Secret values were not displayed or persisted by the verification.

The maintainer reports saving the business-login redirect URI from the table and
accepting the Instagram tester invitation. Their Meta setup screen confirms
`thatjoshguy69` (Instagram account ID `17841455280723932`) is now in the account
list, with webhook subscriptions off. Live Twidget authorization and API calls
remain untested.

The maintainer's live permissions screen on 19 September 2026 confirms
`instagram_business_basic` is ready for testing and
`instagram_business_manage_insights` is available to add. Its description covers
professional-account and media insights, so retain Instagram Login despite the
setup banner directing insights users to Facebook Login. Adding the permission,
consent and live analytics endpoint access are not yet verified. Request insights
in the authorization flow when the implementation actually uses those endpoints.

1. Create/select a Twidget app in [Meta for Developers](https://developers.facebook.com/apps/).
2. Add/configure **Instagram API with Instagram Login**. This integration supports
   professional accounts (Business or Creator); arrange an eligible account for
   testing rather than assuming an ordinary personal account will work.
3. Add the Instagram callback in the table to the permitted OAuth redirect URIs.
   Add yourself as the appropriate developer/tester and accept any Instagram
   tester invitation shown by Meta.
4. Record the **Instagram client/app ID associated with this login product** and
   keep its corresponding secret privately. Do not confuse credentials from a
   different Meta login product with this Instagram registration.
5. Configure `instagram_business_basic` and add
   `instagram_business_manage_insights` for the planned owner analytics. Do not
   request messaging, comment moderation or publishing permissions for analytics.
6. Keep development/testing access initially. Public distribution needs the
   applicable Meta review/access approval, working login, accurate privacy and
   deletion information, and a reproducible test flow. Prepare that submission
   after the actual Twidget flow is working, so its recording matches the app.

Meta's official [Postman collection](https://www.postman.com/meta/instagram/folder/1z5vxzu/instagram-api-with-instagram-login)
confirms professional-account support and `instagram_business_basic`.
The [developer guide](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/)
was rate-limited during this session; the insights permission above was verified
from the maintainer-provided live Meta permissions screen instead.

## Bluesky and Twitter/X

Bluesky public profile lookup needs a handle/DID, not a developer application or
password. Have a public account available for a live test. The provider stores
the returned DID so later handle changes cannot attach history to another user.
Source: [Bluesky profile API lexicon](https://github.com/bluesky-social/atproto/blob/main/lexicons/app/bsky/actor/getProfile.json).

Existing Twitter/X providers, credentials and optional Buffer registration stay
usable. No new X developer registration is required for this feature.

## What to send back

- Google project ID and Android OAuth client IDs; confirm which test accounts
  and signing certificates are registered.
- GitHub OAuth client ID and test username.
- Instagram login client/app ID and confirmation that the professional account
  has accepted its test access.
- Any dashboard error or unavailable permission, without secrets or access tokens.

The bridge OAuth routes were deployed on 19 September 2026. Developer-app review
for public release and complete provider login acceptance remain separate checks.


## Feature-branch implementation configuration

Setup handoff verified on 19 September 2026: Railway `twidget-bridge` /
`production` has both matching provider client IDs, both nonempty provider
secrets, a Base64 `SOCIAL_OAUTH_TICKET_KEY` decoding to 32 bytes, and `REDIS_URL`.
The maintainer set these variables with `--skip-deploys`. Verification exposed
only boolean results; no secrets were printed or written into the repository.
Developer-app setup work is complete for development testing. The deployment
and live route checks are recorded below; full OAuth and API acceptance still
requires the maintainer to complete each provider login. Google/Meta public-release verification or review is
separate from this development setup; neither was completed here.

The OAuth routes from `feat-multiplat` commit `f34a0486` were deployed to the
existing production bridge on 19 September 2026 after the maintainer reported
live login failures. Railway deployment `b19948b8-020b-4ecb-93f6-3688216b411e`
reached `SUCCESS`; all 38 bridge regression tests passed beforehand.
Both provider readiness endpoints report `available: true`, both start routes
return HTTP 200 with the registered client IDs and callback URIs, and `/health`
returns HTTP 200. This verifies configuration and login initiation; provider
consent, token exchange, and Android callback completion still need a real-user
acceptance test. The maintainer confirmed YouTube works after adding the Google
account to the testing audience.

Required bridge environment (private service variables; never Android resources):

| Variable | Purpose |
| --- | --- |
| `SOCIAL_OAUTH_TICKET_KEY` | Random 32-byte key encoded as Base64, shared across bridge replicas. Encrypts ten-minute OAuth state and two-minute token tickets. Generate privately; do not paste it into chat. |
| `SOCIAL_OAUTH_ORIGIN` | Optional; defaults to `https://twidget-bridge-production.up.railway.app`. Must be an HTTPS origin without a path. |
| `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET` | GitHub registration. |
| `INSTAGRAM_OAUTH_CLIENT_ID` / `INSTAGRAM_OAUTH_CLIENT_SECRET` | Instagram Login registration. |
| `REDIS_URL` | Existing shared Redis connection; required when running multiple bridge replicas. A single instance can use bounded in-memory pending sessions, which expire on restart. |

Readiness endpoints are `/oauth/github/status` and `/oauth/instagram/status`.
They report only whether required configuration is present. They do not verify
provider approval or credential validity. Missing configuration fails closed.

Android uses Google `AuthorizationClient` with `youtube.readonly`; no Google
client secret or web redirect is included in the APK. Background authorization
may renew access only when Google does not require interaction, and the returned
channel ID must match the saved channel before data is applied. Instagram tokens
are refreshed shortly before their long-lived expiry; revoked grants require
reconnection. GitHub refresh-token rotation is persisted before the next read.

Meta profile fields/permissions and all authenticated providers still need live
acceptance with authorized test accounts. Do not treat mocked token exchanges as
proof of a working developer registration. Public Bluesky lookup has been exercised
through the Android UI on the disposable emulator.
