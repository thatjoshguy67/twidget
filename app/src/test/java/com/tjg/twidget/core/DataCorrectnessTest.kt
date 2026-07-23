package com.tjg.twidget.core

import com.tjg.twidget.data.HistorySample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DataCorrectnessTest {
    @Test
    fun `direct provider success never invokes optional bridge`() {
        var bridgeCalls = 0

        val value = ProviderFallback.directThenOptionalFallback(
            direct = { "direct" },
            fallback = { bridgeCalls++; "bridge" },
        )

        assertEquals("direct", value)
        assertEquals(0, bridgeCalls)
    }

    @Test
    fun `optional bridge can recover a failed direct provider`() {
        val value = ProviderFallback.directThenOptionalFallback(
            direct = { throw IllegalStateException("direct unavailable") },
            fallback = { "bridge" },
        )

        assertEquals("bridge", value)
    }

    @Test
    fun `failed fallback preserves selected provider error`() {
        val directError = IllegalStateException("direct unavailable")
        val thrown = runCatching {
            ProviderFallback.directThenOptionalFallback<String>(
                direct = { throw directError },
                fallback = { throw IllegalArgumentException("bridge unavailable") },
            )
        }.exceptionOrNull()

        assertSame(directError, thrown)
    }

    @Test
    fun `Fx filtering accepts only own original posts inside the window`() {
        val now = Instant.parse("2026-07-10T12:00:00Z").toEpochMilli()
        val weekAgo = now - 7 * DAY_MS
        val original = candidate(now - DAY_MS)

        assertTrue(FxPostPolicy.isOwnOriginalInWindow(original, "@Example", weekAgo, now))
        assertFalse(FxPostPolicy.isOwnOriginalInWindow(original.copy(isRepost = true), "example", weekAgo, now))
        assertFalse(FxPostPolicy.isOwnOriginalInWindow(original.copy(isReply = true), "example", weekAgo, now))
        assertFalse(FxPostPolicy.isOwnOriginalInWindow(original.copy(authorUsername = "someoneelse"), "example", weekAgo, now))
        assertFalse(FxPostPolicy.isOwnOriginalInWindow(original.copy(timestamp = weekAgo - 1), "example", weekAgo, now))
        assertFalse(FxPostPolicy.isOwnOriginalInWindow(original.copy(conversationId = "parent"), "example", weekAgo, now))
    }

    @Test
    fun `analytics pagination ignores an old pinned first row`() {
        val weekAgo = 1_000L

        assertFalse(AnalyticsPaging.reachedWindowBoundary(listOf(100L, 2_000L, 1_500L), weekAgo))
        assertTrue(AnalyticsPaging.reachedWindowBoundary(listOf(2_000L, 1_500L, 900L), weekAgo))
        assertTrue(AnalyticsPaging.reachedWindowBoundary(emptyList(), weekAgo))
    }

    @Test
    fun `server day label stays on that local calendar date across BST`() {
        val timestamp = Instant.parse("2026-07-10T00:00:00Z").toEpochMilli()
        val zone = ZoneId.of("Europe/London")

        val local = HistoryDates.localDayFromServer(timestamp, "Jul 10", TimeZone.getTimeZone(zone))

        assertEquals(LocalDate.of(2026, 7, 10), Instant.ofEpochMilli(local).atZone(zone).toLocalDate())
        assertEquals(0, Instant.ofEpochMilli(local).atZone(zone).hour)
    }

    @Test
    fun `server label chooses closest year around new year`() {
        val timestamp = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val zone = ZoneId.of("Europe/London")

        val local = HistoryDates.localDayFromServer(timestamp, "Dec 31", TimeZone.getTimeZone(zone))

        assertEquals(LocalDate.of(2025, 12, 31), Instant.ofEpochMilli(local).atZone(zone).toLocalDate())
    }

    @Test
    fun `migration matches only exact synthetic follower ramp`() {
        val start = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
        val gains = listOf(18L, 9L, 21L, 15L, 14L, 26L)
        var followers = 100L
        val samples = (0..gains.size).map { day ->
            if (day > 0) followers += gains[day - 1]
            history(start + day * DAY_MS, followers)
        }

        assertTrue(HistoryMigrationPolicy.matchesLegacySeededRamp(samples, gains) { it })
        assertFalse(HistoryMigrationPolicy.matchesLegacySeededRamp(samples.map { it.copy(followers = 100) }, gains) { it })
        assertFalse(HistoryMigrationPolicy.matchesLegacySeededRamp(samples.drop(1), gains) { it })
    }

    @Test
    fun `known live zero likes is not replaced by stale cached likes`() {
        assertEquals(
            KnownLong(0, true),
            MetricProvenance.preferLiveThenPrevious(0, true, 42, true),
        )
    }

    @Test
    fun `unknown official likes preserve known cached value without fabricating zero`() {
        assertEquals(
            KnownLong(42, true),
            MetricProvenance.preferLiveThenPrevious(null, false, 42, true),
        )
        assertEquals(
            KnownLong(0, false),
            MetricProvenance.preferLiveThenPrevious(null, false, 0, false),
        )
    }

    private fun candidate(timestamp: Long) = FxStatusCandidate(
        type = "status",
        authorUsername = "example",
        url = "https://x.com/example/status/123",
        timestamp = timestamp,
        id = "123",
        conversationId = "123",
        isRepost = false,
        isReply = false,
    )

    private fun history(timestamp: Long, followers: Long) = HistorySample(
        dayLabel = "",
        followers = followers,
        following = 1,
        posts = 1,
        likes = 1,
        timestamp = timestamp,
    )

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
