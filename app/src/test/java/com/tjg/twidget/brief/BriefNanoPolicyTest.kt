package com.tjg.twidget.brief

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BriefNanoPolicyTest {
    private val strings = TestBriefStrings()
    private val source = BriefSnapshot(
        username = "person", generatedAt = 1, sourceSyncedAt = 1, analyticsCachedAt = 1,
        followerScanCompletedAt = 0, followers = 100, following = 20, posts = 30,
        followersToday = 0, followersWeek = -2, topFollowerRanks = emptyMap(),
        cards = listOf(BriefCard("context", BriefCardType.FOLLOWER_CONTEXT, "Source",
            "Recorded follower change: -2.", 70, metrics = listOf(BriefMetric("Net change", "-2")))),
    )

    @Test fun budgetReservesRoomForBothInputAndOutputOnDifferentRuntimes() {
        assertEquals(1024, BriefNanoPolicy.outputBudget(900, 4096, 1024))
        assertEquals(560, BriefNanoPolicy.outputBudget(400, 1024, 1024))
        assertEquals(256, BriefNanoPolicy.outputBudget(900, 4096, 256))
        assertNull(BriefNanoPolicy.outputBudget(970, 1024, 256))
        assertNull(BriefNanoPolicy.outputBudget(4000, 8192, 1024))
        assertNull(BriefNanoPolicy.outputBudget(-1, 4096, 1024))
    }

    @Test fun outputBudgetNegotiationNeverRetriesQuotaOrAvailabilityFailures() {
        assertTrue(BriefNanoPolicy.isOutputBudgetRejection("maxOutputTokens must be within range 1..256"))
        assertTrue(BriefNanoPolicy.isOutputBudgetRejection("INVALID_ARGUMENT: max_output_tokens exceeds supported limit"))
        assertFalse(BriefNanoPolicy.isOutputBudgetRejection("Output token quota exceeded"))
        assertFalse(BriefNanoPolicy.isOutputBudgetRejection("Model download unavailable"))
        assertFalse(BriefNanoPolicy.isOutputBudgetRejection("Input token limit exceeded"))
        assertEquals(512, BriefNanoPolicy.lowerOutputBudget(1024))
        assertEquals(256, BriefNanoPolicy.lowerOutputBudget(512))
        assertNull(BriefNanoPolicy.lowerOutputBudget(64))
    }

    @Test fun smallRuntimeCanGenerateEveryNarrativeWithoutGeneratingJson() = runBlocking {
        val stages = mutableListOf<String>()
        val result = BriefNanoTextNarrative.generate(source, strings) { stage, prompt ->
            stages += stage
            assertTrue(prompt.contains("Return plain text, no JSON"))
            assertTrue(prompt.contains("-2"))
            response(stage)
        }
        assertTrue(result!!.hasGeneratedNarrative)
        assertEquals(5, stages.size)
        assertEquals("A small follower dip over the recorded period.", result.cards.single().body)
        assertEquals(source.cards.single().metrics, result.cards.single().metrics)
    }

    @Test fun truncatedOrOversizedFieldsGetOneSmallerRequestWithoutTemplateFallback() = runBlocking {
        val stages = mutableListOf<String>()
        val result = BriefNanoTextNarrative.generate(source, strings) { stage, _ ->
            stages += stage
            if (stage == "summary") "x".repeat(200) else response(stage.removeSuffix("-compact"))
        }
        assertTrue(result!!.hasGeneratedNarrative)
        assertEquals(1, stages.count { it == "summary-compact" })
        val missing = BriefNanoTextNarrative.generate(source, strings) { stage, _ ->
            if (stage.startsWith("card-0-description")) null else response(stage)
        }
        assertNull(missing)
    }

    @Test fun inventedNumbersStillInvalidatePlainTextGeneration() = runBlocking {
        val result = BriefNanoTextNarrative.generate(source, strings) { stage, _ ->
            if (stage.startsWith("card-0-description")) "You gained 999 followers." else response(stage)
        }
        assertNull(result)
    }

    @Test fun `diagnostic callback records the rejected candidate and its allowed evidence`() = runBlocking {
        val attempts = mutableListOf<BriefNanoTextNarrative.FieldAttempt>()
        val result = BriefNanoTextNarrative.generate(source, strings, onFieldAttempt = { attempts += it }) { stage, _ ->
            if (stage.startsWith("card-0-description")) "You gained 999 followers." else response(stage)
        }
        assertNull(result)
        assertEquals(3, attempts.size)
        assertEquals("card-0-description", attempts.last().stage)
        assertEquals("You gained 999 followers.", attempts.last().candidate)
        assertEquals("numeric evidence validation", attempts.last().rejection)
        assertTrue(attempts.last().allowedNumbers.contains("-2"))
    }

    @Test fun narratorMistakesAreRetriedBeforeTheyCanBecomeVisibleCopy() = runBlocking {
        val stages = mutableListOf<String>()
        val result = BriefNanoTextNarrative.generate(source, strings) { stage, prompt ->
            stages += stage
            assertTrue(prompt.contains("Address the reader as you/your"))
            if (stage == "summary") "I share content regularly with a growing audience."
            else response(stage.removeSuffix("-compact"))
        }
        assertTrue(result!!.hasGeneratedNarrative)
        assertTrue(stages.contains("summary-compact"))
        assertEquals("A small dip in your recorded follower count.", result.subheading)
    }

    @Test fun unrelatedAccountCountsNeverReachCardPromptsAndNumericRepairsKeepValidWork() = runBlocking {
        val post = BriefCard("post", BriefCardType.POST_CONTEXT, "Your recent posts",
            "Your posts received 1,600 views.", 80, metrics = listOf(BriefMetric("Views", "1,600")))
        val stages = mutableListOf<String>()
        val result = BriefNanoTextNarrative.generate(source.copy(followers = 9876, cards = listOf(post)), strings) { stage, prompt ->
            stages += stage
            if (stage.startsWith("card-")) {
                assertFalse("Unrelated followers must stay out of this card's evidence", prompt.contains("9876"))
                assertFalse(prompt.contains("1,200 engagements")) // No example statistics in Nano instructions.
            }
            when (stage) {
                "card-0-description" -> "You have 9876 followers."
                "card-0-description-compact" -> {
                    assertTrue(prompt.contains("numeric evidence validation"))
                    "Your posts received 1600 views."
                }
                else -> response(stage)
            }
        }
        assertTrue(result!!.hasGeneratedNarrative)
        assertEquals(1, stages.count { it == "headline" })
        assertTrue(stages.contains("card-0-description-compact"))
        assertEquals("Your posts received 1600 views.", result.cards.single().body)
    }

    private fun response(stage: String): String = when (stage) {
        "headline" -> "Your follower update"
        "summary" -> "A small dip in your recorded follower count."
        "compact-summary" -> "A small follower dip."
        "card-0-title" -> "Recorded follower change"
        else -> "A small follower dip over the recorded period."
    }
}
