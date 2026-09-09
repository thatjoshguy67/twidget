# Changelog

All notable changes to Twidget are documented here.

## [1.2.0-beta.2] - 2026-08-09

The second Twidget 1.2 beta makes Your Brief more consistent and useful, reconciles
Buffer posts after their scheduled time, improves widget typography, and adds
proactive update and Top Followers notifications.

### Added

- Added six-hour background update checks with a notification that shows the
  available version and offers **Remind me later** and **Install now** actions.
- Added a Google Sans Flex option to the Your Brief widget alongside
  One UI Sans.
- Added background bridge syncing for opted-in Top Followers rankings and a
  notification when a new follower enters the highest-ranked results.

### Changed

- Your Brief now writes sentence-case dashboard headings, expanded-page titles,
  grammatically correct subheadings, and a separate concise description for
  the dashboard card and home-screen widget.
- Refined Your Brief cards with clearer follower context, correctly routed schedule
  and composer actions, exact goal progress and remaining-follower copy, and
  more useful names and explanations for posting and scheduling guidance.
- Prioritized tweet insights by engagements, impressions, likes, quote tweets,
  and retweets; quote tweets and retweets are now reported separately instead
  of being combined as shares.
- Changing Your Brief content categories now regenerates the next brief and excludes disabled categories and their schedule data from the refresh.
- Reworked Your Brief and Followers widget typography with accurate variable-font
  weights, improved heading spacing, responsive two-line descriptions,
  body text on narrower tall widgets, and vertically centred one-row layouts.
- Moved follower-change context out of the chart card so the page summary and
  follower card no longer repeat the same sentence.
- Improved Top Followers refresh, rescan confirmation, empty-state guidance,
  bridge-backed daily ranking updates, and server/client sync behavior.

### Fixed

- Scheduled Buffer posts are now treated as published once their scheduled time
  passes, moved below upcoming posts and drafts, and removed from upcoming Brief
  content unless Buffer reports a real publishing error or the post is known not
  to be live.
- Fixed follower-goal editing so typed values work with or without grouping
  commas and do not have to match one of the picker presets.
- Fixed release-note Markdown so source-wrapped paragraphs and list items flow
  naturally, with continuation lines aligned beneath the list text.
- Fixed truncated or repetitive Brief summaries, including singular follower
  grammar, and kept dashboard/widget descriptions to a useful two-line length.
- Raised debug-build version codes above the corresponding beta build so debug
  APKs can be installed over the latest beta without a downgrade error.

[1.2.0-beta.2]: https://github.com/thatjoshguy67/twidget/compare/twidget-v1.2.0-beta.1...twidget-v1.2.0-beta.2

## [1.2.0] - 2026-08-06

Twidget 1.2 introduces Twidget Brief, richer goals and history, and a more
personal visual experience across phones, tablets, foldables, and widgets.

### Added

- Added Twidget Brief, a local-first personal guide that prioritizes account
  changes, post performance, milestones, scheduled posts, and useful next
  actions. Supported devices can refine Brief cards with on-device Gemini Nano.
- Added Brief settings, content controls, diagnostics, refresh behavior, and a
  dedicated Brief home-screen widget with responsive card, square, strip, and
  compact layouts.
- Added milestone goals, daily streaks, longer-range chart history, and a
  browsable Top Followers experience from the dashboard.
- Added official X API follower scans for users who provide compatible
  credentials, alongside the existing TwitterAPIs flow.
- Added bridge-owned Top Followers scans for opted-in accounts, with durable
  progress, full-list reuse, pagination, cost controls, and automatic expiry.
- Added wallpaper-derived and custom app palettes, including controls for
  accent, surface tint, and intensity.
- Added richer schedule link previews and improved thread composition controls.
- Added German as a first-class app and widget language, with a language
  picker in Settings and widget configuration.

### Changed

- Brief cards now rank by current relevance, retain generated responses, and
  adapt into independent columns on larger screens.
- Refined the Brief launch, onboarding, guide, headings, hero, settings, and
  widgets to make useful content visible sooner and navigation more direct.
- Polished dashboard milestones, analytics explanations, Top Followers
  browsing, and tweet composition interactions.
- Matched the Streak card to the standard smaller one-column card dimensions.

### Fixed

- Hardened Gemini Nano model selection with stable defaults, compatibility
  handling, automatic fallback, caching, and clearer loading diagnostics.
- Prevented overdue Buffer posts from appearing as upcoming Brief items.
- Fixed Brief card ordering, missing editorial summaries and section headings,
  widget rendering, large-screen tiling, and launch-transition regressions.
- Improved Top Followers scrolling and corrected several icon, theme, and
  palette details across light and dark appearances.

[1.2.0]: https://github.com/thatjoshguy67/twidget/compare/twidget-v1.1.1...twidget-v1.2.0

## [1.1.1] - 2026-07-26

Twidget 1.1.1 moves scheduled publishing to Buffer, makes Top Followers scans
more resilient and accessible, and improves onboarding and adaptive layouts
across phones, tablets, and launchers.

### Added

- Added an included, rate-limited TwitterAPIs trial for one Top Followers scan
  per account each day. Personal keys in Advanced settings take priority and
  remove Twidget's daily limit.
- Participating shared-history installs can reuse trusted completed Top
  Followers rankings when the same account is added elsewhere.
- Added a dedicated onboarding permissions step for notifications and exact
  reminders before account setup.
- Buffer drafts and scheduled posts can attach local images and videos, with
  Twidget securely hosting the media before Buffer publishes it.
- The native composer now uses Android's active keyboard for spell checking,
  autocorrection, and word suggestions.
- Drafts can be pinned with quick actions, a long-press context menu, and bulk
  pin, unpin, and delete selection mode.
- Added initial Samsung Modes and Routines support for refreshing Twidget
  statistics on compatible Galaxy devices.

### Changed

- Replaced Postpone cloud scheduling and API-key onboarding with Buffer OAuth,
  encrypted refresh-token rotation, connected X-channel mapping, and
  Buffer-backed draft and schedule syncing.
- Long-running Top Followers scans now continue as foreground work after the
  app leaves the screen and show progress through Android Live Updates on
  supported devices.
- Redesigned scheduled-post cards with clearer dates and actions, more readable
  post text, and responsive media thumbnails.
- Kept the shared-history choice in initial onboarding only, so adding another
  account cannot accidentally change the install-wide privacy setting.
- Reorganized the Android source tree for clearer feature ownership and easier
  maintenance.
- Prepared Play Store distribution with Android 16 targeting and signed App
  Bundles for debug, beta, and stable release workflows.

### Fixed

- Hardened Buffer request throttling, media synchronization, refresh-token
  handling, and background schedule updates.
- Buffer publishing now sends a confirmation after a successful post and a
  detailed notification when publishing fails.
- Buffer refreshes now preserve per-item media added to X threads and use
  Buffer thumbnails for reliable in-app previews.
- Made Top Followers scans resumable and more reliable when the app is
  backgrounded or interrupted.
- Restricted shared Top Followers ranking publication to authenticated,
  server-side publishers so public clients cannot replace cached results.
- Kept Scheduling scoped to the account selected in the drawer, including
  queue filtering, Buffer channel selection, and newly composed posts.
- Limited imported scheduling media to 100 MiB and delete partial private
  copies when a provider omits or misreports the file size.
- Prevented cancelled Top Followers scans from committing an in-flight page,
  promoted scans to foreground work immediately, and validated nonexistent
  handles during onboarding.
- Improved adaptive dashboard layouts, chart spacing, widget sizing, and
  blank-state handling across phones, tablets, and non-One UI launchers.

[1.1.1]: https://github.com/thatjoshguy67/twidget/compare/twidget-v1.1.1-beta.2...twidget-v1.1.1

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
