# Security Policy

Twidget handles account identifiers and optional API credentials, and the
hosted bridge is an internet-facing service. Please report security problems
privately and give the maintainer reasonable time to investigate before public
disclosure.

## Supported versions

Security fixes are made for the latest stable release. Users should update to
the newest release before reporting a problem that may already be fixed.

## Reporting a vulnerability

Use **Security → Advisories → Report a vulnerability** in this repository. Do
not open a public issue for suspected credential exposure, authentication or
authorization bypasses, remote code execution, release-signing problems,
privacy leaks, or ways to evade bridge limits.

If private vulnerability reporting is unavailable, contact the maintainer via
[tjg.gg](https://tjg.gg) and ask for a private reporting channel without
including exploit details in the initial message.

Include, where possible:

- the affected app or bridge version and commit;
- the smallest reproducible sequence of actions;
- the security or privacy impact;
- logs or proof of concept with tokens and personal data removed; and
- whether the issue is already being exploited.

You should receive an acknowledgement within seven days. A fix timeline will
depend on severity and whether upstream services are involved.

## Secrets and signing material

Never commit production credentials, Railway variables, database or Redis
URLs, API tokens, populated `.env` files, `keystore.properties`, or release
keystores. Local maintainer credentials live outside the checkout under
`~/.config/twidget/`; GitHub Actions and Railway receive secrets through their
encrypted environment settings.

The checked-in `app/debug.keystore` is intentionally public. It contains the
standard Android debug credentials, has no production trust, and must never be
used to sign a stable or beta release.

Self-hosted bridge deployments should set `BRIDGE_API_TOKEN` so data routes
require a bearer token. The maintainer-operated shared bridge remains
token-free by design; it relies on rate limits and operational monitoring
instead. Check `/health` for `publicMode: true` when no token is configured.

## Scope notes

Reports about Twidget's code and hosted bridge are welcome. Availability or
content problems in X/Twitter, FxTwitter, GitHub, Railway, or another upstream
service should normally be reported to that service unless Twidget handles the
failure unsafely.
