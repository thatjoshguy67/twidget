# Contributing to Twidget

Thanks for helping improve Twidget. Bug reports, focused fixes, tests, and
documentation improvements are welcome. To translate the app into your
language, follow the [localisation guide](docs/LOCALISATION.md).

## Before opening an issue

- Search existing issues and confirm the problem still occurs on the latest
  release or `main`.
- Remove X API credentials, bridge tokens, account history, database URLs,
  installation identifiers, and other personal data from screenshots or logs.
- For a suspected vulnerability, follow [SECURITY.md](SECURITY.md) instead of
  filing a public issue.

## Development setup

The Android app requires JDK 25 or newer and Android SDK 37. GitHub Actions
uses JDK 25; the app bytecode target remains JVM 17. Dependencies from the
Tribalfs GitHub Packages registry require a GitHub token with `read:packages`;
copy `github.properties.example` to `~/.config/twidget/github.properties`,
populate it, and run `chmod 600` on the result (on Windows, restrict file
permissions through your account settings).

**macOS (JDK 25+):**

```bash
JAVA_HOME="/path/to/jdk25/Contents/Home" \
  ./gradlew testGithubDebugUnitTest assembleGithubDebug lintGithubDebug
```

**Windows (PowerShell, JDK 25+):**

```powershell
$env:JAVA_HOME = "C:\path\to\jdk25"
.\gradlew testGithubDebugUnitTest assembleGithubDebug lintGithubDebug
```

**Linux (system JDK 25+):**

```bash
./gradlew testGithubDebugUnitTest assembleGithubDebug lintGithubDebug
```

The bridge uses the Node version in `bridge/.nvmrc`:

```bash
cd bridge
npm ci
npm run check
npm test
npm audit --omit=dev
```

The values in `bridge/.env.example` are documentation, not hosted-service
credentials. Put local overrides in an ignored `.env` file and never submit a
populated environment file.

## Pull requests

- Keep each pull request focused and explain the user-visible behavior.
- Add or update deterministic tests for correctness and data-migration changes.
- Preserve honest unknown history values; do not synthesize historical data.
- Keep shared-bridge data collection opt-in and avoid silently broadening what
  is stored or transmitted.
- Do not weaken rate limits, authentication, retention controls, or secret
  boundaries merely to simplify local development.
- Confirm the Android and bridge checks above pass before requesting review.

### CI on fork pull requests

Pull requests opened from a fork run **bridge checks only** in GitHub Actions.
The Android build job is skipped because Tribalfs GitHub Packages credentials
cannot be given to untrusted contributor code. Before requesting review from a
fork, run the Android Gradle checks locally with your own
`github.properties`. Maintainers must not dispatch a secret-bearing workflow
against a fork ref. After reviewing the contributor diff, reproduce the change
on a trusted same-repository branch before using repository-backed Android CI.

Maintainers may decline changes that expand hosted-service cost, data
collection, or operational risk even when the change is otherwise valid.
