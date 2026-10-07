<!--suppress HtmlDeprecatedAttribute CheckImageSize-->
<div align="center">

<img src="img/twidget-icon.png" height="150" alt="Twidget icon"/>

# Twidget

Twitter widget app, with extra stats

[![Release](https://badgen.net/github/release/thatjoshguy67/twidget)](https://github.com/thatjoshguy67/twidget/releases)
[![License](https://badgen.net/badge/license/MIT/blue)](LICENSE)
[![API Level](https://badgen.net/badge/API/26%2B/green)](https://android-arsenal.com/api?level=26)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

[![Last Commit](https://img.shields.io/github/last-commit/thatjoshguy67/twidget)](https://github.com/thatjoshguy67/twidget/commits/)
[![Contributors](https://img.shields.io/github/contributors/thatjoshguy67/twidget)](https://github.com/thatjoshguy67/twidget/graphs/contributors)

<br>

<img loading="lazy" src="img/screenshot-4.jpg" height="350" alt="Twidget onboarding"/>
<img loading="lazy" src="img/screenshot-3.jpg" height="350" alt="Follower analytics dashboard"/>
<img loading="lazy" src="img/screenshot-1.jpg" height="350" alt="Home-screen widgets"/>
<img loading="lazy" src="img/screenshot-2.jpg" height="350" alt="Lock-screen widgets"/>

<br>

<h3>
  <a href="https://github.com/thatjoshguy67/twidget/releases/latest">Download the latest release</a>
  &nbsp;|&nbsp;
  <a href="https://github.com/thatjoshguy67/twidget/releases">See all releases & betas</a>
</h3>

</div>

---

## Features

- Dashboard for all your Twitter account stats. Followers, following, impressions, engagement, etc...
- View your best and worst tweets of the week.
- Find the largest public accounts following you with a rate-limited TwitterAPIs trial or your own key.
- Local or cloud based stats history
- Track multiple accounts
- One UI style app design
- Blurred home screen widgets (One UI 7+ only)
- Lock screen widget support (One UI 6.1+ only)

## Data sources

Twidget can fetch stats a few ways.

1. **FxTwitter** — a free public FxTwitter/FxEmbed API source called directly from the app for both profile stats and weekly tweet analytics/media. No credentials are needed, calls happen on-device. 
2. **Twidget bridge** — an externally hosted instance of [`bridge/`](bridge/). Currently uses FxTwitter first and falls back to Rettiwt for profile lookups when possible. Caches fetched results for other Twidget users.
3. **Self-hosted bridge** — deploy [`bridge/`](bridge/) yourself with any Node 22 host. Point Twidget at it under Settings → Advanced → Self-hosted bridge. Bridge routes include `GET /user/:username` and `GET /analytics/:username`. Set `BRIDGE_API_TOKEN` on self-hosted instances; the shared Twidget bridge remains token-free by design.
4. **Official X API (bring your own credentials)** — for direct official profile stats. Bring your own API keys and fetch data directly from X using their V2 API. This is not cheap, so only paying X API users can utilise this option. Twidget does not provide this. 
5. **TwitterAPIs** — Top Followers scans use Twidget's bridge and require shared-history consent. Add your own key for direct profile stats and bounded seven-day post analytics/media. Paid post analytics are cached for six hours and fall back to the configured bridge when unavailable.

The **Your Top Followers** dashboard card uses the shared-history bridge in both GitHub and Play builds. Enable shared history under Settings → Data and sources, then tap **Find top follower**. The bridge reuses a fresh completed scan or scans the public account through [TwitterAPIs](https://twitterapis.com), then refreshes enrolled accounts daily. Completed lists are shared with other opted-in installs tracking that handle. Device-side follower scans and their foreground service have been removed. Existing rankings remain cached when upgrading or disabling shared history. Personal provider keys remain available for profile and post analytics. Protected accounts are not supported.

> Shared history is opt-in. The [`bridge/`](bridge/) stores only accounts explicitly registered through the history route. Normal profile lookups do not create persistent records. 

## Credits

- [That Josh Guy](https://tjg.gg) — design, development, testing and many Red Bulls.
- [KingOwenFYI](https://x.com/KingOwenFYI) — ideas and inspiration.
- [FxTwitter / FxEmbed](https://github.com/FxEmbed/FxEmbed) — public X/Twitter profile, status, media, and recent-post analytics data.
- [Rettiwt](https://github.com/Rishikant181/Rettiwt-API) — alternate Twitter/X profile stats API.
- [One UI Project](https://github.com/OneUIProject) — One UI components used across the app.
- [oneui-icons](https://github.com/thatjoshguy67/oneui-icons) - Iconography used throughout the app.

---
## Dependencies and services

- Android app: `oneui-design`, One UI icons, AndroidX WorkManager, and SESL SwipeRefreshLayout.
- Bridge: Node 22, Express, FxTwitter/FxEmbed public APIs, and Rettiwt-API fallback support.
- Optional bridge scaling: PostgreSQL for shared history and Redis for shared rate limits, response caches, and scheduled-job locks.

## Build it yourself

Requires JDK 25 or newer and Android SDK 37. GitHub Actions uses JDK 25; the
app bytecode target is JVM 17.

**macOS:**

```bash
JAVA_HOME="/path/to/jdk25/Contents/Home" ./gradlew :app:assembleGithubDebug
```

**Windows (PowerShell):**

```powershell
$env:JAVA_HOME = "C:\path\to\jdk25"
.\gradlew :app:assembleGithubDebug
```

**Linux:**

```bash
./gradlew :app:assembleGithubDebug
```

The app id is `com.tjg.twidget`.

Buffer scheduling uses a public OAuth client with PKCE. Register the redirect
URI `https://thatjoshguy67.github.io/twidget/oauth/buffer/` in Buffer, or build
with your own using `BUFFER_OAUTH_REDIRECT_URI` or `-PbufferOAuthRedirectUri=...`.
The minimal callback page forwards the short-lived response to
`twidget://oauth/buffer` on the device. Provide the client ID at build time with
`BUFFER_OAUTH_CLIENT_ID` or `-PbufferOAuthClientId=...`; no client secret is
embedded in the Android app.

Device media attached to Buffer posts is hosted on Cloudinary through an
unsigned upload preset. Provide the shared account at build time with
`CLOUDINARY_CLOUD_NAME`/`CLOUDINARY_UPLOAD_PRESET` (or
`-PcloudinaryCloudName=...`/`-PcloudinaryUploadPreset=...`); users can override
both in Buffer settings. Only the cloud name and unsigned preset name are
embedded — no API key or secret.

GitHub Packages credentials belong in
`~/.config/twidget/github.properties`; start from
[`github.properties.example`](github.properties.example) and keep the populated
file outside the checkout. Production signing material is never required for a
contributor build.

The stable version is set in `version.properties`. Debug APKs are labeled
`v<version>-debug.N`, where `N` is one plus the number of commits since the
base version was set. Beta APKs are labeled `v<version>-beta.N`; the
Pre-release workflow derives `N` from existing beta tags. Changing the stable
version resets both channels to `.1`, and rebuilding the same tagged commit
keeps the same version. Use `-PprereleaseNumber=N` or `-PbetaNumber=N` only
when a non-Git build needs an explicit sequence number.

## Project policies

- [Security policy](SECURITY.md)
- [Privacy and data handling](PRIVACY.md)
- [Contributing](CONTRIBUTING.md)
- [Localisation guide](docs/LOCALISATION.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Maintainer release process](docs/RELEASING.md)
