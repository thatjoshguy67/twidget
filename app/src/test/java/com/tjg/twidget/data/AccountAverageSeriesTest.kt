package com.tjg.twidget.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountAverageSeriesTest {
    @Test
    fun `first week has no account average`() {
        val week = weekStarting("2026-07-06T00:00:00Z", 100)

        assertEquals(emptyList<Long>(), AccountAverageSeries.values(week, week, HistorySample::followers))
    }

    @Test
    fun `second week is aligned to prior weekdays`() {
        val first = weekStarting("2026-07-06T00:00:00Z", 100)
        val second = weekStarting("2026-07-13T00:00:00Z", 200)

        assertEquals(
            first.map(HistorySample::followers),
            AccountAverageSeries.values(first + second, second, HistorySample::followers),
        )
    }

    @Test
    fun `multiple historical weeks are averaged by weekday`() {
        val first = weekStarting("2026-06-29T00:00:00Z", 100)
        val second = weekStarting("2026-07-06T00:00:00Z", 200)
        val current = weekStarting("2026-07-13T00:00:00Z", 300)

        assertEquals(
            (0L..6L).map { 150L + it },
            AccountAverageSeries.values(first + second + current, current, HistorySample::followers),
        )
    }

    @Test
    fun `sparse imported history still produces a complete baseline`() {
        val historical = listOf(sample(Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(), 80, imported = true))
        val current = weekStarting("2026-07-13T00:00:00Z", 200)

        assertEquals(
            List(7) { 80L },
            AccountAverageSeries.values(historical + current, current, HistorySample::followers),
        )
    }

    private fun weekStarting(start: String, base: Long): List<HistorySample> {
        val first = Instant.parse(start).toEpochMilli()
        return (0L..6L).map { day -> sample(first + day * DAY_MILLIS, base + day) }
    }

    private fun sample(timestamp: Long, followers: Long, imported: Boolean = false) = HistorySample(
        dayLabel = "",
        followers = followers,
        following = 0,
        posts = 0,
        likes = 0,
        timestamp = timestamp,
        imported = imported,
    )

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
