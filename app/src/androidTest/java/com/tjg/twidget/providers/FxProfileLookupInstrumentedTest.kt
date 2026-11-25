package com.tjg.twidget.providers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.analytics.WeeklyTimelineRepository
import com.tjg.twidget.data.TwidgetStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FxProfileLookupInstrumentedTest {
    @Test fun knownProfileAndWeeklyTimelineUseLiveIdRoutes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = TwidgetStore.currentStats(context)
        assumeTrue(saved.userId.matches(Regex("[0-9]+")))
        // The intentionally unrelated handle must never be looked up when an ID exists.
        val profile = FxTwitterClient.fetchProfile("unused_handle", saved.userId)
        assertEquals(saved.userId, profile.userId)
        assertTrue(profile.followersKnown)
        assertTrue(profile.userName.isNotBlank())
        WeeklyTimelineRepository.invalidateCompleted(profile.userName)
        val timeline = WeeklyTimelineRepository.fxTwitter(profile.userName, profile.userId)
        assertTrue(timeline.checkedAt > 0)
        assertTrue(timeline.statuses.size <= 200)
    }
}
