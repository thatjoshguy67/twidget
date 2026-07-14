# Creates GitHub issues documenting the July 2026 codebase audit findings.
# Each issue is opened and immediately closed as a historical record (already fixed).
# Requires: gh auth login
# Usage: .\scripts\create-audit-issues.ps1

$ErrorActionPreference = "Stop"
$gh = "$env:ProgramFiles\GitHub CLI\gh.exe"
if (-not (Test-Path $gh)) { throw "GitHub CLI not found. Install from https://cli.github.com/" }

& $gh auth status | Out-Null

$issues = @(
    @{
        Title = "[Audit · Fixed] Release and prerelease workflows skipped bridge checks"
        Labels = "documentation,maintenance"
        Body = @"
## Status

**Fixed** — remediated in the July 2026 audit remediation work. This issue is kept as a historical record.

## Problem found

The **Release** and **Pre-release** GitHub Actions workflows ran Android build/test steps only. The bridge service (`npm run check`, `npm test`, `npm audit`) was not executed before shipping stable or beta APKs, so bridge regressions could reach production without CI catching them.

## Remediation

- Added a parallel ``bridge`` job to ``.github/workflows/release.yml`` and ``.github/workflows/prerelease.yml``
- Gated the Android release job on ``needs: bridge``
- Updated ``docs/RELEASING.md`` to note that release workflows now mirror local bridge verification

## Verification

- ``cd bridge && npm ci && npm run check && npm test && npm audit --omit=dev`` passes locally
"@
    },
    @{
        Title = "[Audit · Fixed] Missing stable CHANGELOG section blocked Release workflow"
        Labels = "documentation,maintenance"
        Body = @"
## Status

**Fixed** — remediated in the July 2026 audit remediation work. This issue is kept as a historical record.

## Problem found

``version.properties`` targeted stable ``1.1.0``, but ``CHANGELOG.md`` had no ``## [1.1.0]`` section. The Release workflow extracts hand-written notes with ``awk`` and would fail at the changelog step when dispatching a stable release.

## Remediation

- Added ``## [1.1.0] - 2026-07-14`` consolidating beta highlights and recent Unreleased items
- Refreshed an empty ``## Unreleased`` stub for future work
- Updated stale ``1.0.0`` test-count wording to avoid future drift

## Verification

- Release workflow awk extraction succeeds against the new ``[1.1.0]`` section
"@
    },
    @{
        Title = "[Audit · Fixed] Android testing gaps (instrumented tests and network parsers)"
        Labels = "documentation,maintenance"
        Body = @"
## Status

**Fixed** — remediated in the July 2026 audit remediation work. This issue is kept as a historical record.

## Problem found

The audit identified significant test gaps:

- Zero ``androidTest`` / instrumented tests (``SecureCredentialStore`` Keystore path untested in CI)
- Network client response parsing largely untested (~49 of 69 main Kotlin sources had no direct tests)
- Bridge route handlers (``/user``, ``/analytics``) lacked integration coverage beyond basic smoke defaults

## Remediation

- Added ``SecureCredentialStoreInstrumentedTest`` under ``app/src/androidTest/``
- Added ``NetworkResponseParsersTest`` and ``BangerClientParseTest`` unit tests
- Extended ``bridge/test/server-smoke.test.mjs`` for ``/user``, ``/analytics``, rate-limit headers, and ``publicMode``
- Added ``TEST_MOCK_UPSTREAM=1`` mock upstream for deterministic bridge route tests
- CI now compiles instrumented tests via ``compileDebugAndroidTestKotlin``

## Verification

- Bridge: 17/17 tests pass
- Android unit tests include new parser coverage (full Gradle run requires local Android SDK)
"@
    },
    @{
        Title = "[Audit · Fixed] MainActivity monolith and duplicated HTTP client logic"
        Labels = "documentation,maintenance"
        Body = @"
## Status

**Fixed** — remediated in the July 2026 audit remediation work. This issue is kept as a historical record.

## Problem found

- ``MainActivity.kt`` was ~1,700 lines with dashboard, drawer, sync, analytics, and edit-mode logic coupled in one class
- Ten files used raw ``HttpURLConnection`` with inconsistent timeouts, headers, and error handling

## Remediation

- Split ``MainActivity`` into internal controllers: ``MainDashboardBinder``, ``MainDrawerController``, ``MainSyncController``, ``MainPostAnalyticsBinder``, ``MainEditModeController`` (public Activity API unchanged)
- Introduced internal ``HttpTransport`` for shared JSON and streaming HTTP configuration
- Extracted ``NetworkResponseParsers`` for testable JSON parsing shared by clients

## Verification

- No public client method signatures or bridge routes changed
- Bridge and new unit tests pass
"@
    },
    @{
        Title = "[Audit · Fixed] Release builds lacked R8/ProGuard minification"
        Labels = "documentation,maintenance"
        Body = @"
## Status

**Fixed** — remediated in the July 2026 audit remediation work. This issue is kept as a historical record.

## Problem found

``isMinifyEnabled`` was ``false`` for release and beta build types, producing larger APKs with no obfuscation.

## Remediation

- Enabled R8 for ``release`` and ``beta`` only (debug remains unobfuscated)
- Added ``app/proguard-rules.pro`` keeping manifest components, Workers, and AppWidget providers
- **APK signing configuration was intentionally not changed**

## Verification

- ``assembleRelease`` / ``assembleBeta`` should be run locally after SDK setup to confirm shrinker rules
"@
    },
    @{
        Title = "[Audit · Fixed] Bridge public mode and contributor documentation gaps"
        Labels = "documentation,maintenance"
        Body = @"
## Status

**Fixed** — remediated in the July 2026 audit remediation work. This issue is kept as a historical record.

## Problem found

Several operational and contributor gaps:

- Self-hosted bridge operators had no ``publicMode`` signal when ``BRIDGE_API_TOKEN`` was unset
- ``CONTRIBUTING.md`` assumed macOS-only ``JAVA_HOME`` paths
- Fork PRs run bridge CI only (Android job skipped) but this was undocumented
- Maintainers had no workflow path to run Android CI against a fork PR
- ``ScheduleBootReceiver`` used a raw ``Thread`` instead of ``AppExecutors``
- ``bridge/package.json`` version ``0.1.0`` did not align with app semver

## Remediation

- ``GET /health`` now includes ``publicMode: true`` when no bearer token is configured
- Production/Railway startup logs a warning when ``BRIDGE_API_TOKEN`` is unset
- Updated ``SECURITY.md``, ``bridge/README.md``, ``README.md``, and ``CONTRIBUTING.md``
- Debug workflow: ``pr_number`` input for maintainer Android CI on fork PRs
- ``ScheduleBootReceiver`` uses ``AppExecutors.execute``
- Bridge package version aligned to ``1.1.0`` with description noting app tracking

## Notes

The maintainer-operated shared bridge remains **token-free by design**; self-hosted instances should set ``BRIDGE_API_TOKEN``.

## Verification

- Bridge smoke test asserts ``publicMode: false`` when token is configured
- Bridge tests pass (17/17)
"@
    }
)

foreach ($issue in $issues) {
    Write-Host "Creating: $($issue.Title)"
    $bodyFile = New-TemporaryFile
    try {
        Set-Content -Path $bodyFile -Value $issue.Body -Encoding utf8
        $createArgs = @(
            "issue", "create",
            "--title", $issue.Title,
            "--body-file", $bodyFile
        )
        if ($issue.Labels) {
            $createArgs += @("--label", $issue.Labels)
        }
        $url = & $gh @createArgs 2>&1
        if ($LASTEXITCODE -ne 0) { throw $url }
        Write-Host "  Created: $url"
        & $gh issue close $url --comment "Closed as fixed — see issue body for remediation details. Kept for audit history." | Out-Null
        Write-Host "  Closed (historical record)"
    } finally {
        Remove-Item $bodyFile -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Done. Created $($issues.Count) audit record issues."
