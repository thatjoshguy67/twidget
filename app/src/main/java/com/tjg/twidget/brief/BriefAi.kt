package com.tjg.twidget.brief

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.widget.TwidgetBriefWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class BriefLocalStatus { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

internal fun briefRequiresApiKey(localStatus: BriefLocalStatus, cloudApiKey: String): Boolean =
    localStatus == BriefLocalStatus.UNAVAILABLE && cloudApiKey.isBlank()

enum class BriefNanoModelMode(val storageId: String, val label: String) {
    AUTO_STABLE("auto_stable", "Auto · Stable (Fast → Full)"),
    STABLE_FULL("stable_full", "Stable · Full"),
    STABLE_FAST("stable_fast", "Stable · Fast"),
    PREVIEW_FULL("preview_full", "Preview · Full"),
    PREVIEW_FAST("preview_fast", "Preview · Fast");

    internal fun probeOrder(): List<BriefNanoModelMode> = when (this) {
        AUTO_STABLE -> listOf(STABLE_FAST, STABLE_FULL)
        else -> listOf(this)
    }

    companion object {
        fun fromStorageId(value: String?): BriefNanoModelMode =
            entries.firstOrNull { it.storageId == value } ?: AUTO_STABLE
    }
}

data class BriefAiResult(
    val snapshot: BriefSnapshot,
    val localStatus: BriefLocalStatus,
)

data class BriefAiDiagnostics(
    val mode: BriefProviderMode,
    val nanoModelMode: BriefNanoModelMode,
    val resolvedNanoModelMode: BriefNanoModelMode?,
    val nanoProbeAttempts: String?,
    val runtimePresent: Boolean,
    val localStatus: BriefLocalStatus,
    val statusError: String?,
    val localModelName: String?,
    val localTokenLimit: Int?,
    val savedProvider: BriefProviderUsed,
    val lastAttemptAt: Long,
    val lastAttemptedProvider: String?,
    val lastOutcome: String?,
    val lastLocalFailure: String?,
    val lastLocalDetail: String?,
    val cloudConfigured: Boolean,
)

object BriefAiCoordinator {
    private val generationMutex = Mutex()

    suspend fun enrich(
        context: Context,
        source: BriefSnapshot,
        force: Boolean = false,
    ): BriefAiResult = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val mode = BriefSettingsStore.provider(context)
            val cached = if (force) {
                source
            } else {
                BriefAiCachePolicy.retain(BriefStore.read(context, source.username), source)
            }
            if (!force && cachedProviderMatches(mode, cached.providerUsed)) {
                if (cached !== source) BriefStore.write(context, cached)
                return@withLock BriefAiResult(
                    cached,
                    if (cached.providerUsed == BriefProviderUsed.LOCAL) {
                        BriefLocalStatus.AVAILABLE
                    } else {
                        BriefLocalStatus.UNAVAILABLE
                    },
                )
            }
            val localProbe = GeminiNanoBriefProvider.probe(context)
            val localStatus = localProbe.status
            val resultMatchesMode = when (mode) {
                BriefProviderMode.LOCAL -> source.providerUsed == BriefProviderUsed.LOCAL
                BriefProviderMode.CLOUD -> source.providerUsed == BriefProviderUsed.CLOUD
                BriefProviderMode.AUTO -> when (localStatus) {
                    BriefLocalStatus.AVAILABLE -> source.providerUsed == BriefProviderUsed.LOCAL
                    else -> source.providerUsed == BriefProviderUsed.CLOUD
                }
            }
            if (!force && resultMatchesMode) {
                return@withLock BriefAiResult(source, localStatus)
            }

            val generated = when (mode) {
                BriefProviderMode.LOCAL -> {
                    BriefAiDiagnosticsStore.attempt(context, "Gemini Nano")
                    GeminiNanoBriefProvider.generate(context, source)?.copy(
                        providerMessage = "Written privately with Gemini Nano on this device",
                    )
                }
                BriefProviderMode.CLOUD -> {
                    BriefAiDiagnosticsStore.attempt(context, "Gemini Cloud")
                    GeminiCloudBriefProvider.generate(context, source)?.copy(
                        providerMessage = "Written with Gemini Cloud using your API key",
                    )
                }
                BriefProviderMode.AUTO -> {
                    BriefAiDiagnosticsStore.attempt(context, "Gemini Nano")
                    GeminiNanoBriefProvider.generate(context, source)?.copy(
                        providerMessage = "Written privately with Gemini Nano on this device",
                    ) ?: run {
                        BriefAiDiagnosticsStore.attempt(context, "Gemini Cloud")
                        GeminiCloudBriefProvider.generate(context, source)?.copy(
                            providerMessage = "Gemini Nano couldn’t complete this Brief; used Gemini Cloud with your API key",
                        )
                    }
                }
            } ?: source.copy(providerMessage = fallbackMessage(mode, localStatus, context))

            BriefAiDiagnosticsStore.outcome(context, generated.providerUsed, generated.providerMessage)
            BriefStore.write(context, generated)
            TwidgetBriefWidget.updateAll(context)
            BriefAiResult(generated, localStatus)
        }
    }

    suspend fun downloadLocalModel(
        context: Context,
        onStatus: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        GeminiNanoBriefProvider.download(context, onStatus)
    }

    fun nanoModelMode(context: Context): BriefNanoModelMode =
        BriefAiDiagnosticsStore.nanoModelMode(context)

    fun setNanoModelMode(context: Context, mode: BriefNanoModelMode) {
        BriefAiDiagnosticsStore.setNanoModelMode(context, mode)
    }

    suspend fun diagnostics(context: Context, username: String): BriefAiDiagnostics = withContext(Dispatchers.IO) {
        val probe = GeminiNanoBriefProvider.probe(context)
        val saved = BriefStore.read(context, username)?.providerUsed ?: BriefProviderUsed.TEMPLATE
        BriefAiDiagnosticsStore.read(context, BriefSettingsStore.provider(context), probe, saved)
    }

    private fun fallbackMessage(
        mode: BriefProviderMode,
        localStatus: BriefLocalStatus,
        context: Context,
    ): String = when {
        mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.DOWNLOADABLE ->
            "Gemini Nano is supported and ready to download"
        mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.DOWNLOADING ->
            "Gemini Nano is still downloading"
        mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.AVAILABLE ->
            "Gemini Nano is available, but couldn’t complete this Brief"
        mode == BriefProviderMode.LOCAL -> "Gemini Nano isn’t available on this device"
        mode == BriefProviderMode.CLOUD && BriefSettingsStore.cloudApiKey(context).isBlank() ->
            "Add a Gemini API key in Settings to enable cloud writing"
        mode == BriefProviderMode.AUTO && localStatus == BriefLocalStatus.DOWNLOADABLE ->
            "Gemini Nano is ready to download; using private factual copy for now"
        mode == BriefProviderMode.AUTO && BriefSettingsStore.cloudApiKey(context).isBlank() ->
            "No AI provider is ready; using private factual copy"
        else -> "AI wasn’t reachable; using private factual copy"
    }

    private fun cachedProviderMatches(
        mode: BriefProviderMode,
        provider: BriefProviderUsed,
    ): Boolean = when (mode) {
        BriefProviderMode.AUTO -> provider == BriefProviderUsed.LOCAL ||
            provider == BriefProviderUsed.CLOUD
        BriefProviderMode.LOCAL -> provider == BriefProviderUsed.LOCAL
        BriefProviderMode.CLOUD -> provider == BriefProviderUsed.CLOUD
    }
}

private data class NanoProbe(
    val status: BriefLocalStatus,
    val error: String?,
    val modelName: String? = null,
    val tokenLimit: Int? = null,
    val selectedMode: BriefNanoModelMode? = null,
    val attempts: String? = null,
)

private object GeminiNanoBriefProvider {
    suspend fun probe(context: Context): NanoProbe {
        val attempts = mutableListOf<String>()
        var lastError: String? = null
        BriefAiDiagnosticsStore.nanoModelMode(context).probeOrder().forEach { mode ->
            val result = probe(mode)
            attempts += buildString {
                append("${mode.label}: ${result.status}")
                result.error?.let { append(" ($it)") }
            }
            if (result.status != BriefLocalStatus.UNAVAILABLE) {
                return result.copy(attempts = attempts.joinToString(" · "))
            }
            if (result.error != null) lastError = result.error
        }
        lastError?.let { BriefAiDiagnosticsStore.localFailure(context, it) }
        return NanoProbe(
            status = BriefLocalStatus.UNAVAILABLE,
            error = lastError,
            attempts = attempts.joinToString(" · "),
        )
    }

    private suspend fun probe(mode: BriefNanoModelMode): NanoProbe = runCatching {
        val model = client(mode)
        try {
            val status = when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> BriefLocalStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> BriefLocalStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> BriefLocalStatus.DOWNLOADING
                else -> BriefLocalStatus.UNAVAILABLE
            }
            NanoProbe(
                status = status,
                error = null,
                modelName = if (status == BriefLocalStatus.AVAILABLE) {
                    runCatching { model.getBaseModelName() }.getOrNull()
                } else null,
                tokenLimit = if (status == BriefLocalStatus.AVAILABLE) {
                    runCatching { model.getTokenLimit() }.getOrNull()
                } else null,
                selectedMode = mode,
            )
        } finally {
            model.close()
        }
    }.getOrElse { error ->
        val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
        NanoProbe(BriefLocalStatus.UNAVAILABLE, reason, selectedMode = mode)
    }

    suspend fun generate(context: Context, source: BriefSnapshot): BriefSnapshot? {
        val probe = probe(context)
        if (probe.status != BriefLocalStatus.AVAILABLE) {
            BriefAiDiagnosticsStore.localFailure(context, "Feature status: ${probe.status}")
            return null
        }
        val selectedMode = probe.selectedMode ?: return null
        return runCatching {
            val model = client(selectedMode)
            try {
                val request = generateContentRequest(
                    TextPart("$SYSTEM_INSTRUCTION\n\n${localPromptFor(source)}"),
                ) {
                    temperature = 0.25f
                    topK = 3
                    // ML Kit's Gemini Nano prompt API currently accepts at most
                    // 256 output tokens. Larger values pass compilation but are
                    // rejected by AICore when the request is executed.
                    maxOutputTokens = 256
                }
                val inputTokens = runCatching { model.countTokens(request).totalTokens }.getOrNull()
                val response = model.generateContent(request)
                val candidate = response.candidates.firstOrNull()
                if (candidate == null) {
                    BriefAiDiagnosticsStore.localFailure(context, "Generation returned no candidate")
                    BriefAiDiagnosticsStore.localDetail(context, "candidate=missing")
                    return@runCatching null
                }
                val finishReason = finishReasonName(candidate.finishReason)
                val parsed = BriefAiCardResponse.apply(
                    source,
                    candidate.text,
                    BriefProviderUsed.LOCAL,
                )
                BriefAiDiagnosticsStore.localDetail(
                    context,
                    buildString {
                        append("finish=$finishReason")
                        inputTokens?.let { append(" · input=$it tokens") }
                        append(" · response=${candidate.text.length} chars · cards=${parsed.appliedCards}")
                    },
                )
                if (parsed.snapshot == null) {
                    BriefAiDiagnosticsStore.localFailure(
                        context,
                        "${parsed.failure ?: "Response parsing failed"} (finish=$finishReason, ${candidate.text.length} chars)",
                    )
                }
                parsed.snapshot
            } finally {
                model.close()
            }
        }.onFailure { error ->
            BriefAiDiagnosticsStore.localFailure(
                context,
                describeLocalError(error),
            )
        }.getOrNull()
    }

    suspend fun download(context: Context, onStatus: (String) -> Unit): Boolean = runCatching {
        val selectedMode = probe(context).selectedMode ?: return@runCatching false
        val model = client(selectedMode)
        try {
            var complete = false
            var total = 0L
            model.download().collect { event ->
                when (event) {
                    is DownloadStatus.DownloadStarted -> {
                        total = event.bytesToDownload
                        onStatus("Downloading on-device model…")
                    }
                    is DownloadStatus.DownloadProgress -> {
                        val percent = if (total > 0) event.totalBytesDownloaded * 100 / total else 0
                        onStatus("Downloading on-device model… $percent%")
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        complete = true
                        onStatus("On-device model is ready")
                    }
                    is DownloadStatus.DownloadFailed -> onStatus("The on-device model couldn’t be downloaded")
                }
            }
            complete
        } finally {
            model.close()
        }
    }.getOrElse {
        onStatus("The on-device model isn’t available")
        false
    }

    private fun client(mode: BriefNanoModelMode) = Generation.getClient(
        generationConfig {
            modelConfig = modelConfig {
                when (mode) {
                    BriefNanoModelMode.AUTO_STABLE ->
                        error("Auto mode must be resolved before creating a Gemini Nano client")
                    BriefNanoModelMode.STABLE_FULL -> {
                        releaseStage = ModelReleaseStage.STABLE
                        preference = ModelPreference.FULL
                    }
                    BriefNanoModelMode.STABLE_FAST -> {
                        releaseStage = ModelReleaseStage.STABLE
                        preference = ModelPreference.FAST
                    }
                    BriefNanoModelMode.PREVIEW_FULL -> {
                        releaseStage = ModelReleaseStage.PREVIEW
                        preference = ModelPreference.FULL
                    }
                    BriefNanoModelMode.PREVIEW_FAST -> {
                        releaseStage = ModelReleaseStage.PREVIEW
                        preference = ModelPreference.FAST
                    }
                }
            }
        },
    )
}

private object BriefAiDiagnosticsStore {
    private const val PREFS = "brief_ai_diagnostics"
    private const val KEY_ATTEMPT_AT = "attempt_at"
    private const val KEY_ATTEMPTED = "attempted"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_LOCAL_FAILURE = "local_failure"
    private const val KEY_LOCAL_DETAIL = "local_detail"
    private const val KEY_NANO_MODEL_MODE = "nano_model_mode"

    fun nanoModelMode(context: Context): BriefNanoModelMode =
        BriefNanoModelMode.fromStorageId(prefs(context).getString(KEY_NANO_MODEL_MODE, null))

    fun setNanoModelMode(context: Context, mode: BriefNanoModelMode) {
        prefs(context).edit()
            .putString(KEY_NANO_MODEL_MODE, mode.storageId)
            .remove(KEY_LOCAL_FAILURE)
            .remove(KEY_LOCAL_DETAIL)
            .apply()
    }

    fun attempt(context: Context, provider: String) {
        prefs(context).edit()
            .putLong(KEY_ATTEMPT_AT, System.currentTimeMillis())
            .putString(KEY_ATTEMPTED, provider)
            .apply()
    }

    fun outcome(context: Context, provider: BriefProviderUsed, message: String) {
        prefs(context).edit().apply {
            putString(KEY_OUTCOME, "$provider · $message")
            if (provider == BriefProviderUsed.LOCAL) remove(KEY_LOCAL_FAILURE)
        }.apply()
    }

    fun localFailure(context: Context, reason: String) {
        prefs(context).edit().putString(KEY_LOCAL_FAILURE, reason).apply()
    }

    fun localDetail(context: Context, detail: String) {
        prefs(context).edit().putString(KEY_LOCAL_DETAIL, detail).apply()
    }

    fun read(
        context: Context,
        mode: BriefProviderMode,
        probe: NanoProbe,
        savedProvider: BriefProviderUsed,
    ): BriefAiDiagnostics {
        val prefs = prefs(context)
        return BriefAiDiagnostics(
            mode = mode,
            nanoModelMode = nanoModelMode(context),
            resolvedNanoModelMode = probe.selectedMode,
            nanoProbeAttempts = probe.attempts,
            runtimePresent = runCatching { Class.forName("com.google.mlkit.genai.prompt.Generation") }.isSuccess,
            localStatus = probe.status,
            statusError = probe.error,
            localModelName = probe.modelName,
            localTokenLimit = probe.tokenLimit,
            savedProvider = savedProvider,
            lastAttemptAt = prefs.getLong(KEY_ATTEMPT_AT, 0L),
            lastAttemptedProvider = prefs.getString(KEY_ATTEMPTED, null),
            lastOutcome = prefs.getString(KEY_OUTCOME, null),
            lastLocalFailure = prefs.getString(KEY_LOCAL_FAILURE, null),
            lastLocalDetail = prefs.getString(KEY_LOCAL_DETAIL, null),
            cloudConfigured = BriefSettingsStore.cloudApiKey(context).isNotBlank(),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

private object GeminiCloudBriefProvider {
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    fun generate(context: Context, source: BriefSnapshot): BriefSnapshot? {
        val key = BriefSettingsStore.cloudApiKey(context)
        if (key.isBlank()) return null
        return runCatching {
            val request = JSONObject().apply {
                put("systemInstruction", JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION)),
                ))
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", promptFor(source))))
                }))
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 600)
                    put("responseMimeType", "application/json")
                })
            }
            val response = HttpTransport.post(
                ENDPOINT,
                request.toString(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "x-goog-api-key" to key,
                ),
                readTimeoutMs = 30_000,
            )
            val root = JSONObject(HttpTransport.requireSuccess(response, "Gemini"))
            val text = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            BriefAiCardResponse.apply(source, text, BriefProviderUsed.CLOUD).snapshot
        }.getOrNull()
    }
}

private const val SYSTEM_INSTRUCTION =
    "You write a concise, personal social guide focused on the user's next useful move. " +
        "You may only reword the supplied factual Brief summary and cards and must preserve their order. " +
        "Never add numbers, names, causes, predictions, or claims. Use sentence case for every title: capitalise " +
        "only the first word and proper nouns, never every major word. Use 'follower' for exactly 1 and " +
        "'followers' for every other count. Keep titles under 45 characters and bodies under 150 characters. " +
        "For the brief_summary only, also write a distinct shortDescription of no more than 100 characters for compact " +
        "surfaces. It may use one or two short sentences, should fill up to two lines when useful, and must preserve " +
        "every numeric fact in its supplied value without adding facts. " +
        "When follower totals are absent from the supplied brief_summary body, keep them only in shortDescription " +
        "because a follower card already shows them. " +
        "Keep quote tweets and retweets separate. Always call them quote tweets and retweets; never shares or reposts. " +
        "Be warm and direct, never shaming. Return only a JSON array."

private const val SUMMARY_ID = "__brief_summary__"

internal fun promptFor(source: BriefSnapshot): String {
    val summary = BriefEditorialSummary.from(source)
    val input = JSONArray().apply {
        put(JSONObject().apply {
            put("id", SUMMARY_ID)
            put("title", summary.title)
            put("body", summary.body)
            put("shortDescription", summary.shortDescription)
            put("kind", "brief_summary")
        })
        source.cards.forEach { card ->
            put(JSONObject().apply {
                put("id", card.id)
                put("title", card.title)
                put("body", card.body)
                put("priority", card.rankingScore.takeIf { it >= 0 } ?: card.score)
            })
        }
    }
    return "Rewrite the Brief summary and ordered cards without reordering them. Keep each id unchanged. " +
        "The brief_summary title is shared everywhere, body is for the expanded page, and shortDescription is " +
        "shared by the dashboard and home-screen widget. Return card objects with exactly id, title, and body; " +
        "the brief_summary object must also include shortDescription: $input"
}

internal fun localPromptFor(source: BriefSnapshot): String {
    val outputCount = minOf(2, source.cards.size)
    val summary = BriefEditorialSummary.from(source)
    val input = JSONArray().apply {
        put(JSONObject().apply {
            put("i", SUMMARY_ID)
            put("t", summary.title)
            put("b", summary.body)
            put("s", summary.shortDescription)
        })
        source.cards.forEach { card ->
            put(JSONObject().apply {
                put("i", card.id)
                put("t", card.title)
                put("b", card.body)
                put("p", card.rankingScore.takeIf { it >= 0 } ?: card.score)
            })
        }
    }
    return """
        ## TASK
        Rewrite the Brief summary and first $outputCount cards in the supplied order.
        ## RULES
        Preserve order. Keep every id and numeric fact unchanged. Use sentence case, never Title Case. Use "follower" for 1 and "followers" otherwise. Keep quote tweets and retweets separate. Always call them quote tweets and retweets; never shares or reposts. Title max 32 characters. Body max 80 characters. For the summary, write a distinct compact description in s, max 100 characters and one or two short sentences, using only the facts supplied in s. If follower totals are absent from the summary body, keep them only in s because a follower card already shows them.
        ## OUTPUT
        JSON array only. Cards use [{"i":"id","t":"title","b":"body"}]. The summary also uses "s":"short description".
        ## CARDS
        $input
    """.trimIndent()
}

internal object BriefAiCardResponse {
    data class Result(
        val snapshot: BriefSnapshot?,
        val appliedCards: Int,
        val failure: String? = null,
    )

    fun apply(source: BriefSnapshot, raw: String, provider: BriefProviderUsed): Result {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return Result(null, 0, "Response did not contain a complete JSON array")
        val array = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull()
            ?: return Result(null, 0, "Response JSON was malformed")
        val originals = source.cards.associateBy(BriefCard::id)
        val originalSummary = BriefEditorialSummary.from(source)
        val seen = mutableSetOf<String>()
        val replacements = mutableMapOf<String, BriefCard>()
        var headline = originalSummary.title
        var subheading = originalSummary.body
        var shortDescription = originalSummary.shortDescription
        var summaryApplied = false
        var applied = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { item.optString("i") }
            if (id == SUMMARY_ID && seen.add(id)) {
                val title = item.optString("title").ifBlank { item.optString("t") }
                    .trim().takeIf { it.length in 1..60 } ?: originalSummary.title
                val body = item.optString("body").ifBlank { item.optString("b") }
                    .trim().takeIf { it.length in 1..180 } ?: originalSummary.body
                val shortBody = item.optString("shortDescription").ifBlank { item.optString("s") }
                    .trim().takeIf { it.length in 1..100 }
                if (numericFacts("${originalSummary.title} ${originalSummary.body}") == numericFacts("$title $body")) {
                    headline = BriefCopyPolicy.sentenceCase(
                        title,
                        "${originalSummary.title} ${originalSummary.body}",
                    )
                    subheading = BriefCopyPolicy.correctFollowerGrammar(body)
                    if (shortBody != null &&
                        numericFacts(originalSummary.shortDescription) == numericFacts(shortBody)
                    ) {
                        shortDescription = BriefCopyPolicy.correctFollowerGrammar(shortBody)
                    }
                    summaryApplied = true
                }
                continue
            }
            val original = originals[id] ?: continue
            if (!seen.add(id)) continue
            val title = item.optString("title").ifBlank { item.optString("t") }
                .trim().takeIf { it.length in 1..60 } ?: original.title
            val body = item.optString("body").ifBlank { item.optString("b") }
                .trim().takeIf { it.length in 1..180 } ?: original.body
            val factual = numericFacts("${original.title} ${original.body}") == numericFacts("$title $body")
            replacements[id] = if (factual) {
                original.copy(
                    title = BriefCopyPolicy.sentenceCase(title, "${original.title} ${original.body}"),
                    body = BriefCopyPolicy.correctFollowerGrammar(body),
                )
            } else {
                original
            }
            applied++
        }
        val rewritten = source.cards.map { replacements[it.id] ?: it }
        if (!summaryApplied && applied == 0) return Result(null, 0, "Response contained no recognised ids")
        val generatedAt = System.currentTimeMillis()
        return Result(
            snapshot = source.copy(
                generatedAt = generatedAt,
                cards = rewritten,
                headline = headline,
                subheading = subheading,
                shortDescription = shortDescription,
                providerUsed = provider,
                aiGeneratedAt = generatedAt,
            ),
            appliedCards = applied,
        )
    }
}

internal object BriefCopyPolicy {
    private val word = Regex("[A-Za-z][A-Za-z’'-]*")
    private val singularFollower = Regex("\\b1 followers\\b", RegexOption.IGNORE_CASE)

    fun sentenceCase(value: String, source: String = ""): String {
        val clean = value.trim()
        if (clean.isBlank()) return clean
        val protected = word.findAll(source)
            .withIndex()
            .filter { (index, match) ->
                val token = match.value
                token.length > 1 && (token.all(Char::isUpperCase) || (index > 0 && token.first().isUpperCase()))
            }
            .map { it.value.value }
            .map(String::lowercase)
            .toSet() + setOf("twidget", "buffer", "gemini", "nano")
        var index = 0
        return word.replace(clean) { match ->
            val token = match.value
            val replacement = when {
                index++ == 0 -> token.replaceFirstChar(Char::uppercase)
                token.all(Char::isUpperCase) -> token
                token.lowercase() in protected -> token
                else -> token.replaceFirstChar(Char::lowercase)
            }
            replacement
        }
    }

    fun correctFollowerGrammar(value: String): String = singularFollower.replace(value) { match ->
        if (match.value.first().isUpperCase()) "1 Follower" else "1 follower"
    }
}

private fun finishReasonName(reason: Int?): String = when (reason) {
    Candidate.FinishReason.STOP -> "STOP"
    Candidate.FinishReason.MAX_TOKENS -> "MAX_TOKENS"
    Candidate.FinishReason.OTHER -> "OTHER"
    null -> "UNKNOWN"
    else -> reason.toString()
}

private fun describeLocalError(error: Throwable): String = buildString {
    append(error.javaClass.simpleName)
    if (error is GenAiException) append(" code=${error.errorCode}")
    error.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
    error.cause?.takeIf { it !== error }?.let { cause ->
        append("; caused by ${cause.javaClass.simpleName}")
        cause.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
    }
}

private fun numericFacts(value: String): List<String> =
    Regex("[+-]?\\d+(?:[.,]\\d+)*%?").findAll(value).map { it.value }.sorted().toList()

internal object BriefAiCachePolicy {
    const val LOCAL_TTL_MS = 4 * 60 * 60 * 1000L
    const val CLOUD_TTL_MS = 2 * 60 * 60 * 1000L

    fun isFresh(snapshot: BriefSnapshot, now: Long = System.currentTimeMillis()): Boolean {
        if (snapshot.providerUsed == BriefProviderUsed.TEMPLATE) return true
        val generatedAt = snapshot.aiGeneratedAt.takeIf { it > 0L } ?: snapshot.generatedAt
        val ttl = when (snapshot.providerUsed) {
            BriefProviderUsed.LOCAL -> LOCAL_TTL_MS
            BriefProviderUsed.CLOUD -> CLOUD_TTL_MS
            BriefProviderUsed.TEMPLATE -> return true
        }
        return generatedAt > 0L && now >= generatedAt && now - generatedAt < ttl
    }

    fun retain(
        previous: BriefSnapshot?,
        refreshed: BriefSnapshot,
        now: Long = System.currentTimeMillis(),
    ): BriefSnapshot {
        previous ?: return refreshed
        if (!previous.username.equals(refreshed.username, ignoreCase = true)) return refreshed
        if (previous.engineVersion != refreshed.engineVersion) return refreshed
        if (previous.providerUsed == BriefProviderUsed.TEMPLATE || !isFresh(previous, now)) {
            return refreshed
        }

        val currentById = refreshed.cards.associateBy(BriefCard::id)
        val previousById = previous.cards.associateBy(BriefCard::id)
        val orderedIds = refreshed.cards.map(BriefCard::id)
        if (orderedIds.none { it in previousById && it in currentById }) return refreshed

        val mergedCards = orderedIds.mapNotNull { id ->
            val current = currentById[id] ?: return@mapNotNull null
            val cached = previousById[id] ?: return@mapNotNull current
            val factsStillMatch = cached.type == current.type &&
                numericFacts("${cached.title} ${cached.body}") ==
                numericFacts("${current.title} ${current.body}")
            if (factsStillMatch) {
                current.copy(title = cached.title, body = cached.body)
            } else {
                current
            }
        }
        val aiGeneratedAt = previous.aiGeneratedAt.takeIf { it > 0L } ?: previous.generatedAt
        val previousSummary = BriefEditorialSummary.from(previous)
        val refreshedSummary = BriefEditorialSummary.from(refreshed)
        val summaryFactsStillMatch = previous.headline.isNotBlank() && previous.subheading.isNotBlank() &&
            numericFacts("${previousSummary.title} ${previousSummary.body}") ==
            numericFacts("${refreshedSummary.title} ${refreshedSummary.body}")
        return refreshed.copy(
            generatedAt = previous.generatedAt,
            cards = mergedCards,
            headline = if (summaryFactsStillMatch) previous.headline else refreshed.headline,
            subheading = if (summaryFactsStillMatch) previous.subheading else refreshed.subheading,
            shortDescription = if (summaryFactsStillMatch) {
                previous.shortDescription.ifBlank { refreshed.shortDescription }
            } else {
                refreshed.shortDescription
            },
            providerUsed = previous.providerUsed,
            providerMessage = previous.providerMessage,
            aiGeneratedAt = aiGeneratedAt,
        )
    }
}
