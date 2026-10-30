package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object BriefStore {
    private const val PREFS = "twidget_briefs"
    const val ACTION_PRESENTATION_UPDATED = "com.tjg.twidget.BRIEF_PRESENTATION_UPDATED"
    const val EXTRA_USERNAME = "username"

    fun read(context: Context, username: String): BriefSnapshot? {
        val raw = prefs(context).getString(key(username), null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    fun write(context: Context, snapshot: BriefSnapshot) {
        synchronized(com.tjg.twidget.data.TwidgetStore) {
            val account = com.tjg.twidget.data.TwidgetStore.canonicalAccount(context, snapshot.username)
            val previous = prefs(context).getString(key(snapshot.username), null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optString("username").trim().trimStart('@').equals(account, true) }
            val runs = BriefRunCache.recent(listOf(snapshot) + recentRuns(previous))
            val encoded = encode(snapshot).put("recentRuns", JSONArray(runs.map(::encode)))
            val strings = BriefStrings.from(context)
            val content = BriefSettingsStore.contentFingerprint(context)
            val remembered = readPresentation(previous) ?: recentRuns(previous)
                .filter { it.language.equals(strings.languageTag, true) &&
                    com.tjg.twidget.data.TwidgetStore.canonicalAccount(context, it.username).equals(account, true) }
                .sortedByDescending { it.aiGeneratedAt }
                .firstNotNullOfOrNull { BriefPresentation.from(it, strings, content)?.takeIf { memory -> memory.matches(strings, content) } }
            val presentation = snapshot.takeIf { it.language.equals(strings.languageTag, true) }
                ?.let { BriefPresentation.from(it, strings, content) }
                ?.takeIf { it.matches(strings, content) } ?: remembered
            presentation?.let { encoded.put("lastPresentation", encodePresentation(it)) }
            prefs(context).edit().putString(key(snapshot.username), encoded.toString()).apply()
            com.tjg.twidget.widget.coloros.ColorOsLockCardProvider.notifyChanged(context)
            if (presentation != null && presentation != readPresentation(previous)) {
                context.sendBroadcast(android.content.Intent(ACTION_PRESENTATION_UPDATED).setPackage(context.packageName)
                    .putExtra(EXTRA_USERNAME, account))
            }
        }
    }

    /** Last successful copy survives data refreshes, expiry, failed attempts and explicit regeneration. */
    fun presentation(context: Context, username: String, source: BriefSnapshot? = null,
        strings: BriefStrings = BriefStrings.from(context)): BriefPresentation? {
        val account = com.tjg.twidget.data.TwidgetStore.canonicalAccount(context, username)
        val content = BriefSettingsStore.contentFingerprint(context)
        if (source != null && source.username.trim().trimStart('@').equals(account, true)) {
            BriefPresentation.from(source, strings, content)?.takeIf { it.matches(strings, content) }?.let { return it }
        }
        val root = prefs(context).getString(key(account), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        if (!root.optString("username").trim().trimStart('@').equals(account, true)) return null
        readPresentation(root)?.takeIf { it.matches(strings, content) }?.let { return it }
        return recentRuns(root).filter { it.language.equals(strings.languageTag, true) &&
            com.tjg.twidget.data.TwidgetStore.canonicalAccount(context, it.username).equals(account, true) }
            .sortedByDescending { it.aiGeneratedAt }
            .firstNotNullOfOrNull { BriefPresentation.from(it, strings, content)?.takeIf { memory -> memory.matches(strings, content) } }
    }

    private fun encodePresentation(value: BriefPresentation) = JSONObject().put("title", value.title)
        .put("body", value.body).put("shortDescription", value.shortDescription).put("heroType", value.heroType.name)
        .put("language", value.language).put("contentFingerprint", value.contentFingerprint).put("generatedAt", value.generatedAt)

    private fun readPresentation(root: JSONObject?): BriefPresentation? = runCatching {
        val value = root?.optJSONObject("lastPresentation") ?: return null
        BriefPresentation(BriefVoicePolicy.withoutEmDashes(value.getString("title")),
            BriefVoicePolicy.withoutEmDashes(value.getString("body")),
            BriefVoicePolicy.withoutEmDashes(value.getString("shortDescription")),
            BriefCardType.valueOf(value.getString("heroType")), value.getString("language"),
            value.getString("contentFingerprint"), value.getLong("generatedAt"))
    }.getOrNull()

    internal fun cachedRun(context: Context, source: BriefSnapshot): BriefSnapshot? {
        val root = prefs(context).getString(key(source.username), null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        return BriefRunCache.find(recentRuns(root), source, BriefSettingsStore.provider(context), BriefStrings.from(context))
    }

    private fun recentRuns(root: JSONObject?): List<BriefSnapshot> = buildList {
        root ?: return@buildList
        runCatching { decode(root) }.getOrNull()?.let(::add)
        val runs = root.optJSONArray("recentRuns") ?: return@buildList
        for (index in 0 until minOf(runs.length(), BriefRunCache.MAX_RUNS)) {
            runCatching { decode(runs.getJSONObject(index)) }.getOrNull()?.let(::add)
        }
    }

    fun resetAi(context: Context, username: String) {
        synchronized(com.tjg.twidget.data.TwidgetStore) {
            read(context, username)?.let { snapshot ->
                write(
                    context,
                    snapshot.copy(
                        providerUsed = BriefProviderUsed.TEMPLATE,
                        providerMessage = context.getString(R.string.brief_provider_note_template),
                        aiGeneratedAt = 0L,
                        headline = "",
                        subheading = "",
                        shortDescription = "",
                    ),
                )
            }
        }
    }

    internal fun encode(snapshot: BriefSnapshot) = JSONObject().apply {
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
        put("contentSettingsFingerprint", snapshot.contentSettingsFingerprint)
        put("providerUsed", snapshot.providerUsed.name)
        put("providerMessage", snapshot.providerMessage)
        put("language", snapshot.language)
        put("summaryGenerated", snapshot.summaryGenerated)
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
                    put("narrativeGenerated", card.narrativeGenerated)
                    put("metrics", JSONArray(card.metrics.map { metric ->
                        JSONObject().put("label", metric.label).put("value", metric.value)
                    }))
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

    internal fun decode(root: JSONObject): BriefSnapshot {
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
            headline = BriefVoicePolicy.withoutEmDashes(root.optString("headline")),
            subheading = BriefVoicePolicy.withoutEmDashes(root.optString("subheading")),
            shortDescription = BriefVoicePolicy.withoutEmDashes(root.optString("shortDescription")),
            cards = buildList {
                for (index in 0 until cardsJson.length()) {
                    val card = cardsJson.getJSONObject(index)
                    val rankSignals = card.optJSONObject("rankSignals")
                    add(BriefCard(
                        id = card.optString("id"),
                        type = runCatching { BriefCardType.valueOf(card.optString("type")) }
                            .getOrDefault(BriefCardType.SUMMARY),
                        title = if (card.optBoolean("narrativeGenerated")) BriefVoicePolicy.withoutEmDashes(card.optString("title")) else card.optString("title"),
                        body = if (card.optBoolean("narrativeGenerated")) BriefVoicePolicy.withoutEmDashes(card.optString("body")) else card.optString("body"),
                        score = card.optInt("score"),
                        action = runCatching { BriefCardAction.valueOf(card.optString("action")) }
                            .getOrDefault(BriefCardAction.NONE),
                        actionData = card.optString("actionData"),
                        sourceAttribution = card.optString("sourceAttribution"),
                        narrativeGenerated = card.optBoolean("narrativeGenerated"),
                        metrics = buildList {
                            val metrics = card.optJSONArray("metrics") ?: JSONArray()
                            for (i in 0 until metrics.length()) {
                                val metric = metrics.getJSONObject(i)
                                add(BriefMetric(metric.optString("label"), metric.optString("value")))
                            }
                        },
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
            contentSettingsFingerprint = root.optString("contentSettingsFingerprint"),
            providerUsed = runCatching { BriefProviderUsed.valueOf(root.optString("providerUsed")) }
                .getOrDefault(BriefProviderUsed.TEMPLATE),
            providerMessage = root.optString("providerMessage"),
            language = root.optString("language"),
            summaryGenerated = root.optBoolean("summaryGenerated"),
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
        com.tjg.twidget.data.AccountPreferences.open(context, PREFS)
}
