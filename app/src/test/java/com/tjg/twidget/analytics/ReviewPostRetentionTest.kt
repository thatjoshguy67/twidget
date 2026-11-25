package com.tjg.twidget.analytics

import org.junit.Assert.*
import org.junit.Test

class ReviewPostRetentionTest {
    private fun post(id: Int) = PostSummary("https://x.com/test/status/$id", "post", id.toLong(),0,0,0,0,0,id.toLong(),"","Test","test","")
    private fun analytics(posts: List<PostSummary>, now: Long = 1000) = AnalyticsClient.analyticsFromPosts("test",100,posts.size,false,posts,cachedAt=now)
    @Test fun retainsMissingPostsUntilAnEleventhEvictsOldest() {
        val first = analytics((1..10).map(::post))
        val retained = first.copy(reviewPosts = ReviewPostRetention.merge(first,null))
        val empty = analytics(emptyList(), 2000)
        assertEquals((10 downTo 1).map { post(it).url }, ReviewPostRetention.merge(empty,retained).map { it.url })
        assertTrue(ReviewPostRetention.merge(empty,retained).all { it.observedAt == 1000L })
        val next = ReviewPostRetention.merge(analytics(listOf(post(11)),3000),retained)
        assertEquals((11 downTo 2).map { post(it).url },next.map { it.url })
        assertEquals(3000L,next.first().observedAt)
        assertEquals(1000L,next.last().observedAt)
    }
    @Test fun retainedCountsAndObservationTimesSurviveCacheRoundTrip() {
        val base = analytics(listOf(post(1)))
        val retained = base.copy(reviewPosts = ReviewPostRetention.merge(base,null))
        val restored = com.tjg.twidget.core.NetworkResponseParsers.parseBridgeAnalytics(AnalyticsClient.serialize(retained))
        assertEquals(retained.reviewPosts,restored.reviewPosts)
    }
    @Test fun updatesExistingPostWithoutDuplicatingOrMixingAccounts() {
        val old = analytics(listOf(post(1)))
        val current = analytics(listOf(post(1).copy(views=99)),2000)
        val result=ReviewPostRetention.merge(current,old)
        assertEquals(1,result.size); assertEquals(99L,result.single().views)
        assertEquals(2000L,result.single().observedAt)
        assertTrue(ReviewPostRetention.merge(analytics(emptyList()).copy(userName="other"),old).isEmpty())
    }
}
