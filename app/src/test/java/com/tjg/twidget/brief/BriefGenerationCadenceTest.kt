package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test

class BriefGenerationCadenceTest {
    private fun source() = BriefSnapshot("test", 1, 1, 1, 1, 8074, 20, 100, 4, -21,
        emptyList(), topFollowerRanks = emptyMap(), language = "en", contentSettingsFingerprint = "content",
        contextFingerprint = "goal|0|false|2026-11-25|100|2026-11-26:morning|content|auto:gemini:model|stable|en")

    @Test fun liveCountsSyncTimesAndTimeOfDayDoNotInvalidateTheDailyOverview() {
        val original = source()
        val updated = original.copy(followers = 8073, followersToday = 3, followersWeek = -22,
            sourceSyncedAt = 9000, analyticsCachedAt = 9000, followerScanCompletedAt = 9000,
            contextFingerprint = original.contextFingerprint.replace("|100|", "|9000|").replace(":morning|", ":afternoon|"))
        assertEquals(BriefGenerationCadence.scope(original), BriefGenerationCadence.scope(updated))
        assertTrue(BriefGenerationCadence.fresh(1, BriefGenerationCadence.REUSE_MS))
        assertFalse(BriefGenerationCadence.fresh(1, BriefGenerationCadence.REUSE_MS + 1))
        assertFalse(BriefGenerationCadence.fresh(10, 1))
    }

    @Test fun newPostsGoalsProvidersLanguageAndContentCanRefreshSooner() {
        val original = source()
        for (changed in listOf(original.copy(posts = 101), original.copy(username = "other"),
            original.copy(language = "de"), original.copy(contentSettingsFingerprint = "different"),
            original.copy(contextFingerprint = original.contextFingerprint.replace("goal|", "other-goal|")),
            original.copy(contextFingerprint = original.contextFingerprint.replace("auto:gemini:model", "cloud:key:model")))) {
            assertNotEquals(BriefGenerationCadence.scope(original), BriefGenerationCadence.scope(changed))
        }
    }
}
