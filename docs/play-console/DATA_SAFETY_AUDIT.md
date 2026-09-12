# Google Play Data safety audit

Audited against the Android app and bundled bridge source on 26 July 2026.
The accompanying `data-safety.csv` is based on Google Play's official sample
CSV downloaded on that date.

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
| Name | Collected | No | Optional | App functionality | An opted-in bridge scan stores the public names in the latest completed Top Followers list so participating installs can reuse it. |
| Personal identifiers | Collected and shared | No | Required | App functionality | The configured X/Twitter account name is sent to the selected profile provider and may be retained by the opt-in shared-history bridge. Automatic refreshes mean provider transfers are not always a single user-initiated sharing action. Buffer account/channel identifiers are also used when that optional integration is enabled. |
| Photos | Collected | No | Optional | App functionality | A user-selected image attached to a Buffer post is uploaded to Cloudinary and retained for Buffer to fetch. |
| Videos | Collected | No | Optional | App functionality | A user-selected video attached to a Buffer post is uploaded to Cloudinary and retained for Buffer to fetch. |
| Contacts | Collected | No | Optional | App functionality | Google's definition includes social-graph usernames. With shared history enabled, the public Top Followers social-graph ranking can be stored in the shared bridge. |
| Other user-generated content | Collected | No | Optional | App functionality | Post and thread text explicitly saved or scheduled through Buffer is transmitted to Buffer. Local-reminder drafts remain on-device. |
| Device or other identifiers | Collected | No | Required | Fraud prevention, security and compliance | The maintainer-operated bridge uses the request IP address as the key for abuse-prevention rate limits, retained across requests for the configured rate-limit window. It is not used to infer location. |

Only **Personal identifiers** is marked shared. Direct, recurring account
lookups can transmit an account name to an independently operated data
provider. The other third-party transfers are excluded from "sharing" under
Google's service-provider or specific user-initiated-action rules:

- Cloudinary processes explicitly selected Buffer attachments to produce URLs
  that Buffer can fetch.
- Buffer receives content only when the user explicitly chooses its remote
  save/schedule functionality.
- Shared-history and server-side Top Followers scans use the first-party bridge
  after a specific opt-in; the in-app disclosure explains that public account
  stats and the latest completed public follower list can be reused by other
  opted-in Twidget users.

## Intentionally not selected

- **Location:** no location permission or location API; the bridge does not use
  IP addresses to infer location.
- **Email address:** Buffer returns the connected account email to the app, but
  the app does not transmit that email off-device.
- **Financial, health, messages, audio, calendar:** no corresponding access or
  transmission.
- **Files and docs:** the X Analytics CSV is read locally. When bridge-backed
  import is active, the app transmits parsed dates and follow/unfollow counts,
  not the file or its metadata.
- **Page views and taps, search history, other actions:** there is no off-device
  product analytics or interaction telemetry.
- **Crash logs, diagnostics, performance data:** there is no crash-reporting or
  telemetry SDK. The hidden bridge debug log is opt-in and on-device only.
- **Installed apps:** package visibility queries are used locally to resolve
  browsers, X, and Samsung Gallery; results are not transmitted.

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
- `PRIVACY.md`

## Submission note

Importing a CSV overwrites answers already entered in the form. Review the
store-listing preview after import. If Play Console has changed its schema and
rejects this file, export a fresh blank/current CSV from that app's Data safety
page and transfer the same answers above into that export.
