package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.core.HttpTransport
import org.json.JSONObject
import java.security.MessageDigest

/** Only typed, allowlisted values from pinned official source reach the model. */
internal object BriefAlgorithmContext {
    const val REVISION = "30647a85a20cbdd114169beb9973680c4dc4f401"
    private const val DAY = 86_400_000L
    private val lock = Any()
    // label, Rust parameter, scorer action. Zero defaults are intentionally not called boosts.
    private val signals = listOf(
        Triple("likes", "FavoriteWeight", "favorite"), Triple("replies", "ReplyWeight", "reply"),
        Triple("reposts", "RetweetWeight", "retweet"), Triple("quote tweets", "QuoteWeight", "quote"),
        Triple("shares", "ShareWeight", "share"), Triple("DM shares", "ShareViaDmWeight", "share_via_dm"),
        Triple("copy-link shares", "ShareViaCopyLinkWeight", "share_via_copy_link"),
        Triple("post clicks", "ClickWeight", "click"), Triple("link opens", "OpenLinkWeight", "open_link"),
        Triple("image expansion", "PhotoExpandWeight", "photo_expand"), Triple("video opens", "VideoOpenWeight", "video_open"),
        Triple("quality video views", "VqvWeight", "vqv"), Triple("profile clicks", "ProfileClickWeight", "profile_click"),
        Triple("follows", "FollowAuthorWeight", "follow_author"), Triple("dwell", "DwellWeight", "dwell"),
        Triple("dwell duration", "ContDwellTimeWeight", "dwell_time"),
        Triple("click dwell duration", "ContClickDwellTimeWeight", "click_dwell_time"),
        Triple("not interested", "NotInterestedWeight", "not_interested"), Triple("blocks", "BlockAuthorWeight", "block_author"),
        Triple("mutes", "MuteAuthorWeight", "mute_author"), Triple("reports", "ReportWeight", "report"),
        Triple("not dwelling", "NotDwelledWeight", "not_dwelled"))
    private val defaults = listOf(.708, 5.0, 1.0, 5.0, 2.118, 5.0, 0.0, .274, .2, .05, 0.0, 0.0, 0.0,
        4.0, .05, .0075, .385, -53.07, -31.2, -58.8, -234.0, -.02)

    data class Snapshot(val revision: String, val weights: List<Double>) {
        val key: String get() = "x-guide-4:" + MessageDigest.getInstance("SHA-256")
            .digest(weights.joinToString(",").toByteArray()).joinToString("") { "%02x".format(it) }
        fun prompt(): String {
            fun labels(predicate: (Double) -> Boolean) = signals.indices.filter { predicate(weights[it]) }.joinToString { signals[it].first }
            return """
                X FOR YOU ALGORITHM BACKGROUND (reference knowledge, NOT evidence about this tweet)
                Official public xai-org/x-algorithm source snapshot: $revision.
                Source: https://github.com/xai-org/x-algorithm/tree/$revision
                The published system personalises recommendations using the viewer's history and predicts responses to candidates.
                This is not a universal quality score for a tweet. The public code and its default configuration are not proof of today's production settings.
                Parsed scorer default directions, for supported signals only:
                Positive: ${labels { it > 0 }}.
                Negative: ${labels { it < 0 }}.
                Zero-default (do not call these automatic boosts): ${labels { it == 0.0 }}.
                Weights operate on predicted probabilities or continuous predictions, NOT public engagement totals.
                Never claim that one reply/report cancels a fixed number of likes, or calculate an algorithm score from the supplied counts.
                Do not invent numerical weights or say one signal always beats another; contextual weights and production overrides can differ.
                Eligibility, viewer preferences, retrieval and subsequent ranking stages also affect distribution. These signals alone cannot explain reach.

                HOW TO APPLY THIS BACKGROUND
                Use it to choose a relevant, specific experiment grounded in the supplied wording, media and measured history.
                Editorial hypotheses: make the subject and intended audience understandable; deliver a clear payoff worth reading, inspecting or sharing;
                invite a substantive response only when the topic genuinely warrants one. Do not mechanically append questions or recommend engagement bait,
                outrage, misleading hooks, spam, coordinated activity or mass reporting. A model predicting replies is not proof that provoking replies helps.
                Text and media should complement each other; do not assume an image/video/hashtag/link is inherently rewarded or penalised.
                Do not prescribe a magic posting time, hashtag count, word count, format or burst of posts from this reference.
                Compare like-aged examples and acknowledge selection bias; do not confuse cumulative views with gains in a specific period.
                Twidget does NOT observe X's per-viewer predictions, dwell time, private shares, hides, mutes, blocks, reports,
                candidate eligibility, viewer-interest histories or impression sources. Do not infer any of those from low views or public reply counts.
                Separate measured observations, general algorithm background and tentative editorial advice. Background cannot be cited as observed tweet evidence.
                Cite supplied evidenceIds only; never invent an algorithm evidenceId or imply the repository was searched live by the review model.
                Keep the existing 1–2 suggestion limit and concise response. Explain a useful change in ordinary language rather than reciting this guide.
            """.trimIndent()
        }
    }
    val bundled = Snapshot(REVISION, defaults)
    val bundledKey get() = bundled.key

    fun parse(revision: String, params: String, scorer: String): Snapshot {
        require(revision.matches(Regex("[a-f0-9]{40}")))
        require(params.length in 100..400_000 && scorer.length in 100..400_000)
        require(scorer.contains("pub fn weighted_terms") && scorer.contains("compute_weighted_score"))
        val values = signals.map { (_, parameter, action) ->
            require(scorer.contains("\"$action\"")) { "Scorer contract changed" }
            val pattern = Regex("param!\\(\\s*${parameter}\\s*,\\s*f64\\s*,\\s*\"[a-z0-9_]+\"\\s*,\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*,?\\s*\\)")
            val matches = pattern.findAll(params).toList()
            require(matches.size == 1) { "Parameter contract changed" }
            matches.single().groupValues[1].toDouble().also { require(it.isFinite() && kotlin.math.abs(it) <= 1_000_000) }
        }
        return Snapshot(revision, values)
    }

    fun current(context: Context): Snapshot = runCatching {
        val raw = context.getSharedPreferences("brief_algorithm_context", 0).getString("snapshot", null) ?: return bundled
        val json = JSONObject(raw)
        val weights = json.getJSONArray("weights")
        require(weights.length() == signals.size)
        val result = Snapshot(json.getString("revision"), (0 until weights.length()).map { weights.getDouble(it) })
        require(result.revision.matches(Regex("[a-f0-9]{40}")) && result.weights.all { it.isFinite() && kotlin.math.abs(it) <= 1_000_000 })
        result
    }.getOrDefault(bundled)

    /** At most one daily check; offline/rate-limit/schema failures back off for an hour. No post data or auth headers. */
    fun refresh(context: Context, now: Long = System.currentTimeMillis(),
        fetch: (String) -> String = { url -> HttpTransport.requireSuccess(HttpTransport.get(url,
            connectTimeoutMs = 1500, readTimeoutMs = 1500), "Algorithm context") }): Snapshot = synchronized(lock) {
        val prefs = context.getSharedPreferences("brief_algorithm_context", 0)
        val existing = current(context)
        if (now < prefs.getLong("nextCheck", 0)) return@synchronized existing
        prefs.edit().putLong("nextCheck", now + 3_600_000L).apply()
        try {
            val ref = JSONObject(fetch("https://api.github.com/repos/xai-org/x-algorithm/git/ref/heads/main")).getJSONObject("object")
            require(ref.getString("type") == "commit")
            val revision = ref.getString("sha").also { require(it.matches(Regex("[a-f0-9]{40}"))) }
            val next = if (revision == existing.revision) existing else {
                val base = "https://raw.githubusercontent.com/xai-org/x-algorithm/$revision/"
                parse(revision, fetch(base + "home-mixer/params/param.rs"), fetch(base + "xai-value-model/scoring.rs"))
            }
            prefs.edit().putString("snapshot", JSONObject().put("revision", next.revision)
                .put("weights", org.json.JSONArray(next.weights)).toString()).putLong("nextCheck", now + DAY).apply()
            next
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            existing
        }
    }
}
