package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test

class BriefInsightVisualTest {
    private val source = BriefSnapshot(username = "preview", generatedAt = 1_800_000_000_000L,
        sourceSyncedAt = 0, analyticsCachedAt = 0, followerScanCompletedAt = 0, followers = 0,
        following = 0, posts = 0, followersToday = 0, followersWeek = 0, topFollowerRanks = emptyMap(), cards = emptyList())
    @Test fun everyPreviewHasNumericEvidenceAndSampleSizesStayAttachedToBars() {
        val preview = BriefInsightPreview.snapshot(TestBriefStrings(), source)
        assertTrue(preview.cards.all { it.chartPoints.isNotEmpty() })
        for (type in listOf(BriefCardType.CONTENT_PATTERN, BriefCardType.TOPIC_SPOTLIGHT)) {
            val card = preview.cards.single { it.type == type }
            assertEquals(listOf(3, 3), card.chartPoints.map { it.samples })
            assertEquals(listOf(300L, 100L), card.chartPoints.map { it.value })
        }
        val recap = preview.cards.single { it.type == BriefCardType.WEEKLY_RECAP }
        assertEquals(6L, recap.activityPoints.sumOf { it.value ?: 0 })
    }
    @Test fun chartEvidenceSurvivesStorageIncludingUnknownObservations() {
        val preview = BriefInsightPreview.snapshot(TestBriefStrings(), source)
        val withGap = preview.copy(cards = preview.cards.map { card ->
            if (card.type == BriefCardType.AUDIENCE_MOMENTUM) card.copy(chartPoints = card.chartPoints.mapIndexed { index, point ->
                if (index == 2) point.copy(value = null) else point
            }) else card
        })
        val restored = BriefStore.decode(BriefStore.encode(withGap))
        assertEquals(withGap.cards.map { it.chartPoints }, restored.cards.map { it.chartPoints })
        assertEquals(withGap.cards.map { it.activityPoints }, restored.cards.map { it.activityPoints })
        assertNull(restored.cards.single { it.type == BriefCardType.AUDIENCE_MOMENTUM }.chartPoints[2].value)
    }
    @Test fun zeroCountsAndFlatTrendsHaveSafeScales() {
        assertEquals(0f, BriefInsightChartPolicy.fraction(0, 0))
        assertEquals(.5f, BriefInsightChartPolicy.fraction(50, 100))
        assertEquals(1f, BriefInsightChartPolicy.fraction(150, 100))
        assertEquals(0f, BriefInsightChartPolicy.fraction(-1, 100))
        assertEquals(.5f, BriefInsightChartPolicy.heightFraction(100, 100, 100))
        assertEquals(.5f, BriefInsightChartPolicy.heightFraction(150, 100, 200))
    }
}
