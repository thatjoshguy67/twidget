package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BriefReviewComparisonsTest {
    private fun post(id: String, author: String = "example") = PostSummary(
        url = "https://x.com/$author/status/$id", text = "A recent post", timestamp = 1,
        views = 100, likes = 1, replies = 0, reposts = 0, quotes = 0, engagements = 1, createdAt = "", authorName = author, authorAvatar = "", authorUserName = author)

    @Test fun comparisonsAreOwnUniqueBoundedAndContinueAfterFailures() = runBlocking {
        val selected = post("1")
        val peers = listOf(selected, post("foreign", "other"), post("2"), post("2")) + (3..8).map { post("$it") }
        val visited = mutableListOf<String>()
        val notes = BriefReviewComparisons.collect(peers, selected.url, "example") {
            visited += it.url
            if (it.url.endsWith("/3")) error("Unavailable peer")
            "Saved analysis"
        }
        assertEquals(listOf("2", "3", "4", "5").map { post(it).url }, visited)
        assertEquals(3, notes.size)
        assertFalse(notes.containsKey(selected.url))
    }

    @Test(expected = CancellationException::class) fun cancellationStopsTheComparisonBatch() = runBlocking {
        BriefReviewComparisons.collect(listOf(post("2")), null, "example") { throw CancellationException("Cancelled") }
        Unit
    }
}
