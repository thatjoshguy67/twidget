package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefHomeIconPolicyTest {
    @Test fun mixedAudienceResultsUseTheSameResetStateAsTheHeadline() {
        for (type in listOf(BriefCardType.SUMMARY, BriefCardType.FOLLOWER_CONTEXT, BriefCardType.AUDIENCE_MOMENTUM)) {
            assertEquals(BriefCardType.INACTIVITY, BriefHomeIconPolicy.artworkType(type, 4, -18, 0))
            assertEquals(BriefCardType.INACTIVITY, BriefHomeIconPolicy.artworkType(type, -4, 18, 0))
        }
    }

    @Test fun audienceGrowthAndNoChangeRemainDistinct() {
        assertEquals(BriefCardType.GROWTH, BriefHomeIconPolicy.artworkType(BriefCardType.FOLLOWER_CONTEXT, 4, 18, 0))
        assertEquals(BriefCardType.FOLLOWER_CONTEXT, BriefHomeIconPolicy.artworkType(BriefCardType.FOLLOWER_CONTEXT, 0, 0, 0))
    }

    @Test fun restartAndActiveStreakUseDifferentArtwork() {
        assertEquals(BriefCardType.INACTIVITY, BriefHomeIconPolicy.artworkType(BriefCardType.STREAK, 4, 18, 0))
        assertEquals(BriefCardType.STREAK, BriefHomeIconPolicy.artworkType(BriefCardType.STREAK, -4, -18, 5))
        assertEquals(BriefCardType.CONTENT_EXPERIMENT, BriefHomeIconPolicy.artworkType(BriefCardType.CONTENT_EXPERIMENT, -4, -18, 0))
    }
}
