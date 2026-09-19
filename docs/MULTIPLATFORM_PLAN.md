# Multi-platform implementation plan

Prepared 19 September 2026, before application-code changes.

Branch: `feat-multiplat`, based on `staging` at `b196d1e7`.

Status: design and architecture investigation complete; implementation pending.
Developer applications will be registered from scratch, per the maintainer.

## Outcome

Support Twitter/X, Instagram, YouTube, Bluesky and GitHub. A standalone
platform account remains its own drawer destination. A linked profile groups
selected accounts into one destination, with a chosen display name/avatar,
combined audience overview, and individual platform cards. Linking is a local
presentation choice, not proof that accounts share an owner.

Use Kotlin, Android Views, the existing One UI wrapper and pinned SESL9 family.
Figma defines flow, hierarchy and layout intent; native components supply sizes,
padding, typography, interaction, selection, dialogs and floating chrome.

## Design inventory and acceptance requirements

Read the four supplied sections, visible screen text and descendant annotations.
Retrieved individual design context and screenshots for platform selection,
linking, profile display, dashboard, accounts and widget settings.

| Area | Figma reference | Required behavior |
| --- | --- | --- |
| Onboarding | [17:2654](https://www.figma.com/design/pAZuve5FxNvCebYmd5dVJY/Twidget?node-id=17-2654) | Welcome, platform selection, handle/OAuth addition, add more, optional linking, display selection, optional services, widget and completion. |
| Existing users | [246:10869](https://www.figma.com/design/pAZuve5FxNvCebYmd5dVJY/Twidget?node-id=246-10869) | One-time upgrade introduction with existing X accounts preserved; optional additions/linking; final action opens latest changelog. |
| Dashboard/drawer | [12:3714](https://www.figma.com/design/pAZuve5FxNvCebYmd5dVJY/Twidget?node-id=12-3714) | Combined linked-platform cards and platform-specific cards; independent accounts remain separate drawer pages. Preserve dashboard editing and adaptive layouts. |
| Settings | [12:3717](https://www.figma.com/design/pAZuve5FxNvCebYmd5dVJY/Twidget?node-id=12-3717) | Platforms & accounts, linked members and display editor, other accounts, optional Buffer/import, active X source and alternate sources, logged-in sessions, refresh/cache settings. |
| Brief categories | [246:9889](https://www.figma.com/design/pAZuve5FxNvCebYmd5dVJY/Twidget?node-id=246-9889) | Group content by platform and linked-profile scope. GitHub stars/forks, applicable YouTube subscriber/activity categories, existing X categories and combined audience. |
| Widgets | [12:3715](https://www.figma.com/design/pAZuve5FxNvCebYmd5dVJY/Twidget?node-id=12-3715) | Choose profile and linked member/platform; hide platform selector for standalone profiles; hide X/Twitter logo style for other platforms; open the selected platform's profile/app. |

Specific design corrections: OAuth cards contain copied Instagram text on the
YouTube and GitHub screens. Replace it with provider-specific, accurate copy.
The existing Figma About version/SESL8 text is illustrative, not a dependency
or release-version instruction. Keep existing notification/reminder permission
handling, requested in context rather than making optional permissions a
condition of adding a platform.

## 1. Branch and CI

- Enable pushes and pull requests targeting `feat-multiplat` in Debug Build.
- Retain manual dispatch and existing unit, instrumentation-compilation, lint,
  bridge, APK/AAB and signing checks.
- Feature builds upload Actions artifacts, but never overwrite
  `twidget-debug-latest`: the installed updater consumes that shared tag.
- Include the commit in artifact names to distinguish builds with the same
  version number; Actions also identifies the branch for each run. Do not change
  stable/beta release workflows.
- Retain the existing app ID and trusted signing path for upgrade testing.
  The existing isolated SESL9 install option remains available for local UI work;
  OAuth testing needs credentials registered for the actual package/signature.
- Validate both GitHub and Play variants before merging. CI must compile when
  new OAuth client configuration is absent; unconfigured providers must display
  an actionable setup/unavailable state, never a successful fake connection.

## 2. Account, profile and metric foundation

The current `TwidgetStore.accounts()` returns handles. `ProfileStats`, history,
widget settings, drawer selection, refresh work, Brief and scheduling all
implicitly mean Twitter. Do not append prefixed usernames to this list and send
them through existing X validators/providers.

Introduce these separate concepts:

- `SocialPlatform`: stable storage ID, display resources, icon, input parsing,
  profile URL and provider capabilities.
- `PlatformAccount`: stable local ID, platform, immutable remote ID when known,
  current handle, name/avatar and optional authorization-session reference.
  Identity uses platform + remote ID, not display name or handle. Bluesky uses
  DID, YouTube channel ID and GitHub numeric user ID. Legacy X accounts can start
  with a deterministic migration identity until their remote ID is resolved.
- `SocialProfile`: stable profile ID, member account IDs, independently selected
  display-name/avatar sources and an optional custom display-name override.
  A one-member profile is standalone. A linked profile has multiple members.
  Initial UI permits one account per platform in a linked profile; additional
  accounts on that platform remain separate profiles. Each account belongs to
  one profile at a time to avoid duplicate drawer entries.
- `MetricSnapshot`/`MetricHistory`: account ID, metric ID, numeric value or an
  explicit unavailable state, timestamp, source, precision and sampling period.
  Followers, subscribers, views, posts, stars and forks are distinct metrics.
- `ProviderCapabilities`: available metrics, content/activity, authentication,
  historical data and scheduling support. UI and Brief consult capabilities.
- `DashboardCardSpec`: profile/account scope + metric + configuration, with a
  stable card ID. Preserve ordering and disabled cards per profile.

Use a transactional, versioned store for the new account/profile/metric tables
(Room is the proposed implementation). Keep unrelated global preferences in
their current store. Extract pure migration and aggregation policies so they
can be tested without an Android activity. Expose repositories to screens and
workers rather than adding more unrelated responsibilities to `TwidgetStore`.

### Migration contract

1. Read the legacy account list, default account and widget-pinned accounts;
   deduplicate case-insensitive X handles and create standalone X profiles.
2. Copy stats and history with all known/unknown, imported and estimated flags
   preserved. Preserve selected/default account, goals, streaks, imports,
   dashboard ordering, Brief preferences, shared-history consent and schedules.
3. Translate widget bindings and account-scoped records to stable IDs. Keep
   compatibility readers/adapters for X stores until each consumer is migrated.
4. Commit the new schema/migration version only after a successful transaction.
   Migrate legacy preference references idempotently and keep old records during
   feature development. A failed/interrupted migration must be safely retryable.
5. Record upgrade-onboarding completion separately from data migration. Existing
   users can continue without adding an account, linking or granting new scopes.

Account removal, unlinking and credential disconnection are separate actions.
Unlinking preserves account history and creates a standalone destination.
Disconnecting authorization retains cached data with a reconnect state.
Removing an account explicitly repairs profile, avatar/name source, default,
widget, Brief and scheduling references. Work completing after removal must
not resurrect the account. Do not change group membership while computing a
snapshot; include membership version in derived-data cache keys.

## 3. Provider and authentication implementation

| Platform | Planned connection | Initial metrics |
| --- | --- | --- |
| Twitter/X | Existing public handle flow and configured providers | Existing supported stats, history/imports, post analytics and top followers. |
| Bluesky | Public handle/DID lookup | Followers, following, posts and available public post activity. Resolve and retain DID across handle changes. |
| GitHub | OAuth connection matching onboarding | Followers/following, public repositories, owned-public-repository stars/forks; paginate repositories and distinguish unavailable or incomplete totals. |
| YouTube | Google authorization and channel selection | Subscribers, public video count and channel views; add owner analytics only with the relevant API/scopes and verified response semantics. |
| Instagram | Meta/Instagram authorization | Profile/follower/media data and insights supported by the approved account type, permissions and API version. |

Provider adapters implement lookup, refresh, capabilities and typed errors.
Separate missing configuration, denied/expired authorization, private or
unsupported account, quota/rate limit and temporary network failure. Preserve
the last good snapshot and expose its timestamp; missing data is never zero.
Use bounded requests, pagination, cache expiry, retry-after/backoff and request
deduplication. Refresh members independently so one failure does not hide the
entire linked profile. Do not invoke X scans/imports for other platforms.

### Developer setup and authentication dependencies

- **Google:** create a Cloud project, configure consent/test users and enable
  YouTube Data API; enable YouTube Analytics only for the owner insights actually
  implemented. Register Android clients against package and signing certificate
  (local debug, trusted GitHub distribution, Play signing as applicable).
  Use Google Identity authorization for API scopes; sign-in identity alone does
  not authorize YouTube data. Handle no-channel, channel choice, denied scopes,
  expired grants, reconnection and background authorization requiring UI.
- **GitHub:** register a Twidget application and callback. The documented OAuth
  web-code exchange requires a client secret, so use a confidential exchange
  endpoint for the browser-return experience. GitHub device flow is a possible
  secretless alternative, but adds a verification-code step absent from Figma;
  do not silently substitute that UX. Request only access needed for public
  analytics; private repository access is outside the initial scope.
- **Meta:** register the app and Instagram product, determine approved login
  route, account eligibility, scopes, API version, redirect and review/test-user
  requirements before declaring live integration complete. Plan for professional
  Business/Creator accounts; support for ordinary personal accounts is not
  promised. Official developer pages returned HTTP 429 during this investigation,
  so exact current permissions/token requirements must be rechecked at setup.
- Keep client secrets server-side. Use a small OAuth exchange service where
  required; regular analytics should be fetched directly on-device where possible.
  Keep it distinct from public shared-history enrollment and caches. Register
  exact HTTPS callbacks; bind short-lived one-use completion tickets to the
  initiating installation/session, and redeem over HTTPS. Do not put provider
  tokens in redirect URLs, logs or public caches. Use state, expiry and PKCE where
  supported, provider-specific callback validation, cancellation and replay tests.
- Extend Keystore-backed credential storage to per-provider/session credentials;
  exclude tokens from backup and diagnostic exports. Provide disconnect/revoke,
  refresh rotation and reauthorization behavior. Account sessions must remain
  separate when multiple accounts use one provider.
- Describe actual data handling in onboarding and privacy disclosures. A
  confidential OAuth exchange and optional cloud Brief processing make a blanket
  “data never leaves your device” claim inaccurate.

New developer apps, review approvals, configured production secrets, registered
callbacks and consenting live test accounts are external integration gates.
They do not block building the data layer, UI or deterministic provider tests.
Do not deploy the exchange service or label a live OAuth flow verified until its
configuration and end-to-end checks are complete.

## 4. Onboarding implementation

Implement an explicit resumable state machine, with a persisted draft and
provider-specific result handling, rather than extending integer-step conditionals.

New install:

`Welcome → platform → handle/OAuth → add more → link? → display → optional services → widget → done`

Upgrade:

`Introduction → add more (existing X prefilled) → link? → display if linking → optional services → changelog`

Adding an account from Settings reuses these connection steps with a destination
profile; it does not reset onboarding or demand relinking unrelated accounts.

- Offer linking only with at least two eligible accounts. “No” keeps each as
  a standalone profile. Pick name and avatar independently from added accounts;
  support editing the display name and later changing either source in Settings.
- Preserve draft state across rotation/process death/browser return. Reject
  stale callbacks from abandoned attempts and duplicate account additions.
- Failures stay on the relevant step with retry/back/other-platform options.
  Cancellation must not leave a half-created linked profile or change the default.
- Optional Buffer connection and X CSV import operate on the relevant account.
  Show X import only when there is an X member. Widget pinning is optional and
  uses the actual chosen account/platform, with launcher-declined pin handling.
- Upgrade “Check what's new” opens the existing latest-changelog reader.

## 5. Dashboard, settings and scheduling

Refactor `MainActivity`, `MainDrawerController`, `MainSyncController` and binders
to accept profile IDs and a resolved profile snapshot. Keep the existing native
drawer, adaptive columns, chart component, floating toolbar/FAB and edit mode.

For linked profiles, include total audience and eligible platform-specific cards
(Instagram followers, YouTube subscribers, GitHub stars/forks, etc.). Each
platform card has its platform icon, metric label and source timestamp.
Standalone accounts get only supported cards. Existing X top-followers and
post cards remain X-specific. Support partial loading/error/reconnect per member.

Aggregate followers and subscribers only under an explicitly labelled audience
total. It is a sum of platform counts, not unique people. Never add repository
stars, views or engagement percentages to that total. Mark rounded inputs as
approximate. Compare matching member sets and aligned, observed time periods;
linking a new account must not be reported as organic growth. A missing member
makes a total partial/unavailable, not an unexplained drop. Preserve individual
history and do not invent a historical total before comparable samples exist.

Settings uses native preference categories, captions, radio controls and dialogs:

- Platforms & accounts: profile display editor, linked members, add/unlink/remove,
  other standalone accounts, optional Buffer and X analytics import.
- Data and sources: X-specific source configuration, separate logged-in sessions
  with status/reconnect/disconnect, shared-history consent, refresh and cache tools.
- Shared history stays X-scoped initially; never send authorized non-X data to
  legacy public X history endpoints. Bluesky shared history needs a namespaced
  backend route and explicit opt-in before exposing that option.
- Scheduling: replace Twitter-only Buffer channel filtering and username mapping
  with explicit supported platform-account/channel bindings. Offer posting only
  where Buffer supports the platform/media operation; GitHub is not a publishing
  destination. Preserve existing scheduled jobs and local X reminders. Any new
  cross-post composer behavior requires destination-specific validation and
  independent publish status so retrying one destination cannot duplicate others.

## 6. Your Brief

Introduce a profile-scoped `BriefContext` and platform fact collectors. Reuse the
existing deterministic ranking, expiry and AI-provider pipeline. Facts carry
account/platform, metric, comparison window, precision and source timestamp.

- Collect existing X insights, Bluesky activity/growth, GitHub stars/forks and
  available Instagram/YouTube trends; gate each category on capability/data.
- Group settings by connected platform and “All linked platforms”, as annotated.
- Calculate combined audience trends with the same rules as dashboard totals.
  Avoid repeating one change as both a top aggregate and platform insight unless
  the second adds useful detail. Never compare unlike metrics as equivalent.
- Replace username-only cache keys with profile/membership/settings/data-version
  fingerprints. Invalidate on linking, unlinking, permissions and source changes.
- Give AI structured, attributed facts; unsupported or unavailable data remains
  unavailable. Preserve existing cloud/local choices and scheduled-content privacy
  controls. Templates remain useful offline or without AI configuration.
- Brief actions target the correct account/platform/profile and scheduling route.
  Update the Brief activity and widget, not just text-generation prompts.

## 7. Widgets

Persist profile ID and selected member-account ID per widget. Selecting a profile
resolves its valid members; changing profile cannot retain an unrelated platform.
Migrate every existing widget as its original X binding with appearance intact.

- Hide platform selection for standalone profiles; show only linked members for
  linked profiles. Selecting YouTube shows “Subscribers”; others use the correct
  platform metric wording. Use matching icons in previews and actual renders.
- X/Twitter logo choice is visible/applicable only for X. Keep the saved X choice
  when switching platforms. Other platforms have their own actual exported assets.
- Tap action opens the selected platform profile using its canonical URL/app
  routing, with browser fallback. Refresh and Open Twidget target the bound account
  and containing profile. PendingIntent identity must distinguish widget/account.
- Cover home widgets, lock-screen small/wide, Samsung service-box output and the
  Brief widget. Retain RemoteViews/custom artwork where required by widget APIs;
  SESL9 controls apply to configuration UI, not remote host views.
- If a member is unlinked, keep the widget attached to that account under its new
  standalone profile. If removed, show a clear reconfiguration state instead of
  silently displaying another person's stats.

## 8. Delivery order and verification gates

Each milestone should leave a buildable feature branch and a testable debug APK.

1. **Preparation:** branch, CI isolation, this plan and developer setup checklist.
2. **Foundation:** platform/profile repositories, metric contracts and legacy
   migration; regress existing X paths before adding new UI.
3. **Providers:** Bluesky public integration first; GitHub, YouTube and Instagram
   adapters/authentication with offline fixtures and explicit setup states.
4. **Onboarding/accounts:** new, upgrade and add-linked-account flows; profile
   presentation editor and session management.
5. **Dashboard/settings:** profile navigation, per-platform/aggregate cards,
   source controls, refresh behavior and scheduling mappings.
6. **Brief/widgets:** facts, ranking/cache migration, settings categories and all
   platform-aware widget renders/actions.
7. **Integration:** real developer registrations/callbacks, live provider tests,
   signed upgrade tests, minified builds and visual/device acceptance.

Required targeted tests:

- Migration from zero/one/multiple legacy X accounts, duplicate handles, orphaned
  widget pins, cached/imported history and existing Buffer schedules. Repeat and
  interrupt migration; preserve defaults, consent and known/unknown values.
- Same handle on different platforms; renamed handles; duplicate remote account;
  link/unlink/remove/default repair; stable membership and widget references.
- Aggregation with missing/stale/rounded data, changed membership, sparse days,
  incompatible metrics and counters decreasing. No fabricated growth or zeroes.
- Provider parsing/pagination/quotas, OAuth cancellation/state mismatch/replay,
  expired/revoked sessions, partial grants and refresh after account removal.
- Onboarding process restoration, browser return, linking declined, independent
  name/avatar selection and returning-user changelog routing.
- Linked/standalone drawer and settings, independent card refresh failures,
  Brief content gates/cache invalidation/privacy and widget targeting/visibility.
- Both debug distributions: unit tests, APK builds, instrumentation compilation
  and lint. Run actual instrumentation flows on a disposable emulator, including
  installed-version upgrade with seeded real-format persisted data.
- Light/dark, large fonts, TalkBack labels, keyboard/back behavior and phone/tablet
  layouts. Review screenshots against Figma's structure using native SESL9 spacing.
- Check release/beta shrinking before merge; verify Samsung launcher/lock-screen,
  blur, fold transitions and OEM behavior on devices when available.

Do not report the feature complete on fixture-only OAuth checks. Final acceptance
requires live authorized accounts for all four additions plus preserved X behavior.

## Primary API references consulted

- [Google Android authorization](https://developer.android.com/identity/authorization):
  API consent is separate from sign-in; Android clients depend on package/signature.
- [YouTube channel statistics](https://developers.google.com/youtube/v3/docs/channels):
  subscriber counts are rounded down to three significant figures; visibility is
  explicit. Counts must retain this precision in charts and Brief.
- [GitHub OAuth authorization](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps):
  code exchange requires a secret; device flow avoids it but changes the flow.
- [GitHub users API](https://docs.github.com/en/rest/users/users): public profile
  metrics and authenticated-user behavior.
- [Bluesky profile lexicon](https://github.com/bluesky-social/atproto/blob/main/lexicons/app/bsky/actor/getProfile.json):
  profile lookup accepts an AT identifier and returns a detailed profile view.
- [Meta's Instagram introduction](https://www.linkedin.com/posts/meta-for-developers_introducing-instagram-api-with-instagram-activity-7226980956711063552-CD7p):
  professional-account integration. [Current Meta documentation](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/)
  needs revalidation during setup because access was rate-limited in this session.
