package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class BriefAiCardResponseTest {
    private val strings = TestBriefStrings()

    private val source = BriefSnapshot(
        username = "account",
        generatedAt = 1L,
        sourceSyncedAt = 2L,
        analyticsCachedAt = 3L,
        followerScanCompletedAt = 4L,
        followers = 8_000,
        following = 100,
        posts = 200,
        followersToday = 2,
        followersWeek = 10,
        cards = listOf(
            BriefCard("growth", BriefCardType.GROWTH, "Growth", "You gained 10 followers.", 80),
            BriefCard("streak", BriefCardType.STREAK, "Three days", "Your streak is 3 days.", 70),
            BriefCard("steady", BriefCardType.SUMMARY, "Steady", "Everything is steady.", 60),
        ),
        topFollowerRanks = emptyMap(),
        language = strings.languageTag,
    )

    @Test
    fun measuredContextAcceptsModelWrittenDescriptionsFromItsEvidence() {
        for (type in listOf(BriefCardType.POST_CONTEXT, BriefCardType.FOLLOWER_CONTEXT)) {
            val card = BriefCard("context", type, "Recorded context", "300 recorded views so far. This is a sample.", 70)
            val result = BriefAiCardResponse.apply(
                source.copy(cards = listOf(card)),
                """[{"i":"context","t":"Your recent activity","b":"A snapshot of your recent activity, with 300 views recorded so far."}]""",
                BriefProviderUsed.LOCAL,
                strings,
            )
            assertEquals("A snapshot of your recent activity, with 300 views recorded so far.", result.snapshot?.cards?.single()?.body)
            assertEquals(true, result.snapshot?.cards?.single()?.narrativeGenerated)
        }
    }

    @Test
    fun compactNanoResponseRewritesCardsWithoutOverridingEngineOrder() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"streak","t":"3-day streak","b":"Your streak is 3 days."},{"i":"growth","t":"Growing","b":"You gained 10 followers."}]""",
            BriefProviderUsed.LOCAL,
            strings,
        )

        assertNotNull(result.snapshot)
        assertEquals(2, result.appliedCards)
        assertEquals(listOf("growth", "streak", "steady"), result.snapshot?.cards?.map(BriefCard::id))
        assertEquals("Growing", result.snapshot?.cards?.first()?.title)
        assertEquals(BriefProviderUsed.LOCAL, result.snapshot?.providerUsed)
    }

    @Test
    fun truncatedNanoResponseReportsAParseFailure() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"growth","t":"Growing"""",
            BriefProviderUsed.LOCAL,
            strings,
        )

        assertNull(result.snapshot)
        assertEquals(0, result.appliedCards)
        assertEquals("Response did not contain a complete JSON array", result.failure)
    }

    @Test
    fun changedNumericFactsFallBackToOriginalCopyButStillApplyRanking() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"growth","t":"Huge growth","b":"You gained 99 followers."}]""",
            BriefProviderUsed.LOCAL,
            strings,
        )

        assertEquals("Growth", result.snapshot?.cards?.first()?.title)
        assertEquals("You gained 10 followers.", result.snapshot?.cards?.first()?.body)
        assertEquals(1, result.appliedCards)
    }

    @Test
    fun signedNumericFactsCannotLoseTheirSign() {
        val card = BriefCard("follower-context", BriefCardType.FOLLOWER_CONTEXT,
            "Your follower week", "Your follower change was -1, from 131 to 130.", 70)
        val source = source.copy(cards = listOf(card))

        val unsigned = BriefAiCardResponse.apply(source,
            """[{"i":"follower-context","t":"Your follower week","b":"Your follower count fell by 1, from 131 to 130."}]""",
            BriefProviderUsed.CLOUD, strings).snapshot!!.cards.single()
        val signed = BriefAiCardResponse.apply(source,
            """[{"i":"follower-context","t":"Your follower week","b":"Your follower change was -1, from 131 to 130."}]""",
            BriefProviderUsed.CLOUD, strings).snapshot!!.cards.single()

        assertEquals(false, unsigned.narrativeGenerated)
        assertEquals(true, signed.narrativeGenerated)
    }

    @Test
    fun overlongCompactSummaryKeepsTheSentencesThatFit() {
        val german = TestBriefStrings(Locale.GERMAN)
        val followerBody = "Vom 30.10.2026 bis 06.11.2026 betrug deine Nettoveränderung bei Followern 0 (130 → 130)."
        val cards = listOf(
            BriefCard("start-streak", BriefCardType.STREAK, "Starte einen Streak",
                "Twidget hat seit mehr als 3 Tagen keinen eigenen Tweet erkannt.", 70),
            BriefCard("follower-context-2026-11-06", BriefCardType.FOLLOWER_CONTEXT, "Deine Follower-Woche", followerBody, 80),
            BriefCard("milestone-followers-200.0", BriefCardType.MILESTONE, "Weiter so",
                "Du näherst dich deinem Ziel von 200 Followern.", 60),
        )
        val short = "Vom 30.10.2026 bis 06.11.2026 blieb deine Follower-Zahl bei 130. Dein Ziel von 200 Followern ist in Sicht."
        val result = BriefAiCardResponse.apply(
            source.copy(followers = 130, followersToday = 0, followersWeek = 0, cards = cards),
            """[
                {"id":"__brief_summary__","title":"Deine Woche im Überblick","body":"$followerBody Dein Ziel bleibt in Sicht.","shortDescription":"$short"},
                {"id":"start-streak","title":"Starte einen Streak","body":"Twidget hat seit mehr als 3 Tagen keinen eigenen Tweet erkannt. Tweete heute, um anzufangen."},
                {"id":"follower-context-2026-11-06","title":"Deine Follower-Woche","body":"$followerBody"},
                {"id":"milestone-followers-200.0","title":"Weiter so","body":"Du näherst dich deinem Ziel von 200 Followern."}
            ]""",
            BriefProviderUsed.CLOUD,
            german,
        )

        assertEquals(true, result.snapshot?.hasGeneratedNarrative)
        assertEquals(
            "Vom 30.10.2026 bis 06.11.2026 blieb deine Follower-Zahl bei 130.",
            result.snapshot?.shortDescription,
        )
    }

    @Test
    fun compactSummaryWithoutAFittingSentenceReportsItsLength() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"id":"__brief_summary__","title":"Your week","body":"You gained 10 followers.","shortDescription":"${"You gained 10 followers this week and the rest of this sentence keeps going past the compact limit without another stop. "}"}]""",
            BriefProviderUsed.CLOUD,
            strings,
        )

        assertEquals(false, result.snapshot?.hasGeneratedNarrative == true)
        assertEquals(true, result.failure?.contains("summary_length") == true)
    }

    @Test
    fun cloudNumericRepairPromptForbidsDroppingSigns() {
        val prompt = cloudNumericRepairPrompt(source, strings)
        assertEquals(true, prompt.contains("never write 1 when the evidence says -1"))
        assertEquals(true, prompt.contains("before-and-after values"))
    }

    @Test
    fun aiSummaryIsSentenceCasedAndStoredForEveryBriefSurface() {
        val result = BriefAiCardResponse.apply(
            source.copy(followersToday = 1),
            """[{"i":"__brief_summary__","t":"Your Growth Is Building","b":"Your posting rhythm is active.","s":"You gained 1 follower today and 10 this week. Your posting rhythm is active."}]""",
            BriefProviderUsed.LOCAL,
            strings,
        )

        assertEquals("Your growth is building", result.snapshot?.headline)
        assertEquals("Your posting rhythm is active.", result.snapshot?.subheading)
        assertEquals(
            "You gained 1 follower today and 10 this week. Your posting rhythm is active.",
            result.snapshot?.shortDescription,
        )
        assertEquals(
            BriefEditorialSummary(
                "Your growth is building",
                "Your posting rhythm is active.",
                "You gained 1 follower today and 10 this week. Your posting rhythm is active.",
            ),
            result.snapshot?.let { BriefEditorialSummary.from(it, strings) },
        )
    }

    @Test
    fun inventedCompactFactsRejectTheSummaryInsteadOfShowingATemplate() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"__brief_summary__","t":"Momentum is building","b":"Your posting rhythm is active.","s":"Up 99 followers today."}]""",
            BriefProviderUsed.LOCAL,
            strings,
        )

        assertNull(result.snapshot)
    }

    @Test
    fun nonEnglishCopyKeepsTheModelCapitalisationBecauseSentenceCaseIsAnEnglishRule() {
        val german = TestBriefStrings(Locale.GERMAN)

        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"growth","t":"Dein Publikum Wächst","b":"Du hast 10 Follower gewonnen."}]""",
            BriefProviderUsed.LOCAL,
            german,
        )

        assertEquals("Dein Publikum Wächst", result.snapshot?.cards?.first()?.title)
        assertEquals(
            "Growing fast",
            BriefAiCardResponse.apply(
                source,
                """[{"i":"growth","t":"growing Fast","b":"You gained 10 followers."}]""",
                BriefProviderUsed.LOCAL,
                strings,
            ).snapshot?.cards?.first()?.title,
        )
    }
}
