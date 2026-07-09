# Twidget

Twidget is an Android follower-count dashboard and widget app for X/Twitter profiles. It is forked from the One UI blur widget demo, keeps the Samsung One UI widget metadata, and turns the base into a follower tracker with home-screen blur widgets and lock-screen widgets.

## What is included

- Dashboard for followers, following, posts, likes, and estimated impressions.
- Weekly post analytics with best/quietest tweet cards, media previews, and view/reply/like counts.
- Local and bridge-backed history with One UI-style metric charts.
- Three-step One UI onboarding, plus multiple tracked accounts with a default-account selector.
- Public FxTwitter data, the default bridge, self-hosted bridges, and the official X API with automatic fallback.
- Background refresh on a configurable interval (WorkManager) plus refresh-on-launch.
- Phone layout plus `sw600dp` tablet/foldable two-column layout.
- Home-screen widgets (compact strip, 2x1, square, large) with tint, opacity, colour mode, logo, and font options.
- Samsung lock-screen/AOD widget receivers (1x1 and 2x1) with monotone rendering.

## Data sources

Twidget can fetch stats a few ways. None of them are required to be the shared server — pick whichever fits:

1. **FxTwitter** — a free public FxTwitter/FxEmbed API source called directly from the app for profile stats, and by the bridge for weekly tweet analytics/media. No credentials needed; best-effort because it follows public X/Twitter surface changes.
2. **Default bridge** — a small community-hosted instance of [`bridge/`](bridge/) on Railway (`https://twidget-bridge-production.up.railway.app`). Zero setup; currently uses FxTwitter first and falls back to Rettiwt for profile lookups when possible.
3. **Self-hosted bridge** — deploy [`bridge/`](bridge/) yourself (any Node 22 host; on Railway it is `railway up` from the `bridge/` directory). Point Twidget at it under Settings → Advanced → Self hosted. Bridge routes include `GET /user/:username` and `GET /analytics/:username`.
4. **Official X API (bring your own credentials)** — for direct official profile stats. Enter your X developer API key and secret (or an app-only bearer token) under Advanced options. The app exchanges and calls `api.x.com` **directly from your device**; your credentials never touch any Twidget server. Mind your tier's rate limits when choosing a refresh interval.

If the selected source fails, Twidget falls back to another configured source, then to cached stats. Keep any Rettiwt `API_KEY` on the bridge server only — the app's bridge token setting is for protected self-hosted bridges, not Rettiwt cookie credentials.

## Credits

- [That Josh Guy](https://tjg.gg) — design, development, and testing.
- [KingOwenFYI](https://x.com/KingOwenFYI) — ideas, product feedback, and helping shape the rough edges into actual features.
- [FxTwitter / FxEmbed](https://github.com/FxEmbed/FxEmbed) — public X/Twitter profile, status, media, and recent-post analytics data.
- [One UI Project](https://github.com/OneUIProject) — One UI components and icons used across the app.

## Dependencies and services

- Android app: `oneui-design`, One UI icons, AndroidX WorkManager, and SESL SwipeRefreshLayout.
- Bridge: Node 22, Express, FxTwitter/FxEmbed public APIs, and Rettiwt-API fallback support.

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

The app id is `com.tjg.twidget`.

## Notes

- **Estimated impressions** are derived from follower count, not measured — they are labelled "Est." in the app.
- **Self-hosted bridges must be HTTPS.** Android blocks cleartext `http://` traffic by default, so a plain-HTTP LAN bridge will silently fail to load.
- When a profile image is unavailable, Twidget falls back to `unavatar.io` to resolve an avatar by handle.
- FxTwitter is public and credential-free, but still best-effort; X/Twitter changes can affect availability or response shape.
- The official X API's free tier has tight read quotas; pick a longer refresh interval if you bring free-tier credentials.
