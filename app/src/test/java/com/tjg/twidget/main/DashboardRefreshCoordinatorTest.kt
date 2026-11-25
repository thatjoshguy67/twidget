package com.tjg.twidget.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class DashboardRefreshCoordinatorTest {
    @Test fun visibleProfileAlwaysComesFirstWithoutDuplicates() {
        assertEquals(listOf("bob", "alice", "carol"), dashboardRefreshOrder(listOf("alice", "Bob", "carol", "@bob"), "bob"))
        assertEquals(listOf("alice", "bob"), dashboardRefreshOrder(listOf("alice", "bob"), "removed"))
    }
    @Test fun duplicateRequestsJoinButProfilesAndSourcesRemainIndependent() = runBlocking {
        val coordinator = DashboardRefreshCoordinator(this)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = coordinator.request("Alice", DashboardDataSource.POSTS) { calls++; gate.await(); true }
        val joined = coordinator.request("@alice", DashboardDataSource.POSTS) { error("Duplicate request") }
        val other = coordinator.request("bob", DashboardDataSource.POSTS) { true }
        val profile = coordinator.request("alice", DashboardDataSource.PROFILE) { false }
        assertSame(first, joined)
        assertNotSame(first, other)
        yield()
        assertEquals(DashboardFetchState.LOADING, coordinator.status("alice", DashboardDataSource.POSTS)?.state)
        gate.complete(Unit)
        assertTrue(first.await().getOrThrow())
        assertTrue(other.await().getOrThrow())
        assertFalse(profile.await().getOrThrow())
        assertEquals(1, calls)
        assertEquals(DashboardFetchState.UPDATED, coordinator.status("alice", DashboardDataSource.POSTS)?.state)
        assertEquals(DashboardFetchState.UNAVAILABLE, coordinator.status("alice", DashboardDataSource.PROFILE)?.state)
    }
    @Test fun failureIsDistinctFromUnavailableAndExplicitRetryBypassesAutomaticCooldown() = runBlocking {
        var now = 1000L
        val coordinator = DashboardRefreshCoordinator(this) { now }
        assertTrue(coordinator.request("alice", DashboardDataSource.POSTS) { throw java.io.IOException() }.await().isFailure)
        assertEquals(DashboardFetchState.FAILED, coordinator.status("alice", DashboardDataSource.POSTS)?.state)
        assertFalse(coordinator.mayAttempt("alice", DashboardDataSource.POSTS))
        assertTrue(coordinator.mayAttempt("bob", DashboardDataSource.POSTS))
        assertTrue(coordinator.request("alice", DashboardDataSource.POSTS) { true }.await().getOrThrow())
        assertEquals(DashboardFetchState.UPDATED, coordinator.status("alice", DashboardDataSource.POSTS)?.state)
        now += 60_000
        assertTrue(coordinator.mayAttempt("alice", DashboardDataSource.POSTS))
    }
}
