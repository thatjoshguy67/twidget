# Tweet review algorithm context

Cloud reviews include `BriefAlgorithmContext` alongside tweet, media, statistics
and selected history. Nano's debug-only text prompt stays compact. Review output
still uses the existing schema and 1–2 suggestions, not a longer report.

The shipped snapshot was checked on 2026-11-15 against xai-org/x-algorithm revision
`30647a85a20cbdd114169beb9973680c4dc4f401`:

- [Overview](https://github.com/xai-org/x-algorithm/blob/30647a85a20cbdd114169beb9973680c4dc4f401/README.md)
- [Model](https://github.com/xai-org/x-algorithm/blob/30647a85a20cbdd114169beb9973680c4dc4f401/phoenix/README.md)
- [Default parameters](https://github.com/xai-org/x-algorithm/blob/30647a85a20cbdd114169beb9973680c4dc4f401/home-mixer/params/param.rs)
- [Scorer](https://github.com/xai-org/x-algorithm/blob/30647a85a20cbdd114169beb9973680c4dc4f401/xai-value-model/scoring.rs)

## Updates

When cloud reviews open or generate, check the official GitHub main ref at most
once per 24 hours. If its revision changed, fetch the parameter and scorer files
from that exact commit. The app sends no tweet content or credentials to GitHub.
Only allowlisted action labels, validated finite numeric defaults and a hexadecimal
revision enter the stored snapshot. Raw repository text never enters a review prompt.
The prompt describes positive, negative and zero default directions, not engagement
multipliers. Revisions with identical supported defaults reuse existing advice.

This updates the supported ranking-signal defaults; it is not autonomous interpretation
of every repository change. Unsupported parameter/schema changes retain the last
validated snapshot and require a code update to the parser/guide. Offline, quota and
invalid-source failures also retain cached context (or the shipped snapshot), with
an hour before another attempt. HTTP requests have bounded timeouts and response sizes.
The public code/defaults are not a claim about live X production configuration.

Review results persist the guide/default fingerprint. Both time-based reuse and the
unchanged-evidence shortcut check it. Existing advice remains readable while a
replacement generates; old reviews without the fingerprint are refreshed on opening.
The in-memory and draft cache keys also separate algorithm context versions.

## Editorial boundaries

The general guide is separate from measured post evidence. The model must not infer
unobserved dwell, private sharing, negative feedback, audience histories, eligibility
or distribution sources. Suggestions to improve relevance, clarity, payoff and
substantive discussion are editorial hypotheses, not proven ranking causes. Do not
invent fixed weights, reach guarantees, format/link penalties or magic posting rules.
Retain quote/media attribution and the existing evidence and numeric validation.

## Verification

Unit tests parse excerpts of the pinned official source, check changed/invalid
defaults, retain the guide in cloud retries, omit it from Nano's compact prompt, and
verify persisted-version invalidation without discarding old advice. The phone test
checks the daily cache and failed-refresh backoff with a fake transport. Its live
source check is opt-in with `algorithmContextLive=true`, uses only public source
requests, and restores the prior algorithm cache after testing. Upstream fixture
attribution and license are under `src/test/resources/brief-algorithm`.
