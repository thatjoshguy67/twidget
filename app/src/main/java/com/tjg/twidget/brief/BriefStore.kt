package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object BriefStore {
    private const val PREFS = "twidget_briefs"

    fun read(context: Context, username: String): BriefSnapshot? {
        val raw = prefs(context).getString(key(username), null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    fun write(context: Context, snapshot: BriefSnapshot) {
        prefs(context).edit().putString(key(snapshot.username), encode(snapshot).toString()).apply()
    }

    fun resetAi(context: Context, username: String) {
        read(context, username)?.let { snapshot ->
            write(
                context,
                snapshot.copy(
                    providerUsed = BriefProviderUsed.TEMPLATE,
                    providerMessage = "Built on device from your Twidget data",
                    aiGeneratedAt = 0L,
                    headline = "",
                    subheading = "",
                    shortDescription = "",
                ),
            )
        }
    }

    private fun encode(snapshot: BriefSnapshot) = JSONObject().apply {
        put("username", snapshot.username)
        put("generatedAt", snapshot.generatedAt)
        put("sourceSyncedAt", snapshot.sourceSyncedAt)
        put("analyticsCachedAt", snapshot.analyticsCachedAt)
        put("followerScanCompletedAt", snapshot.followerScanCompletedAt)
        put("followers", snapshot.followers)
        put("following", snapshot.following)
        put("posts", snapshot.posts)
        put("followersToday", snapshot.followersToday)
        put("followersWeek", snapshot.followersWeek)
        put("headline", snapshot.headline)
        put("subheading", snapshot.subheading)
        put("shortDescription", snapshot.shortDescription)
        put("engineVersion", snapshot.engineVersion)
        put("contextFingerprint", snapshot.contextFingerprint)
        put("providerUsed", snapshot.providerUsed.name)
        put("providerMessage", snapshot.providerMessage)
        put("aiGeneratedAt", snapshot.aiGeneratedAt)
        put("cards", JSONArray().apply {
            snapshot.cards.forEach { card ->
                put(JSONObject().apply {
                    put("id", card.id)
                    put("type", card.type.name)
                    put("title", card.title)
                    put("body", card.body)
                    put("score", card.score)
                    put("rankingScore", card.rankingScore)
                    put("action", card.action.name)
                    put("actionData", card.actionData)
                    put("sourceAttribution", card.sourceAttribution)
                    put("rankSignals", JSONObject().apply {
                        put("contextRelevance", card.rankSignals.contextRelevance)
                        put("timeRelevance", card.rankSignals.timeRelevance)
                        put("occurredAt", card.rankSignals.occurredAt)
                        put("freshForMillis", card.rankSignals.freshForMillis)
                        put("validUntil", card.rankSignals.validUntil)
                        put("maintainUntil", card.rankSignals.maintainUntil)
                    })
                })
            }
        })
        put("upcomingTweets", JSONArray().apply {
            snapshot.upcomingTweets.forEach { tweet ->
                put(JSONObject().apply {
                    put("id", tweet.id)
                    put("provider", tweet.provider.name)
                    put("status", tweet.status.name)
                    put("scheduledAt", tweet.scheduledAt)
                    put("preview", tweet.preview)
                    put("threadCount", tweet.threadCount)
                    put("mediaCount", tweet.mediaCount)
                    put("errorMessage", tweet.errorMessage)
                })
            }
        })
        put("topFollowerRanks", JSONObject().apply {
            snapshot.topFollowerRanks.forEach { (id, rank) -> put(id, rank) }
        })
    }

    private fun decode(root: JSONObject): BriefSnapshot {
        val cardsJson = root.optJSONArray("cards") ?: JSONArray()
        val ranksJson = root.optJSONObject("topFollowerRanks") ?: JSONObject()
        val upcomingJson = root.optJSONArray("upcomingTweets") ?: JSONArray()
        return BriefSnapshot(
            username = root.optString("username"),
            generatedAt = root.optLong("generatedAt"),
            sourceSyncedAt = root.optLong("sourceSyncedAt"),
            analyticsCachedAt = root.optLong("analyticsCachedAt"),
            followerScanCompletedAt = root.optLong("followerScanCompletedAt"),
            followers = root.optLong("followers"),
            following = root.optLong("following"),
            posts = root.optLong("posts"),
            followersToday = root.optLong("followersToday"),
            followersWeek = root.optLong("followersWeek"),
            headline = root.optString("headline"),
            subheading = root.optString("subheading"),
            shortDescription = root.optString("shortDescription"),
            cards = buildList {
                for (index in 0 until cardsJson.length()) {
                    val card = cardsJson.getJSONObject(index)
                    val rankSignals = card.optJSONObject("rankSignals")
                    add(BriefCard(
                        id = card.optString("id"),
                        type = runCatching { BriefCardType.valueOf(card.optString("type")) }
                            .getOrDefault(BriefCardType.SUMMARY),
                        title = card.optString("title"),
                        body = card.optString("body"),
                        score = card.optInt("score"),
                        action = runCatching { BriefCardAction.valueOf(card.optString("action")) }
                            .getOrDefault(BriefCardAction.NONE),
                        actionData = card.optString("actionData"),
                        sourceAttribution = card.optString("sourceAttribution"),
                        rankSignals = BriefRankSignals(
                            contextRelevance = rankSignals?.optDouble("contextRelevance", 0.5) ?: 0.5,
                            timeRelevance = rankSignals?.optDouble("timeRelevance", 0.5) ?: 0.5,
                            occurredAt = rankSignals?.optLong("occurredAt") ?: 0L,
                            freshForMillis = rankSignals?.optLong("freshForMillis") ?: 0L,
                            validUntil = rankSignals?.optLong("validUntil") ?: 0L,
                            maintainUntil = rankSignals?.optLong("maintainUntil") ?: 0L,
                        ),
                        rankingScore = card.optInt("rankingScore", -1),
                    ))
                }
            },
            upcomingTweets = buildList {
                for (index in 0 until upcomingJson.length()) {
                    val tweet = upcomingJson.getJSONObject(index)
                    add(BriefUpcomingTweet(
                        id = tweet.optString("id"),
                        provider = runCatching { ScheduleProvider.valueOf(tweet.optString("provider")) }
                            .getOrDefault(ScheduleProvider.LOCAL_REMINDER),
                        status = runCatching { ScheduleStatus.valueOf(tweet.optString("status")) }
                            .getOrDefault(ScheduleStatus.SCHEDULED),
                        scheduledAt = tweet.optLong("scheduledAt"),
                        preview = tweet.optString("preview"),
                        threadCount = tweet.optInt("threadCount"),
                        mediaCount = tweet.optInt("mediaCount"),
                        errorMessage = tweet.optString("errorMessage"),
                    ))
                }
            },
            topFollowerRanks = buildMap {
                ranksJson.keys().forEach { id -> put(id, ranksJson.optInt(id)) }
            },
            engineVersion = root.optInt("engineVersion"),
            contextFingerprint = root.optString("contextFingerprint"),
            providerUsed = runCatching { BriefProviderUsed.valueOf(root.optString("providerUsed")) }
                .getOrDefault(BriefProviderUsed.TEMPLATE),
            providerMessage = root.optString(
                "providerMessage",
                "Built on device from your Twidget data",
            ),
            aiGeneratedAt = root.optLong("aiGeneratedAt").takeIf { it > 0L }
                ?: if (root.optString("providerUsed") != BriefProviderUsed.TEMPLATE.name) {
                    root.optLong("generatedAt")
                } else {
                    0L
                },
        )
    }

    private fun key(username: String): String =
        username.trim().trimStart('@').lowercase(Locale.US)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
