package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefLaunchGenerationTest {
    @Test
    fun continueUsesSpinnerWhileGenerationIsRunning() {
        assertEquals(
            BriefContinueDestination.SPINNER,
            briefContinueDestination(generationComplete = false),
        )
    }

    @Test
    fun continueOpensBriefWhenGenerationIsComplete() {
        assertEquals(
            BriefContinueDestination.BRIEF,
            briefContinueDestination(generationComplete = true),
        )
    }

    @Test
    fun unavailableNanoWithoutCloudKeyRequiresApiKey() {
        assertTrue(briefRequiresApiKey(BriefLocalStatus.UNAVAILABLE, ""))
        assertTrue(briefRequiresApiKey(BriefLocalStatus.UNAVAILABLE, "   "))
    }

    @Test
    fun unavailableNanoWithCloudKeyCanUseCloud() {
        assertFalse(briefRequiresApiKey(BriefLocalStatus.UNAVAILABLE, "test-api-key"))
    }

    @Test
    fun supportedNanoStatesDoNotRequireCloudKey() {
        assertFalse(briefRequiresApiKey(BriefLocalStatus.AVAILABLE, ""))
        assertFalse(briefRequiresApiKey(BriefLocalStatus.DOWNLOADABLE, ""))
        assertFalse(briefRequiresApiKey(BriefLocalStatus.DOWNLOADING, ""))
    }
}
