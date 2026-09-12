package com.tjg.twidget.brief

import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefUpcomingPrivacyTest {
    @Test
    fun aiPromptsNeverContainUnpublishedScheduledCopy() {
        val secret = "unpublished launch announcement"
        val snapshot = BriefSnapshot(
            username = "person",
            generatedAt = 1L,
            sourceSyncedAt = 1L,
            analyticsCachedAt = 1L,
            followerScanCompletedAt = 1L,
            followers = 10,
            following = 5,
            posts = 2,
            followersToday = 0,
            followersWeek = 0,
            cards = listOf(BriefCard("steady", BriefCardType.SUMMARY, "Steady", "All steady.", 50)),
            upcomingTweets = listOf(
                BriefUpcomingTweet(
                    id = "schedule",
                    provider = ScheduleProvider.BUFFER,
                    status = ScheduleStatus.SCHEDULED,
                    scheduledAt = 2L,
                    preview = secret,
                    threadCount = 1,
                    mediaCount = 0,
                ),
            ),
            topFollowerRanks = emptyMap(),
        )

        assertFalse(promptFor(snapshot).contains(secret))
        assertFalse(localPromptFor(snapshot).contains(secret))
        assertTrue(promptFor(snapshot).contains("__brief_summary__"))
        assertTrue(promptFor(snapshot).contains("shortDescription"))
        assertTrue(localPromptFor(snapshot).contains("\"s\""))
        assertTrue(localPromptFor(snapshot).contains("Use sentence case, never Title Case"))
    }
}
