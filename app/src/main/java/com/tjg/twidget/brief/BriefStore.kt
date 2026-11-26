package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.R
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

    fun write(context: Context, snapshot: BriefSnapshot, overview: BriefSnapshot? = null) {
        synchronized(com.tjg.twidget.data.TwidgetStore) {
            val account = com.tjg.twidget.data.TwidgetStore.canonicalAccount(context, snapshot.username)
            val previous = prefs(context).getString(key(snapshot.username), null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optString("username").trim().trimStart('@').equals(account, true) }
            val runs = BriefRunCache.recent(listOf(snapshot) + recentRuns(previous))
            val encoded = encode(snapshot).put("recentRuns", JSONArray(runs.map(::encode)))
            val generationScope = BriefGenerationCadence.scope(snapshot)
            val previousGeneration = if (previous?.optString("overviewGenerationScope") == generationScope)
                previous.optLong("overviewGeneratedAt") else 0L
            val completedOverview = (listOf(snapshot) + listOfNotNull(overview) + recentRuns(previous))
                .filter { BriefGenerationCadence.completedOverview(it) && BriefGenerationCadence.scope(it) == generationScope }
                .maxOfOrNull(BriefGenerationCadence::completedAt) ?: 0L
            maxOf(previousGeneration, completedOverview).takeIf { it > 0L }?.let {
                encoded.put("overviewGenerationScope", generationScope).put("overviewGeneratedAt", it)
            }
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
            if (presentation != null && presentation != readPresentation(previous)) {
                context.sendBroadcast(android.content.Intent(ACTION_PRESENTATION_UPDATED).setPackage(context.packageName)
                    .putExtra(EXTRA_USERNAME, account))
            }
        }
    }

    internal fun overviewGeneratedAt(context: Context, source: BriefSnapshot): Long {
        val root = prefs(context).getString(key(source.username), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return 0L
        return if (root.optString("overviewGenerationScope") == BriefGenerationCadence.scope(source))
            root.optLong("overviewGeneratedAt") else 0L
    }

    /** Legacy display memory survives failures; scoped summaries must still match current evidence. */
    fun presentation(context: Context, username: String, source: BriefSnapshot? = null,
        strings: BriefStrings = BriefStrings.from(context)): BriefPresentation? {
        val account = com.tjg.twidget.data.TwidgetStore.canonicalAccount(context, username)
        val content = BriefSettingsStore.contentFingerprint(context)
        val root = prefs(context).getString(key(account), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("username").trim().trimStart('@').equals(account, true) }
        val current = source?.takeIf { it.username.trim().trimStart('@').equals(account, true) }
            ?: root?.let { runCatching { decode(it) }.getOrNull() }
        val remembered = readPresentation(root)
        fun usable(value: BriefPresentation): Boolean = value.matches(strings, content) &&
            (current == null || value.matchesFollowerFacts(current))
        if (source != null && source.username.trim().trimStart('@').equals(account, true)) {
            BriefPresentation.from(source, strings, content)?.takeIf { it.matches(strings, content) }?.let { return it }
            // Keep successful prose through provider failures only while its measured
            // follower facts still match. Legacy prose has no proof of those facts.
            if (remembered != null && !remembered.matchesFollowerFacts(source)) {
                return measuredPresentation(source, strings, content, allowTemplate = true)?.takeIf { it.matches(strings, content) }
            }
            if (source.summaryEvidenceKey.isNotBlank()) return measuredPresentation(source, strings, content,
                allowTemplate = BriefGenerationCadence.isCovered(context, source))
                ?.takeIf { it.matches(strings, content) }
        }
        if (root == null) return null
        if (current != null && remembered != null && !remembered.matchesFollowerFacts(current)) {
            return measuredPresentation(current, strings, content, allowTemplate = true)?.takeIf { it.matches(strings, content) }
        }
        if (root.optString("summaryEvidenceKey").isNotBlank()) {
            return runCatching { decode(root) }.getOrNull()?.let {
                BriefPresentation.from(it, strings, content) ?: measuredPresentation(it, strings, content,
                    allowTemplate = BriefGenerationCadence.isCovered(context, it))
            }
                ?.takeIf { it.matches(strings, content) }
        }
        remembered?.takeIf(::usable)?.let { return it }
        return recentRuns(root).filter { it.language.equals(strings.languageTag, true) &&
            com.tjg.twidget.data.TwidgetStore.canonicalAccount(context, it.username).equals(account, true) }
            .sortedByDescending { it.aiGeneratedAt }
            .firstNotNullOfOrNull { BriefPresentation.from(it, strings, content)?.takeIf(::usable) }
    }

    // While a changed summary is rewritten, show its current measured overview.
    // This is display-only: it never marks a template as a successful AI result.
    private fun measuredPresentation(source: BriefSnapshot, strings: BriefStrings, content: String, allowTemplate: Boolean = false): BriefPresentation? {
        if ((!allowTemplate && source.providerUsed == BriefProviderUsed.TEMPLATE) || !source.language.equals(strings.languageTag, true)) return null
        val summary = BriefEditorialSummary.from(source.cards, strings, source.followersToday, source.followersWeek)
        val mixedDirections = source.followersToday > 0 && source.followersWeek < 0 || source.followersToday < 0 && source.followersWeek > 0
        val short = if (mixedDirections) strings.text(R.string.followers_net_today_week,
            com.tjg.twidget.data.TwidgetStore.signedNumber(source.followersToday, strings.locale),
            com.tjg.twidget.data.TwidgetStore.signedNumber(source.followersWeek, strings.locale)) else summary.shortDescription
        return BriefPresentation(summary.title, summary.body, short,
            source.cards.firstOrNull()?.type ?: BriefCardType.SUMMARY, source.language,
            source.contentSettingsFingerprint.ifBlank { content },
            source.generatedAt, source.summaryEvidenceKey, BriefPresentation.followerFactsKey(source))
    }

    private fun readPoints(array: JSONArray?): List<BriefChartPoint> = buildList {
        if (array != null) for (i in 0 until minOf(array.length(), 64)) {
            val point = array.optJSONObject(i) ?: continue
            add(BriefChartPoint(point.optString("label"), if (point.isNull("value")) null else point.optLong("value"),
                point.optLong("timestamp"), point.optInt("samples").coerceAtLeast(0)))
        }
    }

    private fun encodePresentation(value: BriefPresentation) = JSONObject().put("title", value.title)
        .put("body", value.body).put("shortDescription", value.shortDescription).put("heroType", value.heroType.name)
        .put("language", value.language).put("contentFingerprint", value.contentFingerprint).put("generatedAt", value.generatedAt)
        .put("evidenceKey", value.evidenceKey)
        .put("followerFactsKey", value.followerFactsKey)

    private fun readPresentation(root: JSONObject?): BriefPresentation? = runCatching {
        val value = root?.optJSONObject("lastPresentation") ?: return null
        BriefPresentation(BriefVoicePolicy.withoutEmDashes(value.getString("title")),
            BriefVoicePolicy.withoutEmDashes(value.getString("body")),
            BriefVoicePolicy.withoutEmDashes(value.getString("shortDescription")),
            BriefCardType.valueOf(value.getString("heroType")), value.getString("language"),
            value.getString("contentFingerprint"), value.getLong("generatedAt"), value.optString("evidenceKey"), value.optString("followerFactsKey"))
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
                        summaryGenerated = false,
                        summaryGeneratedAt = 0L,
                        cards = snapshot.cards.map { it.copy(narrativeGenerated = false, narrativeGeneratedAt = 0L) },
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
        put("summaryEvidence", snapshot.summaryEvidence)
        put("summaryEvidenceKey", snapshot.summaryEvidenceKey)
        put("summaryGeneratedAt", snapshot.summaryGeneratedAt)
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
                    put("evidenceKey", card.evidenceKey)
                    put("narrativeGeneratedAt", card.narrativeGeneratedAt)
                    fun points(values: List<BriefChartPoint>) = JSONArray().apply { values.forEach { point ->
                        put(JSONObject().put("label", point.label).put("value", point.value ?: JSONObject.NULL)
                            .put("timestamp", point.timestamp).put("samples", point.samples))
                    } }
                    put("chartPoints", points(card.chartPoints))
                    put("activityPoints", points(card.activityPoints))
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
        put("topFollowerRanks", JSONObject().apply {
            snapshot.topFollowerRanks.forEach { (id, rank) -> put(id, rank) }
        })
    }

    internal fun decode(root: JSONObject): BriefSnapshot {
        val cardsJson = root.optJSONArray("cards") ?: JSONArray()
        val ranksJson = root.optJSONObject("topFollowerRanks") ?: JSONObject()
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
                    if (card.optString("type") in setOf("SCHEDULE_GUIDE", "POST_FOLLOW_THROUGH")) continue
                    add(BriefCard(
                        id = card.optString("id"),
                        type = runCatching { BriefCardType.valueOf(card.optString("type")) }
                            .getOrDefault(BriefCardType.SUMMARY),
                        title = if (card.optBoolean("narrativeGenerated")) BriefVoicePolicy.withoutEmDashes(card.optString("title")) else card.optString("title"),
                        body = if (card.optBoolean("narrativeGenerated")) BriefVoicePolicy.withoutEmDashes(card.optString("body")) else card.optString("body"),
                        score = card.optInt("score"),
                        action = runCatching { BriefCardAction.valueOf(card.optString("action")) }
                            .getOrDefault(BriefCardAction.NONE)
                            .takeUnless { it == BriefCardAction.OPEN_SCHEDULER || it == BriefCardAction.COMPOSE_TWEET }
                            ?: BriefCardAction.NONE,
                        actionData = card.optString("actionData"),
                        sourceAttribution = card.optString("sourceAttribution"),
                        narrativeGenerated = card.optBoolean("narrativeGenerated"),
                        evidenceKey = card.optString("evidenceKey"),
                        narrativeGeneratedAt = card.optLong("narrativeGeneratedAt"),
                        chartPoints = readPoints(card.optJSONArray("chartPoints")),
                        activityPoints = readPoints(card.optJSONArray("activityPoints")),
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
            upcomingTweets = emptyList(),
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
            summaryEvidence = root.optString("summaryEvidence"),
            summaryEvidenceKey = root.optString("summaryEvidenceKey"),
            summaryGeneratedAt = root.optLong("summaryGeneratedAt"),
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
