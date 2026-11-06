package com.tjg.twidget.brief

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComposerRetirementTest {
    private fun snapshot() = BriefSnapshot("example", 1, 1, 1, 1, 10, 5, 3, 0, 0,
        cards = listOf(BriefCard("growth", BriefCardType.GROWTH, "Growth", "Steady growth", 50)),
        topFollowerRanks = emptyMap())

    @Test fun oldBriefsDiscardScheduleCardsAndUnpublishedQueueWithoutLosingAnalyticsCards() {
        val raw = BriefStore.encode(snapshot())
        raw.getJSONArray("cards").getJSONObject(0).put("action", "COMPOSE_TWEET")
        raw.getJSONArray("cards").put(JSONObject().put("id", "old-schedule").put("type", "SCHEDULE_GUIDE"))
            .put(JSONObject().put("id", "old-follow-through").put("type", "POST_FOLLOW_THROUGH"))
        // Retired payloads are deliberately ignored, even when their provider schema is obsolete.
        raw.put("upcomingTweets", JSONArray().put(JSONObject().put("provider", "POSTPONE")
            .put("preview", "Unpublished launch announcement")))
        val restored = BriefStore.decode(raw)
        assertEquals(listOf("growth"), restored.cards.map { it.id })
        assertTrue(restored.upcomingTweets.isEmpty())
        assertEquals(BriefCardAction.NONE, restored.cards.single().action)
        assertFalse(BriefStore.encode(restored).has("upcomingTweets"))
    }

    @Test fun legacyRewriteFieldsAreIgnoredAndNewResponsesNeedOnlyAnalysis() {
        val input = BriefCoachInput("published", "I shipped a comparison tool.", true, 0,
            BriefCoachReport(emptyList(), emptyList(), emptyList(), emptyList()), emptyList(), 1, "en")
        val response = JSONObject().put("possibleExplanation", "The purpose is clear.")
            .put("suggestions", JSONArray().put(JSONObject().put("action", "Ask about a specific use case.")
                .put("reason", "That gives readers a focused question.").put("quoteId", "q0")
                .put("evidenceIds", JSONArray().put("subject"))))
        val packet = input.packet(cloud = true)
        val current = requireNotNull(BriefCoachAi.parse(response.toString(), packet))
        response.getJSONArray("suggestions").getJSONObject(0)
            .put("rewrite", "A retired replacement tweet").put("needsDetails", "obsolete malformed flag")
        assertEquals(current, BriefCoachAi.parse(response.toString(), packet))
        assertNull(current.suggestions.single().rewrite)
        assertFalse(BriefCoachAi.prompt(input, packet).contains("\"rewrite\":"))
    }
}
