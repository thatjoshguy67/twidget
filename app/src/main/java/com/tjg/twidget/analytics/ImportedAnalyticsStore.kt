package com.tjg.twidget.analytics

import android.content.Context
import com.tjg.twidget.schedule.json
import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Local-only daily account analytics imported from an X Analytics CSV. */
object ImportedAnalyticsStore {
    private const val PREFS = "twidget_imported_analytics"
    private const val MAX_DAYS = 366

    fun validate(context: Context, username: String, incoming: List<XAnalyticsMovement>) {
        val existing = all(context, username).associateBy { it.date }
        incoming.forEach { sample ->
            existing[sample.date]?.let { saved -> validateAnalyticsOverlap(saved, sample) }
        }
    }

    fun saveVerified(context: Context, username: String, incoming: List<XAnalyticsMovement>) {
        if (incoming.none(::hasAnalytics)) return
        validate(context, username, incoming)
        val merged = linkedMapOf<LocalDate, XAnalyticsMovement>()
        (all(context, username) + incoming).sortedBy { it.date }.forEach { sample ->
            if (hasAnalytics(sample)) {
                merged[sample.date] = merged[sample.date]?.let { saved -> merge(saved, sample) } ?: sample
            }
        }
        val retained = merged.values.toList().takeLast(MAX_DAYS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(username), JSONArray(retained.map(::toJson)).toString())
            .apply()
    }

    fun recent(context: Context, username: String, days: Long = 7): List<XAnalyticsMovement> {
        val first = LocalDate.now().minusDays((days - 1).coerceAtLeast(0))
        return all(context, username).filter { !it.date.isBefore(first) }
    }

    fun all(context: Context, username: String): List<XAnalyticsMovement> {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(username), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            List(array.length()) { index -> fromJson(array.getJSONObject(index)) }
                .sortedBy { it.date }
        }.getOrDefault(emptyList())
    }

    private fun hasAnalytics(sample: XAnalyticsMovement): Boolean =
        sample.analyticsValues().any { it.second != null }

    private fun merge(saved: XAnalyticsMovement, incoming: XAnalyticsMovement): XAnalyticsMovement = incoming.copy(
        impressions = saved.impressions ?: incoming.impressions,
        likes = saved.likes ?: incoming.likes,
        engagements = saved.engagements ?: incoming.engagements,
        bookmarks = saved.bookmarks ?: incoming.bookmarks,
        shares = saved.shares ?: incoming.shares,
        replies = saved.replies ?: incoming.replies,
        reposts = saved.reposts ?: incoming.reposts,
        profileVisits = saved.profileVisits ?: incoming.profileVisits,
        postsCreated = saved.postsCreated ?: incoming.postsCreated,
        videoViews = saved.videoViews ?: incoming.videoViews,
        mediaViews = saved.mediaViews ?: incoming.mediaViews,
    )

    private fun toJson(sample: XAnalyticsMovement): JSONObject = JSONObject()
        .put("date", sample.date.toString())
        .put("newFollows", sample.newFollows)
        .put("unfollows", sample.unfollows)
        .apply {
            sample.analyticsValues().forEach { (name, value) ->
                value?.let { put(name.replace(" ", "_"), it) }
            }
        }

    private fun fromJson(json: JSONObject): XAnalyticsMovement = XAnalyticsMovement(
        date = LocalDate.parse(json.getString("date")),
        newFollows = json.optLong("newFollows", 0L),
        unfollows = json.optLong("unfollows", 0L),
        impressions = optionalLong(json, "impressions"),
        likes = optionalLong(json, "likes"),
        engagements = optionalLong(json, "engagements"),
        bookmarks = optionalLong(json, "bookmarks"),
        shares = optionalLong(json, "shares"),
        replies = optionalLong(json, "replies"),
        reposts = optionalLong(json, "reposts"),
        profileVisits = optionalLong(json, "profile_visits"),
        postsCreated = optionalLong(json, "posts_created"),
        videoViews = optionalLong(json, "video_views"),
        mediaViews = optionalLong(json, "media_views"),
    )

    private fun optionalLong(json: JSONObject, key: String): Long? =
        if (json.has(key) && !json.isNull(key)) json.optLong(key) else null

    private fun key(username: String): String = username.trim().trimStart('@').lowercase(Locale.US)
}

internal fun XAnalyticsMovement.analyticsValues(): List<Pair<String, Long?>> = listOf(
    "impressions" to impressions,
    "likes" to likes,
    "engagements" to engagements,
    "bookmarks" to bookmarks,
    "shares" to shares,
    "replies" to replies,
    "reposts" to reposts,
    "profile visits" to profileVisits,
    "posts created" to postsCreated,
    "video views" to videoViews,
    "media views" to mediaViews,
)

internal fun validateAnalyticsOverlap(saved: XAnalyticsMovement, incoming: XAnalyticsMovement) {
    saved.analyticsValues().zip(incoming.analyticsValues()).forEach { (old, new) ->
        if (old.second != null && new.second != null && old.second != new.second) {
            throw IllegalArgumentException(
                "The CSV changes ${old.first} on ${incoming.date} from ${old.second} to ${new.second}. " +
                    "Previously imported analytics cannot be rewritten.",
            )
        }
    }
}
