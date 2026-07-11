package com.tjg.twidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneId

class XAnalyticsCsvImporterTest {
    @Test
    fun `reconstructs follower totals backwards from selected account anchor`() {
        val csv = """
            Date,Impressions,New follows,Unfollows
            "Sat, Jul 11, 2026",100,5,2
            "Fri, Jul 10, 2026",90,4,1
            "Thu, Jul 9, 2026",80,1,3
        """.trimIndent()

        val result = XAnalyticsCsvImporter.parse(
            StringReader(csv),
            anchorFollowers = 1_000,
            today = LocalDate.of(2026, 7, 11),
            zoneId = ZoneId.of("Europe/London"),
        )

        assertEquals(LocalDate.of(2026, 7, 9), result.firstDate)
        assertEquals(LocalDate.of(2026, 7, 11), result.lastDate)
        assertEquals(listOf(994L, 997L, 1_000L), result.samples.map { it.followers })
        assertEquals(listOf(1L, 4L, 5L), result.movements.map { it.newFollows })
        assertEquals(null, result.detectedFollowers)
        assertTrue(result.samples.all { it.imported && it.followersKnown })
        assertTrue(result.samples.all { !it.estimated })
        assertTrue(result.samples.all { !it.followingKnown && !it.postsKnown && !it.likesKnown })
    }

    @Test
    fun `local validation accepts matching trusted anchors`() {
        val imported = listOf(history(1, 994, true), history(2, 997, true), history(3, 1_000, true))
        val trusted = listOf(history(1, 995), history(2, 997), history(3, 1_000))

        assertEquals(3, XAnalyticsImportPolicy.validate(imported, trusted, 1_000))
        assertEquals(8, XAnalyticsImportPolicy.trendTolerance(7, 7_647))
    }

    @Test
    fun `local validation refuses edited movement and missing history`() {
        val imported = listOf(history(1, 900, true), history(2, 997, true), history(3, 1_000, true))
        val trusted = listOf(history(1, 994), history(2, 997), history(3, 1_000))

        assertTrue(runCatching { XAnalyticsImportPolicy.validate(imported, trusted, 1_000) }.isFailure)
        assertTrue(runCatching { XAnalyticsImportPolicy.validate(imported, trusted.takeLast(1), 1_000) }.isFailure)
    }

    @Test
    fun `latest mismatch reports stored and detected follower counts`() {
        val imported = listOf(history(1, 994, true), history(2, 997, true), history(3, 1_050, true))

        val error = runCatching {
            XAnalyticsImportPolicy.validate(imported, listOf(history(1, 994)), 1_000)
        }.exceptionOrNull() as AnalyticsValidationException

        assertEquals("analytics_follower_mismatch", error.code)
        assertEquals(1_000L, error.expectedFollowers)
        assertEquals(1_050L, error.detectedFollowers)
    }

    @Test
    fun `explicit follower total is detected and compared with cached count`() {
        val csv = """
            Date,New follows,Unfollows,Followers
            "Sat, Jul 11, 2026",1,0,150
        """.trimIndent()

        val error = runCatching {
            XAnalyticsCsvImporter.parse(
                StringReader(csv),
                anchorFollowers = 100,
                today = LocalDate.of(2026, 7, 11),
            )
        }.exceptionOrNull() as AnalyticsValidationException

        assertEquals(100L, error.expectedFollowers)
        assertEquals(150L, error.detectedFollowers)
    }

    @Test
    fun `impossible reconstruction reports cached count and missing detection`() {
        val csv = """
            Date,New follows,Unfollows
            "Sat, Jul 11, 2026",6,0
            "Fri, Jul 10, 2026",6,0
        """.trimIndent()

        val error = runCatching {
            XAnalyticsCsvImporter.parse(
                StringReader(csv),
                anchorFollowers = 10,
                today = LocalDate.of(2026, 7, 11),
            )
        }.exceptionOrNull() as AnalyticsCsvException

        assertEquals(10L, error.cachedFollowers)
        assertEquals(null, error.detectedFollowers)
        assertTrue(error.message.orEmpty().contains("+12"))
        assertTrue(error.message.orEmpty().contains("-2"))
    }

    @Test
    fun `imports all available daily analytics metrics`() {
        val csv = """
            Date,Impressions,Likes,Engagements,Bookmarks,Shares,New follows,Unfollows,Replies,Reposts,Profile visits,Create Post,Video views,Media views
            "Sat, Jul 11, 2026",156,3,6,0,0,1,0,1,0,2,1,4,5
        """.trimIndent()

        val result = XAnalyticsCsvImporter.parse(
            StringReader(csv),
            anchorFollowers = 100,
            today = LocalDate.of(2026, 7, 11),
        )
        val day = result.movements.single()

        assertEquals(156L, day.impressions)
        assertEquals(3L, day.likes)
        assertEquals(6L, day.engagements)
        assertEquals(0L, day.bookmarks)
        assertEquals(0L, day.shares)
        assertEquals(1L, day.replies)
        assertEquals(0L, day.reposts)
        assertEquals(2L, day.profileVisits)
        assertEquals(1L, day.postsCreated)
        assertEquals(4L, day.videoViews)
        assertEquals(5L, day.mediaViews)
    }

    @Test
    fun `rejects engagement totals below their visible components`() {
        val csv = """
            Date,Likes,Engagements,Bookmarks,Shares,New follows,Unfollows,Replies,Reposts
            "Sat, Jul 11, 2026",5,6,1,0,0,0,1,1
        """.trimIndent()

        val error = runCatching {
            XAnalyticsCsvImporter.parse(
                StringReader(csv),
                anchorFollowers = 100,
                today = LocalDate.of(2026, 7, 11),
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("visible interaction actions"))
    }

    private fun history(day: Long, followers: Long, imported: Boolean = false) = HistorySample(
        dayLabel = "Jul $day",
        followers = followers,
        following = 0,
        posts = 0,
        likes = 0,
        timestamp = day * 24 * 60 * 60 * 1000,
        imported = imported,
    )

    @Test
    fun `rejects stale exports that cannot be anchored accurately`() {
        val csv = """
            Date,New follows,Unfollows
            "Fri, Jul 10, 2026",1,0
        """.trimIndent()

        val error = runCatching {
            XAnalyticsCsvImporter.parse(
                StringReader(csv),
                anchorFollowers = 100,
                today = LocalDate.of(2026, 7, 11),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("newest row must be today", ignoreCase = true))
    }

    @Test
    fun `rejects missing dates instead of inventing movement`() {
        val csv = """
            Date,New follows,Unfollows
            "Sat, Jul 11, 2026",1,0
            "Thu, Jul 9, 2026",1,0
        """.trimIndent()

        val error = runCatching {
            XAnalyticsCsvImporter.parse(
                StringReader(csv),
                anchorFollowers = 100,
                today = LocalDate.of(2026, 7, 11),
            )
        }.exceptionOrNull()

        assertFalse(error == null)
        assertTrue(error?.message.orEmpty().contains("gap", ignoreCase = true))
    }
}
