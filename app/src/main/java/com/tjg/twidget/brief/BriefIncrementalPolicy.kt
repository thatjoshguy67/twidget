package com.tjg.twidget.brief

import org.json.JSONArray
import org.json.JSONObject

/** Reuse is tied to original measured evidence, never to matching AI-generated numbers alone. */
internal object BriefIncrementalPolicy {
    // Exact evidence can safely keep its wording for the day. A new local day,
    // changed evidence or an explicit refresh still invalidates it immediately.
    const val NARRATIVE_TTL_MS = 24 * 60 * 60 * 1000L

    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun evidence(card: BriefCard): String = JSONObject().put("id", card.id).put("kind", card.type.name)
        .put("title", card.title).put("body", card.body).put("action", card.action.name).put("target", card.actionData)
        .put("metrics", JSONArray(card.metrics.map { JSONObject().put("label", it.label).put("value", it.value) }))
        .toString()

    fun prepare(source: BriefSnapshot, strings: BriefStrings, scope: String): BriefSnapshot {
        val date = java.time.Instant.ofEpochMilli(source.generatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val scoped = "$scope|${source.username.lowercase(java.util.Locale.ROOT)}|${source.engineVersion}|${source.language}|$date"
        val summary = BriefEditorialSummary.from(source, strings)
        val summaryEvidence = JSONObject().put("title", summary.title).put("overview", summary.body)
            .put("compactOverview", summary.shortDescription)
            .put("primaryCards", JSONArray(source.cards.take(3).map { JSONObject(evidence(it)) })).toString()
        val cards = source.cards.map { card ->
            // These legacy post cards may use age-peer context not fully expressed in their metrics.
            val postContext = if (card.type in setOf(BriefCardType.POST, BriefCardType.WORST_POST, BriefCardType.POSTING_GUIDE)) source.analyticsCachedAt else 0L
            card.copy(evidenceKey = hash("$scoped|$postContext|${evidence(card)}"), narrativeGeneratedAt = 0L)
        }
        return source.copy(cards = cards.map { it.copy(narrativeGenerated = false) }, summaryGenerated = false,
            summaryEvidence = summaryEvidence,
            summaryEvidenceKey = hash("$scoped|$summaryEvidence"), summaryGeneratedAt = 0L)
    }
    private fun fresh(at: Long, now: Long) = at > 0 && now >= at && now - at < NARRATIVE_TTL_MS

    fun isFresh(source: BriefSnapshot, now: Long): Boolean = source.hasGeneratedNarrative &&
        fresh(source.summaryGeneratedAt, now) && source.cards.all { fresh(it.narrativeGeneratedAt, now) }
    fun retain(previous: BriefSnapshot, current: BriefSnapshot, now: Long): BriefSnapshot {
        if (previous.providerUsed == BriefProviderUsed.TEMPLATE || previous.engineVersion != current.engineVersion ||
            !previous.username.equals(current.username, true) || previous.language != current.language) return current
        val old = previous.cards.associateBy { it.id }
        val cards = current.cards.map { card ->
            val cached = old[card.id]
            if (cached != null && cached.narrativeGenerated && card.evidenceKey.isNotBlank() &&
                card.evidenceKey == cached.evidenceKey && fresh(cached.narrativeGeneratedAt, now)) {
                card.copy(title = cached.title, body = cached.body, narrativeGenerated = true, narrativeGeneratedAt = cached.narrativeGeneratedAt)
            } else card.copy(narrativeGenerated = false, narrativeGeneratedAt = 0L)
        }
        val summary = previous.summaryGenerated && current.summaryEvidenceKey.isNotBlank() &&
            previous.summaryEvidenceKey == current.summaryEvidenceKey && fresh(previous.summaryGeneratedAt, now)
        return current.copy(cards = cards, headline = if (summary) previous.headline else current.headline,
            subheading = if (summary) previous.subheading else current.subheading,
            shortDescription = if (summary) previous.shortDescription else current.shortDescription,
            summaryGenerated = summary, summaryGeneratedAt = if (summary) previous.summaryGeneratedAt else 0L,
            providerUsed = previous.providerUsed, providerMessage = previous.providerMessage,
            aiGeneratedAt = previous.aiGeneratedAt)
    }
    fun pending(source: BriefSnapshot) = source.copy(cards = source.cards.filterNot { it.narrativeGenerated })
    fun merge(source: BriefSnapshot, generated: BriefSnapshot): BriefSnapshot {
        val updated = generated.cards.associateBy { it.id }
        return generated.copy(cards = source.cards.map { updated[it.id] ?: it })
    }
}
