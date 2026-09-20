# Maintainer Release Process

This process keeps production signing and hosted-service credentials outside
the Git repository.

## Credential boundaries

- The release keystore and `keystore.properties` live in
  `~/.config/twidget/` with owner-only permissions.
- GitHub Package credentials live in
  `~/.config/twidget/github.properties` with owner-only permissions.
- GitHub Actions stores the base64-encoded release keystore, signing passwords,
  and package-registry credentials as encrypted repository secrets.
- Railway stores the server-only Top Followers provider credential as the
  sealed `TWITTERAPIS_API_KEY` service variable. It is never injected into an
  APK or GitHub Actions build.
- GitHub Actions stores the Discord release-channel webhook as the encrypted
  repository secret `DISCORD_RELEASE_WEBHOOK_URL`. Stable and beta workflows
  post through it only after their GitHub Release is published; the Debug Build
  workflow never uses it.
- Railway stores its database, Redis, X API, administration, and service tokens
  in Railway variables. Never copy their values into a repository file,
  workflow, issue, log, or release note.

`keystore.properties` uses the standard Android signing keys:

```properties
storeFile=/absolute/path/to/twidget-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

For a one-off alternative location, pass
`-PtwidgetSigningProperties=/absolute/path/to/keystore.properties`.

Set or rotate the bridge provider key without putting it in shell history:

```bash
printf "%s" "$TWITTERAPIS_API_KEY" | railway variable set TWITTERAPIS_API_KEY --stdin --service twidget-bridge --environment production
```

For local bridge development, set `TWITTERAPIS_API_KEY` only in the bridge
process environment. Android builds deliberately contain no included provider
credential. Top Followers always uses the bridge and requires shared-history
consent. Personal provider credentials are only used for profiles and post
analytics. Both distributions omit foreground-service permissions and migrate
away from any previously queued device-side follower scans.

## Stable release checklist

### Distribution flavors

`github` retains the APK updater. `play` delegates updates to Google Play and
omits the sideload permission, file provider and reminder receiver. Both use
`com.tjg.twidget` and the same version codes. To preserve upgrades for existing
installs, configure Play App Signing with the existing app signing identity;
the upload key alone does not determine the certificate delivered to devices.

Use JDK 25 or newer for all commands below; the SESL9 dependencies contain
Java 24 bytecode. Set `JAVA_HOME` to that JDK (CI uses JDK 25).

Build a signed Play release locally with:

```bash
./gradlew testPlayReleaseUnitTest assemblePlayRelease bundlePlayRelease lintPlayRelease
python3 scripts/verify-play-bundle.py app/build/outputs/bundle/playRelease/app-play-release.aab
```

CI provides these downloads in each run's **Artifacts** section:

| Workflow | GitHub distribution | Play distribution |
| --- | --- | --- |
| Debug Build + Play Build (pushes to main/staging) | Existing production-signed debug APK/AAB and rolling GitHub release | Unsigned validation AAB in `twidget-play-validation-unsigned`; do not upload |
| Pre-release | Signed beta APK/AAB on the GitHub pre-release | Signed beta APK/AAB in `twidget-play-<version>-beta.<number>` |
| Release | Signed release APK/AAB on the GitHub release | Signed release APK/AAB in `twidget-play-<version>` |

The Play APK can be installed directly for testing; upload the Play AAB to
Play Console. The signed APK and AAB must have matching certificates, and
stable/beta workflows also verify that Play matches the GitHub distribution.
Play artifacts stay separate from GitHub release assets because older GitHub
updaters may select any attached APK.

Routine Play Build runs (pushes, pull requests, and manual runs without a beta
number) produce only an unsigned validation AAB. Use the Pre-release or Release
workflow's versioned Play artifact for Play Console uploads. Do not upload a
GitHub-flavor AAB or a debuggable build to Google Play.

For a signed rebuild of an existing beta, dispatch **Play Build** with
`beta_number` and `highest_play_code`, using a reviewed ref containing the fixes.
The beta tag must exist, and the built version code must exceed the supplied
highest code before CI uploads the signed files. The artifact and both filenames
include the beta version and code; its run summary identifies the upload AAB.
This recovery path does not move the published beta tag or replace GitHub APKs.

A permanent offset of 100 was added to the semantic-version code allocation
after code `100300099` (the 1.3.0 stable slot) was uploaded during beta testing.
The corrected beta.2 uses `100300181`, beta.3 will use `100300182`, and stable
1.3.0 will use `100300199`. Retain the offset for future versions: 1.3.1 beta.1
uses `100300280`, preserving upgrades. Both distributions keep the same code
allocation. Never upload an unreleased stable build as a beta.

### Publishing

1. Ensure `main` is clean, current with `origin/main`, and green in GitHub
   Actions.
2. Set the intended stable semantic version in `version.properties`.
3. Add the matching dated section and comparison link to `CHANGELOG.md`.
4. Run the Android, bridge, and secret-boundary checks documented below.
5. Confirm the shared bridge health endpoint and one representative public
   profile lookup succeed without exposing operator credentials.
6. Dispatch the **Release** workflow with the plain version, for example
   `1.0.0`. The workflow runs bridge checks first, then verifies the version,
   builds and signs the APK and AAB, creates `twidget-v<version>`, and publishes
   the GitHub release.
7. Verify the workflow conclusion, Discord announcement, release notes, APK and
   AAB filenames, versions, signing certificates, and updater visibility from
   a logged-out client.

For a public release, the release repository itself must be public. GitHub
release assets in a private repository are not anonymously downloadable, and
the app updater deliberately does not embed a GitHub credential.

Do not create the stable tag manually unless recovering a failed workflow; the
workflow owns the tag and published asset.

Each semantic version reserves Play Store version-code slots in release order:
beta builds use 80–98, trusted debug builds use 98, and the stable build uses
99. This lets a production-signed debug APK install over any beta of the same
version while keeping the stable release as the final upgrade. Do not publish
debug AABs to Play; the debug slot is for local and workflow testing only.

## Discord release notifications

In the target Discord channel, create a webhook under **Edit Channel >
Integrations > Webhooks**. Copy its URL directly into the GitHub repository
secret without committing it:

```bash
gh secret set DISCORD_RELEASE_WEBHOOK_URL --repo thatjoshguy67/twidget
```

The command securely prompts for the webhook URL. To prefix each announcement
with a release-role mention, set the optional repository variable to the
Discord role ID in mention syntax:

```bash
gh variable set DISCORD_RELEASE_MENTION \
  --body '<@&ROLE_ID>' \
  --repo thatjoshguy67/twidget
```

Use `@everyone` instead only when a server-wide release ping is intentional and
the webhook has permission to mention everyone. Leave the variable unset for a
plain channel notification. Rotating or deleting the Discord webhook requires
updating the GitHub secret before the next release.

## Local verification

Trusted debug builds from `main` and manual runs of the **Debug Build** workflow
are signed with the production certificate. Pull-request builds retain the
checked-in debug certificate so pull-request code never receives production
signing credentials. To build an interchangeable debug APK locally, use:

```bash
./gradlew assembleGithubDebug -PsignDebugWithRelease=true
```

This keeps the `-debug.N` version name but makes the APK signature compatible
with beta and stable builds. It is a debuggable production-signed artifact and
must not be published or shared.

```bash
./gradlew testGithubDebugUnitTest assembleGithubDebug lintGithubDebug \
  bundleGithubDebug testGithubReleaseUnitTest assembleGithubRelease bundleGithubRelease lintVitalGithubRelease \
  testGithubBetaUnitTest assembleGithubBeta bundleGithubBeta lintVitalGithubBeta \
  testPlayBetaUnitTest assemblePlayBeta bundlePlayBeta lintPlayBeta

cd bridge
npm ci
npm run check
npm test
npm audit --omit=dev
```

Release and Pre-release workflows run the bridge checks automatically before
building the Android APK and AAB. The local checklist above mirrors both jobs.

Keep unit tests enabled for every build type in `gradle.properties`: AGP 9's
default only creates them for the instrumentation-tested build type (`debug`).
The beta and release workflows need their own variant tests, including the Play
updater boundary checks. Minified builds also enforce that the unused SESL8
immersive-scroll helper is discarded; do not remove that R8 guard when changing
the One UI wrapper or SESL dependencies. The scoped rule makes the old wrapper's
activation API a no-op in optimized builds. On wrapper upgrades, also review
new uses of that API: the discard check cannot detect a newly intended call
that the rule would silently disable.

Before making the repository public, scan the complete Git history—not merely
the working tree—for credentials and sensitive signing files. If a real secret
ever entered Git, remove it from history and rotate it before changing
visibility; history rewriting alone does not make a credential safe again.

Also review author email addresses, commit messages, screenshots, and asset
provenance because all of those become public with the Git history. Enable
GitHub private vulnerability reporting before announcing the repository.
