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
- Keep local history and account settings when an X handle changes
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

Changed your X handle? Twidget saves the permanent X account ID during profile sync and can resolve a missing or recycled handle to the same account. For older accounts with no saved ID, open **Settings → Accounts**, long-press the account, and choose **Change handle**. Enter the new handle for that same person to move local history, goals, widgets, imported analytics, streaks, cached results and retained legacy drafts together. Accounts that already have data under the new handle—including leftover goals or cached data—are kept separate to prevent overwriting either account. Migrations resume after an interruption, and late local writes follow the new handle. Shared-history reads require a matching permanent account ID. With shared history enabled, bridge transfers are queued and retried while offline; legacy bridge records with no saved ID require an administrator-verified transfer.

The **Your Top Followers** dashboard card uses the shared-history bridge in both GitHub and Play builds. Enable shared history under Settings → Data and sources, then tap **Find top follower**. The bridge reuses a fresh completed scan or scans the public account through [TwitterAPIs](https://twitterapis.com), then refreshes enrolled accounts daily. Completed lists are shared with other opted-in installs tracking that handle. Device-side follower scans and their foreground service have been removed. Existing rankings remain cached when upgrading or disabling shared history. Personal provider keys remain available for profile and post analytics. Protected accounts are not supported.

> Shared history is opt-in. The [`bridge/`](bridge/) stores only accounts explicitly registered through the history route. Normal profile lookups do not create persistent records. 

## Brief post comparisons

Brief narratives are generated by the selected local or cloud model from
measured evidence, structured metrics and account context. All card descriptions
appear above the cards; their measured values are rendered separately. A complete
model response is required before publishing new copy. The last successful title,
subtitle and compact description persist independently of the generation cache
and stay available through routine data refreshes, expiry and failed attempts.
The dashboard and widget retain that copy while updating. The Brief page keeps
generation full screen, without showing its previous title or subtitle. First-time
failures show setup/retry; later failures restore the saved summary with a retry
area beneath it.
No partial response or template text becomes an AI-generated summary. Local generation
adapts each request to the model’s combined input/output token limit. It uses
short model-written text fields with card-specific evidence, validates and repairs
each field before continuing, and negotiates rejected output budgets. Equivalent
numeric formatting is accepted without allowing changed values, signs or percentages. Successful
settings are scoped to model/mode and AICore version with a short cache lifetime;
busy or quota-limited runtimes back off instead of retrying smaller requests.

Twidget keeps up to four recent successful Brief runs per account in its private
local cache (four hours for Nano, two hours for cloud). While the app is visible,
it quietly prepares the enabled default account's Brief using already cached
measurements and the selected AI provider. It pauses when the app leaves the
foreground, shares the generation queue with the Brief screen, and backs off
after failures. Preserved display copy never suppresses regeneration or makes
outdated measurements fresh. A completed new Brief replaces the saved summary,
updates the dashboard immediately and refreshes the widgets. Display memory stays
account-scoped, follows handle changes, and respects language and content switches.
Opening Your Brief reuses a matching run; returning to a recent
content configuration can reuse its earlier result. Changed evidence, language,
provider or eligibility still receives validation, and explicit refresh requests
new generation. This preparation does not analyse draft text or attachments.

Your Brief also includes ordinary-day context when no exceptional growth or
standout event qualifies. Follower context reports the change between actual
recorded dates, including flat or negative changes. Partial histories use their
actual dates; seven-day growth needs a known, real sample from seven days ago.
Recent-post context sums only the valid, distinct original posts included in the
snapshot, labels sampled data, and describes lifetime counts so far rather than
implying that those views were earned this week. These context cards use the
existing Followers and Top tweet content switches.

Relative standouts qualify at 1.5 times the median views or engagements of at
least two other posts of similar age, with at least 100 views or five engagements
in the supporting metric. Absolute breakout posts and replies still qualify.
The preview always follows the post selected for its explanation. Context and
standout snapshots older than two days are omitted until refreshed.

Your Brief compares each post with at least two distinct original posts from the
same account whose ages, at the analytics snapshot time, are within a factor of
1.5. It uses the peers’ median observed counts and leaves the subject out of its
own baseline. Quiet-post advice also requires at least 24 hours of observed
counts and lower views and engagements; waiting does not turn fresh cached
counts into mature measurements. Older caches without individual posts cannot
supply this comparison.

Posting-time and media suggestions use median views in the largest group of
posts with comparable ages, with at least two posts per compared category.
These are suggestions for experiments, not explanations of X’s ranking or
predictions of final reach. No historical counts are reconstructed from feed
ranking weights, and no additional collection or transmission is introduced.

## Your tweet assistant

The unlocked Brief debug menu groups UI previews, AI diagnostics and engine
ranking into native settings pages. Tweet Reviews adds synthetic loading,
completed, refreshing and error previews; selected tweet/draft evidence and cache
inspection; policy checks; an explicit cloud-provider test; and an attempt log.
Previews and policy checks do not call a model or replace saved reviews. The
provider test sends the selected source, history and media only when tapped and
does not save its advice. The bounded attempt log records metadata and validation
codes, never source text, generated advice, media bytes or API keys.

Your Brief speaks to you directly and uses digits for statistics. Goal cards keep
their native titles and measured current/target progress; AI descriptions appear
above them and cannot replace the goal labels.

Enable **Tweet reviews** at the top of **Settings → Your Brief → Manage content**. Open a draft for feedback on its opening, question,
context, repetition and patterns in your available history. Open a recent tweet
or an engaged reply for a review with separate **Evidence**, **Possible
explanation**, **Try next** and **What we cannot tell** sections. Draft feedback
can open the original draft for editing. Suggested rewrites open a new, unsaved composer
session, preserving the other draft entries and attachments. Published-video
previews are not copied as original video uploads.

Tap **View report** on the dashboard’s all-time best, best or worst tweet card,
or **View review** on a Brief tweet or draft card, to open the same dedicated review page.
Dashboard reports remain available when review previews are hidden in Manage content.
Older all-time tweets use saved lifetime totals without a weekly comparison; when
their original measurement date is unknown, the report says so. The
original post remains visible above the AI loading spinner, and generated cards
fade into place. Reviews provide adaptive feedback on the post's meaning, audience
context and possible edits. AI suggestions quote the passage being edited,
explain the reason, validate references to supplied evidence or comparison tweets internally, and
include a rewrite where useful. Missing facts become labelled fill-in details
or a question; the assistant must not invent what was shipped. Measured evidence
is kept separate from AI explanations, and failed generation does not show fixed
writing advice as AI feedback.

Suggestions use plain-language references without internal source IDs or an
“Evidence used” block. Possible explanations sit in a rounded card. Feedback uses
the app language; rewrites keep the original tweet or selected thread entry's
language. Opening a saved review in a different app language starts regeneration
and hides the old-language feedback, including if regeneration fails. Concurrent
viewers share work only when their languages match.
Confident system language detection supplies rewrite-language hints for each own
thread entry. The generator retries a rewrite detected in a different language;
uncertain or mixed-language text keeps the original-language preservation instruction.

Each review preview and its description form an independent item in the Brief’s responsive
columns. **Review your recent tweets** is a compact illustrated card after the
previews. It opens the full review library, including the visible previews,
drafts and older saved reviews, on a separate page.
Brief pages use SESL9 floating navigation controls above the custom top gradient.
Controls start flat, gain native floating surfaces on scroll and return flat at the
top. Scroll viewports extend through the system navigation area with safe bottom padding.
The review library opens with an expanded native heading. Tweet reviews open
collapsed; a downward swipe expands the heading and a second pull refreshes the review.

Attached images and video previews/frames supply context to the model. At most
four bounded images are included; video motion and audio are not analysed.
Each result lists which attachments were inspected, omitted or unavailable.
The AI can select interaction breakdowns, age/format comparisons and a recorded
engagement chart when those panels are supported by the supplied evidence. Chart
values come from actual analytics observations stored locally in the existing
provider/account cache, bounded to 24 posts and 32 observations per post over seven
days. Observations within six hours share one sample. Missing days are never
filled in, and cumulative totals are not described as daily gains. No extra
network collection is scheduled for this history.
Draft thread entries are kept in order so text or media later in a thread can
answer a question raised by its opening. Rewrites remain suggestions; they do
not overwrite drafts.

Tweet and draft reviews require a saved Gemini API key and use cloud AI in **Auto**
or **Cloud** mode. **Local** keeps Brief generation on Gemini Nano without a key
or cloud fallback; review generation asks you to choose Auto or Cloud first.
Saved reviews can still be read in Local mode. Review analysis sends the selected
post/draft text, bounded attached images or video previews and selected public
history to Google; this is disclosed before generation. Successful reviews are
saved locally with their post, evidence, statistics and suggestions. Fresh reviews
reopen without another AI call. Draft advice also survives app restarts in a private
cache matched to the draft revision, evidence and app language, bounded to 32 drafts
per account for up to 7 days. Changing those inputs or manually refreshing requests
new advice. Reviews check for updated evidence when opened after 4 hours for a
tweet reviewed during its first day, 12 hours during its first week, or 48 hours
thereafter. Unchanged evidence reuses the successful review and renews the check
interval, keeping the original review date and measured snapshot. An open foreground
page also checks for expiry. Pull to refresh (or
use the toolbar refresh action) to fetch updated analytics and request a new
review immediately. If fresh measurements are unavailable, or generation fails,
the previous successful review stays available with its original timestamp and
statistics. Failed automatic attempts back off for 15 minutes; manual refresh can
retry sooner. Concurrent viewers share an attempt, which can finish and save
after the page closes. The compact tweet preview gently rocks while generating
and settles with the completed cards; reduced motion disables these animations.
The footer info action explains what cannot be inferred from the evidence.
Review explanations and reasons use typed measurement references: the app supplies
the figure, metric, locale and historical tweet attribution. Explicit literal performance
counts are rejected for new responses; older saved responses are checked against the
correct metric and source. Product and version numbers in media remain usable context.
Manual draft refreshes retain matching saved advice if regeneration fails. If a published
review has no usable analytics cache, the app refreshes it before declaring the tweet
unavailable. A shared 90-second review budget includes queueing, media loading and
provider retries. Draft thread rewrite previews show the selected
entry and its media, and the composer preserves every other entry.
On the main Brief, review previews show how long ago a tweet or reply was posted.
Before a successful review they invite you to review it; afterward their description
summarises the review’s recorded views and public interactions, with a cautious
comparison only when at least three matching tweets at similar ages are available.
Successful reviews update visible descriptions without another Brief generation
or changing card order. Returning to the Brief refreshes the copy from local storage.
Quoted tweets are included as a separate attributed source when supplied by the
selected data source, including bounded text and media. They can answer context
questions, but their wording cannot become the user's rewrite target and their
counts are never added to the user's performance. Only one quoted level is kept;
quote media share the four-attachment inspection budget, and unseen or truncated
source content is explicit. The app shows that source in a filled, rounded inset preview after the main tweet’s
media, with its own avatar, author and date when supplied. Tapping the outer card
opens the original tweet; tapping the inset opens the quoted tweet. The separate
View review button opens the review. Both touch ripples follow their card’s rounded
corners, and quote-card actions sit below the quoted content.
Opening a suggested rewrite in the composer retains the quoted source link without
copying the source author’s images as your own attachments.
Saved reviews remain in **Review your recent tweets** after leaving the timeline;
refresh needs the data source to return that tweet again. Removing an account
removes its saved reviews; a handle change preserves them. Errors distinguish
Local-only mode, a missing key, authentication, quota, connectivity, timeouts,
provider failures and invalid AI responses.
Draft feedback uses a memory front cache and a bounded local database,
invalidated by changed draft/history inputs or language; draft text is excluded
from persisted Brief card caches and widgets. History examples are selected by wording overlap and recency,
including quiet posts, and are not evidence of a winning pattern. The feature
uses the existing analytics snapshot, not a trained copy of X's ranking model.
Similar-age, same-format comparisons require three other posts;
replies are compared only with replies. Counts observed before 24 hours do not
receive underperformance judgments, and missing views or shortened previews
limit the analysis. Available replies with at least ten public interactions
(likes, replies, retweets and quote tweets) can be reviewed without changing
original-post aggregates or streaks. Public text is retained up to 10,000
characters for coaching, separately from the 180-character preview. Older bridge
responses remain readable and receive a preview-only review until refreshed
from a source that supplies complete text and reply metadata.

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

Stable and beta use `com.tjg.twidget`. Local debug builds install separately as
**Twidget Debug** (`com.tjg.twidget.debug`), with an inverted-palette launcher icon (blue bars on a white-to-pale-blue gradient)
and the development signing key only. Debug builds do not use the APK updater.

Install or update the debug app while keeping its data:

```bash
./gradlew :app:installGithubDebug
```

Or sideload a built APK explicitly:

```bash
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

Stable and debug have independent app data and widget instances.

Tweet composing and scheduling have been retired. Twidget no longer connects to
Buffer or uploads media to Cloudinary. Compose and schedule tweets in X. Manage
any posts already queued with Buffer directly in Buffer; this update does not
cancel them. Existing local draft archives are retained. Your Brief no longer
uses scheduling data, and Tweet Report provides analysis and advice without
generating tweet rewrites.

GitHub Packages credentials belong in
`~/.config/twidget/github.properties`; start from
[`github.properties.example`](github.properties.example) and keep the populated
file outside the checkout. Production signing material is never required for a
contributor build.

The stable version is set in `version.properties`. Debug APKs use
`<version>-dev.YYYYMMDD.HHmm`, recording the build's start time in Europe/London,
including local uncommitted changes. For example: `1.3.0-dev.20261010.2130`.
The debug package uses fixed version code `100300198`, so `adb install -r`
updates the existing debug app and preserves its data. Rebuilds started in the
same minute share a label.

Beta APKs keep `<version>-beta.N`; the Pre-release workflow derives `N` from
existing beta tags. Changing the stable version resets beta numbering to `.1`.
Use `-PbetaNumber=N` when a non-Git build needs an explicit beta number.

## Project policies

- [Security policy](SECURITY.md)
- [Privacy and data handling](PRIVACY.md)
- [Contributing](CONTRIBUTING.md)
- [Localisation guide](docs/LOCALISATION.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Maintainer release process](docs/RELEASING.md)

Banger and Best tweet this week rankings include your own replies, so a breakout reply can win. Worst tweet, weekly averages, and posting streaks use original posts only; replies and reposts do not enter those baselines. Updated Banger scans revisit older pages while keeping the previous winner until a stronger post is found.
