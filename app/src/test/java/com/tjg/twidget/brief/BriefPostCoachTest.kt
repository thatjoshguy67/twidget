package com.tjg.twidget.brief

import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduleThreadItem
import com.tjg.twidget.schedule.ScheduledPost
import org.junit.Assert.*
import org.junit.Test

class BriefPostCoachTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val strings = TestBriefStrings()

    @Test fun `low totals with normal response are distinguished from weak response`() {
        val peers = List(3) { post("peer-$it", views = 1000, likes = 50) }
        val lowExposure = post("low-exposure", views = 200, likes = 10)
        val weakResponse = post("weak-response", views = 1000, likes = 10)
        val analytics = analytics(peers + lowExposure + weakResponse)
        val exposure = BriefPostCoach.review(lowExposure, analytics, strings, now)!!
        val response = BriefPostCoach.review(weakResponse, analytics, strings, now)!!
        assertTrue(exposure.possibleFactors.any { it.startsWith("Lower recorded exposure") })
        assertFalse(exposure.possibleFactors.any { it.startsWith("Visible response per view was weaker") })
        assertTrue(response.possibleFactors.any { it.startsWith("Visible response per view was weaker") })
        assertTrue(exposure.evidence.any { "5 public interactions per 100 views" in it })
    }

    @Test fun `replies qualify on their own interactions without entering original benchmarks`() {
        val original = post("original", views = 1000, likes = 50)
        val reply = post("reply", views = 200, likes = 10, reply = true)
        val quietReply = post("quiet-reply", views = 100, likes = 9, reply = true)
        val analytics = analytics(listOf(original), listOf(reply, quietReply))
        val reviews = BriefPostCoach.reviewablePosts(analytics, "example", now)
        assertEquals(listOf(original, reply), reviews)
        assertEquals(1, analytics.postsAnalyzed)
        assertEquals(1000, analytics.totalViews.toInt())
        assertTrue(analytics.recentReplies.contains(quietReply))
        val report = BriefPostCoach.review(reply, analytics, strings, now)!!
        assertTrue(report.limitations.any { "parent conversation" in it })
        assertFalse(report.evidence.any { "median" in it })
    }

    @Test fun `reply comparisons cannot use originals or different formats`() {
        val target = post("reply", views = 200, likes = 10, reply = true)
        val originals = List(6) { post("original-$it", views = 2000, likes = 100) }
        val replyPeers = List(3) { post("reply-$it", views = 1000, likes = 50, reply = true) }
        val report = BriefPostCoach.review(target, analytics(originals, replyPeers + target), strings, now)!!
        assertTrue(report.evidence.any { "3 other same-format tweets" in it })
        assertTrue(report.examples.all(PostSummary::isReply))
        val mediaPeers = replyPeers.map { it.copy(media = listOf(media)) }
        val noBaseline = BriefPostCoach.review(target, analytics(originals, mediaPeers + target), strings, now)!!
        assertFalse(noBaseline.evidence.any { "median" in it })
    }

    @Test fun `early cached counts never mature when only the clock moves`() {
        val early = post("early", views = 100, likes = 1, ageHours = 2)
        val peers = List(3) { post("peer-$it", views = 1000, likes = 50, ageHours = 2) }
        val analytics = analytics(peers + early)
        val report = BriefPostCoach.review(early, analytics, strings, now + 30 * hour)!!
        assertTrue(report.limitations.any { "less than 24 hours" in it })
        assertFalse(report.evidence.any { "median" in it })
        assertFalse(report.possibleFactors.any { "Visible response" in it })
    }

    @Test fun `stale future invalid and foreign data do not become reviews`() {
        val good = post("good")
        assertTrue(BriefPostCoach.reviewablePosts(analytics(listOf(good)).copy(cachedAt = now + 1), "example", now).isEmpty())
        assertTrue(BriefPostCoach.reviewablePosts(analytics(listOf(good)), "other", now).isEmpty())
        assertTrue(BriefPostCoach.reviewablePosts(analytics(listOf(good)), "example", now + 49 * hour).isEmpty())
        val invalid = listOf(good.copy(authorUserName = "other"), good.copy(views = -1), good.copy(timestamp = now + 1))
        assertTrue(BriefPostCoach.reviewablePosts(analytics(invalid), "example", now).isEmpty())
    }

    @Test fun `short previews cannot support claims about complete writing`() {
        val short = post("short").copy(text = "Finally shipped this. Thoughts?", fullText = null)
        val report = BriefPostCoach.review(short, analytics(listOf(short)), strings, now)!!
        assertTrue(report.limitations.any { "shortened preview" in it })
        assertFalse(report.evidence.any { "opening says" in it || "closing question" in it })
    }

    @Test fun `dashboard can review an old all-time winner without weekly comparisons`() {
        val peers = List(3) { post("peer-$it") }
        val old = post("101", views = 100_000, ageHours = 365 * 24)
        val snapshot = analytics(peers).copy(banger = old)
        assertFalse(BriefPostCoach.reviewablePosts(snapshot, "example", now).contains(old))
        assertTrue(BriefPostCoach.reviewTargets(snapshot, "example", now).contains(old))
        val report = requireNotNull(BriefPostCoach.review(old, snapshot, strings, now))
        assertTrue(report.measurements.first().contains("100,000"))
        assertFalse(report.measurements.any { "hours" in it || "median" in it })
        assertTrue(report.limitations.any { "saved lifetime totals" in it })
        val evidence = BriefReviewEvidence.create(old, snapshot, emptyList())
        assertEquals(0L, evidence.observedAt)
        assertEquals(setOf("performance", "interaction_breakdown"), evidence.panelIds)
        assertEquals(peers, BriefCoachAi.review(old, snapshot, report, strings, now).history)
        assertTrue(evidence.peers.isEmpty())
        assertNull(BriefPostCoach.review(old.copy(views = 1), snapshot, strings, now))
        assertTrue(BriefPostCoach.reviewTargets(snapshot.copy(banger = old.copy(authorUserName = "other")), "example", now).none { it.url == old.url })
    }

    @Test fun `explicit dashboard selections do not loosen automatic reply eligibility`() {
        val original = post("101")
        val reply = post("102", likes = 2, reply = true, ageHours = 0).copy(timestamp = now - 60_000L)
        val snapshot = analytics(listOf(original)).copy(best = reply)
        assertFalse(BriefPostCoach.reviewablePosts(snapshot, "example", now).contains(reply))
        assertTrue(BriefPostCoach.reviewTargets(snapshot, "example", now).contains(reply))
        assertNotNull(BriefPostCoach.review(reply, snapshot, strings, now))
        assertEquals(listOf(original), snapshot.recentPosts)
        assertTrue(BriefPostCoach.reviewTargets(snapshot.copy(best = reply.copy(timestamp = now + 1)), "example", now).none { it.isReply })
    }

    @Test fun `draft feedback grounds wording suggestions in quoted text`() {
        val report = BriefPostCoach.draft(draft("Finally shipped this. Thoughts?"), null, strings, now)
        assertTrue(report.evidence.any { "“Finally shipped this.”" in it })
        assertTrue(report.evidence.any { "“Thoughts?”" in it })
        assertTrue(report.possibleFactors.any { "People outside" in it })
        assertTrue(report.nextSteps.any { "Name what changed" in it })
        assertTrue(report.limitations.any { "not a prediction" in it })
    }

    @Test fun `clear named announcement is not mistaken for a contextless opening`() {
        val report = BriefPostCoach.draft(draft("Finally shipped this feature: Twidget can compare tweets of similar ages."), null, strings, now)
        assertFalse(report.evidence.any { "without naming the subject" in it })
    }

    @Test fun `draft examples come only from this account and exact repeats need complete text`() {
        val text = "Android widgets now support a new useful layout."
        val own = post("own").copy(text = text, fullText = text)
        val foreign = own.copy(url = "https://x.com/other/status/1", authorUserName = "other")
        val repeated = BriefPostCoach.draft(draft(text), analytics(listOf(own, foreign)), strings, now)
        assertTrue(repeated.evidence.any { "matches one of your recent" in it })
        assertEquals(listOf(own), repeated.examples)
        val excerpt = BriefPostCoach.draft(draft(text), analytics(listOf(own.copy(fullText = null))), strings, now)
        assertFalse(excerpt.evidence.any { "matches one of your recent" in it })
    }

    @Test fun `single viral post cannot produce a media recommendation`() {
        val history = listOf(post("viral", views = 1_000_000).copy(media = listOf(media))) +
            List(6) { post("text-$it", views = 100) }
        val report = BriefPostCoach.draft(draft("A useful concrete announcement."), analytics(history), strings, now)
        assertFalse(report.evidence.any { "with media had a median" in it })
    }

    @Test fun `format observations use medians and age overlap with explicit uncertainty`() {
        val history = List(3) { post("media-$it", views = 1000).copy(media = listOf(media)) } +
            List(3) { post("text-$it", views = 100) }
        val report = BriefPostCoach.draft(draft("A useful concrete announcement."), analytics(history), strings, now)
        assertTrue(report.evidence.any { "3 with media had a median of 1,000 views" in it })
        assertTrue(report.possibleFactors.any { "Topic, timing and audience" in it })
        val separated = history.map { if (it.media.isEmpty()) it.copy(timestamp = now - 150 * hour) else it }
        assertFalse(BriefPostCoach.draft(draft("A useful concrete announcement."), analytics(separated), strings, now)
            .evidence.any { "with media had a median" in it })
    }

    @Test fun `missing or tiny denominators cannot support a response rate judgment`() {
        val target = post("tiny", views = 1, likes = 0)
        val peers = List(3) { post("peer-$it", views = 1, likes = 1) }
        val report = BriefPostCoach.review(target, analytics(peers + target), strings, now)!!
        assertFalse(report.evidence.any { "per 100 views" in it })
        assertFalse(report.possibleFactors.any { "response per view" in it })
        val unknown = target.copy(viewsKnown = false)
        val noViews = BriefPostCoach.review(unknown, analytics(peers + unknown), strings, now)!!
        assertTrue(noViews.limitations.any { "did not supply a view count" in it })
        assertFalse(noViews.evidence.any { "median" in it })
    }

    @Test fun `deleted published and different-account drafts are excluded`() {
        val good = draft("An Android widget announcement.")
        val all = listOf(good, good.copy(id = "deleted", deletedAt = now),
            good.copy(id = "published", status = ScheduleStatus.PUBLISHED), good.copy(id = "foreign", accountUsername = "other"))
        assertEquals(listOf(good), BriefPostCoach.readyDrafts(all, "@Example"))
    }

    private val media = PostMedia("photo", "https://example.com/photo.jpg", "", 100, 100)
    private fun draft(text: String) = ScheduledPost(provider = ScheduleProvider.LOCAL_REMINDER,
        accountUsername = "example", scheduledAt = null, thread = listOf(ScheduleThreadItem(text = text)))

    private fun post(id: String, views: Long = 1000, likes: Long = 50, reply: Boolean = false, ageHours: Long = 30) = PostSummary(
        url = "https://x.com/example/status/$id", text = "Android widgets can show useful information.",
        fullText = "Android widgets can show useful information.", views = views, likes = likes,
        replies = 0, reposts = 0, quotes = 0, engagements = likes, timestamp = now - ageHours * hour,
        createdAt = "", authorName = "Example", authorUserName = "example", authorAvatar = "", isReply = reply,
    )

    private fun analytics(posts: List<PostSummary>, replies: List<PostSummary> = emptyList()) = AnalyticsClient.analyticsFromPosts(
        username = "example", followers = 100, statusesInspected = posts.size + replies.size, sampled = false,
        posts = posts, bestCandidates = posts + replies, cachedAt = now,
    )
}
