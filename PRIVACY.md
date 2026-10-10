# Twidget Privacy Policy

**Effective date:** 26 July 2026
**Last updated:** 19 September 2026

This Privacy Policy explains how **Twidget** (`com.tjg.twidget`) accesses,
uses, stores, transmits, and deletes information. Twidget is developed and
published by **That Josh Guy**.

Twidget is an independent app for viewing public X/Twitter account statistics,
displaying widgets, importing account analytics, finding top followers, generating an optional Your Brief summary, and
optionally preparing or scheduling posts. It is not affiliated with X Corp.,
Buffer, Cloudinary, Google, Samsung, or the other services named below.

## Summary

- Twidget does not sell personal data.
- Twidget contains no advertising or cross-service behavioural tracking.
  Google's ML Kit SDK collects diagnostic and usage information as described
  below; the app does not include a separate crash-reporting service.
- Most app data is stored only on the device. Android cloud backup and
  device-to-device transfer are disabled for Twidget's private data.
- Network features contact the relevant service provider. Optional Gemini Cloud
  writing sends Brief content to Google; ML Kit also sends SDK diagnostics.
- Contributing public account history to the Twidget bridge is optional and is
  off by default.
- Twidget does not create a Twidget user account. Users may optionally connect
  an existing Buffer account for scheduling.

## Information stored on the device

Twidget may store the following in app-private storage:

- configured public X/Twitter usernames;
- app, dashboard, schedule, and widget preferences;
- public profile information and statistics returned by the selected provider,
  including display name, username, avatar URL, follower and following counts,
  post counts, likes, verification status, and protected-account status;
- local history samples and cached recent-post analytics;
- imported X Analytics movements and metrics;
- cached Top Followers rankings and follower lists;
- Your Brief preferences, generated summaries and cards, goals, and streaks;
- locally created post drafts, reminders, publishing state, and references to
  media explicitly selected through Android's system picker;
- optional self-hosted bridge settings and local diagnostic logs enabled
  through the hidden debug menu.

Optional official X API credentials, self-hosted bridge tokens, cached bearer
tokens, Buffer OAuth tokens, a user-supplied TwitterAPIs key, and a Gemini API
key are protected
with Android Keystore-backed encryption.

Local data remains until it is replaced, removed using the relevant app
control, cleared through Android, or removed when the app is uninstalled.
Removing a tracked account from Twidget removes its app-managed local profile
and history. Cached images and other temporary files may be removed by Android
or by clearing the app's cache.

## Information transmitted off the device

The information sent depends on the feature and data source chosen.

### Public X/Twitter account lookups

To retrieve public account information, Twidget sends the configured public
X/Twitter username to one or more of the following, depending on the selected
source and fallback configuration:

- FxTwitter/FxEmbed;
- the maintainer-operated Twidget bridge;
- a bridge URL configured by the user;
- X's official API using credentials supplied by the user; or
- TwitterAPIs using a user-supplied key.

These requests return public profile fields, statistics, posts, and media
needed for the dashboard and widgets. Providers also receive ordinary network
information such as the IP address and user agent.

### Shared history and Top Followers

Shared history is optional and off by default. When enabled, the
maintainer-operated Twidget bridge may store:

- the tracked public X/Twitter username;
- public follower, following, post, and like counts;
- sample dates, timestamps, and metric provenance;
- verified movements derived from an X Analytics import, including dates and
  follow/unfollow counts; and
- the latest completed Top Followers scan for that public account, including
  the public names, usernames, account IDs, follower counts, verification
  state, avatar URLs, mutual-follow state when the provider supplies it, and
  scan metadata for the public accounts in the follower list; and
- the current Hall of Fame post and scan progress, including public post text,
  links, media URLs and alt text, engagement metrics, timestamps, and author
  details. The bridge stores this in the shared account metadata.

This information is pooled so participating Twidget users can receive genuine
historical samples or reuse a completed public ranking. Ordinary direct
FxTwitter lookups do not register an account in the shared-history pool.
Private accounts are not accepted into the pool.

The bridge may use PostgreSQL for history and Redis for short-lived caches,
request limits, registration limits, and scheduled-job locks.

### X Analytics CSV import

The selected CSV file is opened and parsed on the device. Twidget does not
upload the original file or its file name. When a bridge-backed import is
active, Twidget sends the tracked username and parsed dates and follow/unfollow
movements to the configured bridge for validation. The original CSV and other imported metrics are not sent to the bridge.
Derived facts included in Your Brief may be sent to Gemini Cloud if enabled,
as described below.

### Top Followers and post analytics providers

Top Followers requires shared history to be enabled. Requests send the selected
public username to the Twidget bridge. The bridge reuses a recent completed
scan when available or sends the username to TwitterAPIs using a server-held,
rate-limited provider key. Completed lists are retained for up to 30 days by
default and can be viewed by other opted-in Twidget installs tracking that
public handle. Twidget does not run Top Followers scans on the device. Turning
off shared history stops new bridge requests; previously cached rankings can
still be viewed on the device.

Personal TwitterAPIs keys and official X API credentials remain available for
optional profile requests, and a personal TwitterAPIs key can also be used for
post analytics. These requests send the username directly to the selected
provider. Personal provider keys remain encrypted on the device and are not
sent through the Twidget bridge.

### Buffer scheduling and Cloudinary media

Buffer integration is optional. Twidget does not create a Twidget account.
When the user connects an existing Buffer account, Buffer's OAuth service
processes the sign-in and consent flow. Twidget stores the resulting access and
refresh tokens in encrypted app-private storage and uses them to retrieve the
Buffer account, organisations, X channels, drafts, and scheduled-post state.

When the user explicitly saves or schedules a Buffer post, Twidget sends
Buffer:

- the selected Buffer organisation and X channel identifiers;
- post or thread text;
- the requested publishing time;
- draft or scheduling state; and
- public media URLs for any attachments.

Buffer's API requires attachments to be reachable by public URL. Images or
videos explicitly selected for a Buffer post are therefore uploaded to the
configured Cloudinary account using its unsigned upload preset. A release may
provide a maintainer-operated Cloudinary configuration, and users can replace
it with their own Cloudinary cloud and preset. The upload includes the media
content, file name, and media type. Cloudinary returns a public URL that is
then sent to Buffer.

Cloudinary-hosted media may remain available after a Buffer post or local draft
is removed. For media uploaded to the maintainer-operated Cloudinary account,
use the deletion process below. If the user supplied their own Cloudinary
configuration, they control deletion and retention in that account.

Local-reminder drafts and attachments are not uploaded to Buffer or Cloudinary.

### Your Brief, Gemini Nano, and Gemini Cloud

Your Brief is optional and is off until enabled. Twidget builds a factual
summary and cards from the selected account's statistics, history, public post
performance, Top Followers, goals, streaks, and scheduling state. It stores the
result on the device and may ask an AI model to rewrite that copy.

On supported devices, Gemini Nano performs this rewriting on the device through
Google's ML Kit and Android AICore. Brief prompt content is not sent to Gemini
Cloud in Local mode. This does not turn off the SDK diagnostics described below
or network requests used to retrieve account data.

Cloud mode requires a Gemini API key supplied by the user. Twidget sends the
Brief summary and card titles and bodies, card identifiers and priorities, and
language instructions directly to Google's Gemini API over HTTPS. This can
include public names or handles, account metrics, goal and streak information,
and summaries of scheduling activity. It does not upload the original analytics
CSV, attachments, or the full local draft database. The API key is sent only to
Google for these requests and is encrypted in app-private storage. Requests do
not pass through the Twidget bridge.

Auto mode prefers on-device rewriting and can fall back to Gemini Cloud when a
key is configured. Selecting Local mode, removing the Gemini key, or disabling
Your Brief stops future cloud rewriting. Removing a key does not delete prior
requests held by Google.

Google's treatment of prompts, responses and service logs depends on the API
account, region and service terms. Unpaid-service content may be used for product
improvement and reviewed by people where Google's terms permit; paid-service
content is not used for product improvement but may be retained for abuse
prevention. Review the [Gemini API terms](https://ai.google.dev/gemini-api/terms)
and [Google Privacy Policy](https://policies.google.com/privacy) before enabling
cloud writing. Do not include sensitive information in content sent to unpaid
services. Requests to exercise rights over Google-controlled information should
be directed to Google.

### Updates, images, and external links

- GitHub-distributed builds check GitHub for updates and download APKs from
  matching release assets. Google Play builds use Google Play for app updates
  and do not offer APK downloads or installation. Both distributions may fetch
  release notes from GitHub when the Notices screen is opened.
- Profile and post images may be downloaded from URLs returned by a provider
  or from Unavatar and cached on the device.
- Links deliberately opened by the user are handled by the selected browser or
  external app, which applies its own privacy practices.

## Operational and device information

The maintainer-operated bridge uses the request IP address as a short-lived
rate-limit key to prevent abuse and protect service availability. Hosting
providers may process request timestamps, routes, network information, errors,
and health data for operation and security. Twidget does not use IP addresses
to infer the user's location.

The Android app's hidden debug log is disabled by default, stored only on the
device, and never uploaded automatically.

Google's ML Kit GenAI SDK also sends device and app information, identifiers,
performance measurements, feature events and errors, input/output sizes, API
configuration and configured languages to Google for SDK diagnostics and usage
analytics. These are SDK operational records, not the on-device Brief prompt
text. SDK checks can occur when opening Brief setup or settings, before a Brief
is generated; there is no separate in-app switch for SDK telemetry. See
[Google's ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

## How information is used

Information is used only to:

- provide dashboards, widgets, history, post insights, provider access,
  scheduling, media upload, Your Brief, and distribution-specific update features;
- authenticate requests to services the user has configured;
- support Google SDK diagnostics and usage analytics as described above;
- maintain optional shared public history and reusable public rankings;
- validate imported history and protect data accuracy;
- cache results, apply provider limits, diagnose faults, secure the bridge, and
  prevent abuse; and
- respond to privacy and deletion requests.

Twidget does not use information for advertising or cross-service behavioural
tracking.

## Sharing and service providers

Information may be processed by the following categories of recipient:

- public-profile and analytics providers selected by the user, including
  FxTwitter/FxEmbed, X, TwitterAPIs, and a configured bridge operator;
- Buffer, when the user connects Buffer or explicitly uses remote
  draft/scheduling features;
- Cloudinary, when the user attaches media to a Buffer post;
- Google, for optional Gemini Cloud processing and ML Kit SDK operations;
- GitHub, for release notes and, in GitHub builds, update checks and downloads;
- Unavatar and image hosts, when the app retrieves an image; and
- infrastructure providers used to host the maintainer-operated bridge and its
  storage.

Each third-party service processes information under its own terms and privacy
policy. Twidget does not sell personal data or provide it to data brokers.

## Security

Built-in services use HTTPS to encrypt information in transit. Android blocks
cleartext traffic for Twidget's target SDK by default. Sensitive credentials
stored by the app are protected using Android Keystore-backed encryption.
App-private cloud backup and device transfer are disabled. The bridge applies
input validation, bounded request bodies, rate limits, access controls for
administrative functions, and restricted retention controls.

No method of transmission or storage is completely secure. Users should not
submit X passwords, private-account content, or unrelated secrets to the
Twidget bridge.

## Retention

- **On-device data:** retained until removed in the app, cleared through
  Android, replaced by newer cached data, or deleted on uninstall.
- **Shared bridge history:** retained according to the bridge operator's
  configured sample-retention and inactive-account rules. The operator may
  retain history until a deletion request when automatic limits are not
  configured. A public account that becomes private is removed from the pool.
  Hall of Fame post data is retained in account metadata until replaced or the
  account is deleted; it has no separate post-expiry timer. Turning off sharing
  or removing an account locally does not delete its shared bridge records.
- **Bridge caches and request limits:** retained only for their configured
  cache or rate-limit window. Hosting-platform logs may follow the hosting
  provider's operational retention settings.
- **Buffer content:** retained under the user's Buffer account and Buffer's
  policies until deleted or otherwise expired there.
- **Cloudinary media:** retained by the configured Cloudinary account until
  deleted by that account's operator. It is not automatically deleted when a
  Twidget draft or Buffer post is removed.

Google controls retention of Gemini service records and ML Kit diagnostics
under its applicable terms and policies. Clearing Twidget data or uninstalling
the app removes its local copies, but does not erase records held by Google.

Limited records may be retained when reasonably necessary to comply with law,
resolve disputes, prevent abuse, or enforce applicable agreements.

## Your choices and rights

Users can:

- use direct FxTwitter mode without joining shared history;
- turn shared-history contribution on or off in Settings;
- choose a different data provider or a self-hosted bridge;
- use local reminders instead of Buffer;
- disable Your Brief, select Local writing, or remove a Gemini API key to stop
  future cloud rewriting;
- disconnect Buffer and remove its local OAuth tokens;
- remove a tracked account and its local history;
- clear cached app data through Android; and
- request deletion of information controlled by the maintainer.

Depending on local law, users may also have rights to access, correct, object
to processing of, restrict, or receive a copy of their personal information.

## Data deletion requests

Twidget does not create user accounts. To request deletion of a public
X/Twitter account's shared bridge history, stored Top Followers scan,
related bridge metadata, or media uploaded to the maintainer-operated
Cloudinary account, contact **That Josh Guy** through
[support@tjg.gg](mailto:support@tjg.gg).

Identify the X/Twitter username to remove and, for a Cloudinary attachment,
provide its public Cloudinary URL. Do not send passwords, API keys, OAuth
tokens, or bridge administration tokens. The maintainer may ask for reasonable
information to locate the data and prevent fraudulent deletion requests.

Deleting information from Twidget's bridge does not delete the corresponding
public X/Twitter account or data held independently by X, Buffer, Cloudinary,
or another provider. Requests concerning a user-configured service or account
must be directed to that service's operator.

## Children

Twidget is not designed for children and does not knowingly collect personal
information from children. If you believe a child has provided personal
information to a maintainer-operated service, use the contact method above to
request its deletion.

## Changes to this policy

This policy may be updated when Twidget's features, providers, or legal
obligations change. The effective date at the top will be revised when a new
version is published. Material changes will be described through the app,
release notes, or the published policy page where appropriate.

## Contact

The privacy contact and data controller for maintainer-operated Twidget
services is:

**That Josh Guy**

Support and privacy requests: [support@tjg.gg](mailto:support@tjg.gg)

General contact: [email@tjg.gg](mailto:email@tjg.gg)

Website: [https://tjg.gg](https://tjg.gg)

For the quickest handling, state that the request concerns **Twidget privacy**
and include only the information needed to identify the relevant data.
