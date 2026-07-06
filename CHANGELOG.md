# Changelog

All notable changes to Twidget are documented here.

## [1.0.0] - 2026-07-04

First public release of Twidget, an X/Twitter follower dashboard and Samsung One UI widget app. Rebuilt from the One UI blur widget demo into a follower tracker.

### Added

- Follower dashboard for followers, following, posts, likes, and estimated impressions, with seven-sample local history and One UI metric charts.
- Three data sources with automatic fallback: the shared Rettiwt bridge (zero setup), a self-hosted bridge, and the official X API using your own credentials called directly from the device.
- Three-step One UI onboarding: app intro, X handle entry (with an advanced dialog to connect your own X API key/secret), and widget setup.
- Home-screen widgets in compact strip, 2x1, square, and large sizes with tint, opacity, colour mode, logo, font, and per-widget account options.
- Samsung lock-screen and AOD widgets (1x1 and 2x1) with monotone rendering.
- Multiple tracked accounts with a default-account star selector and long-press delete; widgets pinned to a deleted account revert to the default.
- Background refresh on a configurable interval (15–240 minutes) via WorkManager, plus refresh-on-launch.
- Included `bridge/` Node service (Rettiwt wrapper) for self-hosting.

[1.0.0]: https://github.com/thatjoshguy67/twidget/releases/tag/v1.0.0
