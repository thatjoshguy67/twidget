package com.tjg.twidget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
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
)

data class HistorySample(
    val dayLabel: String,
    val followers: Long,
    val impressions: Long,
    val following: Long,
    val posts: Long,
    val likes: Long,
    val followersGain: Long,
    val impressionsDaily: Long,
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

enum class HistoryRange(val labelRes: Int, val points: Int) {
    WEEK(R.string.range_7d, 7),
    MONTH(R.string.range_1m, 6),
    THREE_MONTHS(R.string.range_3m, 12),
    YTD(R.string.range_ytd, 0),
    YEAR(R.string.range_1y, 12),
}

object TwidgetStore {
    const val PREFS = "twidget_prefs"
    const val TAP_REFRESH = "refresh"
    const val TAP_PROFILE = "profile"
    const val TAP_APP = "app"
    const val DATA_SOURCE_DEFAULT = "default_rettiwt"
    const val DATA_SOURCE_SELF_HOSTED = "self_hosted_rettiwt"
    const val DATA_SOURCE_X_API = "official_x_api"
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
    private const val DEFAULT_BRIDGE_URL = "https://twidget-bridge-production.up.railway.app"
    val DEFAULT_DASHBOARD_CARDS = listOf(
        "follower_ratio",
        "post_rate",
        "likes_per_post",
        "milestone",
        "growth_pace",
        "best_day",
        "momentum",
        "audience_balance",
        "account_health",
        "followers",
        "following",
        "posts",
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
            colorMode = prefs.getString("widget_color_mode$suffix", COLOR_MODE_LIGHT) ?: COLOR_MODE_LIGHT,
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

    fun history(context: Context, username: String = settings(context).username): List<HistorySample> {
        val cleanUsername = normalizeUsername(username).ifBlank { settings(context).username }
        val saved = historyJson(context, cleanUsername)?.let { encoded ->
            runCatching {
                val array = JSONArray(encoded)
                List(array.length()) { index -> historyFromJson(array.getJSONObject(index)) }
            }.getOrNull()
        }
        return saved
            ?.let { normalizeHistory(it, useDemoDefaults = !isOnboarded(context)) }
            ?.let { backfillProfileMetrics(it, currentStats(context, cleanUsername)) }
            ?.takeIf { it.size >= 4 }
            ?: fallbackHistory(context, cleanUsername)
    }

    // History over a selectable time range for the dashboard charts. Real daily
    // samples only cover recent days, so longer ranges are a deterministic
    // estimated growth curve that ends exactly at the current values.
    fun rangedHistory(context: Context, username: String, range: HistoryRange): List<HistorySample> {
        if (range == HistoryRange.WEEK) return history(context, username)
        val cleanUsername = normalizeUsername(username).ifBlank { settings(context).username }
        val stats = currentStats(context, cleanUsername)
        val totalDays = when (range) {
            HistoryRange.MONTH -> 30
            HistoryRange.THREE_MONTHS -> 90
            HistoryRange.YEAR -> 365
            HistoryRange.YTD -> daysSinceYearStart().coerceAtLeast(range.points)
            else -> 7
        }
        val dayMillis = 24 * 60 * 60 * 1000L
        val today = System.currentTimeMillis()
        val labelFormat = SimpleDateFormat(if (range == HistoryRange.YEAR || range == HistoryRange.YTD) "MMM" else "MMM d", Locale.US)
        val points = chartPointCount(range, totalDays)
        return (0 until points).map { index ->
            val frac = if (points == 1) 1.0 else index.toDouble() / (points - 1)
            val daysAgo = ((1.0 - frac) * totalDays).toLong()
            val followers = growthValue(stats.followersCount, frac, totalDays, 1)
            HistorySample(
                dayLabel = labelFormat.format(Date(today - daysAgo * dayMillis)),
                followers = followers,
                impressions = (followers * 6.45).toLong(),
                following = growthValue(stats.followingsCount, frac, totalDays, 2),
                posts = growthValue(stats.statusesCount, frac, totalDays, 3),
                likes = growthValue(stats.likeCount, frac, totalDays, 4),
                followersGain = 0,
                impressionsDaily = 0,
            )
        }
    }

    private fun chartPointCount(range: HistoryRange, totalDays: Int): Int = when (range) {
        HistoryRange.YTD -> monthsSinceYearStart().coerceIn(1, 12)
        else -> range.points.coerceAtLeast(1)
    }

    // Rising series from an estimated starting value to `current` (reached at
    // frac == 1). A small deterministic wobble keeps the bars from looking
    // perfectly linear without introducing per-redraw jitter.
    private fun growthValue(current: Long, frac: Double, totalDays: Int, seed: Int): Long {
        if (current <= 0) return 0
        val growthRate = when {
            current >= 100_000 -> 0.18
            current >= 10_000 -> 0.14
            current >= 1_000 -> 0.10
            else -> 0.06
        } * (totalDays / 365.0)
        val start = (current / (1.0 + growthRate)).coerceAtLeast(0.0)
        val base = start + (current - start) * frac
        if (frac >= 1.0) return current
        val wobble = Math.sin((frac * 6.0) + seed) * (current - start) * 0.05
        return (base + wobble).toLong().coerceIn(0, current)
    }

    private fun daysSinceYearStart(): Int {
        val now = java.util.Calendar.getInstance()
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
        }
        return (((now.timeInMillis - start.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()).coerceAtLeast(1)
    }

    private fun monthsSinceYearStart(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1

    fun saveStats(context: Context, stats: ProfileStats) {
        val username = normalizeUsername(stats.userName).ifBlank { settings(context).username }
        addAccount(context, username)
        val hasSavedHistory = prefs(context).contains(historyKey(username))
        val currentHistory = if (hasSavedHistory) {
            backfillProfileMetrics(history(context, username), stats)
        } else {
            seedHistoryFor(stats).dropLast(1)
        }
        val previous = currentHistory.lastOrNull()
        val sample = sampleFor(stats, previous)
        val next = if (currentHistory.lastOrNull()?.dayLabel == sample.dayLabel) {
            currentHistory.dropLast(1) + sample
        } else {
            currentHistory + sample
        }.takeLast(7)
        prefs(context).edit()
            .putString(profileKey(username), statsToJson(stats.copy(userName = username)).toString())
            .putString(historyKey(username), JSONArray(next.map { historyToJson(it) }).toString())
            .apply()
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

    fun followersDelta(context: Context, username: String = settings(context).username): Long {
        val samples = history(context, username)
        if (samples.size < 2) return 0
        return samples.last().followers - samples[samples.lastIndex - 1].followers
    }

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

    private fun sampleFor(stats: ProfileStats, previous: HistorySample? = null): HistorySample =
        HistorySample(
            dayLabel = SimpleDateFormat("MMM d", Locale.US).format(Date(stats.syncedAt)),
            followers = stats.followersCount,
            impressions = (stats.followersCount * 6.45).toLong(),
            following = stats.followingsCount,
            posts = stats.statusesCount,
            likes = stats.likeCount,
            followersGain = previous?.let { stats.followersCount - it.followers }?.coerceAtLeast(0) ?: 0,
            impressionsDaily = previous?.let { (stats.followersCount * 6.45).toLong() - it.impressions }?.coerceAtLeast(0) ?: 0,
        )

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
        .put("impressions", sample.impressions)
        .put("following", sample.following)
        .put("posts", sample.posts)
        .put("likes", sample.likes)
        .put("followersGain", sample.followersGain)
        .put("impressionsDaily", sample.impressionsDaily)

    private fun historyFromJson(json: JSONObject): HistorySample = HistorySample(
        dayLabel = json.optString("dayLabel", ""),
        followers = json.optLong("followers", 0),
        impressions = json.optLong("impressions", 0),
        following = json.optLong("following", 0),
        posts = json.optLong("posts", 0),
        likes = json.optLong("likes", 0),
        followersGain = json.optLong("followersGain", 0),
        impressionsDaily = json.optLong("impressionsDaily", 0),
    )

    private fun normalizeHistory(samples: List<HistorySample>, useDemoDefaults: Boolean): List<HistorySample> {
        val merged = linkedMapOf<String, HistorySample>()
        if (useDemoDefaults) {
            demoHistory().forEach { merged[it.dayLabel] = it }
        }
        samples.filter { it.dayLabel.isNotBlank() }.forEach { sample ->
            val existing = merged[sample.dayLabel]
            merged[sample.dayLabel] =
                if (existing != null && sample.followersGain == 0L && sample.impressionsDaily == 0L) {
                    sample.copy(
                        followersGain = existing.followersGain,
                        impressionsDaily = existing.impressionsDaily,
                    )
                } else {
                    sample
                }
        }
        return merged.values.toList().takeLast(7)
    }

    private fun backfillProfileMetrics(samples: List<HistorySample>, stats: ProfileStats): List<HistorySample> {
        if (samples.size < 3) return samples
        fun shouldBackfill(selector: (HistorySample) -> Long): Boolean =
            samples.dropLast(1).map(selector).distinct().size <= 1 &&
                samples.map(selector).distinct().size <= 2
        val fillFollowing = shouldBackfill { it.following }
        val fillPosts = shouldBackfill { it.posts }
        val fillLikes = shouldBackfill { it.likes }
        if (!fillFollowing && !fillPosts && !fillLikes) return samples
        val followingValues = backfilledSeries(samples, stats.followingsCount) { it.following }
        val postValues = backfilledSeries(samples, stats.statusesCount) { it.posts }
        val likeValues = backfilledSeries(samples, stats.likeCount) { it.likes }
        return samples.mapIndexed { index, sample ->
            sample.copy(
                following = if (fillFollowing) followingValues[index] else sample.following,
                posts = if (fillPosts) postValues[index] else sample.posts,
                likes = if (fillLikes) likeValues[index] else sample.likes,
            )
        }
    }

    private fun backfilledSeries(samples: List<HistorySample>, current: Long, selector: (HistorySample) -> Long): List<Long> {
        val firstSaved = selector(samples.first()).takeIf { it > 0L && it != current }
        val start = firstSaved ?: seedStartValue(current, samples.size)
        val steps = (samples.size - 1).coerceAtLeast(1)
        return samples.indices.map { index ->
            if (index == samples.lastIndex) {
                current
            } else {
                start + ((current - start) * index / steps)
            }.coerceAtLeast(0)
        }
    }

    private fun seedStartValue(current: Long, sampleCount: Int): Long {
        val drift = when {
            current >= 10_000 -> sampleCount * 6L
            current >= 1_000 -> sampleCount * 3L
            else -> sampleCount.toLong()
        }
        return (current - drift).coerceAtLeast(0)
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

    private fun fallbackHistory(context: Context, username: String = settings(context).username): List<HistorySample> =
        if (isOnboarded(context)) seedHistoryFor(fallbackStats(context, username)) else demoHistory()

    private fun seedHistoryFor(stats: ProfileStats): List<HistorySample> {
        val formatter = SimpleDateFormat("MMM d", Locale.US)
        val dayMillis = 24 * 60 * 60 * 1000L
        val today = System.currentTimeMillis()
        val gains = seedFollowerGains(stats.followersCount)
        val totalGain = gains.sum()
        val startFollowers = (stats.followersCount - totalGain).coerceAtLeast(0)
        val followingGains = listOf(0L, 1L, 0L, 0L, 1L, 0L, 0L)
        val postGains = listOf(4L, 5L, 3L, 6L, 4L, 3L, 0L)
        val likeGains = listOf(7L, 8L, 5L, 9L, 6L, 6L, 0L)
        return (6 downTo 0).map { offset ->
            val index = 6 - offset
            val followers = if (index == 6) {
                stats.followersCount
            } else {
                (startFollowers + gains.take(index + 1).sum()).coerceAtMost(stats.followersCount)
            }
            val impressions = (followers * 6.45).toLong()
            HistorySample(
                dayLabel = formatter.format(Date(today - offset * dayMillis)),
                followers = followers,
                impressions = impressions,
                following = backfilledValue(stats.followingsCount, followingGains, index),
                posts = backfilledValue(stats.statusesCount, postGains, index),
                likes = backfilledValue(stats.likeCount, likeGains, index),
                followersGain = gains[index],
                impressionsDaily = (gains[index] * 6.45).toLong(),
            )
        }
    }

    private fun seedFollowerGains(current: Long): List<Long> {
        val baseGains = listOf(12L, 18L, 9L, 21L, 15L, 14L, 26L)
        if (current <= 0L) return baseGains.map { 0L }
        val baseTotal = baseGains.sum()
        if (current >= baseTotal) return baseGains

        val scaled = baseGains.map { it * current / baseTotal }.toMutableList()
        if (scaled.last() == 0L) scaled[scaled.lastIndex] = 1L

        var assigned = scaled.sum()
        val order = baseGains.indices.sortedWith(
            compareByDescending<Int> { (baseGains[it] * current) % baseTotal }
                .thenByDescending { it },
        )
        var orderIndex = 0
        while (assigned < current) {
            scaled[order[orderIndex % order.size]] += 1L
            assigned += 1L
            orderIndex += 1
        }
        return scaled
    }

    private fun backfilledValue(current: Long, gains: List<Long>, index: Int): Long {
        if (index >= gains.lastIndex) return current
        val remainingGain = gains.drop(index).sum()
        return (current - remainingGain).coerceAtLeast(0)
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
        val labels = listOf("Jun 24", "Jun 25", "Jun 26", "Jun 27", "Jun 28", "Jun 29", "Jun 30")
        val followerGains = listOf(25L, 40L, 30L, 38L, 22L, 52L, 109L)
        val impressions = listOf(10_200L, 18_300L, 13_700L, 19_800L, 8_900L, 22_400L, 49_515L)
        return labels.indices.map { index ->
            HistorySample(
                dayLabel = labels[index],
                followers = 7_371L + followerGains.take(index + 1).sum(),
                impressions = impressions[index],
                following = 300L + index,
                posts = 2_080L + index * 4L,
                likes = 47_000L + impressions.take(index + 1).sum() / 20,
                followersGain = followerGains[index],
                impressionsDaily = impressions[index],
            )
        }
    }
}
