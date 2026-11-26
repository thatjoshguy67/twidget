package com.tjg.twidget.brief

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BriefProviderRoutingTest {
    private fun snapshot(complete: Boolean) = BriefSnapshot(
        username = "person", generatedAt = 1, sourceSyncedAt = 1, analyticsCachedAt = 1,
        followerScanCompletedAt = 0, followers = 100, following = 20, posts = 30,
        followersToday = 0, followersWeek = 0, topFollowerRanks = emptyMap(),
        cards = listOf(BriefCard("context", BriefCardType.FOLLOWER_CONTEXT, "Your activity", "Your recent activity.", 70,
            narrativeGenerated = complete)),
        providerUsed = BriefProviderUsed.LOCAL, headline = "Your activity", subheading = "Your recent activity.",
        shortDescription = "Your recent activity.", summaryGenerated = true,
    )

    @Test fun completeLocalGenerationNeverInvokesCloud() = runBlocking {
        val local = snapshot(true)
        val result = BriefProviderRouting.auto(local = { local }, cloud = { fail("Local succeeded"); null })
        assertSame(local, result)
    }

    @Test fun incompleteLocalWorkReachesSelectedCloudOnlyAfterTheLocalAttempt() = runBlocking {
        val calls = mutableListOf<String>()
        val partial = snapshot(false)
        val result = BriefProviderRouting.auto(local = { calls += "local"; partial }, cloud = {
            calls += "selected-cloud"
            assertSame(partial, it)
            snapshot(true).copy(providerUsed = BriefProviderUsed.CLOUD)
        })!!
        assertEquals(listOf("local", "selected-cloud"), calls)
        assertEquals(BriefProviderUsed.CLOUD, result.providerUsed)
        assertTrue(result.hasGeneratedNarrative)
    }

    @Test fun failedCloudKeepsTheValidLocalFields() = runBlocking {
        val partial = snapshot(false)
        assertSame(partial, BriefProviderRouting.auto(local = { partial }, cloud = { null }))
        var cloudInvoked = false
        val cloud = snapshot(true).copy(providerUsed = BriefProviderUsed.CLOUD)
        assertSame(cloud, BriefProviderRouting.auto(local = { null }, cloud = { cloudInvoked = true; cloud }))
        assertTrue(cloudInvoked)
    }
}
