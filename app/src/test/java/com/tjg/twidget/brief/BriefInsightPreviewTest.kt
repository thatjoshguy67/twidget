package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test

class BriefInsightPreviewTest {
    @Test fun previewShowsEveryInsightWithoutAccountHistoryOrActions() {
        val source = BriefSnapshot(username = "preview", generatedAt = 0, sourceSyncedAt = 0,
            analyticsCachedAt = 0, followerScanCompletedAt = 0, followers = 1, following = 1,
            posts = 0, followersToday = 0, followersWeek = 0, topFollowerRanks = emptyMap(), cards = emptyList())
        val preview = BriefInsightPreview.snapshot(TestBriefStrings(), source, 1_800_000_000_000L)
        assertEquals(setOf(BriefCardType.CONVERSATION_PULSE, BriefCardType.CONTENT_PATTERN,
            BriefCardType.AUDIENCE_MOMENTUM, BriefCardType.WEEKLY_RECAP,
            BriefCardType.TOPIC_SPOTLIGHT, BriefCardType.CONTENT_EXPERIMENT), preview.cards.map { it.type }.toSet())
        assertTrue(preview.cards.all { it.action == BriefCardAction.NONE })
        assertEquals(BriefProviderUsed.TEMPLATE, preview.providerUsed)
        assertTrue(preview.subheading.contains("Sample data"))
        assertEquals(1L, source.followers)
    }
}
