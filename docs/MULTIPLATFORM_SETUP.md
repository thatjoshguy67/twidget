# Developer-app setup for multi-platform Twidget

Start these registrations while the feature is built. Client IDs may be shared
in the task. Keep client secrets in a password manager until the backend exchange
is ready; they will be configured on the server, never in the APK or Git history.

The maintainer chose the existing Twidget bridge hostname on 19 September 2026.
These exact callback paths are reserved for implementation, **not live yet**:

| Provider | HTTPS callback to register |
| --- | --- |
| GitHub | `https://twidget-bridge-production.up.railway.app/oauth/github/callback` |
| Instagram | `https://twidget-bridge-production.up.railway.app/oauth/instagram/callback` |

The existing Buffer callback remains unchanged. These callbacks must exchange
authorization server-side and return a one-use, installation-bound completion
ticket to Android, not expose provider tokens in a browser redirect.

## Google / YouTube

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
| Play-distributed build | Obtain the **app signing** certificate SHA-1 from Play Console. |

These are public certificate fingerprints. The APK/AAB certificate from
[feature CI run 35439107834](https://github.com/thatjoshguy67/twidget/actions/runs/35439107834)
matches the local production key by SHA-256. Recheck if signing configuration changes.

Source: [Google's Android authorization setup](https://developer.android.com/identity/authorization).

## GitHub

1. Open [Developer settings → OAuth Apps](https://github.com/settings/developers)
   under the account/organization that should own Twidget's registration.
2. Register an OAuth app such as **Twidget development**. Set its homepage to the
   actual Twidget site/repository and its callback to the GitHub URL in the table.
3. Copy the client ID. Generate a client secret and save it privately.
4. We will use browser authorization plus the server-side code exchange. Device
   flow does not need to be enabled for this design.
5. The first integration reads public account/repository analytics. Do not request
   private `repo` or write permissions just to read follower or public-star totals.
6. Send the client ID and a test username when ready. The secret can be configured
   in the bridge service environment once the exchange routes are implemented.

Source: [GitHub OAuth app registration](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app)
and [authorization/code exchange](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps).

## Meta / Instagram

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
5. Start with `instagram_business_basic`. The exact additional insights permission
   and access level must be verified in the current app dashboard/official guide
   before requesting it. Do not enable messaging, comment moderation or publishing
   permissions for follower analytics.
6. Keep development/testing access initially. Public distribution needs the
   applicable Meta review/access approval, working login, accurate privacy and
   deletion information, and a reproducible test flow. Prepare that submission
   after the actual Twidget flow is working, so its recording matches the app.

Meta's official [Postman collection](https://www.postman.com/meta/instagram/folder/1z5vxzu/instagram-api-with-instagram-login)
confirms professional-account support and `instagram_business_basic`.
The [developer guide](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/)
was rate-limited during this session; the collection's overview does not establish
the current insights permission, so that remains a setup verification step.

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

No backend deployment or developer-app review has been performed by this code
change. Live OAuth verification follows registration and exchange implementation.
