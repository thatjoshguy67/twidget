package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.data.TwidgetStore
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class BriefEngineReport(
    val username: String,
    val generatedAt: Long,
    val followers: Long,
    val following: Long,
    val posts: Long,
    val followersToday: Long,
    val followersWeek: Long,
    val historySamples: Int,
    val analyticsCachedAt: Long,
    val standoutPostViews: Long?,
    val quietPostViews: Long?,
    val postingStreak: Int,
    val originalActivityComplete: Boolean,
    val upcomingTweets: Int,
    val followerScanCompletedAt: Long,
    val followersScanned: Int,
    val rankedCandidates: List<BriefCard>,
    val selectedIds: Set<String>,
) {
    fun asText(): String = buildString {
        appendLine("Twidget Brief engine report")
        appendLine("Account: @$username")
        appendLine("Generated: ${date(generatedAt)}")
        appendLine()
        appendLine("INPUT FACTS")
        appendLine("followers=${number(followers)}")
        appendLine("following=${number(following)}")
        appendLine("posts=${number(posts)}")
        appendLine("followersToday=${signed(followersToday)}")
        appendLine("followersWeek=${signed(followersWeek)}")
        appendLine("historySamples=$historySamples")
        appendLine("analyticsCachedAt=${dateOrMissing(analyticsCachedAt)}")
        appendLine("standoutPostViews=${standoutPostViews?.let(::number) ?: "none"}")
        appendLine("quietPostViews=${quietPostViews?.let(::number) ?: "none"}")
        appendLine("postingStreak=$postingStreak")
        appendLine("originalActivityComplete=$originalActivityComplete")
        appendLine("upcomingTweets=$upcomingTweets")
        appendLine("followerScanCompletedAt=${dateOrMissing(followerScanCompletedAt)}")
        appendLine("followersScanned=$followersScanned")
        appendLine()
        appendLine("RANKED CANDIDATES")
        rankedCandidates.forEachIndexed { index, card ->
            val selection = if (card.id in selectedIds) "SELECTED" else "OMITTED"
            val rank = card.rankingScore.takeIf { it >= 0 } ?: card.score
            appendLine("${index + 1}. rank=$rank base=${card.score} type=${card.type} $selection id=${card.id}")
            appendLine("   ${card.title}")
            appendLine("   ${card.body}")
        }
        if (rankedCandidates.isEmpty()) appendLine("none")
    }.trimEnd()

    companion object {
        private fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)
        private fun signed(value: Long): String = if (value > 0) "+${number(value)}" else number(value)
        private fun date(value: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(value))
        private fun dateOrMissing(value: Long): String = if (value > 0L) date(value) else "none"
    }
}

object BriefDebugLog {
    data class Entry(
        val timestamp: Long,
        val event: String,
        val username: String,
        val report: String,
    )

    private const val PREFS = "twidget_brief_debug"
    private const val KEY_ENTRIES = "engine_log"
    private const val MAX_ENTRIES = 30

    fun record(context: Context, event: String, report: BriefEngineReport) {
        if (!TwidgetStore.debugMenuUnlocked(context)) return
        val entries = entries(context).toMutableList()
        entries.add(0, Entry(System.currentTimeMillis(), event, report.username, report.asText()))
        write(context, entries.take(MAX_ENTRIES))
    }

    fun entries(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(Entry(
                        timestamp = item.optLong("timestamp"),
                        event = item.optString("event"),
                        username = item.optString("username"),
                        report = item.optString("report"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("event", entry.event)
                put("username", entry.username)
                put("report", entry.report)
            })
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

enum class BriefDebugScenario(val storageId: String, val label: String) {
    REAL("real", "Real account data"),
    POST("post", "Getting attention"),
    WORST_POST("worst_post", "Quietest tweet"),
    GROWTH("growth", "Growth surge"),
    SLOWDOWN("slowdown", "Growth slowdown"),
    MILESTONE("milestone", "Milestone reached"),
    TOP_FOLLOWER("top_follower", "New top follower"),
    INACTIVITY("inactivity", "Start a streak"),
    STREAK("streak", "Posting streak"),
    SCHEDULE_GUIDE("schedule_guide", "Schedule health guide"),
    POST_FOLLOW_THROUGH("post_follow_through", "Scheduled post follow-through"),
    POSTING_GUIDE("posting_guide", "Next post guidance"),
    STEADY("steady", "Everything steady");

    fun snapshot(base: BriefSnapshot): BriefSnapshot = when (this) {
        REAL -> base
        POST -> fixture(base, BriefCard("debug-post", BriefCardType.POST, "Why this tweet worked", "It reached substantially more people than your typical tweet this week.", 98))
        WORST_POST -> fixture(base, BriefCard("debug-worst-post", BriefCardType.WORST_POST, "What may have limited it", "It reached fewer people than your typical tweet this week.", 86))
        GROWTH -> fixture(base, BriefCard("debug-growth", BriefCardType.GROWTH, "Your audience is taking off", "You gained 84 followers today and 679 over the last week.", 95), today = 84, week = 679)
        SLOWDOWN -> fixture(base, BriefCard("debug-slowdown", BriefCardType.SLOWDOWN, "Growth has slowed down", "Your recent pace is behind the previous few days. A fresh post could help restart it.", 82), today = -8, week = 3)
        MILESTONE -> fixture(base, BriefCard("debug-milestone", BriefCardType.MILESTONE, "Milestone reached!", "You made it to ${number(base.followers)} followers. That deserves a victory lap.", 100))
        TOP_FOLLOWER -> fixture(base, BriefCard("debug-top-follower", BriefCardType.TOP_FOLLOWER, "A new top follower", "@JohnCena is now your #2 most popular follower.", 92))
        INACTIVITY -> fixture(base, BriefCard("debug-inactivity", BriefCardType.STREAK, "Start a posting streak", "Twidget hasn’t detected an original tweet for more than three days. Tweet today to begin.", 84))
        STREAK -> fixture(base, BriefCard("debug-streak", BriefCardType.STREAK, "14-day posting streak", "Tweet today to keep your 14-day rhythm going.", 84))
        SCHEDULE_GUIDE -> fixture(base, BriefCard("debug-schedule-guide", BriefCardType.SCHEDULE_GUIDE, "Plan your next tweet", "Nothing is scheduled for the next three days. Queue one idea while the week is still flexible.", 82, BriefCardAction.COMPOSE_TWEET))
        POST_FOLLOW_THROUGH -> fixture(base, BriefCard(
            "debug-post-follow-through",
            BriefCardType.POST_FOLLOW_THROUGH,
            "Build on your scheduled tweet",
            "It beat your recent baseline. Open it, note what people responded to, then plan a useful follow-up.",
            94,
            BriefCardAction.OPEN_POST,
            sourceAttribution = "Tweeted with Buffer",
        ))
        POSTING_GUIDE -> fixture(base, BriefCard("debug-posting-guide", BriefCardType.POSTING_GUIDE, "Try your next tweet in the evening", "Your recent evening tweets performed better than your other time windows. Test that timing again.", 86, BriefCardAction.COMPOSE_TWEET))
        STEADY -> fixture(base, BriefCard("debug-steady", BriefCardType.SUMMARY, "Everything looks steady", "Keep showing up and Twidget will watch for the next meaningful change.", 50), today = 0, week = 0)
    }

    companion object {
        fun fromStorageId(value: String?): BriefDebugScenario =
            entries.firstOrNull { it.storageId == value } ?: REAL

        private fun fixture(
            base: BriefSnapshot,
            card: BriefCard,
            today: Long = base.followersToday,
            week: Long = base.followersWeek,
        ) = base.copy(
            generatedAt = System.currentTimeMillis(),
            followersToday = today,
            followersWeek = week,
            cards = listOf(card),
            providerUsed = BriefProviderUsed.TEMPLATE,
            providerMessage = "Synthetic debug state · no AI request was made",
        )

        private fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)
    }
}
