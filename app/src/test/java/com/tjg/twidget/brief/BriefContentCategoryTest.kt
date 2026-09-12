package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BriefContentCategoryTest {
    @Test
    fun everyRenderedCardTypeMapsToTheExpectedUserFacingCategory() {
        assertEquals(BriefContentCategory.FOLLOWERS, BriefContentCategory.forCard(BriefCardType.SUMMARY))
        assertEquals(BriefContentCategory.FOLLOWERS, BriefContentCategory.forCard(BriefCardType.GROWTH))
        assertEquals(BriefContentCategory.FOLLOWERS, BriefContentCategory.forCard(BriefCardType.SLOWDOWN))
        assertEquals(BriefContentCategory.TWEET_ACTIVITY, BriefContentCategory.forCard(BriefCardType.INACTIVITY))
        assertEquals(BriefContentCategory.TWEET_ACTIVITY, BriefContentCategory.forCard(BriefCardType.STREAK))
        assertEquals(BriefContentCategory.ACCOUNT_GOALS, BriefContentCategory.forCard(BriefCardType.MILESTONE))
        assertEquals(BriefContentCategory.TOP_TWEET, BriefContentCategory.forCard(BriefCardType.POST))
        assertEquals(BriefContentCategory.WORST_TWEET, BriefContentCategory.forCard(BriefCardType.WORST_POST))
        assertEquals(BriefContentCategory.TOP_FOLLOWERS, BriefContentCategory.forCard(BriefCardType.TOP_FOLLOWER))
        assertEquals(BriefContentCategory.SCHEDULE_HEALTH, BriefContentCategory.forCard(BriefCardType.SCHEDULE_GUIDE))
        assertEquals(BriefContentCategory.POST_FOLLOW_THROUGH, BriefContentCategory.forCard(BriefCardType.POST_FOLLOW_THROUGH))
        assertEquals(BriefContentCategory.POSTING_GUIDANCE, BriefContentCategory.forCard(BriefCardType.POSTING_GUIDE))
    }

    @Test
    fun scheduledTweetsAreAStandaloneBlockRatherThanACardType() {
        assertNull(BriefContentCategory.entries
            .firstOrNull { it == BriefContentCategory.SCHEDULED_TWEETS }
            ?.takeIf { category -> BriefCardType.entries.any(category::includes) })
    }

    @Test
    fun onlyScheduleCategoriesRequestScheduledPostData() {
        val expected = setOf(
            BriefContentCategory.SCHEDULED_TWEETS,
            BriefContentCategory.SCHEDULE_HEALTH,
            BriefContentCategory.POST_FOLLOW_THROUGH,
        )

        assertEquals(expected, BriefContentCategory.entries.filterTo(linkedSetOf()) {
            it.usesScheduledPostData()
        })
    }
}
