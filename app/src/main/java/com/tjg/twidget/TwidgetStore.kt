package com.tjg.twidget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ProfileStats(
    val fullName: String,
    val userName: String,
    val followersCount: Long,
    val followingsCount: Long,
    val statusesCount: Long,
    val likeCount: Long,
    val profileImage: String = "",
    val isVerified: Boolean? = null,
    val isPrivate: Boolean? = null,
    val syncedAt: Long = System.currentTimeMillis(),
    val history: List<HistorySample> = emptyList(),
)

data class HistorySample(
    val dayLabel: String,
    val followers: Long,
    val following: Long,
    val posts: Long,
    val likes: Long,
    val timestamp: Long,
    // Interpolated chart filler between real samples. Render-only: estimated
    // samples are never persisted and never feed deltas or insight numbers.
    val estimated: Boolean = false,
)

data class TwidgetSettings(
    val username: String,
    val bridgeUrl: String,
    val apiKey: String,
    val xApiToken: String,
    val xApiKey: String,
    val xApiSecret: String,
    val refreshOnLaunch: Boolean,
    val refreshIntervalMinutes: Int,
    val widgetTapAction: String,
    val dataSource: String,
)

data class TwidgetWidgetSettings(
    val tintAlpha: Int,
    val tintColor: Int,
    val logo: String,
    val tapAction: String,
    val accountUsername: String,
    val colorMode: String,
    val fontFamily: String,
    val showDelta: Boolean = true,
)

enum class HistoryRange(val labelRes: Int) {
    WEEK(R.string.range_7d),
    MONTH(R.string.range_1m),
    THREE_MONTHS(R.string.range_3m),
    YTD(R.string.range_ytd),
    YEAR(R.string.range_1y),
}

object TwidgetStore {
    const val PREFS = "twidget_prefs"
    const val TAP_REFRESH = "refresh"
    const val TAP_PROFILE = "profile"
    const val TAP_APP = "app"
    const val DATA_SOURCE_DEFAULT = "default_rettiwt"
    const val DATA_SOURCE_SELF_HOSTED = "self_hosted_rettiwt"
    const val DATA_SOURCE_X_API = "official_x_api"
    const val DATA_SOURCE_FXTWITTER = "fxtwitter"
    const val COLOR_MODE_LIGHT = "light"
    const val COLOR_MODE_DARK = "dark"
    const val COLOR_MODE_SYSTEM = "system"
    const val FONT_ONE_UI_SANS = "one_ui_sans"
    const val FONT_GOOGLE_SANS_FLEX = "google_sans_flex"

    private const val KEY_USERNAME = "username"
    private const val KEY_BRIDGE_URL = "bridge_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_X_API_TOKEN = "x_api_token"
    private const val KEY_X_API_KEY = "x_api_key"
    private const val KEY_X_API_SECRET = "x_api_secret"
    private const val KEY_X_API_BEARER = "x_api_bearer"
    private const val KEY_REFRESH_ON_LAUNCH = "refresh_on_launch"
    private const val KEY_REFRESH_INTERVAL = "refresh_interval"
    private const val KEY_TAP_ACTION = "tap_action"
    private const val KEY_DATA_SOURCE = "data_source"
    private const val KEY_PROFILE = "profile"
    private const val KEY_HISTORY = "history"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_DASHBOARD_CARDS = "dashboard_cards"
    private const val KEY_HISTORY_MIGRATION_VERSION = "history_migration_version"
    private const val DEFAULT_BRIDGE_URL = "https://twidget-bridge-production.up.railway.app"
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    private const val MAX_HISTORY_DAYS = 400
    private const val HISTORY_MIGRATION_VERSION = 2
    private val LEGACY_SEEDED_FOLLOWER_GAINS = listOf(18L, 9L, 21L, 15L, 14L, 26L)
    val DEFAULT_DASHBOARD_CARDS = listOf(
        "followers",
        "follower_ratio",
        "post_rate",
        "likes_per_post",
        "momentum",
        "following",
        "growth_pace",
        "best_day",
        "posts",
        "milestone",
        "audience_balance",
        "account_health",
        "likes",
    )
    const val LOGO_X = "x"
    const val LOGO_TWITTER = "twitter"

    fun settings(context: Context): TwidgetSettings {
        val prefs = prefs(context)
        return TwidgetSettings(
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            bridgeUrl = (prefs.getString(KEY_BRIDGE_URL, "") ?: "").ifBlank { DEFAULT_BRIDGE_URL },
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            xApiToken = prefs.getString(KEY_X_API_TOKEN, "") ?: "",
            xApiKey = prefs.getString(KEY_X_API_KEY, "") ?: "",
            xApiSecret = prefs.getString(KEY_X_API_SECRET, "") ?: "",
            refreshOnLaunch = prefs.getBoolean(KEY_REFRESH_ON_LAUNCH, true),
            refreshIntervalMinutes = prefs.getInt(KEY_REFRESH_INTERVAL, 15).coerceIn(15, 240),
            widgetTapAction = prefs.getString(KEY_TAP_ACTION, TAP_REFRESH) ?: TAP_REFRESH,
            dataSource = prefs.getString(KEY_DATA_SOURCE, DATA_SOURCE_DEFAULT) ?: DATA_SOURCE_DEFAULT,
        )
    }

    fun saveSettings(context: Context, settings: TwidgetSettings) {
        val username = normalizeUsername(settings.username)
        prefs(context).edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_BRIDGE_URL, settings.bridgeUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_X_API_TOKEN, settings.xApiToken.trim())
            .putString(KEY_X_API_KEY, settings.xApiKey.trim())
            .putString(KEY_X_API_SECRET, settings.xApiSecret.trim())
            .putBoolean(KEY_REFRESH_ON_LAUNCH, settings.refreshOnLaunch)
            .putInt(KEY_REFRESH_INTERVAL, settings.refreshIntervalMinutes.coerceIn(15, 240))
            .putString(KEY_TAP_ACTION, settings.widgetTapAction)
            .putString(KEY_DATA_SOURCE, settings.dataSource)
            .apply()
        if (username.isNotBlank()) addAccount(context, username)
    }

    fun cachedXApiBearer(context: Context): String =
        prefs(context).getString(KEY_X_API_BEARER, "") ?: ""

    fun saveXApiBearer(context: Context, token: String) {
        prefs(context).edit().putString(KEY_X_API_BEARER, token.trim()).apply()
    }

    fun isOnboarded(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_ONBOARDED, false) ||
            normalizeUsername(prefs.getString(KEY_USERNAME, "").orEmpty()).isNotBlank()
    }

    fun completeOnboarding(context: Context, username: String) {
        val cleanUsername = normalizeUsername(username)
        val current = settings(context)
        prefs(context).edit()
            .putString(KEY_USERNAME, cleanUsername)
            .putString(KEY_BRIDGE_URL, current.bridgeUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, current.apiKey.trim())
            .putBoolean(KEY_REFRESH_ON_LAUNCH, current.refreshOnLaunch)
            .putInt(KEY_REFRESH_INTERVAL, current.refreshIntervalMinutes.coerceIn(15, 240))
            .putString(KEY_TAP_ACTION, current.widgetTapAction)
            .putString(KEY_DATA_SOURCE, current.dataSource)
            .putBoolean(KEY_ONBOARDED, cleanUsername.isNotBlank())
            .remove(KEY_PROFILE)
            .remove(KEY_HISTORY)
            .apply()
        if (cleanUsername.isNotBlank()) addAccount(context, cleanUsername)
    }

    fun addOnboardingAccount(context: Context, username: String) {
        val cleanUsername = normalizeUsername(username)
        if (cleanUsername.isBlank()) return
        prefs(context).edit()
            .putBoolean(KEY_ONBOARDED, true)
            .apply()
        addAccount(context, cleanUsername)
    }

    fun widgetSettings(context: Context, appWidgetId: Int = 0): TwidgetWidgetSettings {
        val prefs = prefs(context)
        val suffix = if (appWidgetId > 0) "_$appWidgetId" else ""
        return TwidgetWidgetSettings(
            tintAlpha = prefs.getInt("widget_tint_alpha$suffix", prefs.getInt("widget_tint_alpha", 205)).coerceIn(30, 245),
            tintColor = prefs.getInt("widget_tint_color$suffix", prefs.getInt("widget_tint_color", 0x00FFFFFF)),
            logo = prefs.getString("widget_logo$suffix", prefs.getString("widget_logo", LOGO_X)) ?: LOGO_X,
            tapAction = prefs.getString("widget_tap_action$suffix", prefs.getString(KEY_TAP_ACTION, TAP_REFRESH)) ?: TAP_REFRESH,
            accountUsername = prefs.getString("widget_account$suffix", "") ?: "",
            colorMode = prefs.getString("widget_color_mode$suffix", COLOR_MODE_SYSTEM) ?: COLOR_MODE_SYSTEM,
            fontFamily = prefs.getString("widget_font_family$suffix", FONT_ONE_UI_SANS) ?: FONT_ONE_UI_SANS,
            showDelta = prefs.getBoolean("widget_show_delta$suffix", prefs.getBoolean("widget_show_delta", true)),
        )
    }

    fun saveWidgetSettings(context: Context, appWidgetId: Int, settings: TwidgetWidgetSettings) {
        val suffix = if (appWidgetId > 0) "_$appWidgetId" else ""
        prefs(context).edit()
            .putInt("widget_tint_alpha$suffix", settings.tintAlpha.coerceIn(30, 245))
            .putInt("widget_tint_color$suffix", settings.tintColor)
            .putString("widget_logo$suffix", settings.logo)
            .putString("widget_tap_action$suffix", settings.tapAction)
            .putString("widget_account$suffix", normalizeUsername(settings.accountUsername))
            .putString("widget_color_mode$suffix", settings.colorMode)
            .putString("widget_font_family$suffix", settings.fontFamily)
            .putBoolean("widget_show_delta$suffix", settings.showDelta)
            .apply()
    }

    fun accounts(context: Context): List<String> {
        val saved = prefs(context).getString(KEY_ACCOUNTS, null)?.let { encoded ->
            runCatching {
                val array = JSONArray(encoded)
                List(array.length()) { index -> normalizeUsername(array.getString(index)) }
            }.getOrNull()
        }.orEmpty()
        val default = settings(context).username
        return (saved + default)
            .map(::normalizeUsername)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
    }

    fun addAccount(context: Context, username: String) {
        val cleanUsername = normalizeUsername(username)
        if (cleanUsername.isBlank()) return
        val next = (accounts(context) + cleanUsername).distinctBy { it.lowercase(Locale.US) }
        prefs(context).edit()
            .putString(KEY_ACCOUNTS, JSONArray(next).toString())
            .apply()
    }

    fun removeAccount(context: Context, username: String) {
        val cleanUsername = normalizeUsername(username)
        if (cleanUsername.isBlank()) return
        val remaining = accounts(context).filterNot { it.equals(cleanUsername, ignoreCase = true) }
        val edit = prefs(context).edit()
            .putString(KEY_ACCOUNTS, JSONArray(remaining).toString())
        // Widgets pinned to the deleted account revert to the default account.
        prefs(context).all.forEach { (key, value) ->
            if (key.startsWith("widget_account") && value is String &&
                normalizeUsername(value).equals(cleanUsername, ignoreCase = true)
            ) {
                edit.putString(key, "")
            }
        }
        edit.apply()
        clearCachedStats(context, cleanUsername)
        val current = settings(context)
        if (current.username.equals(cleanUsername, ignoreCase = true)) {
            saveSettings(context, current.copy(username = remaining.firstOrNull().orEmpty()))
        }
    }

    fun dashboardCards(context: Context): List<String> {
        val saved = prefs(context).getString(KEY_DASHBOARD_CARDS, null)?.let { encoded ->
            runCatching {
                val array = JSONArray(encoded)
                List(array.length()) { index -> array.getString(index) }
            }.getOrNull()
        }.orEmpty()
        if (saved.isEmpty()) return DEFAULT_DASHBOARD_CARDS
        return saved.filter { it in DEFAULT_DASHBOARD_CARDS }
    }

    fun saveDashboardCards(context: Context, cards: List<String>) {
        val clean = cards.filter { it in DEFAULT_DASHBOARD_CARDS }.distinct()
        prefs(context).edit()
            .putString(KEY_DASHBOARD_CARDS, JSONArray(clean).toString())
            .apply()
    }

    fun resetDashboardCards(context: Context) {
        prefs(context).edit().remove(KEY_DASHBOARD_CARDS).apply()
    }

    fun currentStats(context: Context, username: String = settings(context).username): ProfileStats {
        val cleanUsername = normalizeUsername(username).ifBlank { settings(context).username }
        val saved = statsJson(context, cleanUsername)?.let { encoded ->
            runCatching { statsFromJson(JSONObject(encoded)) }.getOrNull()
        }
        return saved ?: fallbackStats(context, cleanUsername)
    }

    // Recent real daily samples (last week), used by the widgets.
    fun history(context: Context, username: String = settings(context).username): List<HistorySample> =
        fullHistory(context, username).filterNot { it.estimated }.takeLast(7)

    // Every stored sample, oldest first: real daily closes and archive
    // anchors, plus bridge-estimated reconstruction samples (est flag). Demo
    // samples are merged in only before onboarding and never persisted.
    fun fullHistory(context: Context, username: String = settings(context).username): List<HistorySample> {
        val cleanUsername = normalizeUsername(username).ifBlank { settings(context).username }
        val stats = currentStats(context, cleanUsername)
        val saved = savedHistory(context, cleanUsername)
        val onboarded = isOnboarded(context)
        val samples = if (onboarded) saved else mergeByDay(demoHistory() + saved)
        val backfilled = flatBackfillProfileMetrics(samples, stats)
            .ifEmpty { listOf(sampleFor(stats)) }
        return backfilled
    }

    // REAL samples inside the range window, oldest first — the input for
    // every delta and insight number. Estimated samples never appear here.
    fun rangedHistory(context: Context, username: String, range: HistoryRange): List<HistorySample> {
        val all = fullHistory(context, username).filterNot { it.estimated }
        return all.filter { it.timestamp >= rangeStart(range) }
            .ifEmpty { listOfNotNull(all.lastOrNull()) }
    }

    // Chart-friendly view of the range: fixed buckets across the whole window.
    // A bucket holding a recorded sample shows it as-is; empty buckets between
    // two real data points (including anchors outside the window) get a linear
    // interpolation flagged `estimated` so the chart can draw them lighter.
    // Buckets before the first real data point are dropped, not invented.
    fun chartHistory(context: Context, username: String, range: HistoryRange): List<HistorySample> {
        val all = fullHistory(context, username)
        if (all.isEmpty()) return all
        val monthly = range == HistoryRange.YTD || range == HistoryRange.YEAR
        val labelFormat = SimpleDateFormat(if (monthly) "MMM" else "MMM d", Locale.US)
        var previousEnd = rangeStart(range) - 1
        return bucketEnds(range).mapNotNull { end ->
            val bucketStart = previousEnd + 1
            previousEnd = end
            // Real sample in the bucket wins; else a stored estimate (graph
            // reconstruction); else interpolate between surrounding points.
            val sample = all.lastOrNull { it.timestamp in bucketStart..end && !it.estimated }
                ?: all.lastOrNull { it.timestamp in bucketStart..end }
                ?: estimateAt(all, end)
            sample?.copy(dayLabel = labelFormat.format(Date(sample.timestamp)))
        }
    }

    private fun bucketEnds(range: HistoryRange): List<Long> {
        val today = startOfDay(System.currentTimeMillis())
        return when (range) {
            HistoryRange.WEEK -> (6 downTo 0).map { today - it * DAY_MILLIS }
            HistoryRange.MONTH -> (5 downTo 0).map { today - it * 5 * DAY_MILLIS }
            HistoryRange.THREE_MONTHS -> (5 downTo 0).map { today - it * 15 * DAY_MILLIS }
            HistoryRange.YTD, HistoryRange.YEAR -> {
                val months = if (range == HistoryRange.YTD) {
                    Calendar.getInstance().get(Calendar.MONTH) + 1
                } else {
                    12
                }
                (months - 1 downTo 0).map { offset ->
                    val endOfMonth = Calendar.getInstance().apply {
                        timeInMillis = today
                        add(Calendar.MONTH, -offset)
                        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    }
                    minOf(startOfDay(endOfMonth.timeInMillis), today)
                }
            }
        }
    }

    // Linear interpolation between the nearest real samples around `ts`. Null
    // when `ts` isn't bracketed by real data — charts drop those buckets.
    private fun estimateAt(all: List<HistorySample>, ts: Long): HistorySample? {
        val before = all.lastOrNull { it.timestamp <= ts } ?: return null
        val after = all.firstOrNull { it.timestamp > ts } ?: return null
        val frac = (ts - before.timestamp).toDouble() / (after.timestamp - before.timestamp)
        fun lerp(from: Long, to: Long): Long = (from + ((to - from) * frac).toLong())
        return HistorySample(
            dayLabel = "",
            followers = lerp(before.followers, after.followers),
            following = lerp(before.following, after.following),
            posts = lerp(before.posts, after.posts),
            likes = lerp(before.likes, after.likes),
            timestamp = ts,
            estimated = true,
        )
    }

    private fun rangeStart(range: HistoryRange): Long {
        val today = startOfDay(System.currentTimeMillis())
        return when (range) {
            HistoryRange.WEEK -> today - 6 * DAY_MILLIS
            HistoryRange.MONTH -> today - 29 * DAY_MILLIS
            HistoryRange.THREE_MONTHS -> today - 89 * DAY_MILLIS
            HistoryRange.YEAR -> today - 364 * DAY_MILLIS
            HistoryRange.YTD -> Calendar.getInstance().apply {
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }

    fun saveStats(context: Context, stats: ProfileStats) {
        val username = normalizeUsername(stats.userName).ifBlank { settings(context).username }
        addAccount(context, username)
        recordTodayOpen(context, username, stats)
        val saved = savedHistory(context, username)
        // Bridge-estimated samples (est flag) persist so charts can render
        // them; the real-only filters in rangedHistory/history keep them out
        // of every number.
        val next = mergeByDay(saved + stats.history + sampleFor(stats))
        prefs(context).edit()
            .putString(profileKey(username), statsToJson(stats.copy(userName = username)).toString())
            .putString(historyKey(username), JSONArray(next.map { historyToJson(it) }).toString())
            .apply()
    }

    // --- Live "today" delta -------------------------------------------------
    // Delta badges compare current values to yesterday's close when one is
    // recorded, else to the first values seen today (captured at the first
    // sync of the day) so deltas are live within hours of a fresh install.
    fun todayDelta(context: Context, username: String, selector: (HistorySample) -> Long): Long {
        val cleanUsername = normalizeUsername(username).ifBlank { settings(context).username }
        val baseline = deltaBaseline(context, cleanUsername) ?: return 0
        return selector(sampleFor(currentStats(context, cleanUsername))) - selector(baseline)
    }

    private fun deltaBaseline(context: Context, username: String): HistorySample? {
        val today = startOfDay(System.currentTimeMillis())
        return fullHistory(context, username)
            .lastOrNull { it.timestamp == today - DAY_MILLIS && !it.estimated }
            ?: todayOpen(context, username)
    }

    private fun todayOpen(context: Context, username: String): HistorySample? =
        prefs(context).getString(todayOpenKey(username), null)
            ?.let { encoded -> runCatching { historyFromJson(JSONObject(encoded)) }.getOrNull() }
            ?.takeIf { it.timestamp == startOfDay(System.currentTimeMillis()) }

    private fun recordTodayOpen(context: Context, username: String, stats: ProfileStats) {
        if (todayOpen(context, username) != null) return
        prefs(context).edit()
            .putString(todayOpenKey(username), historyToJson(sampleFor(stats)).toString())
            .apply()
    }

    private fun todayOpenKey(username: String) = "today_open_${normalizeUsername(username)}"

    fun migrateStoredHistories(context: Context) {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_HISTORY_MIGRATION_VERSION, 0) >= HISTORY_MIGRATION_VERSION) return
        val edit = prefs.edit()
        accounts(context).forEach { account ->
            val username = normalizeUsername(account)
            if (username.isBlank()) return@forEach
            if (statsJson(context, username) == null && historyJson(context, username) == null) return@forEach
            val stats = currentStats(context, username)
            val normalized = collapseLeadingFlatRun(
                flatBackfillProfileMetrics(savedHistory(context, username), stats)
                    .ifEmpty { listOf(sampleFor(stats)) }
            )
            edit.putString(historyKey(username), JSONArray(normalized.map { historyToJson(it) }).toString())
        }
        edit.putInt(KEY_HISTORY_MIGRATION_VERSION, HISTORY_MIGRATION_VERSION)
        edit.apply()
    }

    fun clearAccount(context: Context) {
        prefs(context).edit()
            .remove(KEY_API_KEY)
            .apply()
        clearCachedStats(context)
    }

    fun clearCachedStats(context: Context, username: String = settings(context).username) {
        val cleanUsername = normalizeUsername(username)
        prefs(context).edit()
            .remove(profileKey(cleanUsername))
            .remove(historyKey(cleanUsername))
            .apply()
    }

    fun followersDelta(context: Context, username: String = settings(context).username): Long =
        todayDelta(context, username) { it.followers }

    fun compactNumber(value: Long): String = when {
        abs(value) >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
        abs(value) >= 10_000 -> "${value / 1_000}K"
        else -> NumberFormat.getIntegerInstance(Locale.US).format(value)
    }

    fun signedNumber(value: Long): String =
        if (value > 0) "+${compactNumber(value)}" else compactNumber(value)

    fun lastSyncedText(context: Context, stats: ProfileStats = currentStats(context)): String {
        val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.US)
        return context.getString(R.string.last_synced, formatter.format(Date(stats.syncedAt)))
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun normalizeUsername(username: String): String =
        username.trim().trimStart('@')

    private fun profileKey(username: String): String =
        if (username.isBlank()) KEY_PROFILE else "${KEY_PROFILE}_${username.lowercase(Locale.US)}"

    private fun historyKey(username: String): String =
        if (username.isBlank()) KEY_HISTORY else "${KEY_HISTORY}_${username.lowercase(Locale.US)}"

    private fun statsJson(context: Context, username: String): String? {
        val prefs = prefs(context)
        val accountJson = prefs.getString(profileKey(username), null)
        val defaultUsername = settings(context).username
        return accountJson ?: prefs.getString(KEY_PROFILE, null)
            ?.takeIf { username.equals(defaultUsername, ignoreCase = true) }
    }

    private fun historyJson(context: Context, username: String): String? {
        val prefs = prefs(context)
        val accountJson = prefs.getString(historyKey(username), null)
        val defaultUsername = settings(context).username
        return accountJson ?: prefs.getString(KEY_HISTORY, null)
            ?.takeIf { username.equals(defaultUsername, ignoreCase = true) }
    }

    private fun sampleFor(stats: ProfileStats): HistorySample =
        HistorySample(
            dayLabel = SimpleDateFormat("MMM d", Locale.US).format(Date(stats.syncedAt)),
            followers = stats.followersCount,
            following = stats.followingsCount,
            posts = stats.statusesCount,
            likes = stats.likeCount,
            timestamp = startOfDay(stats.syncedAt),
        )

    private fun savedHistory(context: Context, username: String): List<HistorySample> {
        val parsed = historyJson(context, username)?.let { encoded ->
            runCatching {
                val array = JSONArray(encoded)
                List(array.length()) { index -> historyFromJson(array.getJSONObject(index)) }
            }.getOrNull()
        }.orEmpty()
        return flattenLegacySeededRamp(mergeByDay(assignTimestamps(parsed.filter { it.dayLabel.isNotBlank() })))
    }

    // One sample per calendar day (the newest wins), oldest first. Daily
    // resolution for the recent window; older samples (Wayback anchors) thin
    // to one per month instead of being evicted by a hard cap.
    private fun mergeByDay(samples: List<HistorySample>): List<HistorySample> {
        val merged = linkedMapOf<Long, HistorySample>()
        samples.sortedBy { it.timestamp }.forEach { sample ->
            val day = startOfDay(sample.timestamp)
            val current = merged[day]
            // A real measurement on a day always beats an estimate for it.
            if (current != null && !current.estimated && sample.estimated) return@forEach
            merged[day] = sample
        }
        val cutoff = startOfDay(System.currentTimeMillis()) - MAX_HISTORY_DAYS * DAY_MILLIS
        val (older, recent) = merged.values.partition { it.timestamp < cutoff }
        if (older.isEmpty()) return recent
        val monthly = linkedMapOf<Int, HistorySample>()
        older.forEach { sample ->
            val calendar = Calendar.getInstance().apply { timeInMillis = sample.timestamp }
            monthly[calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)] = sample
        }
        return monthly.values.toList() + recent
    }

    private fun flattenLegacySeededRamp(samples: List<HistorySample>): List<HistorySample> {
        if (samples.size < LEGACY_SEEDED_FOLLOWER_GAINS.size + 1) return samples
        val seeded = samples.take(LEGACY_SEEDED_FOLLOWER_GAINS.size + 1)
        if (!isConsecutiveDailyHistory(seeded)) return samples
        val gains = seeded.zipWithNext().map { (previous, current) -> current.followers - previous.followers }
        if (gains != LEGACY_SEEDED_FOLLOWER_GAINS) return samples

        return mergeByDay(listOf(seeded.last()) + samples.drop(seeded.size))
    }

    private fun collapseLeadingFlatRun(samples: List<HistorySample>): List<HistorySample> {
        if (samples.size < 2) return samples
        val first = samples.first()
        val flatRunEnd = samples.indexOfFirst { sample -> !sameMetricValues(first, sample) }
            .let { if (it == -1) samples.lastIndex else it - 1 }
        if (flatRunEnd <= 0) return samples
        return samples.drop(flatRunEnd)
    }

    private fun sameMetricValues(left: HistorySample, right: HistorySample): Boolean =
        left.followers == right.followers &&
            left.following == right.following &&
            left.posts == right.posts &&
            left.likes == right.likes

    private fun isConsecutiveDailyHistory(samples: List<HistorySample>): Boolean =
        samples.zipWithNext().all { (previous, current) ->
            startOfDay(current.timestamp) - startOfDay(previous.timestamp) == DAY_MILLIS
        }

    // Samples saved before timestamps existed get their day reconstructed from
    // the "MMM d" label, anchored to the most recent matching date.
    private fun assignTimestamps(samples: List<HistorySample>): List<HistorySample> =
        samples.map { sample ->
            if (sample.timestamp > 0) sample else sample.copy(timestamp = timestampFromLabel(sample.dayLabel))
        }

    private fun timestampFromLabel(label: String): Long {
        val parsed = runCatching { SimpleDateFormat("MMM d", Locale.US).parse(label) }.getOrNull()
            ?: return startOfDay(System.currentTimeMillis())
        val day = Calendar.getInstance().apply {
            val currentYear = get(Calendar.YEAR)
            time = parsed
            set(Calendar.YEAR, currentYear)
        }
        if (day.timeInMillis > System.currentTimeMillis()) day.add(Calendar.YEAR, -1)
        return startOfDay(day.timeInMillis)
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun statsToJson(stats: ProfileStats): JSONObject = JSONObject()
        .put("fullName", stats.fullName)
        .put("userName", stats.userName)
        .put("followersCount", stats.followersCount)
        .put("followingsCount", stats.followingsCount)
        .put("statusesCount", stats.statusesCount)
        .put("likeCount", stats.likeCount)
        .put("profileImage", stats.profileImage)
        .put("statusFieldsKnown", stats.isVerified != null || stats.isPrivate != null)
        .apply {
            stats.isVerified?.let { put("isVerified", it) }
            stats.isPrivate?.let { put("isPrivate", it) }
        }
        .put("syncedAt", stats.syncedAt)

    private fun statsFromJson(json: JSONObject): ProfileStats = ProfileStats(
        fullName = json.optString("fullName", "That Josh Guy"),
        userName = json.optString("userName", "thatjoshguy69"),
        followersCount = json.optLong("followersCount", 7_671),
        followingsCount = json.optLong("followingsCount", 321),
        statusesCount = json.optLong("statusesCount", 2_104),
        likeCount = json.optLong("likeCount", 49_515),
        profileImage = json.optString("profileImage", ""),
        isVerified = if (json.optBoolean("statusFieldsKnown", false) && json.has("isVerified")) json.optBoolean("isVerified") else null,
        isPrivate = if (json.optBoolean("statusFieldsKnown", false) && json.has("isPrivate")) json.optBoolean("isPrivate") else null,
        syncedAt = json.optLong("syncedAt", System.currentTimeMillis()),
    )

    private fun historyToJson(sample: HistorySample): JSONObject = JSONObject()
        .put("dayLabel", sample.dayLabel)
        .put("followers", sample.followers)
        .put("following", sample.following)
        .put("posts", sample.posts)
        .put("likes", sample.likes)
        .put("ts", sample.timestamp)
        .apply { if (sample.estimated) put("est", true) }

    private fun historyFromJson(json: JSONObject): HistorySample = HistorySample(
        dayLabel = json.optString("dayLabel", ""),
        followers = json.optLong("followers", 0),
        following = json.optLong("following", 0),
        posts = json.optLong("posts", 0),
        likes = json.optLong("likes", 0),
        timestamp = json.optLong("ts", 0),
        estimated = json.optBoolean("est", false),
    )

    // Older builds only recorded followers, so early samples can carry zero
    // following/posts/likes. Fill those gaps flat with the nearest later
    // recorded value rather than inventing a trend.
    private fun flatBackfillProfileMetrics(samples: List<HistorySample>, stats: ProfileStats): List<HistorySample> {
        var following = stats.followingsCount
        var posts = stats.statusesCount
        var likes = stats.likeCount
        return samples.asReversed().map { sample ->
            if (sample.following > 0) following = sample.following
            if (sample.posts > 0) posts = sample.posts
            if (sample.likes > 0) likes = sample.likes
            sample.copy(
                following = if (sample.following > 0) sample.following else following,
                posts = if (sample.posts > 0) sample.posts else posts,
                likes = if (sample.likes > 0) sample.likes else likes,
            )
        }.asReversed()
    }

    private fun fallbackStats(context: Context, accountUsername: String = settings(context).username): ProfileStats {
        val username = normalizeUsername(accountUsername).ifBlank { settings(context).username }
        return if (isOnboarded(context) && username.isNotBlank()) {
            demoStats().copy(
                fullName = username,
                userName = username,
                profileImage = fallbackAvatarUrl(username),
                syncedAt = System.currentTimeMillis(),
            )
        } else {
            demoStats()
        }
    }

    private fun fallbackAvatarUrl(username: String): String =
        "https://unavatar.io/twitter/${username.trimStart('@')}"

    private fun demoStats(): ProfileStats = ProfileStats(
        fullName = "That Josh Guy",
        userName = "thatjoshguy69",
        followersCount = 7_671,
        followingsCount = 321,
        statusesCount = 2_104,
        likeCount = 49_515,
        profileImage = "",
        syncedAt = System.currentTimeMillis(),
    )

    private fun demoHistory(): List<HistorySample> {
        val formatter = SimpleDateFormat("MMM d", Locale.US)
        val today = startOfDay(System.currentTimeMillis())
        val followerGains = listOf(25L, 40L, 30L, 38L, 22L, 52L, 109L)
        return followerGains.indices.map { index ->
            val timestamp = today - (followerGains.lastIndex - index) * DAY_MILLIS
            HistorySample(
                dayLabel = formatter.format(Date(timestamp)),
                followers = 7_371L + followerGains.take(index + 1).sum(),
                following = 300L + index,
                posts = 2_080L + index * 4L,
                likes = 47_000L + index * 180L,
                timestamp = timestamp,
            )
        }
    }
}
