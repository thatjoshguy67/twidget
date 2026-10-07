package com.tjg.twidget.brief

import com.tjg.twidget.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BriefLocalisationTest {
    @Test fun oneDayStreakUsesSingularCopyInEveryLanguageAndSchedulingState() {
        for (locale in listOf(Locale.US, Locale.GERMAN, Locale.FRENCH)) {
            val strings = TestBriefStrings(locale)
            val expectedTitle = when (locale.language) {
                "de" -> "1 Streak-Tag"
                "fr" -> "Série de publications de 1 jour"
                else -> "1-day posting streak"
            }
            assertEquals(expectedTitle, strings.quantityText(R.plurals.brief_card_streak_title, 1, 1))
            val bodies = listOf(R.plurals.brief_card_streak_buffer, R.plurals.brief_card_streak_ready,
                R.plurals.brief_card_streak_queued, R.plurals.brief_card_streak_tweet_today)
            for (id in bodies) {
                val singular = strings.quantityText(id, 1, 1)
                val plural = strings.quantityText(id, 2, 2)
                when (locale.language) {
                    "de" -> {
                        assertTrue(singular.contains("1-Tag-Rhythmus"))
                        assertTrue(plural.contains("2-Tage-Rhythmus"))
                    }
                    "fr" -> {
                        assertTrue(singular.contains("1 jour"))
                        assertFalse(singular.contains("1 jours"))
                        assertTrue(plural.contains("2 jours"))
                    }
                    else -> {
                        assertTrue(singular.contains("1-day"))
                        assertTrue(plural.contains("2-day"))
                    }
                }
            }
            val day = when (locale.language) { "de" -> "Tag"; "fr" -> "jour"; else -> "day" }
            assertEquals("1 $day", strings.quantityText(R.plurals.daily_streak_days, 1, 1))
            assertEquals(day, strings.quantityText(R.plurals.dashboard_streak_days, 1))
        }
    }

    @Test fun germanSummaryPreservesNounsAndTheRestartAction() {
        val strings = TestBriefStrings(Locale.GERMAN)
        val restart = BriefCard("start-streak", BriefCardType.STREAK, "Starte einen Streak", "Tweete heute.", 84)
        val summary = BriefEditorialSummary.from(listOf(restart), strings)
        assertEquals("Starte deinen Posting-Rhythmus neu.", summary.body)
        assertEquals(summary.body, summary.shortDescription)
        assertFalse(summary.shortDescription.contains("ist aktiv"))
        val growth = BriefEditorialSummary.from(
            listOf(BriefCard("goal", BriefCardType.MILESTONE, "Ziel", "Fortschritt", 90)),
            strings, followersToday = 1, followersWeek = 2)
        assertTrue(growth.body.contains("Follower"))
        assertTrue(growth.body.contains("Fortschritt"))
        assertTrue(growth.body.contains("Ziel"))
    }

    @Test fun cachedSummaryWithoutCompactCopyUsesTheRestartFallback() {
        val strings = TestBriefStrings(Locale.GERMAN)
        val snapshot = snapshot(strings).copy(headline = "Neustart", subheading = "Starte neu.", language = "de",
            cards = listOf(BriefCard("start-streak", BriefCardType.STREAK, "Start", "Tweete heute.", 84)))
        assertEquals("Starte deinen Posting-Rhythmus neu.", BriefEditorialSummary.from(snapshot, strings).shortDescription)
    }

    @Test fun bothAiProvidersFollowGermanGrammarRatherThanEnglishCasing() {
        val german = TestBriefStrings(Locale.GERMAN)
        for (prompt in listOf(promptFor(snapshot(german), german), localPromptFor(snapshot(german), german))) {
            assertTrue(prompt.contains("Capitalise all German nouns"))
            assertTrue(prompt.contains("Follower, Fortschritt, Ziel"))
            assertTrue(prompt.contains("Starte deinen Posting-Rhythmus neu."))
            assertFalse(prompt.contains("Capitalise only the first word"))
            assertFalse(prompt.contains("'followers' otherwise"))
        }
        val french = TestBriefStrings(Locale.FRENCH)
        assertFalse(localPromptFor(snapshot(french), french).contains("'followers' otherwise"))
        assertTrue(languageInstruction(french).contains("singular/plural rules"))
    }

    private fun snapshot(strings: BriefStrings) = BriefSnapshot(
        username = "test", generatedAt = 1, sourceSyncedAt = 1, analyticsCachedAt = 0,
        followerScanCompletedAt = 0, followers = 10, following = 1, posts = 1,
        followersToday = 0, followersWeek = 0, cards = emptyList(), topFollowerRanks = emptyMap(),
        language = strings.languageTag,
    )
}
