# Twidget

Twidget is an Android follower-count dashboard and widget app for X/Twitter profiles. It is forked from the One UI blur widget demo, keeps the Samsung One UI widget metadata, and turns the base into a follower tracker with home-screen blur widgets and lock-screen widgets.

## What is included

- Dashboard for followers, following, posts, likes, and estimated impressions.
- Seven-sample local history with One UI-style metric charts.
- Three-step One UI onboarding, plus multiple tracked accounts with a default-account selector.
- Three data sources with automatic fallback (see below), including the official X API called directly from the device with your own credentials.
- Background refresh on a configurable interval (WorkManager) plus refresh-on-launch.
- Phone layout plus `sw600dp` tablet/foldable two-column layout.
- Home-screen widgets (compact strip, 2x1, square, large) with tint, opacity, colour mode, logo, and font options.
- Samsung lock-screen/AOD widget receivers (1x1 and 2x1) with monotone rendering.

## Data sources

Twidget can fetch stats three ways. None of them are required to be the shared server — pick whichever fits:

1. **Default Rettiwt bridge** — a small community-hosted instance of [`bridge/`](bridge/) on Railway (`https://twidget-bridge-production.up.railway.app`). Zero setup, uses Rettiwt guest auth for public profiles. Best-effort only: it is a low-power shared box and scraping can break whenever X changes things.
2. **Self-hosted bridge** — deploy [`bridge/`](bridge/) yourself (any Node 22 host; on Railway it is `railway up` from the `bridge/` directory). Point Twidget at it under Settings → Advanced → Self hosted. Bridge routes: `GET /user/:username` (also `/users/`, `/details/`, `/?username=`).
3. **Official X API (bring your own credentials)** — for the most accurate stats. Enter your X developer API key and secret (or an app-only bearer token) under Advanced options. The app exchanges and calls `api.x.com` **directly from your device**; your credentials never touch any Twidget server. Mind your tier's rate limits when choosing a refresh interval.

If the selected source fails, Twidget falls back to the other configured source, then to cached stats. Keep any Rettiwt `API_KEY` on the bridge server only — the app's bridge token setting is for protected self-hosted bridges, not Rettiwt cookie credentials.

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

The app id is `com.tjg.twidget`.

## Notes

- **Estimated impressions** are derived from follower count, not measured — they are labelled "Est." in the app.
- **Self-hosted bridges must be HTTPS.** Android blocks cleartext `http://` traffic by default, so a plain-HTTP LAN bridge will silently fail to load.
- When a profile image is unavailable, Twidget falls back to `unavatar.io` to resolve an avatar by handle.
- The official X API's free tier has tight read quotas; pick a longer refresh interval if you bring free-tier credentials.
