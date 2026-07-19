# Changelog

All notable changes to Twidget are documented here.

## Unreleased

### Changed

- Reorganized the app's source tree from one flat package into feature packages (analytics, schedule, settings, widget, and friends). Existing home and lock screen widgets keep working: the original widget provider component names stay registered.
- Replaced Postpone cloud scheduling and API-key onboarding with Buffer OAuth, encrypted refresh-token rotation, connected X-channel mapping, and Buffer-backed draft and schedule syncing.

### Added

- Buffer drafts and scheduled posts can now attach local images and videos; Twidget hosts them with its built-in Cloudinary configuration before syncing them to Buffer.
- The native composer now uses Android's active keyboard for spell checking, autocorrection, and word suggestions.
- Drafts can be pinned with quick actions, a long-press context menu, and bulk pin/unpin/delete selection mode.

## [1.1.0] - 2026-07-14

Stable release of Twidget 1.1, bringing scheduled publishing, richer analytics, release notices, and more reliable widgets across launchers.

### Added

- A complete scheduling workspace with calendar and agenda views, local reminder notifications, Postpone integration, drafts, account mapping, and recovery after reboot.
- A native One UI composer for single tweets and threads, including media attachments, camera capture, date and time selection, character limits, a dedicated Draft action, and publish checklists.
- Detailed X Analytics CSV imports covering followers, impressions, engagements, likes, bookmarks, shares, replies, reposts, profile visits, posts, video views, and media views.
- Import validation against trusted snapshots, honest gaps for unavailable data, diagnostic rejection messages, and blending of verified imports into dashboard cards and averages.
- Configurable analytics cards and range-aware insights surfaced directly on the dashboard.
- An in-app Notices feed backed by GitHub Releases, with prerelease labels, offline caching, unread indicators, changelog previews, and full in-app release notes.
- Automatic update checks when the app launches, respecting the selected stable or beta release channel.
- Pull-to-refresh on the About page to immediately recheck the selected update channel.
- Debug builds now expose a debug-only update channel backed by the latest successful, production-signed CI build, using public sidecar metadata that does not consume the GitHub API quota.

### Changed

- Scheduling now uses native One UI calendar, card, switcher, pop-over, floating-toolbar, and composer patterns throughout.
- The composer header is shorter, with a plain Draft action beside the contained Save action.
- Analytics remain embedded in the dashboard; the redundant standalone Analytics page and drawer entry were removed.
- Notices are now a toolbar action with an orange unread dot; redundant drawer and About-page entries were removed.
- Home-screen widgets render to the exact launcher-provided size while preserving artwork proportions, including on non-One UI launchers.
- Private-account analytics now explain their limited availability instead of presenting incomplete data without context.
- GitHub release workflows now place these human-written notes before generated commit and pull-request links.

### Fixed

- Fixed blank or incorrectly sized widgets on non-One UI launchers and prevented artwork from stretching or cropping at unusual launcher dimensions.
- Fixed analytics imports that contain untracked follower removals while continuing to reject genuinely inconsistent histories.
- Restored analytics-import shortcuts and kept the import action available from the account menu.
- Fixed dashboard card touch feedback, chart interactions, and drawer avatar tint persistence.
- Fixed scheduling switcher expansion, composer token highlighting, floating chrome insets, and several light-theme notice/composer surface artifacts.
- Prevented the expanded About-page update control from being clipped on tablets, foldables, and other large-screen layouts.
- Debug builds can now move to beta or stable builds of the same base version, so testers are not stranded on older builds.

[1.1.0]: https://github.com/thatjoshguy67/twidget/compare/twidget-v1.1.0-beta.1...twidget-v1.1.0

## [1.1.0-beta.1] - 2026-07-13

First beta of Twidget 1.1, focused on scheduled publishing, richer analytics, release notices, and more reliable widgets across launchers.

### Added

- A complete scheduling workspace with calendar and agenda views, local reminder notifications, Postpone integration, drafts, account mapping, and recovery after reboot.
- A native One UI composer for single tweets and threads, including media attachments, camera capture, date and time selection, character limits, a dedicated Draft action, and publish checklists.
- Detailed X Analytics CSV imports covering followers, impressions, engagements, likes, bookmarks, shares, replies, reposts, profile visits, posts, video views, and media views.
- Import validation against trusted snapshots, honest gaps for unavailable data, diagnostic rejection messages, and blending of verified imports into dashboard cards and averages.
- Configurable analytics cards and range-aware insights surfaced directly on the dashboard.
- An in-app Notices feed backed by GitHub Releases, with prerelease labels, offline caching, unread indicators, changelog previews, and full in-app release notes.
- Automatic update checks when the app launches, respecting the selected stable or beta release channel.

### Changed

- Scheduling now uses native One UI calendar, card, switcher, pop-over, floating-toolbar, and composer patterns throughout.
- The composer header is shorter, with a plain Draft action beside the contained Save action.
- Analytics remain embedded in the dashboard; the redundant standalone Analytics page and drawer entry were removed.
- Notices are now a toolbar action with an orange unread dot; redundant drawer and About-page entries were removed.
- Home-screen widgets render to the exact launcher-provided size while preserving artwork proportions, including on non-One UI launchers.
- Private-account analytics now explain their limited availability instead of presenting incomplete data without context.
- GitHub release workflows now place these human-written notes before generated commit and pull-request links.

### Fixed

- Fixed blank or incorrectly sized widgets on non-One UI launchers and prevented artwork from stretching or cropping at unusual launcher dimensions.
- Fixed analytics imports that contain untracked follower removals while continuing to reject genuinely inconsistent histories.
- Restored analytics-import shortcuts and kept the import action available from the account menu.
- Fixed dashboard card touch feedback, chart interactions, and drawer avatar tint persistence.
- Fixed scheduling switcher expansion, composer token highlighting, floating chrome insets, and several light-theme notice/composer surface artifacts.

[1.1.0-beta.1]: https://github.com/thatjoshguy67/twidget/compare/twidget-v1.0.0-beta.1...twidget-v1.1.0-beta.1

## [1.0.0] - 2026-07-10

First public release of Twidget, an X/Twitter follower dashboard and Samsung One UI widget app. Rebuilt from the One UI blur widget demo into a follower tracker.

### Added

- Follower dashboard for followers, following, posts, likes, and recent post analytics, with local history and One UI metric charts.
- Four data sources with automatic fallback: direct FxTwitter, the shared bridge, a self-hosted bridge, and the official X API using your own credentials called directly from the device.
- Direct FxTwitter weekly analytics, so FxTwitter mode does not depend on the bridge for dashboard analytics or post media.
- Three-step One UI onboarding: app intro, X handle entry with the shared-history opt-in, and widget setup. X API credentials are configured in Advanced options instead.
- Home-screen widgets in compact strip, 2x1, square, and large sizes with tint, opacity, colour mode, logo, font, and per-widget account options.
- Samsung lock-screen and AOD widgets (1x1 and 2x1) with monotone rendering.
- Multiple tracked accounts with a default-account star selector and long-press delete; widgets pinned to a deleted account revert to the default.
- Background refresh on a configurable interval (15–240 minutes) via WorkManager, plus refresh-on-launch.
- Included the optional FxTwitter/Rettiwt `bridge/` Node service for self-hosting and pooled history.
- Bridge smoke tests and CI checks for security defaults, authentication, syntax, and dependency vulnerabilities.
- Deterministic unit tests for provider fallback, analytics filtering/pagination, history migration, metric provenance, official-X likes handling, encrypted credential envelopes, and update-channel version selection.
- Hidden debug menu, unlocked by tapping the version in About seven times: rerun onboarding, a dummy profile with an editable follower count for widget testing, and a log of bridge traffic.

### Changed

- Hardened the bridge with bounded rate-limit/cache state, expensive-route budgets, upstream concurrency limits, duplicate request coalescing, stale-cache fallback, optional token enforcement, security headers, and shorter HTTP timeouts.
- Limited pooled-history registration and stopped ordinary profile lookups from silently creating persistent accounts.
- Disabled Wayback amplification, client history uploads, public official-X proxying, and bridge OAuth by default; each now requires an explicit opt-in.
- Background refresh now includes every tracked account, not only the default and widget-pinned accounts.
- FxTwitter analytics now follows bounded pagination and labels capped or cyclic results as sampled instead of presenting a partial set as complete.
- API credentials and cached bearer tokens migrate from plaintext preferences to Android Keystore-backed AES-GCM storage.
- Split bridge configuration: the shared Twidget bridge is fixed and token-free, while a self-hosted URL and token live in their own Advanced options section and are used only when that source is selected.
- Sharing history is now the bridge opt-in for FxTwitter mode — with it off, the app talks to FxTwitter only, with no bridge fallback.
- Advanced options shows each source in its own section with a live status row and an info dialog behind an (i) button.
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
