# Tweet review conversation context

Reviews read the selected tweet's content, quoted source and visible media together
with supplied history, connected author posts, available parent context and sampled
public replies. Performance figures remain those of the selected tweet, not the
combined thread. Three or more same-author tweets linked by reply-to IDs count as a
thread. Chronological proximity and a shared conversation ID alone never establish
one. Two connected tweets remain context; branches, cycles and missing links are not
silently filled in. Input excerpts and missing coverage are labelled explicitly.

## Data access and cost

Only when the selected analytics source is TwitterAPIs and its existing API key is
configured, a necessary conversation refresh attempts at most two reads:

- `GET /twitter/tweet/thread?id=<conversation root>`: $0.0040 per call.
- `GET /twitter/tweet/replies?id=<selected tweet>&sort=latest`: $0.0008 per page.

This is at most $0.0048 per context refresh attempt, plus existing AI input/output
charges or ChatGPT plan usage. These provider prices were checked on 2026-11-15:
[thread reference](https://docs.twitterapis.com/docs/reference/tweet-details/tweet-thread)
and [replies reference](https://docs.twitterapis.com/docs/reference/tweet-details/tweet-replies).
Provider charges can still apply if AI generation fails or the unchanged-evidence
check subsequently reuses existing advice. A reusable saved review skips these
reads. Manual refresh can request them again. Parallel viewers share the existing
review-generation task. There is no pagination crawl, new provider account, or
silent switch from FxTwitter/bridge sources to a paid provider.

Other sources use available cached parent links and author posts. Reply text is
marked unavailable if the source cannot supply it; a public reply count is never
used as a substitute for reception. A failed conversation read leaves the basic
review usable. Empty or unavailable reply context has no visible notice or panel;
the model still receives coverage limits and uses the remaining evidence. Only
public post data is sent to the existing selected AI provider.

## Limits and interpretation

Keep at most 12 connected author posts, 3 available parent posts and 20 direct
public audience replies. Self-replies inform the author's thread rather than the
audience-reception sample. Reply IDs must match the selected parent; the documented
direct-replies endpoint supplies that scope when its objects omit the parent field.
The model receives bounded excerpts, explicit uninspected-media flags and separate
citation IDs. It must distinguish a partial, latest-reply sample from the full
audience, consider sarcasm and selection bias, and avoid invented sentiment rates,
majority claims or causal explanations of distribution.

Conversation evidence is saved with successful reviews and participates in the
unchanged-evidence comparison. Fetch timestamps alone do not invalidate advice,
but changed reply wording does, even when numerical statistics are unchanged.
Review excerpts are optional: quote a supplied passage only for advice about that
specific wording. Absent, empty or null excerpts are valid; fabricated text or IDs
remain invalid. Existing rendering omits the quotation block when no excerpt exists.
