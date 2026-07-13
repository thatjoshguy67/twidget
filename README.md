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
- Local or cloud based stats history
- Track multiple accounts
- One UI style app design
- Blurred home screen widgets (One UI 7+ only)
- Lock screen widget support (One UI 6.1+ only)

## Data sources

Twidget can fetch stats a few ways.

1. **FxTwitter** — a free public FxTwitter/FxEmbed API source called directly from the app for both profile stats and weekly tweet analytics/media. No credentials are needed, calls happen on-device. 
2. **Twidget bridge** — an externally hosted instance of [`bridge/`](bridge/). Currently uses FxTwitter first and falls back to Rettiwt for profile lookups when possible. Caches fetched results for other Twidget users.
3. **Self-hosted bridge** — deploy [`bridge/`](bridge/) yourself with any Node 22 host. Point Twidget at it under Settings → Advanced → Self-hosted bridge. Bridge routes include `GET /user/:username` and `GET /analytics/:username`. Supports bridge bearer tokens for added security.
4. **Official X API (bring your own credentials)** — for direct official profile stats. Bring your own API keys and fetch data directly from X using their V2 API. This is not cheap, so only paying X API users can utilise this option. Twidget does not provide this. 

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

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

The app id is `com.tjg.twidget`.

GitHub Packages credentials belong in
`~/.config/twidget/github.properties`; start from
[`github.properties.example`](github.properties.example) and keep the populated
file outside the checkout. Production signing material is never required for a
contributor build.

The stable version is set in `version.properties`. Debug and beta APKs are
labeled `v<version>-debug.N` and `v<version>-beta.N`; `N` is one plus the
number of commits since the base version was set, so changing the stable
version resets both channels to `.1`. Rebuilding the same commit keeps the
same reproducible version. Use `-PprereleaseNumber=N` only when a non-Git build
needs an explicit sequence number.

## Project policies

- [Security policy](SECURITY.md)
- [Privacy and data handling](PRIVACY.md)
- [Contributing](CONTRIBUTING.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Maintainer release process](docs/RELEASING.md)
