package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import org.junit.Assert.*
import org.junit.Test

class BriefInsightPolicyTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val strings = TestBriefStrings()
    private fun post(id: String, views: Long = 100, image: Boolean = false, age: Long = 2 * day) = PostSummary(
        "https://x.com/person/status/$id", "Post $id", views, 5, 2, 0, 1, 8,
        now - age, "", "Person", "person", "", media = if (image) listOf(PostMedia("photo", "", "", 100, 100)) else emptyList())
    private fun data(posts: List<PostSummary>) = PostAnalytics(
        userName = "person", followers = 100, postsAnalyzed = posts.size, windowDays = 7,
        totalViews = 999999, avgViews = 100.0, medianViews = 100.0, avgViewsPerFollower = 1.0,
        totalEngagements = 999999, avgEngagements = 8.0, medianEngagements = 8.0,
        avgEngagementsPerFollower = .08, engagementRate = .08, best = null, worst = null,
        cachedAt = now, recentPosts = posts)
    private fun stats() = ProfileStats("Person", "person", 120, 20, 30, 0, syncedAt = now)
    private fun sample(days: Int, followers: Long) = HistorySample("", followers, 20, 30, 0, now - days * day)

    @Test fun conversationUsesOnlyKnownOriginalUniquePostsAndLocalTotals() {
        val posts = (1..3).map { post("$it") }
        val card = BriefInsightPolicy.conversation(data(posts + posts + post("foreign").copy(authorUserName = "other") +
            post("reply").copy(isReply = true) + post("unknown").copy(viewsKnown = false)), strings, now)!!
        assertEquals("30", card.metrics[0].value)
        assertEquals("6", card.metrics[1].value)
        assertEquals("3", card.metrics[2].value)
        assertFalse(card.body.contains("999"))
        assertNull(BriefInsightPolicy.conversation(data(posts).copy(cachedAt = now - 3 * day), strings, now))
        assertNull(BriefInsightPolicy.conversation(data(posts.take(2)), strings, now))
    }
    @Test fun formatsRequireEnoughSimilarAgeTweetsAndUseMedianNotOutlier() {
        val text = (1..3).map { post("text$it") }
        val images = (1..3).map { post("image$it", if (it == 3) 10000 else 200, image = true) }
        val card = BriefInsightPolicy.contentPattern(data(text + images), strings, now)!!
        assertEquals("200", card.metrics[0].value)
        assertEquals("100", card.metrics[1].value)
        assertEquals("6", card.metrics[2].value)
        assertTrue(card.body.contains("not proof"))
        assertNull(BriefInsightPolicy.contentPattern(data(text + images.take(2)), strings, now))
        assertNull(BriefInsightPolicy.contentPattern(data(text + images.map { it.copy(timestamp = now - 6 * day) }), strings, now))
        assertNull(BriefInsightPolicy.contentPattern(data(text + images.map { it.copy(viewsKnown = false) }), strings, now))
    }
    @Test fun topicsNeedRepeatedHashtagsAndComparableUntargetedTweets() {
        val tagged = (1..3).map { post("tag$it", 200).copy(text = "A post about #Android #Android") }
        val others = (1..3).map { post("other$it") }
        val card = BriefInsightPolicy.topic(data(tagged + others), strings, now)!!
        assertTrue(card.body.contains("#android"))
        assertEquals("3", card.metrics.last().value)
        assertNull(BriefInsightPolicy.topic(data(tagged + others.take(2)), strings, now))
        assertNull(BriefInsightPolicy.topic(data(tagged.map { it.copy(text = "No subject tag") } + others), strings, now))
        assertNull(BriefInsightPolicy.topic(data(tagged + others.map { it.copy(timestamp = now - 6 * day) }), strings, now))
    }
    @Test fun experimentRequiresAnObservedPatternAndOnlyOpensTheComposer() {
        val comparison = data((1..3).map { post("text$it") } + (1..3).map { post("image$it", 200, image = true) })
        val card = BriefInsightPolicy.experiment(comparison, strings, now)!!
        assertEquals(BriefCardAction.COMPOSE_TWEET, card.action)
        assertTrue(card.body.contains("not a guaranteed outcome"))
        assertNull(BriefInsightPolicy.experiment(data(listOf(post("a"))), strings, now))
    }
    @Test fun momentumRequiresTwoRealWeeksAndPreservesSignedChanges() {
        val card = BriefInsightPolicy.momentum(stats(), listOf(sample(7, 110), sample(14, 115)), strings, now)!!
        assertEquals("+10", card.metrics[0].value)
        assertEquals("-5", card.metrics[1].value)
        assertNull(BriefInsightPolicy.momentum(stats(), listOf(sample(7, 110)), strings, now))
        assertNull(BriefInsightPolicy.momentum(stats(), listOf(sample(7, 110), sample(14, 115).copy(estimated = true)), strings, now))
        assertNull(BriefInsightPolicy.momentum(stats().copy(followersKnown = false), listOf(sample(7, 110), sample(14, 115)), strings, now))
    }
    @Test fun recapLabelsObservedSampleAndNeverTreatsViewsAsWeeklyGains() {
        val data = data((1..3).map { post("$it") })
        val card = BriefInsightPolicy.recap(stats(), listOf(sample(7, 110)), data, strings, now)!!
        assertEquals("+10", card.metrics[0].value)
        assertEquals("3", card.metrics[1].value)
        assertEquals("100", card.metrics[2].value)
        assertTrue(card.body.contains("sample"))
        assertTrue(card.body.contains("lifetime"))
        assertNull(BriefInsightPolicy.recap(stats(), emptyList(), data, strings, now))
        assertNull(BriefInsightPolicy.recap(stats(), listOf(sample(7, 110)), data.copy(windowDays = 2), strings, now))
    }
    @Test fun eachNewCardHasAnIndependentContentToggle() {
        assertTrue(BriefContentCategory.CONVERSATIONS.includes(BriefCardType.CONVERSATION_PULSE))
        assertTrue(BriefContentCategory.CONTENT_PATTERNS.includes(BriefCardType.CONTENT_PATTERN))
        assertTrue(BriefContentCategory.AUDIENCE_MOMENTUM.includes(BriefCardType.AUDIENCE_MOMENTUM))
        assertTrue(BriefContentCategory.WEEKLY_RECAP.includes(BriefCardType.WEEKLY_RECAP))
        assertFalse(BriefContentCategory.TOP_TWEET.includes(BriefCardType.CONTENT_PATTERN))
    }
}
