package com.tjg.twidget.analytics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedFollowMovementsInstrumentedTest {
    @Test fun followOnlyExportsRetainUnfollowsAndObservedZeroDays() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "unfollow_test"
        val date = LocalDate.now().minusDays(1)
        val loss = XAnalyticsMovement(date, newFollows = 2, unfollows = 5)
        val zero = XAnalyticsMovement(date.plusDays(1), newFollows = 0, unfollows = 0)
        val previous = ImportedAnalyticsStore.all(context, account)
        try {
            ImportedAnalyticsStore.clear(context, account)
            ImportedAnalyticsStore.saveVerified(context, account, listOf(loss, zero))
            assertEquals(listOf(loss, zero), ImportedAnalyticsStore.all(context, account))
            ImportedAnalyticsStore.saveVerified(context, account, listOf(loss.copy(impressions = 100)))
            assertEquals(5L, ImportedAnalyticsStore.all(context, account).first().unfollows)
            assertEquals(100L, ImportedAnalyticsStore.all(context, account).first().impressions)
            assertEquals(0L, ImportedAnalyticsStore.all(context, account).last().unfollows)
        } finally {
            ImportedAnalyticsStore.clear(context, account)
            ImportedAnalyticsStore.saveVerified(context, account, previous)
        }
    }
}
