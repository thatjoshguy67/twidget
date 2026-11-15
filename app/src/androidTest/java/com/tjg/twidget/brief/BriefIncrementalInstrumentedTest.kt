package com.tjg.twidget.brief

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefIncrementalInstrumentedTest {
    @Test fun partialUpdatesPersistAndNeverResurrectAContradictedSummary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "brief_incremental_fixture"
        val prefs = context.getSharedPreferences("twidget_briefs", Context.MODE_PRIVATE)
        val saved = prefs.getString(account, null)
        val strings = BriefStrings.from(context)
        val now = System.currentTimeMillis()
        val content = BriefSettingsStore.contentFingerprint(context)
        val cards = listOf(
            BriefCard("audience", BriefCardType.AUDIENCE_MOMENTUM, "Audience momentum", "Your audience grew by 20 this week.", 90),
            BriefCard("pattern", BriefCardType.CONTENT_PATTERN, "Content pattern", "Your images received more recorded views.", 80),
            BriefCard("recap", BriefCardType.WEEKLY_RECAP, "Weekly recap", "You published 6 sampled tweets.", 70),
            BriefCard("pulse", BriefCardType.CONVERSATION_PULSE, "Conversation pulse", "Your tweets received 10 replies.", 60))
        fun measured(values: List<BriefCard>, week: Long = 20) = BriefIncrementalPolicy.prepare(BriefSnapshot(
            account, now, now, now, 0, 100, 20, 30, 0, week, values,
            topFollowerRanks = emptyMap(), engineVersion = 26, language = strings.languageTag,
            contentSettingsFingerprint = content), strings, "fixture")
        try {
            val source = measured(cards)
            val generated = source.copy(headline = "Your audience update", subheading = "Your audience grew by 20 this week.",
                shortDescription = "Your audience grew by 20 this week.", summaryGenerated = true, summaryGeneratedAt = now,
                providerUsed = BriefProviderUsed.CLOUD, aiGeneratedAt = now,
                cards = source.cards.map { it.copy(narrativeGenerated = true, narrativeGeneratedAt = now) })
            BriefStore.write(context, generated)
            val remembered = BriefStore.presentation(context, account)!!
            val partial = BriefIncrementalPolicy.retain(generated,
                measured(cards.dropLast(1) + cards.last().copy(body = "Your tweets received 11 replies.")), now)
            BriefStore.write(context, partial)
            val restored = BriefStore.read(context, account)!!
            assertEquals(partial, restored)
            assertFalse(restored.hasGeneratedNarrative)
            assertEquals(listOf("pulse"), BriefIncrementalPolicy.pending(restored).cards.map { it.id })
            assertEquals(remembered, BriefStore.presentation(context, account))
            // Change a fact used by the summary while the new provider request
            // is still pending. The last successful overview must not reappear.
            val changed = BriefIncrementalPolicy.retain(restored,
                measured(cards.map { if (it.id == "audience") it.copy(body = "Your audience declined this week.") else it }, week = -1), now)
            BriefStore.write(context, changed)
            assertFalse(BriefStore.read(context, account)!!.summaryGenerated)
            assertEquals(BriefEditorialSummary.from(changed, strings).body, BriefStore.presentation(context, account)!!.body)
            assertNotEquals(remembered.body, BriefStore.presentation(context, account)!!.body)
        } finally {
            prefs.edit().apply { if (saved == null) remove(account) else putString(account, saved) }.commit()
        }
    }
}
