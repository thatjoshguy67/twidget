# Google Play Data safety audit

Updated against the Android app and bundled bridge source on 13 September 2026,
including Your Brief, ML Kit GenAI, and the Play distribution.
The accompanying `data-safety.csv` is based on Google Play's official sample
CSV downloaded on 26 July 2026.

## Recommended top-level answers

- Does the app collect or share required user data types? **Yes**
- Is all collected user data encrypted in transit? **Yes**
- Can users request deletion? **Yes**
- Supported Twidget account-creation methods: **The app does not allow users
  to create an account**
- Can users connect an account created outside the app? **Yes — Other**
  (an optional existing Buffer account connected through Buffer OAuth)

The deletion answer relies on the deletion-request route documented in
`PRIVACY.md`: users contact the maintainer at `support@tjg.gg` and identify the
X/Twitter username to remove from the maintainer-operated shared bridge.
The CSV supplies the public privacy policy and deletion instructions at
`https://tjg.gg/blog/twidget-privacy-policy`.

Connecting Buffer does not create a Twidget account. Buffer owns the external
account and its sign-in flow; Twidget receives delegated OAuth access only for
the optional scheduling feature.

## Selected data types

| Play data type | Handling | Ephemeral | Required? | Purpose | Code basis |
| --- | --- | --- | --- | --- | --- |
| Name | Collected and shared | No | Optional | App functionality | Shared Top Followers lists contain public names; Brief cards can also send these names to Gemini Cloud. |
| Personal identifiers | Collected and shared | No | Required | App functionality | The configured X/Twitter account name is sent to the selected profile provider and may be retained by the opt-in shared-history bridge. Automatic refreshes mean provider transfers are not always a single user-initiated sharing action. Buffer account/channel identifiers are also used when that optional integration is enabled. |
| Photos | Collected | No | Optional | App functionality | A user-selected image attached to a Buffer post is uploaded to Cloudinary and retained for Buffer to fetch. |
| Videos | Collected | No | Optional | App functionality | A user-selected video attached to a Buffer post is uploaded to Cloudinary and retained for Buffer to fetch. |
| Contacts | Collected and shared | No | Optional | App functionality | Google's definition includes social-graph usernames. With shared history enabled, the public Top Followers social-graph ranking can be stored in the shared bridge. A Brief card can send a public follower name/identifier and ranking context to Gemini Cloud. |
| Other user-generated content | Collected | No | Optional | App functionality | Post and thread text explicitly saved or scheduled through Buffer is transmitted to Buffer. Shared history also retains the current public Hall of Fame post text and metadata on the bridge. Local-reminder drafts remain on-device. |
| Device or other identifiers | Collected | No | Required | Analytics; fraud prevention, security and compliance | Bridge IP rate limits and ML Kit GenAI identifiers used for diagnostics and usage analytics. |
| Diagnostics | Collected | No | Required | Analytics | ML Kit reports device/app configuration, latency, event errors, feature versions, input/output sizes, and configured languages. |
| Page views and taps / app interactions | Collected | No | Required | Analytics | ML Kit records SDK feature events such as initialization, model downloads, and generation. These are SDK usage events, not a custom screen/tap tracking system. |
| Other app activity | Collected and shared | No | Optional | App functionality | Gemini Cloud receives factual Brief summaries/cards with statistics, goals, streaks and scheduling summaries when cloud writing is enabled. |

Names, personal identifiers, contacts, and other app activity are marked shared.
Recurring provider lookups and automatic cloud Brief generation are not treated
as a single user-initiated sharing action. Cloud can use a user-supplied unpaid
Gemini account; where its terms permit, Google may use prompts/responses for its
own product improvement. The CSV conservatively covers that supported path
rather than assuming every Gemini request qualifies for the service-provider
exception. Only the data actually included in summary/card text and IDs is sent;
the full local database, CSV and attachments are not sent to Gemini.

Buffer and Cloudinary still receive content only for user-selected remote
save/schedule and attachment actions. Their existing sharing exceptions remain.
ML Kit telemetry is collected, with Analytics as its purpose; Google's SDK
disclosure says it is not transferred to third parties. SDK telemetry is marked
required because the app has no separate telemetry opt-out and model capability
checks can run before Brief setup is complete. It is not declared ephemeral,
because Google retains operational records beyond a single request.

Sources: [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
and [Gemini API terms](https://ai.google.dev/gemini-api/terms). The maintainer does
not control Google retention or deletion; the privacy policy explains this.

## Intentionally not selected

- **Location:** no location permission or location API; the bridge does not use
  IP addresses to infer location.
- **Email address:** Buffer returns the connected account email to the app, but
  the app does not transmit that email off-device.
- **Financial, health, messages, audio, calendar:** no corresponding access or
  transmission.
- **Files and docs:** the X Analytics CSV is read locally. When bridge-backed
  import is active, the app transmits parsed dates and follow/unfollow counts,
  not the file or its metadata. Derived Brief facts may additionally reach Gemini Cloud.
- **Search history:** no search-history collection.
- **Crash logs and other performance data:** no separate crash-reporting SDK.
  ML Kit operational errors/latency are covered under Diagnostics above.
  Hidden Twidget debug logs stay on-device and are not uploaded automatically.
- **Installed apps:** package visibility queries are used locally to resolve
  browsers, X, and Samsung Gallery; results are not transmitted.

## Hall of Fame storage

With shared history enabled, the bridge retains the current public Hall of Fame
post in account metadata: text, links, media URLs/alt text, engagement metrics,
timestamps, author details, and resumable scan progress. It is replaced by a
new winning post or removed with the shared account, with no independent post
expiry. Removing an account in the app clears local data, not shared records.
See the policy for the operator deletion process.

## Security and retention evidence

- All built-in endpoints that receive disclosed data use HTTPS. Android's
  target-SDK cleartext default also blocks an unconfigured HTTP self-hosted
  endpoint.
- Android cloud backup and device transfer are disabled for app-private data.
- API keys and OAuth credentials are protected with Android
  Keystore-backed encryption on-device.
- Shared-history retention is operator-configurable. A protected bridge
  administration endpoint permanently deletes a username's stored history,
  samples, and metadata.
- Local account deletion removes app-managed local profile/history, and
  uninstalling removes app-private data under normal Android behaviour.

## Evidence locations

- `app/src/main/AndroidManifest.xml`
- `app/build.gradle.kts`
- `app/src/main/java/com/tjg/twidget/bridge/HistoryPool.kt`
- `app/src/main/java/com/tjg/twidget/followers/TopFollowersBridgeCache.kt`
- `app/src/main/java/com/tjg/twidget/schedule/BufferClient.kt`
- `app/src/main/java/com/tjg/twidget/schedule/BufferMediaUploader.kt`
- `app/src/main/java/com/tjg/twidget/analytics/AnalyticsImportActivity.kt`
- `app/src/main/java/com/tjg/twidget/data/SecureCredentialStore.kt`
- `bridge/src/server.js`
- `bridge/src/infrastructure.js`
- `app/src/main/java/com/tjg/twidget/brief/BriefAi.kt`
- `app/src/main/java/com/tjg/twidget/brief/BriefEngine.kt`
- `app/src/main/java/com/tjg/twidget/brief/BriefSettingsStore.kt`
- `PRIVACY.md`

## Submission note

Importing a CSV overwrites answers already entered in the form. Review the
store-listing preview after import. If Play Console has changed its schema and
rejects this file, export a fresh blank/current CSV from that app's Data safety
page and transfer the same answers above into that export.
