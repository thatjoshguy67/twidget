package com.tjg.twidget.brief

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BriefIncrementalPolicyTest {
    private val strings = TestBriefStrings()
    private val now = LocalDate.of(2026, 11, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val cards = listOf(
        BriefCard("audience", BriefCardType.AUDIENCE_MOMENTUM, "Audience momentum", "Your audience grew by 20 this week.", 90),
        BriefCard("pattern", BriefCardType.CONTENT_PATTERN, "Content pattern", "Your images received more recorded views.", 80),
        BriefCard("recap", BriefCardType.WEEKLY_RECAP, "Weekly recap", "You published 6 sampled tweets.", 70),
        BriefCard("pulse", BriefCardType.CONVERSATION_PULSE, "Conversation pulse", "Your tweets received 10 replies.", 60),
    )
    private fun measured(cards: List<BriefCard> = this.cards, at: Long = now, scope: String = "cloud|gemini") =
        BriefIncrementalPolicy.prepare(BriefSnapshot("person", at, at, at, at, 100, 20, 30, 0, 20,
            cards, topFollowerRanks = emptyMap(), engineVersion = 26, language = strings.languageTag), strings, scope)

    private fun generated(source: BriefSnapshot = measured(), at: Long = now - 60_000L) = source.copy(
        headline = "Your week in focus", subheading = "Your audience is growing.", shortDescription = "Your week in focus.",
        summaryGenerated = true, summaryGeneratedAt = at, providerUsed = BriefProviderUsed.CLOUD, aiGeneratedAt = at,
        cards = source.cards.map { it.copy(title = "Your update", body = "Your recorded activity is in focus.",
            narrativeGenerated = true, narrativeGeneratedAt = at) })

    @Test fun unchangedPollingAndChartUpdatesKeepAllCopyBeyondTheOldCloudExpiry() {
        val previous = generated()
        val updatedCards = cards.map { it.copy(chartPoints = listOf(BriefChartPoint("Today", 123, now))) }
        val current = measured(updatedCards, at = now + 6 * 60 * 60_000L).copy(contextFingerprint = "new polling time")
        val kept = BriefAiCachePolicy.retain(previous, current, strings, now + 6 * 60 * 60_000L)
        assertTrue(kept.hasGeneratedNarrative)
        assertEquals(previous.summaryGeneratedAt, kept.summaryGeneratedAt)
        assertEquals(previous.cards.first().body, kept.cards.first().body)
        assertEquals(updatedCards.first().chartPoints, kept.cards.first().chartPoints)
        assertTrue(BriefIncrementalPolicy.pending(kept).cards.isEmpty())
    }

    @Test fun aChangedSupportingCardDoesNotRewriteTheSummaryOrOtherCards() {
        val previous = generated()
        val current = measured(cards.dropLast(1) + cards.last().copy(body = "Your tweets received 11 replies."))
        val kept = BriefIncrementalPolicy.retain(previous, current, now)
        assertTrue(kept.summaryGenerated)
        assertEquals(previous.subheading, kept.subheading)
        assertEquals(listOf("pulse"), BriefIncrementalPolicy.pending(kept).cards.map { it.id })
        assertEquals("Your tweets received 11 replies.", kept.cards.last().body)
        assertFalse(kept.cards.last().narrativeGenerated)
        val prompt = promptFor(BriefIncrementalPolicy.pending(kept), strings)
        assertFalse(prompt.contains("__brief_summary__"))
        assertFalse(prompt.contains("\"id\":\"audience\""))
        assertTrue(prompt.contains("\"id\":\"pulse\""))
    }

    @Test fun changedPrimaryFactsRefreshOnlyThatCardAndTheSummaryEvenWithoutNumbers() {
        val current = measured(cards.map { if (it.id == "pattern") it.copy(body = "There is not enough comparable data.") else it })
        val kept = BriefIncrementalPolicy.retain(generated(), current, now)
        assertFalse(kept.summaryGenerated)
        assertEquals(current.subheading, kept.subheading)
        assertEquals(listOf("pattern"), BriefIncrementalPolicy.pending(kept).cards.map { it.id })
        val prompt = promptFor(BriefIncrementalPolicy.pending(kept), strings)
        assertTrue(prompt.contains("__brief_summary__"))
        assertTrue(prompt.contains("not enough comparable data"))
    }

    @Test fun removalOfAPrimaryCardInvalidatesItsSummaryButKeepsRemainingWording() {
        val kept = BriefIncrementalPolicy.retain(generated(), measured(cards.drop(1)), now)
        assertFalse(kept.summaryGenerated)
        assertTrue(kept.cards.all { it.narrativeGenerated })
        assertTrue(BriefIncrementalPolicy.pending(kept).cards.isEmpty())
    }

    @Test fun providerModelLanguageAndDayChangesCannotReuseCopy() {
        val previous = generated()
        val nextDay = now + 24 * 60 * 60_000L
        for (current in listOf(measured(scope = "cloud|openai"), measured(scope = "cloud|gemini|new-model"),
            measured(at = nextDay), measured().copy(language = "de"))) {
            val kept = BriefIncrementalPolicy.retain(previous, current, now)
            assertFalse(kept.hasPartialNarrative)
            assertFalse(kept.summaryGenerated)
        }
    }

    @Test fun updatingOneCardNeverRenewsTheExpiryOfOlderFields() {
        val previous = generated(at = now - BriefIncrementalPolicy.NARRATIVE_TTL_MS + 1_000L)
        val current = measured(cards.dropLast(1) + cards.last().copy(body = "Your tweets received 11 replies."))
        val kept = BriefIncrementalPolicy.retain(previous, current, now)
        val request = BriefIncrementalPolicy.pending(kept)
        val response = BriefAiCardResponse.apply(request,
            """[{"id":"pulse","title":"Your conversation update","body":"Your tweets received 11 replies."}]""",
            BriefProviderUsed.CLOUD, strings).snapshot!!
        val merged = BriefIncrementalPolicy.merge(kept, response)
        assertTrue(merged.hasGeneratedNarrative)
        assertEquals(previous.summaryGeneratedAt, merged.summaryGeneratedAt)
        assertEquals(previous.cards.first().narrativeGeneratedAt, merged.cards.first().narrativeGeneratedAt)
        assertFalse(BriefAiCachePolicy.isFresh(merged, now + 1_001L))
    }

    @Test fun nanoRequestsOnlyMissingCardFieldsAndKeepsValidPartialWork() = runBlocking {
        val changed = measured(cards.dropLast(1) + cards.last().copy(body = "Your tweets received 11 replies."))
        val kept = BriefIncrementalPolicy.retain(generated(), changed, now)
        val stages = mutableListOf<String>()
        val result = BriefNanoTextNarrative.generate(BriefIncrementalPolicy.pending(kept), strings) { stage, _ ->
            stages += stage
            if (stage.endsWith("title")) "Your conversation update" else "Your tweets received 11 replies."
        }!!
        assertEquals(listOf("card-0-description"), stages)
        val merged = BriefIncrementalPolicy.merge(kept, result)
        assertTrue(merged.hasGeneratedNarrative)
        assertEquals(kept.headline, merged.headline)
        assertEquals(kept.cards.first(), merged.cards.first())
        val partial = BriefNanoTextNarrative.generate(measured(), strings) { stage, _ ->
            when {
                stage.startsWith("card-0-") -> null
                stage.endsWith("title") || stage == "headline" -> "Your recorded activity"
                else -> "Your recorded activity is in focus."
            }
        }!!
        assertFalse(partial.cards.first().narrativeGenerated)
        assertTrue(partial.cards.drop(1).all { it.narrativeGenerated })
        assertTrue(partial.summaryGenerated)
        assertEquals(listOf("audience"), BriefIncrementalPolicy.pending(partial).cards.map { it.id })
    }

    @Test fun unrequestedSummaryAndInventedCardFactsCannotOverwriteCachedWork() {
        val current = measured(cards.dropLast(1) + cards.last().copy(body = "Your tweets received 11 replies."))
        val kept = BriefIncrementalPolicy.retain(generated(), current, now)
        val response = BriefAiCardResponse.apply(BriefIncrementalPolicy.pending(kept),
            """[{"id":"__brief_summary__","title":"An unwanted rewrite","body":"Your activity changed.","shortDescription":"Your activity changed."},
                {"id":"pulse","title":"Your conversation update","body":"Your tweets received 999 replies."}]""",
            BriefProviderUsed.CLOUD, strings)
        assertNull(response.snapshot)
        assertEquals("Your tweets received 11 replies.", kept.cards.last().body)
    }

    @Test fun aProviderFailureKeepsCompletedFieldsWithoutRetryingEveryRemainingCard() = runBlocking {
        val stages = mutableListOf<String>()
        val partial = BriefNanoTextNarrative.generate(measured(), strings) { stage, _ ->
            stages += stage
            if (stage == "card-1-description") throw IllegalStateException("Provider quota reached")
            if (stage.endsWith("title") || stage == "headline") "Your recorded activity"
            else "Your recorded activity is in focus."
        }!!
        assertTrue(partial.summaryGenerated)
        assertTrue(partial.cards.first().narrativeGenerated)
        assertEquals("card-1-description", stages.last())
        assertEquals(listOf("pattern", "recap", "pulse"), BriefIncrementalPolicy.pending(partial).cards.map { it.id })
    }

    @Test fun partialNarrativeAndEvidenceSurvivePersistenceWithoutLookingComplete() {
        val current = measured(cards.dropLast(1) + cards.last().copy(body = "Your tweets received 11 replies."))
        val kept = BriefIncrementalPolicy.retain(generated(), current, now)
        val decoded = BriefStore.decode(BriefStore.encode(kept))
        assertEquals(kept, decoded)
        assertFalse(decoded.hasGeneratedNarrative)
        assertTrue(decoded.hasPartialNarrative)
        assertNotNull(BriefPresentation.from(decoded, strings, ""))
        assertEquals(listOf("pulse"), BriefIncrementalPolicy.pending(decoded).cards.map { it.id })
        val summaryChanged = decoded.copy(summaryGenerated = false)
        assertNull(BriefPresentation.from(summaryChanged, strings, ""))
    }

    @Test fun summaryEvidenceAndNumericValidationExcludeUnrelatedSupportingCards() {
        val source = measured(cards.dropLast(1) + cards.last().copy(body = "Your tweets received 9876 replies."))
        assertFalse(source.summaryEvidence.contains("9876"))
        val result = BriefAiCardResponse.apply(source,
            """[{"id":"__brief_summary__","title":"Your audience update","body":"Your tweets received 9876 replies.","shortDescription":"Your replies grew."}]""",
            BriefProviderUsed.CLOUD, strings)
        assertNull(result.snapshot)
        assertEquals(3, JSONObject(source.summaryEvidence).getJSONArray("primaryCards").length())
    }
}
