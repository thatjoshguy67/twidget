package com.tjg.twidget.analytics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedAnalyticsStoreTest {
    @Test
    fun `metric pairs expose every imported analytics value`() {
        val sample = XAnalyticsMovement(
            date = LocalDate.of(2026, 7, 11),
            newFollows = 2,
            unfollows = 1,
            impressions = 100,
            likes = 5,
            engagements = 9,
            bookmarks = 1,
            shares = 1,
            replies = 1,
            reposts = 1,
            profileVisits = 4,
            postsCreated = 2,
            videoViews = 3,
            mediaViews = 6,
        )

        assertEquals(11, sample.analyticsValues().size)
        assertEquals(100L, sample.analyticsValues().first().second)
    }

    @Test
    fun `previously imported daily metrics cannot be inflated`() {
        val date = LocalDate.of(2026, 7, 11)
        val saved = XAnalyticsMovement(date, 2, 1, impressions = 100, engagements = 9)
        val edited = saved.copy(impressions = 10_000)

        val error = runCatching { validateAnalyticsOverlap(saved, edited) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("impressions"))
        assertTrue(error?.message.orEmpty().contains("100 to 10000"))
    }
}
