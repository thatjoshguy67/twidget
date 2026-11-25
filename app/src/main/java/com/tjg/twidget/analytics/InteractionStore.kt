package com.tjg.twidget.analytics

import android.content.Context
import com.tjg.twidget.data.AccountPreferences
import com.tjg.twidget.data.TwidgetStore
import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Account-scoped interaction history. Counts absent from a provider remain null. */
object InteractionStore {
    private const val PREFS = "twidget_interactions"
    private fun key(username: String) = username.trim().trimStart('@').lowercase(Locale.US)
    fun all(context: Context, username: String): InteractionData {
        val raw = AccountPreferences.open(context, PREFS).getString(key(username), null) ?: return InteractionData()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(InteractionData())
    }
    fun merge(context: Context, username: String, data: InteractionData): InteractionData = synchronized(TwidgetStore) {
        val merged = mergeInteractions(all(context, username), data)
        AccountPreferences.open(context, PREFS).edit().putString(key(username), encode(merged).toString()).apply()
        merged
    }
    fun seedCsv(context: Context, username: String) = saveCsv(context, username, ImportedAnalyticsStore.all(context, username))
    fun saveCsv(context: Context, username: String, rows: List<XAnalyticsMovement>) {
        val daily = rows.map { row ->
            InteractionDay(row.date, InteractionCounts(row.likes, row.reposts, row.replies, row.shares, row.quotes, row.bookmarks, row.impressions))
        }
        merge(context, username, InteractionData(daily = daily))
    }
    fun clear(context: Context, username: String) {
        synchronized(TwidgetStore) { AccountPreferences.open(context, PREFS).edit().remove(key(username)).apply() }
    }
    fun recordAnalytics(context: Context, username: String, analytics: PostAnalytics) {
        val posts = (analytics.recentPosts + analytics.recentReplies).mapNotNull { post ->
            val id = Regex("/status/([0-9]+)").find(post.url)?.groupValues?.get(1) ?: return@mapNotNull null
            if (post.timestamp <= 0L) return@mapNotNull null
            InteractionPost(id, post.timestamp, analytics.cachedAt, post.isReply, InteractionCounts(
                post.likes.takeIf { post.likesKnown }, post.reposts.takeIf { post.repostsKnown },
                post.replies.takeIf { post.repliesKnown }, null, post.quotes.takeIf { post.quotesKnown },
                null, post.views.takeIf { post.viewsKnown },
            ))
        }
        merge(context, username, InteractionData(posts = posts))
    }
    fun encode(data: InteractionData): JSONObject = JSONObject()
        .put("daily", JSONArray().apply { data.daily.forEach { row -> put(countsJson(row.counts).put("date", row.date.toString())) } })
        .put("posts", JSONArray().apply { data.posts.forEach { row -> put(countsJson(row.counts)
            .put("id", row.id).put("createdAt", row.createdAt).put("capturedAt", row.capturedAt).put("isReply", row.isReply)) } })
    fun decode(root: JSONObject): InteractionData {
        val daily = root.optJSONArray("daily") ?: JSONArray()
        val posts = root.optJSONArray("posts") ?: JSONArray()
        return InteractionData(
            (0 until daily.length()).mapNotNull { index -> runCatching { daily.getJSONObject(index).let { InteractionDay(LocalDate.parse(it.getString("date")), counts(it)) } }.getOrNull() },
            (0 until posts.length()).mapNotNull { index -> runCatching { posts.getJSONObject(index).let {
                InteractionPost(it.getString("id"), it.getLong("createdAt"), it.getLong("capturedAt"), it.getBoolean("isReply"), counts(it))
            } }.getOrNull() },
        )
    }
    private fun countsJson(c: InteractionCounts) = JSONObject().apply {
        InteractionMetric.entries.forEach { metric -> put(metric.name.lowercase(Locale.US), c.value(metric) ?: JSONObject.NULL) }
    }
    private fun counts(json: JSONObject): InteractionCounts {
        fun value(key: String): Long? = if (json.has(key) && !json.isNull(key)) json.optLong(key).takeIf { it >= 0 } else null
        return InteractionCounts(value("likes"), value("reposts"), value("replies"), value("shares"), value("quotes"), value("bookmarks"), value("views"))
    }
}
