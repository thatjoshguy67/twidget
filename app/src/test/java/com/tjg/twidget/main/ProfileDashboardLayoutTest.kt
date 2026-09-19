package com.tjg.twidget.main

import org.junit.Assert.*
import org.junit.Test

class ProfileDashboardLayoutTest {
    @Test fun migrationKeepsLegacyChoicesAndIntroducesPlatformCards() {
        assertEquals(listOf("followers", "metric:yt:subscribers", "combined_audience"), ProfileDashboardLayout.resolve(
            null, emptySet(), listOf("followers", "posts", "metric:yt:subscribers", "combined_audience"),
            listOf("followers", "metric:yt:subscribers", "combined_audience")))
    }
    @Test fun reorderAndRemovalSurviveRefreshAndNewAccount() {
        assertEquals(listOf("metric:yt:subscribers", "followers", "metric:gh:stars"), ProfileDashboardLayout.resolve(
            listOf("metric:yt:subscribers", "followers"), setOf("followers", "metric:yt:subscribers", "combined_audience"),
            listOf("followers", "metric:yt:subscribers", "combined_audience", "metric:gh:stars"),
            listOf("followers", "metric:yt:subscribers", "combined_audience", "metric:gh:stars")))
    }
    @Test fun detachedAccountCardsDisappearWithoutRevivingHiddenCards() {
        assertEquals(listOf("followers"), ProfileDashboardLayout.resolve(listOf("metric:yt:subscribers", "followers"),
            setOf("followers", "metric:yt:subscribers", "posts"), listOf("followers", "posts"), listOf("followers", "posts")))
    }
}
