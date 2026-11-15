package com.tjg.twidget.brief

/** Display-only memory. It never makes measured cards fresh or suppresses a new generation. */
data class BriefPresentation(
    val title: String,
    val body: String,
    val shortDescription: String,
    val heroType: BriefCardType,
    val language: String,
    val contentFingerprint: String,
    val generatedAt: Long,
    val evidenceKey: String = "",
) {
    fun summary() = BriefEditorialSummary(title, body, shortDescription)

    internal fun matches(strings: BriefStrings, content: String): Boolean =
        title.isNotBlank() && body.isNotBlank() && shortDescription.isNotBlank() && generatedAt > 0 &&
            language.equals(strings.languageTag, true) && contentFingerprint == content &&
            BriefVoicePolicy.accepts("$title $body $shortDescription", strings)

    companion object {
        internal fun from(snapshot: BriefSnapshot, strings: BriefStrings, content: String): BriefPresentation? {
            if (snapshot.summaryEvidenceKey.isNotBlank()) {
                if (!snapshot.summaryGenerated || snapshot.providerUsed == BriefProviderUsed.TEMPLATE) return null
            } else if (!snapshot.hasGeneratedNarrative) return null
            val summary = BriefEditorialSummary.from(snapshot, strings)
            // Older persisted snapshots include the content switches near the end of their context key.
            val legacyContent = snapshot.contextFingerprint.split('|').dropLast(3).lastOrNull()
                ?.takeIf { it.startsWith("top_tweet:") }
            return BriefPresentation(summary.title, summary.body, summary.shortDescription,
                snapshot.cards.firstOrNull()?.type ?: BriefCardType.SUMMARY,
                strings.languageTag, snapshot.contentSettingsFingerprint.ifBlank { legacyContent ?: content },
                snapshot.summaryGeneratedAt.takeIf { it > 0 } ?: snapshot.aiGeneratedAt.takeIf { it > 0 } ?: snapshot.generatedAt,
                snapshot.summaryEvidenceKey)
        }
    }
}
