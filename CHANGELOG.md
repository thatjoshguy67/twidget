# Changelog

All notable changes to Twidget are documented here.

## [1.0.0] - 2026-07-10

First public release of Twidget, an X/Twitter follower dashboard and Samsung One UI widget app. Rebuilt from the One UI blur widget demo into a follower tracker.

### Added

- Follower dashboard for followers, following, posts, likes, and recent post analytics, with local history and One UI metric charts.
- Four data sources with automatic fallback: direct FxTwitter, the shared bridge, a self-hosted bridge, and the official X API using your own credentials called directly from the device.
- Direct FxTwitter weekly analytics, so FxTwitter mode does not depend on the bridge for dashboard analytics or post media.
- Three-step One UI onboarding: app intro, X handle entry (with an advanced dialog to connect your own X API key/secret), and widget setup.
- Home-screen widgets in compact strip, 2x1, square, and large sizes with tint, opacity, colour mode, logo, font, and per-widget account options.
- Samsung lock-screen and AOD widgets (1x1 and 2x1) with monotone rendering.
- Multiple tracked accounts with a default-account star selector and long-press delete; widgets pinned to a deleted account revert to the default.
- Background refresh on a configurable interval (15–240 minutes) via WorkManager, plus refresh-on-launch.
- Included the optional FxTwitter/Rettiwt `bridge/` Node service for self-hosting and pooled history.
- Bridge smoke tests and CI checks for security defaults, authentication, syntax, and dependency vulnerabilities.
- Fifteen deterministic Android tests for provider fallback, analytics filtering/pagination, history migration, metric provenance, official-X likes handling, and encrypted credential envelopes.

### Changed

- Hardened the bridge with bounded rate-limit/cache state, expensive-route budgets, upstream concurrency limits, duplicate request coalescing, stale-cache fallback, optional token enforcement, security headers, and shorter HTTP timeouts.
- Limited pooled-history registration and stopped ordinary profile lookups from silently creating persistent accounts.
- Disabled Wayback amplification, client history uploads, public official-X proxying, and bridge OAuth by default; each now requires an explicit opt-in.
- Background refresh now includes every tracked account, not only the default and widget-pinned accounts.
- FxTwitter analytics now follows bounded pagination and labels capped or cyclic results as sampled instead of presenting a partial set as complete.
- API credentials and cached bearer tokens migrate from plaintext preferences to Android Keystore-backed AES-GCM storage.
- Replaced unmanaged background threads with a bounded executor and lifecycle/request guards so saturated or stale work cannot leave the UI or widgets loading indefinitely.
- Added optional PostgreSQL history persistence plus Redis-backed shared limits, caches, registration budgets, and scheduled-job locks for multi-replica bridge deployments.
- Added bridge history retention/deletion controls, an operator-only deletion endpoint, and per-metric provenance through JSON and PostgreSQL storage.

### Fixed

- Prevented demo follower counts from appearing as real data after onboarding or when a cached profile is incomplete.
- Preserved legitimate no-change history days during migration and normalized bridge day timestamps across time zones.
- Fixed high-resolution profile-image query rewriting and avoided repeated compatibility-route waits after non-404 bridge failures.
- Prevented the official X provider from recording a false zero likes count when X omits that metric.
- Prevented unavailable historical metrics from becoming known zeroes, while preserving legitimate observed zero values.
- Removed the onboarding widget preview's hard-coded follower delta; unknown movement is no longer displayed as an invented gain.

[1.0.0]: https://github.com/thatjoshguy67/twidget/releases/tag/twidget-v1.0.0
