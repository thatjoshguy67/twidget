package com.tjg.twidget.brief

import android.content.Context
import androidx.annotation.StringRes
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import java.text.NumberFormat
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
    fun asText(context: Context): String = buildString {
        appendLine(context.getString(R.string.brief_debug_report_title))
        appendLine(context.getString(R.string.brief_debug_report_account, username))
        appendLine(context.getString(R.string.brief_debug_report_generated, date(generatedAt, context)))
        appendLine()
        appendLine(context.getString(R.string.brief_debug_report_input_facts))
        appendLine(context.getString(R.string.brief_debug_report_followers, number(followers)))
        appendLine(context.getString(R.string.brief_debug_report_following, number(following)))
        appendLine(context.getString(R.string.brief_debug_report_posts, number(posts)))
        appendLine(context.getString(R.string.brief_debug_report_followers_today, signed(followersToday)))
        appendLine(context.getString(R.string.brief_debug_report_followers_week, signed(followersWeek)))
        appendLine(context.getString(R.string.brief_debug_report_history_samples, historySamples))
        appendLine(context.getString(R.string.brief_debug_report_analytics_cached_at, dateOrMissing(analyticsCachedAt, context)))
        appendLine(context.getString(R.string.brief_debug_report_standout_post_views, standoutPostViews?.let(::number) ?: context.getString(R.string.brief_debug_report_none)))
        appendLine(context.getString(R.string.brief_debug_report_quiet_post_views, quietPostViews?.let(::number) ?: context.getString(R.string.brief_debug_report_none)))
        appendLine(context.getString(R.string.brief_debug_report_posting_streak, postingStreak))
        appendLine(context.getString(R.string.brief_debug_report_original_activity_complete, context.getString(if (originalActivityComplete) R.string.l10n_debug_yes else R.string.l10n_debug_no)))
        appendLine(context.getString(R.string.brief_debug_report_upcoming_tweets, upcomingTweets))
        appendLine(context.getString(R.string.brief_debug_report_follower_scan_completed_at, dateOrMissing(followerScanCompletedAt, context)))
        appendLine(context.getString(R.string.brief_debug_report_followers_scanned, followersScanned))
        appendLine()
        appendLine(context.getString(R.string.brief_debug_report_ranked_candidates))
        rankedCandidates.forEachIndexed { index, card ->
            val selection = context.getString(if (card.id in selectedIds) R.string.brief_debug_report_selected else R.string.brief_debug_report_omitted)
            val rank = card.rankingScore.takeIf { it >= 0 } ?: card.score
            appendLine(context.getString(R.string.brief_debug_report_candidate, index + 1, rank, card.score, card.type, selection, card.id))
            appendLine(context.getString(R.string.brief_debug_report_candidate_title, card.title))
            appendLine(context.getString(R.string.brief_debug_report_candidate_body, card.body))
        }
        if (rankedCandidates.isEmpty()) appendLine(context.getString(R.string.brief_debug_report_none))
    }.trimEnd()

    companion object {
        private fun number(value: Long): String = NumberFormat.getIntegerInstance(com.tjg.twidget.core.AppLocales.applicationLocale()).format(value)
        private fun signed(value: Long): String = if (value > 0) "+${number(value)}" else number(value)
        private fun date(value: Long, context: Context): String =
            com.tjg.twidget.core.AppLocales.dateTime(context, value)
        private fun dateOrMissing(value: Long, context: Context): String =
            if (value > 0L) date(value, context) else context.getString(R.string.brief_debug_report_none)
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
        entries.add(0, Entry(System.currentTimeMillis(), event, report.username, report.asText(context)))
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

enum class BriefDebugScenario(val storageId: String, @StringRes val labelRes: Int) {
    REAL("real", R.string.brief_debug_scenario_real),
    COMPONENTS("components", R.string.debug_component_library),
    POST("post", R.string.brief_debug_scenario_post),
    WORST_POST("worst_post", R.string.brief_debug_scenario_worst_post),
    GROWTH("growth", R.string.brief_debug_scenario_growth),
    SLOWDOWN("slowdown", R.string.brief_debug_scenario_slowdown),
    MILESTONE("milestone", R.string.brief_debug_scenario_milestone),
    TOP_FOLLOWER("top_follower", R.string.brief_debug_scenario_top_follower),
    INACTIVITY("inactivity", R.string.brief_debug_scenario_inactivity),
    STREAK("streak", R.string.brief_debug_scenario_streak),
    POSTING_GUIDE("posting_guide", R.string.brief_debug_scenario_posting_guide),
    STEADY("steady", R.string.brief_debug_scenario_steady);

    fun label(context: Context): String = context.getString(labelRes)

    fun snapshot(context: Context, base: BriefSnapshot): BriefSnapshot =
        snapshot(BriefStrings.from(context), base)

    internal fun snapshot(strings: BriefStrings, base: BriefSnapshot): BriefSnapshot = when (this) {
        REAL -> base
        COMPONENTS -> fixture(strings, base, BriefCard("debug-library", BriefCardType.SUMMARY,
            strings.text(R.string.debug_component_library), strings.text(R.string.brief_debug_synthetic_provider_message), 0)).copy(
            cards = entries.filterNot { it == REAL || it == COMPONENTS }.flatMap { it.snapshot(strings, base).cards })
        POST -> fixture(strings, base, BriefCard("debug-post", BriefCardType.POST, strings.text(R.string.brief_debug_card_post_title), strings.text(R.string.brief_debug_card_post_body), 98))
        WORST_POST -> fixture(strings, base, BriefCard("debug-worst-post", BriefCardType.WORST_POST, strings.text(R.string.brief_debug_card_worst_post_title), strings.text(R.string.brief_debug_card_worst_post_body), 86))
        GROWTH -> fixture(strings, base, BriefCard("debug-growth", BriefCardType.GROWTH, strings.text(R.string.brief_debug_card_growth_title), strings.text(R.string.brief_debug_card_growth_body), 95), today = 84, week = 679)
        SLOWDOWN -> fixture(strings, base, BriefCard("debug-slowdown", BriefCardType.SLOWDOWN, strings.text(R.string.brief_debug_card_slowdown_title), strings.text(R.string.brief_debug_card_slowdown_body), 82), today = -8, week = 3)
        MILESTONE -> fixture(strings, base, BriefCard("debug-milestone", BriefCardType.MILESTONE, strings.text(R.string.brief_debug_card_milestone_title), strings.text(R.string.brief_debug_card_milestone_body, strings.number(base.followers)), 100))
        TOP_FOLLOWER -> fixture(strings, base, BriefCard("debug-top-follower", BriefCardType.TOP_FOLLOWER, strings.text(R.string.brief_debug_card_top_follower_title), strings.text(R.string.brief_debug_card_top_follower_body), 92))
        INACTIVITY -> fixture(strings, base, BriefCard("debug-inactivity", BriefCardType.STREAK, strings.text(R.string.brief_debug_card_inactivity_title), strings.text(R.string.brief_debug_card_inactivity_body), 84))
        STREAK -> fixture(strings, base, BriefCard("debug-streak", BriefCardType.STREAK, strings.text(R.string.brief_debug_card_streak_title), strings.text(R.string.brief_debug_card_streak_body), 84))
        POSTING_GUIDE -> fixture(strings, base, BriefCard("debug-posting-guide", BriefCardType.POSTING_GUIDE, strings.text(R.string.brief_debug_card_posting_guide_title), strings.text(R.string.brief_debug_card_posting_guide_body), 86, BriefCardAction.NONE))
        STEADY -> fixture(strings, base, BriefCard("debug-steady", BriefCardType.SUMMARY, strings.text(R.string.brief_debug_card_steady_title), strings.text(R.string.brief_debug_card_steady_body), 50), today = 0, week = 0)
    }

    companion object {
        fun fromStorageId(value: String?): BriefDebugScenario =
            entries.firstOrNull { it.storageId == value } ?: REAL

        private fun fixture(
            strings: BriefStrings,
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
            providerMessage = strings.text(R.string.brief_debug_synthetic_provider_message),
        )
    }
}
