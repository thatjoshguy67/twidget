package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduleThreadItem
import com.tjg.twidget.schedule.ScheduledPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefGuidePolicyTest {
    private val now = 1_800_000_000_000L
    private val strings = TestBriefStrings()
    private val day = 24 * 60 * 60 * 1000L









    @Test
    fun nextPostGuidanceRequiresARepeatableSignal() {
        val posts = listOf(
            post("Visual one has enough useful text", now - 2 * day, 2_000, 60, media = true),
            post("Visual two has enough useful text", now - 3 * day, 1_800, 55, media = true),
            post("Plain one has enough useful text", now - 3 * day, 400, 10),
            post("Plain two has enough useful text", now - 5 * day / 2, 500, 12),
        )

        val card = BriefGuidePolicy.postingCard(analytics(posts), strings, now)

        assertEquals(BriefCardType.POSTING_GUIDE, card?.type)
        assertEquals(BriefCardAction.NONE, card?.action)
    }



    @Test
    fun formatAndTimingAdviceStayHiddenWhenAgeExplainsTheGrouping() {
        val posts = listOf(
            post("Older visual one", now - 6 * day, 2_000, 60, media = true),
            post("Older visual two", now - 7 * day, 1_800, 55, media = true),
            post("Younger plain one", now - day, 400, 10),
            post("Younger plain two", now - 5 * day / 4, 500, 12),
        )
        assertNull(BriefGuidePolicy.postingCard(analytics(posts), strings, now))
    }

    @Test
    fun formatExperimentUsesAgesAndMedianViewsWithEnoughPostsInEachGroup() {
        // No fresh breakout candidate; all four posts are 4–5 days old.
        val posts = listOf(
            post("Visual one", now - 4 * day, 2_000, 60, media = true),
            post("Visual two", now - 5 * day, 1_800, 55, media = true),
            post("Plain one", now - 9 * day / 2, 400, 10),
            post("Plain two", now - 19 * day / 4, 500, 12),
        )
        val card = BriefGuidePolicy.postingCard(analytics(posts), strings, now)
        assertTrue(card?.body.orEmpty().contains("median view count"))
        assertTrue(card?.body.orEmpty().contains("similar age"))
    }

    @Test
    fun cachedNewCountsNeverProduceMaturePostingAdvice() {
        val posts = (1..4).map { index ->
            post("New post $index", now - index * day / 24, index * 1_000L, index * 10L)
        }
        assertNull(BriefGuidePolicy.postingCard(analytics(posts), strings, now + 2 * day))
    }


    @Test fun postingWindowUsesEvidenceAndChangesCopyWithTheClock() {
        val zone = java.time.ZoneId.of("UTC")
        val base = java.time.Instant.ofEpochMilli(now).atZone(zone).minusDays(4).withMinute(0).withSecond(0).withNano(0)
        val posts = (0..2).flatMap { index -> listOf(
            post("Morning $index", base.withHour(8).plusMinutes(index.toLong()).toInstant().toEpochMilli(), 400),
            post("Evening $index", base.withHour(19).plusMinutes(index.toLong()).toInstant().toEpochMilli(), 900),
        ) }
        val evidence = analytics(posts)
        val signal = BriefPostingTiming.signal(evidence, now, zone)!!
        assertEquals(BriefPostingWindow.EVENING, signal.window)
        assertEquals(6, signal.sampleSize)
        assertTrue(BriefPostingTiming.card(signal, strings, 9).title.contains("Plan"))
        assertTrue(BriefPostingTiming.card(signal, strings, 19).title.contains("open"))
        assertTrue(BriefPostingTiming.card(signal, strings, 23).title.contains("tomorrow"))
        assertNull(BriefPostingTiming.signal(evidence.copy(recentPosts = posts.map { it.copy(viewsKnown = false) }), now, zone))
        assertNull(BriefPostingTiming.signal(evidence.copy(recentPosts = posts.map { it.copy(isReply = true) }), now, zone))
        assertNull(BriefPostingTiming.signal(evidence.copy(recentPosts = posts.drop(2)), now, zone))
        assertNull(BriefPostingTiming.signal(evidence, now + 8 * day, zone))
        assertNull(BriefPostingTiming.signal(evidence.copy(recentPosts = posts.map { it.copy(authorUserName = "other") }), now, zone))
    }

    @Test fun lateNightWindowWrapsMidnight() {
        assertEquals(BriefPostingPhase.NOW, BriefPostingTiming.phase(BriefPostingWindow.LATE_NIGHT, 0))
        assertEquals(BriefPostingPhase.NOW, BriefPostingTiming.phase(BriefPostingWindow.LATE_NIGHT, 23))
        assertEquals(BriefPostingPhase.UPCOMING, BriefPostingTiming.phase(BriefPostingWindow.LATE_NIGHT, 12))
        assertEquals(BriefPostingPhase.UPCOMING, BriefPostingTiming.phase(BriefPostingWindow.MORNING, 4))
        assertEquals(BriefPostingPhase.NEXT_DAY, BriefPostingTiming.phase(BriefPostingWindow.MORNING, 12))
    }

    private fun analytics(posts: List<PostSummary>) = PostAnalytics(
        userName = "person",
        followers = 1_000,
        postsAnalyzed = posts.size,
        windowDays = 7,
        totalViews = posts.sumOf { it.views },
        avgViews = 1_000.0,
        medianViews = 1_000.0,
        avgViewsPerFollower = 1.0,
        totalEngagements = posts.sumOf { it.engagements },
        avgEngagements = 25.0,
        medianEngagements = 25.0,
        avgEngagementsPerFollower = 0.025,
        engagementRate = 0.025,
        best = posts.maxByOrNull { it.engagements },
        worst = posts.minByOrNull { it.engagements },
        cachedAt = now,
        recentPosts = posts,
    )

    private fun post(
        text: String,
        timestamp: Long,
        views: Long = 1_000,
        engagements: Long = 25,
        media: Boolean = false,
    ) = PostSummary(
        url = "https://x.com/person/status/$timestamp-${text.hashCode()}",
        text = text,
        views = views,
        likes = engagements,
        replies = 0,
        reposts = 0,
        quotes = 0,
        engagements = engagements,
        timestamp = timestamp,
        createdAt = "",
        authorName = "Person",
        authorUserName = "person",
        authorAvatar = "",
        media = if (media) listOf(PostMedia("photo", "https://example.com/$timestamp.jpg", "", 1, 1)) else emptyList(),
    )
}
