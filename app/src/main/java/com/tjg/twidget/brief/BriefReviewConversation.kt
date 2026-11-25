package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.TwitterApisClient
import org.json.JSONArray
import org.json.JSONObject

/** Public conversation evidence, deliberately separate from the selected tweet's metrics. */
internal data class BriefReviewConversation(
    val authorPosts: List<PostSummary> = emptyList(),
    val parents: List<PostSummary> = emptyList(),
    val audienceReplies: List<PostSummary> = emptyList(),
    val replyCoverage: String = "unavailable",
    val source: String = "cached timeline",
    val observedAt: Long = 0,
    val selectedPostId: String = "",
) {
    val isThread get() = authorPosts.size >= 3
    fun json(): JSONObject = JSONObject().put("authorPosts", JSONArray(authorPosts.map(AnalyticsClient::serializePost)))
        .put("parents", JSONArray(parents.map(AnalyticsClient::serializePost)))
        .put("audienceReplies", JSONArray(audienceReplies.map(AnalyticsClient::serializePost)))
        .put("selectedPostId", selectedPostId).put("replyCoverage", replyCoverage).put("source", source).put("observedAt", observedAt)
        .put("isThread", isThread).put("threadCoverage", "Only linked, available posts; may be incomplete")

    fun apply(input: BriefCoachInput): BriefCoachInput {
        if (!isThread) return input.copy(conversation = this)
        val entries = authorPosts.map { (it.fullText ?: it.text).take(2400) }
        val text = entries.joinToString("\n\n")
        return input.copy(kind = "thread", text = text.take(10_000), entries = entries,
            complete = text.length <= 10_000 && authorPosts.all { it.fullText != null && it.fullText.length <= 2400 },
            mediaCount = authorPosts.sumOf { it.media.size + it.quotedPost?.media.orEmpty().size },
            media = authorPosts.flatMapIndexed { index, post -> BriefCoachAi.reviewMedia(post).map { it.copy(entry = index + 1) } },
            conversation = this)
    }

    companion object {
        fun decode(root: JSONObject?): BriefReviewConversation? = root?.let {
            fun posts(name: String, cap: Int) = it.optJSONArray(name)?.let { array ->
                (0 until minOf(array.length(), cap)).mapNotNull { i -> com.tjg.twidget.core.NetworkResponseParsers.parseBridgePost(array.optJSONObject(i)) }
            }.orEmpty()
            BriefReviewConversation(posts("authorPosts", 12), posts("parents", 3), posts("audienceReplies", 20),
                it.optString("replyCoverage", "unavailable"), it.optString("source", "cached timeline"), it.optLong("observedAt"), it.optString("selectedPostId"))
        }
    }
}

internal object BriefReviewConversations {
    private fun id(post: PostSummary) = BriefPublishedReviewStore.postId(post.url).orEmpty()

    /** Same-author parent links, never chronological proximity or conversation ID alone. */
    fun collect(selected: PostSummary, candidates: List<PostSummary>, replies: List<PostSummary> = emptyList(),
        replyCoverage: String = "unavailable", source: String = "cached timeline", now: Long = 0): BriefReviewConversation {
        val incoming = candidates.lastOrNull { id(it) == id(selected) && it.authorUserName.equals(selected.authorUserName, true) }
        val canonical = if (incoming == null) selected else selected.copy(
            replyToId = selected.replyToId.ifBlank { incoming.replyToId },
            conversationId = selected.conversationId.ifBlank { incoming.conversationId },
            fullText = selected.fullText ?: incoming.fullText)
        val pool = (listOf(canonical) + candidates.asReversed()).filter { id(it).isNotBlank() }.distinctBy(::id).associateBy(::id)
        val own = pool.filterValues { it.authorUserName.equals(selected.authorUserName, true) }
        val chain = mutableListOf(canonical)
        val seen = mutableSetOf(id(selected))
        var cyclic = false
        while (chain.size < 12) {
            val parent = own[chain.first().replyToId] ?: break
            if (!seen.add(id(parent))) { cyclic = true; break }
            chain.add(0, parent)
        }
        while (chain.size < 12) {
            val children = own.values.filter { it.replyToId == id(chain.last()) && id(it) !in seen }
            // Branches are ambiguous; do not silently splice competing branches into one thread.
            val next = children.singleOrNull() ?: break
            seen += id(next); chain += next
        }
        val parents = mutableListOf<PostSummary>()
        var parentId = canonical.replyToId
        val parentSeen = mutableSetOf(id(selected))
        while (parents.size < 3) {
            val parent = pool[parentId] ?: break
            if (!parentSeen.add(id(parent))) break
            if (chain.none { id(it) == id(parent) }) parents.add(0, parent)
            parentId = parent.replyToId
        }
        val audience = replies.filter { it.replyToId == id(selected) &&
            !it.authorUserName.equals(selected.authorUserName, true) && it.authorUserName.isNotBlank() &&
            id(it).isNotBlank() && (it.fullText ?: it.text).isNotBlank() }.distinctBy(::id).take(20)
        return BriefReviewConversation(if (cyclic) listOf(canonical) else chain, parents, audience, replyCoverage, source, now, id(selected))
    }

    fun cached(selected: PostSummary, posts: List<PostSummary>) = collect(selected, posts)

    /** On demand only: at most one thread request and one latest-replies page; no crawling or provider switching. */
    fun enrich(context: Context, subject: BriefReviewSubject): BriefReviewSubject {
        val selected = subject.post ?: return subject
        val analytics = AnalyticsClient.cached(context, subject.target.username)
        val candidates = analytics?.let { it.recentPosts + it.recentReplies }.orEmpty()
        if (TwidgetStore.settings(context).dataSource != TwidgetStore.DATA_SOURCE_TWITTERAPIS || !TwitterApisClient.hasCredentials(context))
            return subject.copy(input = cached(selected, candidates).apply(subject.input))
        val now = System.currentTimeMillis()
        fun attempt(thread: Boolean): JSONObject? = try {
            TwitterApisClient.fetchConversationPage(context,
                if (thread) selected.conversationId.ifBlank { id(selected) } else id(selected), thread)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException || Thread.currentThread().isInterrupted) throw error
            null // Missing/unsupported/limited conversation reads must not break an otherwise usable review.
        }
        fun posts(root: JSONObject?): List<PostSummary> = root?.optJSONArray("tweets")?.let { array ->
            (0 until minOf(array.length(), 60)).mapNotNull { index ->
                val tweet = array.optJSONObject(index) ?: return@mapNotNull null
                val author = tweet.optJSONObject("author") ?: tweet.optJSONObject("user") ?: return@mapNotNull null
                val handle = author.optString("username").ifBlank { author.optString("userName") }.ifBlank { author.optString("screen_name") }
                if (handle.isBlank()) null else AnalyticsClient.parseTwitterApisPost(tweet, handle, 0, now, includeReplies = true)
            }
        }.orEmpty()
        val thread = attempt(true)
        val replyPage = attempt(false)
        val replies = posts(replyPage).map { if (it.replyToId.isBlank()) it.copy(replyToId = id(selected)) else it }
        val evidence = collect(selected, candidates + posts(thread) + replies, replies,
            if (replyPage == null) "unavailable" else if (replies.isEmpty()) "no replies returned; coverage unknown" else "one latest-replies page; non-representative sample",
            "TwitterAPIs conversation reads", now)
        return subject.copy(input = evidence.apply(subject.input))
    }
}
