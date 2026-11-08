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
import com.tjg.twidget.R
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.widget.TwidgetBriefWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class BriefLocalStatus { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

internal fun briefRequiresApiKey(localStatus: BriefLocalStatus, cloudApiKey: String): Boolean =
    localStatus == BriefLocalStatus.UNAVAILABLE && cloudApiKey.isBlank()

enum class BriefNanoModelMode(val storageId: String, val label: String) {
    AUTO_STABLE("auto_stable", "Auto · Stable (Fast → Full)"),
    STABLE_FULL("stable_full", "Stable · Full"),
    STABLE_FAST("stable_fast", "Stable · Fast"),
    PREVIEW_FULL("preview_full", "Preview · Full"),
    PREVIEW_FAST("preview_fast", "Preview · Fast");

    fun localizedLabel(context: Context): String = context.getString(when (this) {
        AUTO_STABLE -> com.tjg.twidget.R.string.l10n_nano_auto
        STABLE_FULL -> com.tjg.twidget.R.string.l10n_nano_full
        STABLE_FAST -> com.tjg.twidget.R.string.l10n_nano_fast
        PREVIEW_FULL -> com.tjg.twidget.R.string.l10n_nano_preview_full
        PREVIEW_FAST -> com.tjg.twidget.R.string.l10n_nano_preview_fast
    })

    internal fun probeOrder(preferFull: Boolean = false): List<BriefNanoModelMode> = when (this) {
        AUTO_STABLE -> if (preferFull) listOf(STABLE_FULL, STABLE_FAST) else listOf(STABLE_FAST, STABLE_FULL)
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
    val lastDetailedReport: String?,
)

/** Saved only after the explicit debug action. It may contain the account's Brief source text. */
private class BriefGenerationTrace(private val strings: BriefStrings) {
    private val lines = mutableListOf<String>()

    fun event(value: String) { lines += value }

    fun localAttempt(attempt: BriefNanoTextNarrative.FieldAttempt) {
        val allowed = BriefNumericEvidence.diagnosticFacts(attempt.allowedNumbers, strings)
        val detected = attempt.candidate?.let { BriefNumericEvidence.diagnosticFacts(it, strings) }.orEmpty()
        lines += buildString {
            append("NANO · ").append(attempt.stage).append(" · attempt ").append(attempt.attempt)
            append('\n').append("Candidate: ").append(attempt.candidate ?: "<no response>")
            append('\n').append("Detected numbers: ").append(detected.ifEmpty { listOf("none") }.joinToString())
            append('\n').append("Allowed numbers: ").append(allowed.ifEmpty { listOf("none") }.joinToString())
            append('\n').append("Evidence: ").append(attempt.evidence)
            append('\n').append("Rejected: ").append(attempt.rejection ?: "no")
        }
    }

    fun cloudResponse(raw: String) {
        lines += "CLOUD · Raw response:\n$raw"
    }

    fun persist(context: Context) = BriefAiDiagnosticsStore.detailedReport(context, lines.joinToString("\n\n"))
}

object BriefAiCoordinator {
    private val generationMutex = Mutex()
    private val reviewMutex = Mutex()

    internal suspend fun coach(context: Context, input: BriefCoachInput): BriefCoachGeneration =
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(90_000L) { reviewMutex.withLock {
                BriefReviewPolicy.generate(BriefSettingsStore.provider(context), BriefSettingsStore.cloudApiKey(context).isNotBlank()) {
                    BriefCoachAiRunner.generate(context, input, cloud = true, allowCloudFallback = false)
                }
            } } ?: BriefCoachGeneration.Failure(BriefReviewFailure.TIMEOUT)
        }

    suspend fun enrich(
        context: Context,
        source: BriefSnapshot,
        force: Boolean = false,
    ): BriefAiResult = enrichInternal(context, source, force, null)

    suspend fun diagnoseGeneration(context: Context, source: BriefSnapshot): BriefAiResult {
        val trace = BriefGenerationTrace(BriefStrings.from(context))
        BriefAiDiagnosticsStore.clearDetailedReport(context)
        return try {
            enrichInternal(context, source, force = true, trace = trace)
        } finally {
            trace.persist(context)
        }
    }

    private suspend fun enrichInternal(
        context: Context,
        source: BriefSnapshot,
        force: Boolean,
        trace: BriefGenerationTrace?,
    ): BriefAiResult = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val mode = BriefSettingsStore.provider(context)
            val strings = BriefStrings.from(context)
            // A foreground prewarm may have completed while this caller waited
            // for the model. Re-read measured inputs and reuse that exact run.
            val currentSource = if (force) source else BriefEngine.rebuild(context, source.username)
            val cached = if (force) {
                source
            } else {
                BriefStore.cachedRun(context, currentSource)
                    ?: BriefAiCachePolicy.retain(BriefStore.read(context, source.username), currentSource, strings)
            }
            if (!force && cached.hasGeneratedNarrative && cachedProviderMatches(mode, cached.providerUsed)) {
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
            val generated = when (mode) {
                BriefProviderMode.LOCAL -> {
                    BriefAiDiagnosticsStore.attempt(context, "Gemini Nano")
                    val local = GeminiNanoBriefProvider.generate(context, currentSource, strings, onFieldAttempt = trace?.let { it::localAttempt })
                    if (local == null) trace?.event("NANO · ${BriefAiDiagnosticsStore.lastLocalFailure(context) ?: "no validated response"}")
                    local?.copy(
                        providerMessage = strings.text(R.string.brief_provider_note_nano),
                    )
                }
                BriefProviderMode.CLOUD -> {
                    BriefAiDiagnosticsStore.attempt(context, "Gemini Cloud")
                    GeminiCloudBriefProvider.generate(context, currentSource, strings, trace?.let { it::event }, trace?.let { it::cloudResponse })?.copy(
                        providerMessage = strings.text(R.string.brief_provider_note_cloud),
                    )
                }
                BriefProviderMode.AUTO -> {
                    BriefAiDiagnosticsStore.attempt(context, "Gemini Nano")
                    val local = GeminiNanoBriefProvider.generate(context, currentSource, strings, onFieldAttempt = trace?.let { it::localAttempt })
                    if (local == null) trace?.event("NANO · ${BriefAiDiagnosticsStore.lastLocalFailure(context) ?: "no validated response"}")
                    local?.copy(
                        providerMessage = strings.text(R.string.brief_provider_note_nano),
                    ) ?: run {
                        BriefAiDiagnosticsStore.attempt(context, "Gemini Cloud")
                        GeminiCloudBriefProvider.generate(context, currentSource, strings, trace?.let { it::event }, trace?.let { it::cloudResponse })?.copy(
                            providerMessage = strings.text(R.string.brief_provider_note_nano_then_cloud),
                        )
                    }
                }
            } ?: currentSource.copy(
                providerUsed = BriefProviderUsed.TEMPLATE,
                summaryGenerated = false,
                providerMessage = fallbackMessage(mode, localStatus, context, strings),
            )

            BriefAiDiagnosticsStore.outcome(context, generated.providerUsed, generated.providerMessage)
            trace?.event("OUTCOME · ${generated.providerUsed} · ${generated.providerMessage}")
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
        strings: BriefStrings,
    ): String = strings.text(
        when {
            mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.DOWNLOADABLE ->
                R.string.brief_provider_note_nano_downloadable
            mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.DOWNLOADING ->
                R.string.brief_provider_note_nano_downloading
            mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.AVAILABLE ->
                R.string.brief_provider_note_nano_failed
            mode == BriefProviderMode.LOCAL -> R.string.brief_provider_note_nano_unavailable
            mode == BriefProviderMode.CLOUD && BriefSettingsStore.cloudApiKey(context).isBlank() ->
                R.string.brief_provider_note_cloud_needs_key
            mode == BriefProviderMode.AUTO && localStatus == BriefLocalStatus.DOWNLOADABLE ->
                R.string.brief_provider_note_auto_downloadable
            mode == BriefProviderMode.AUTO && localStatus == BriefLocalStatus.AVAILABLE &&
                BriefSettingsStore.cloudApiKey(context).isBlank() -> R.string.brief_provider_note_nano_failed
            mode == BriefProviderMode.AUTO && BriefSettingsStore.cloudApiKey(context).isBlank() ->
                R.string.brief_provider_note_auto_none
            else -> R.string.brief_provider_note_unreachable
        },
    )

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

internal data class NanoProbe(
    val status: BriefLocalStatus,
    val error: String?,
    val modelName: String? = null,
    val tokenLimit: Int? = null,
    val selectedMode: BriefNanoModelMode? = null,
    val attempts: String? = null,
)

internal object GeminiNanoBriefProvider {
    suspend fun probe(context: Context, preferFull: Boolean = false): NanoProbe {
        val attempts = mutableListOf<String>()
        var lastError: String? = null
        var downloadable: NanoProbe? = null
        BriefAiDiagnosticsStore.nanoModelMode(context).probeOrder(preferFull).forEach { mode ->
            val result = probe(mode)
            attempts += buildString {
                append("${mode.label}: ${result.status}")
                result.error?.let { append(" ($it)") }
            }
            if (result.status != BriefLocalStatus.UNAVAILABLE) {
                if (!preferFull || result.status == BriefLocalStatus.AVAILABLE) {
                    return result.copy(attempts = attempts.joinToString(" · "))
                }
                if (downloadable == null) downloadable = result
            }
            if (result.error != null) lastError = result.error
        }
        downloadable?.let { return it.copy(attempts = attempts.joinToString(" · ")) }
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

    suspend fun generate(
        context: Context,
        source: BriefSnapshot,
        strings: BriefStrings,
        onResponse: (String, String) -> Unit = { _, _ -> },
        onFieldAttempt: ((BriefNanoTextNarrative.FieldAttempt) -> Unit)? = null,
    ): BriefSnapshot? {
        val probe = probe(context)
        if (probe.status != BriefLocalStatus.AVAILABLE) {
            BriefAiDiagnosticsStore.localFailure(context, "Feature status: ${probe.status}")
            return null
        }
        val mode = probe.selectedMode ?: return null
        return runCatching {
            val model = client(mode)
            try {
                val totalLimit = runCatching { model.getTokenLimit() }.getOrDefault(4096).takeIf { it > 0 } ?: 4096
                val baseName = runCatching { model.getBaseModelName() }.getOrDefault("unknown")
                val runtimeVersion = runCatching {
                    val info = context.packageManager.getPackageInfo("com.google.android.aicore", 0)
                    if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                }.getOrDefault(0L)
                val signature = "$mode|$baseName|$runtimeVersion|$totalLimit|beta6"
                val prefs = context.getSharedPreferences("brief_nano_runtime", Context.MODE_PRIVATE)
                val cached = prefs.getString("signature", null) == signature &&
                    System.currentTimeMillis() - prefs.getLong("savedAt", 0L) in 0L..86_400_000L
                var ceiling = if (cached) prefs.getInt("ceiling", 1024).coerceIn(64, 1024) else 1024
                if (prefs.getString("busySignature", null) == signature && prefs.getLong("retryAfter", 0L) > System.currentTimeMillis()) {
                    BriefAiDiagnosticsStore.localFailure(context, "On-device model is temporarily busy or quota-limited; waiting before retrying")
                    return@runCatching null
                }
                fun remember() {
                    prefs.edit().putString("signature", signature).putInt("ceiling", ceiling)
                        .putBoolean("plainText", true).putLong("savedAt", System.currentTimeMillis()).apply()
                }
                suspend fun request(stage: String, prompt: String, preferred: Int): String? {
                    fun build(budget: Int) = generateContentRequest(TextPart(prompt)) {
                        temperature = 0.25f
                        topK = 3
                        maxOutputTokens = budget
                    }
                    val input = model.countTokens(build(minOf(preferred, ceiling))).totalTokens
                    var budget = BriefNanoPolicy.outputBudget(input, totalLimit, minOf(preferred, ceiling))
                    if (budget == null) {
                        BriefAiDiagnosticsStore.localDetail(context, "stage=$stage · model=$baseName · input=$input · totalLimit=$totalLimit · input-budget-exhausted")
                        return null
                    }
                    while (true) {
                        val response = try {
                            model.generateContent(build(budget!!))
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            val code = (error as? GenAiException)?.errorCode
                            if (code == GenAiException.ErrorCode.BUSY || code == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED) {
                                val failures = (prefs.getInt("busyFailures", 0) + 1).coerceAtMost(5)
                                val delay = if (code == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED) 900_000L else (30_000L shl (failures - 1)).coerceAtMost(300_000L)
                                prefs.edit().putString("busySignature", signature).putInt("busyFailures", failures)
                                    .putLong("retryAfter", System.currentTimeMillis() + delay).apply()
                                throw error
                            }
                            if (code == GenAiException.ErrorCode.REQUEST_TOO_LARGE) {
                                BriefAiDiagnosticsStore.localDetail(context, "stage=$stage · input=$input · totalLimit=$totalLimit · runtime-request-too-large")
                                return null
                            }
                            val smaller = BriefNanoPolicy.lowerOutputBudget(budget!!)
                            if (smaller == null || !BriefNanoPolicy.isOutputBudgetRejection(error.message.orEmpty())) throw error
                            ceiling = minOf(ceiling, smaller)
                            remember()
                            budget = smaller
                            continue
                        }
                        prefs.edit().remove("retryAfter").remove("busyFailures").apply()
                        val candidate = response.candidates.firstOrNull() ?: return null
                        onResponse(stage, candidate.text)
                        val finish = finishReasonName(candidate.finishReason)
                        BriefAiDiagnosticsStore.localDetail(context,
                            "stage=$stage · model=$baseName · mode=$mode · totalLimit=$totalLimit · input=$input · outputLimit=$budget · finish=$finish · response=${candidate.text.length} chars")
                        if (finish.contains("MAX_TOKENS")) return null
                        if (finish.contains("SAFETY") || finish.contains("BLOCK")) {
                            error("Local model did not finish: $finish")
                        }
                        return candidate.text.takeIf(String::isNotBlank)
                    }
                }
                var validationFailure: String? = null
                kotlinx.coroutines.withTimeoutOrNull(120_000L) {
                    // Nano writes short fields; the app supplies structure and validates each field
                    // before moving on. Cloud's larger JSON contract is unreliable on small models.
                    BriefNanoTextNarrative.generate(
                        source,
                        strings,
                        onInvalid = { validationFailure = it },
                        onFieldAttempt = onFieldAttempt,
                    ) { stage, prompt ->
                        request(stage, prompt, 256)
                    }?.also { remember() }
                }.also { result ->
                    if (result == null) BriefAiDiagnosticsStore.localFailure(context,
                        "Local generation incomplete: ${validationFailure ?: "see stage diagnostics"}")
                }
            } finally {
                model.close()
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            BriefAiDiagnosticsStore.localFailure(context, describeLocalError(error))
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

    internal fun client(mode: BriefNanoModelMode) = Generation.getClient(
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
    private const val KEY_DETAILED_REPORT = "detailed_report"
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

    fun lastLocalFailure(context: Context): String? =
        prefs(context).getString(KEY_LOCAL_FAILURE, null)

    fun clearDetailedReport(context: Context) {
        prefs(context).edit().remove(KEY_DETAILED_REPORT).apply()
    }

    fun detailedReport(context: Context, report: String) {
        prefs(context).edit().putString(KEY_DETAILED_REPORT, report.take(48_000)).apply()
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
            lastDetailedReport = prefs.getString(KEY_DETAILED_REPORT, null),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal object GeminiCloudBriefProvider {
    internal const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    fun generate(
        context: Context,
        source: BriefSnapshot,
        strings: BriefStrings,
        onDiagnostic: ((String) -> Unit)? = null,
        onRawResponse: ((String) -> Unit)? = null,
    ): BriefSnapshot? {
        val key = BriefSettingsStore.cloudApiKey(context)
        if (key.isBlank()) {
            onDiagnostic?.invoke("CLOUD · API key is missing")
            return null
        }
        return try {
            fun requestText(prompt: String, label: String): String {
                val request = JSONObject().apply {
                    put("systemInstruction", JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION)),
                    ))
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", (300 + source.cards.size * 160).coerceAtMost(4096))
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
                onDiagnostic?.invoke("CLOUD · $label HTTP ${response.code} · response=${response.body.length} chars")
                val root = JSONObject(HttpTransport.requireSuccess(response, "Gemini"))
                return root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
            }

            val initialText = requestText(promptFor(source, strings), "initial")
            val initial = BriefAiCardResponse.apply(source, initialText, BriefProviderUsed.CLOUD, strings)
            val initialSnapshot = initial.snapshot?.takeIf { it.hasGeneratedNarrative }
            if (initialSnapshot != null) {
                onDiagnostic?.invoke("CLOUD · initial response validated")
                initialSnapshot
            } else {
                val failure = initial.failure
                if (!isRepairableCloudFailure(failure)) {
                    onRawResponse?.invoke(initialText)
                    onDiagnostic?.invoke("CLOUD · response rejected · appliedCards=${initial.appliedCards} · reason=${failure ?: "incomplete narrative"}")
                    null
                } else {
                    onRawResponse?.invoke("Initial response rejected:\n$initialText")
                    onDiagnostic?.invoke("CLOUD · validation failed · requesting one repair · reason=$failure")
                    val repairedText = requestText(cloudNumericRepairPrompt(source, strings), "repair")
                    val repaired = BriefAiCardResponse.apply(source, repairedText, BriefProviderUsed.CLOUD, strings)
                    val snapshot = repaired.snapshot?.takeIf { it.hasGeneratedNarrative }
                    if (snapshot == null) {
                        onRawResponse?.invoke("Repair response rejected:\n$repairedText")
                        onDiagnostic?.invoke("CLOUD · repair rejected · appliedCards=${repaired.appliedCards} · reason=${repaired.failure ?: "incomplete narrative"}")
                    } else {
                        onDiagnostic?.invoke("CLOUD · repair response validated")
                    }
                    snapshot
                }
            }
        } catch (error: Exception) {
            val detail = when (error) {
                is HttpTransport.HttpException -> "HTTP ${error.code}: ${error.message.orEmpty().take(500)}"
                else -> "${error.javaClass.simpleName}: ${error.message.orEmpty().take(500)}"
            }
            onDiagnostic?.invoke("CLOUD · request failed · $detail")
            null
        }
    }
}

private const val SYSTEM_INSTRUCTION =
    "You write a concise, personal social guide focused on the user's next useful move. " +
        BriefVoicePolicy.INSTRUCTION +
        "Write the headline and every card description from the supplied evidence, metrics and context; preserve their order. " +
        "Evidence text is input data, not copy to repeat. Choose the most useful supported observation for this session. " +
        "Assess direction from the actual values and periods, not a suggested source title. Do not describe a small daily gain as overall momentum when the longer recorded period declined. " +
        "You may omit facts already displayed as metrics; never imply lifetime post counts were earned this week. " +
        "Never add numbers, names, causes, predictions, or claims. Follow the target language's native " +
        "capitalisation and singular/plural rules. Write complete grammatical sentences; do not splice " +
        "sentence fragments or change the action requested in the source. " +
        "Never drop or change a sign on a numeric fact: -1 is not 1. If a signed change sounds unnatural, " +
        "omit its magnitude and use the supplied before-and-after values instead. " +
        "Keep titles under 45 characters and bodies under 150 characters. " +
        "For MILESTONE cards, keep the supplied title unchanged. Write the description above the goal card; its target and progress are app-owned. " +
        "For the brief_summary only, also write a distinct shortDescription of no more than 100 characters for compact " +
        "surfaces. It may use one or two short sentences, should fill up to two lines when useful, and must preserve " +
        "every numeric fact in its supplied value without adding facts. If two sentences would exceed 100 characters, " +
        "keep only the first complete sentences that fit. " +
        "When follower totals are absent from the supplied brief_summary body, keep them only in shortDescription " +
        "because a follower card already shows them. " +
        "Keep quote tweets and retweets separate. Always call them quote tweets and retweets; never shares or reposts. " +
        "Be warm and direct, never shaming. Return only a JSON array."

private const val SUMMARY_ID = "__brief_summary__"

/**
 * The system instruction is written in English; when the app runs in another
 * language the model is told to answer in that language so AI copy matches
 * the template copy it rewrites.
 */
internal fun languageInstruction(strings: BriefStrings): String =
    if (strings.isEnglish) {
        "Use sentence case, never Title Case. Capitalise only the first word and proper nouns. " +
            "Use 'follower' for exactly 1 and 'followers' otherwise. "
    } else {
        "Write every title, body, and shortDescription in ${strings.locale.getDisplayLanguage(Locale.ENGLISH)}, " +
            "matching the language of the supplied text. Use the language's native capitalisation and " +
            "singular/plural rules. " + if (strings.locale.language == "de") {
                "Capitalise all German nouns, including Follower, Fortschritt, Ziel and Posting-Rhythmus. " +
                    "For a posting rhythm that needs restarting, preserve that action: " +
                    "'Starte deinen Posting-Rhythmus neu.' Do not describe it as already active. "
            } else {
                ""
            }
    }

internal fun promptFor(source: BriefSnapshot, strings: BriefStrings): String {
    val summary = BriefEditorialSummary.from(source, strings)
    val input = JSONArray().apply {
        put(JSONObject().apply {
            put("id", SUMMARY_ID)
            put("evidence", summary.body)
            put("compactEvidence", summary.shortDescription)
            put("kind", "brief_summary")
        })
        source.cards.forEach { card ->
            put(JSONObject().apply {
                put("id", card.id)
                put("title", card.title)
                put("evidence", card.body)
                put("kind", card.type.name)
                put("metrics", JSONArray(card.metrics.map { JSONObject().put("label", it.label).put("value", it.value) }))
                put("context", "Measured values appear inside the card. Your description appears above it. No causes or predictions are known.")
                put("priority", card.rankingScore.takeIf { it >= 0 } ?: card.score)
            })
        }
    }
    val accountContext = JSONObject()
        .put("followers", source.followers).put("followersToday", source.followersToday)
        .put("followersWeek", source.followersWeek).put("generatedAt", source.generatedAt)
        .put("upcomingPosts", source.upcomingTweets.size)
    return "Account context: $accountContext. Write the Brief summary and all ordered card descriptions from their evidence and metrics. Keep each id unchanged. " +
        languageInstruction(strings) +
        BriefVoicePolicy.INSTRUCTION +
        "The brief_summary title is shared everywhere, body is for the expanded page, and shortDescription is " +
        "shared by the dashboard and home-screen widget. Return card objects with exactly id, title, and body; " +
        "the brief_summary object must also include shortDescription: $input"
}

internal fun cloudNumericRepairPrompt(source: BriefSnapshot, strings: BriefStrings): String =
    promptFor(source, strings) +
        "\n\nThe previous JSON was rejected. Rewrite the complete JSON from the supplied evidence. " +
        "Preserve every numeric sign exactly: never write 1 when the evidence says -1. " +
        "When a signed change would read unnaturally, omit its magnitude and use only the supplied before-and-after values. " +
        "Keep the brief_summary shortDescription to at most 100 characters by ending on a complete sentence. " +
        "Include the brief_summary and every supplied card id."

private fun isRepairableCloudFailure(failure: String?): Boolean =
    failure != null && listOf("_numeric", "_length", "_missing").any(failure::contains)

internal fun fitCompactCopy(value: String, maxChars: Int = 100): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length <= maxChars) return trimmed
    val kept = StringBuilder()
    for (part in compactSentenceBoundary.split(trimmed)) {
        val sentence = part.trim()
        if (sentence.isEmpty()) continue
        val next = if (kept.isEmpty()) sentence else "$kept $sentence"
        if (next.length > maxChars) break
        kept.setLength(0)
        kept.append(next)
    }
    return kept.toString().takeIf { it.isNotEmpty() && it.length <= maxChars && it.last() in ".!?" }
}

private val compactSentenceBoundary = Regex("(?<=[.!?])\\s+(?=\\p{Lu})")

internal fun localPromptFor(
    source: BriefSnapshot, strings: BriefStrings, includeSummary: Boolean = true,
    contextCards: List<BriefCard> = source.cards,
): String {
    val outputCount = source.cards.size
    val summary = BriefEditorialSummary.from(source, strings)
    val input = JSONArray().apply {
        if (includeSummary) put(JSONObject().apply {
            put("i", SUMMARY_ID)
            put("evidence", summary.body)
            put("compactEvidence", summary.shortDescription)
            put("cardEvidence", JSONArray(contextCards.map { card ->
                JSONObject().put("kind", card.type.name).put("evidence", card.body)
                    .put("metrics", JSONArray(card.metrics.map { JSONObject().put("label", it.label).put("value", it.value) }))
            }))
        })
        source.cards.forEach { card ->
            put(JSONObject().apply {
                put("i", card.id)
                put("t", card.title)
                put("evidence", card.body)
                put("kind", card.type.name)
                put("metrics", JSONArray(card.metrics.map { JSONObject().put("label", it.label).put("value", it.value) }))
                put("p", card.rankingScore.takeIf { it >= 0 } ?: card.score)
            })
        }
    }
    return """
        ## TASK
        Write ${if (includeSummary) "the Brief summary and" else "only"} all $outputCount supplied card descriptions from evidence and metrics. Do not output objects that were not requested.
        ## RULES
        ${languageInstruction(strings)}${BriefVoicePolicy.INSTRUCTION}Preserve order. Keep every id unchanged. Use only supplied facts; you may omit numbers already displayed in metrics. Never invent causes or predictions. Descriptions appear above the cards. Keep MILESTONE titles unchanged; target and progress are app-owned. Write complete grammatical sentences without splicing fragments or changing the source action. Keep quote tweets and retweets separate. Always call them quote tweets and retweets; never shares or reposts. Title max 32 characters. Body max 80 characters. For the summary, write a distinct compact description in s, max 100 characters and one or two short sentences, using only the facts supplied in s. If follower totals are absent from the summary body, keep them only in s because a follower card already shows them.
        ## OUTPUT
        JSON array only. Cards use [{"i":"id","t":"title","b":"body"}]. The summary also uses "s":"short description".
        ## ACCOUNT CONTEXT
        Followers: ${source.followers}; observed daily change: ${source.followersToday}; observed weekly change: ${source.followersWeek}.
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

    fun apply(
        source: BriefSnapshot,
        raw: String,
        provider: BriefProviderUsed,
        strings: BriefStrings,
    ): Result {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return Result(null, 0, "Response did not contain a complete JSON array")
        val array = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull()
            ?: return Result(null, 0, "Response JSON was malformed")
        val originals = source.cards.associateBy(BriefCard::id)
        val originalSummary = BriefEditorialSummary.from(source, strings)
        val copy = BriefCopyPolicy.forLanguage(strings)
        val summaryFacts =
            "${originalSummary.title} ${originalSummary.body} ${originalSummary.shortDescription} " +
                "${source.followers} ${source.followersToday} ${source.followersWeek} " +
                source.cards.joinToString { card -> card.body + " " + card.metrics.joinToString { it.label + " " + it.value } }
        val seen = mutableSetOf<String>()
        val replacements = mutableMapOf<String, BriefCard>()
        var headline = originalSummary.title
        var subheading = originalSummary.body
        var shortDescription = originalSummary.shortDescription
        var summaryApplied = false
        var applied = 0
        val rejected = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { item.optString("i") }
            if (id == SUMMARY_ID && seen.add(id)) {
                val title = item.optString("title").ifBlank { item.optString("t") }.trim()
                val body = item.optString("body").ifBlank { item.optString("b") }.trim()
                val shortBody = fitCompactCopy(item.optString("shortDescription").ifBlank { item.optString("s") })
                if (title.length !in 1..60 || body.length !in 1..180 || shortBody == null) {
                    rejected += "summary_length"
                    continue
                }
                if (!BriefVoicePolicy.accepts("$title $body $shortBody", strings)) { rejected += "summary_style"; continue }
                if (!BriefNumericEvidence.accepts(shortBody, summaryFacts, strings)) { rejected += "summary_numeric"; continue }
                if (BriefNumericEvidence.accepts("$title $body", summaryFacts, strings)) {
                    headline = copy.title(title, "${originalSummary.title} ${originalSummary.body}")
                    subheading = copy.body(body)
                    shortDescription = copy.body(shortBody)
                    summaryApplied = true
                } else rejected += "summary_numeric"
                continue
            }
            val original = originals[id] ?: continue
            if (!seen.add(id)) continue
            val title = item.optString("title").ifBlank { item.optString("t") }.trim()
            val body = item.optString("body").ifBlank { item.optString("b") }.trim()
            if (title.length !in 1..60 || body.length !in 1..180) {
                rejected += "card_length:${original.type}"
                continue
            }
            val allowedFacts = "${original.title} ${original.body} ${original.metrics.joinToString { it.label + " " + it.value }}"
            val visibleCopy = if (original.type == BriefCardType.MILESTONE) body else "$title $body"
            val factual = BriefNumericEvidence.accepts(visibleCopy, allowedFacts, strings) && BriefVoicePolicy.accepts(visibleCopy, strings)
            if (!BriefNumericEvidence.accepts(visibleCopy, allowedFacts, strings)) rejected += "card_numeric:${original.type}"
            if (!BriefVoicePolicy.accepts(visibleCopy, strings)) rejected += "card_style:${original.type}"
            replacements[id] = if (factual) {
                original.copy(
                    title = if (original.type == BriefCardType.MILESTONE) original.title
                        else copy.title(title, "${original.title} ${original.body}"),
                    body = copy.body(body),
                    narrativeGenerated = true,
                )
            } else {
                original.copy(narrativeGenerated = false)
            }
            applied++
        }
        if (!summaryApplied && rejected.none { it.startsWith("summary_") }) rejected += "summary_missing"
        if (source.cards.any { replacements[it.id]?.narrativeGenerated != true } &&
            rejected.none { it.startsWith("card_") }
        ) rejected += "card_missing"
        val rewritten = source.cards.map { replacements[it.id] ?: it.copy(narrativeGenerated = false) }
        if (!summaryApplied && applied == 0) {
            return Result(null, 0, rejected.joinToString(",").ifBlank { "Response contained no recognised ids" })
        }
        val generatedAt = System.currentTimeMillis()
        return Result(
            snapshot = source.copy(
                generatedAt = generatedAt,
                cards = rewritten,
                headline = headline,
                subheading = subheading,
                shortDescription = shortDescription,
                summaryGenerated = summaryApplied,
                providerUsed = provider,
                aiGeneratedAt = generatedAt,
            ),
            appliedCards = applied,
            failure = rejected.takeIf { it.isNotEmpty() }?.joinToString(","),
        )
    }
}

internal object BriefCopyPolicy {
    private val word = Regex("[A-Za-z][A-Za-z’'-]*")
    private val singularFollower = Regex("\\b1 followers\\b", RegexOption.IGNORE_CASE)

    /**
     * Sentence-case and follower-grammar fixes encode English rules (German, for
     * example, capitalises every noun), so they only run for English copy.
     */
    class Cleaner internal constructor(private val english: Boolean) {
        fun title(value: String, source: String): String =
            BriefVoicePolicy.withoutEmDashes(if (english) BriefCopyPolicy.sentenceCase(value, source) else value.trim())

        fun body(value: String): String =
            BriefVoicePolicy.withoutEmDashes(if (english) BriefCopyPolicy.correctFollowerGrammar(value) else value.trim())
    }

    fun forLanguage(strings: BriefStrings): Cleaner = Cleaner(strings.isEnglish)

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

internal fun finishReasonName(reason: Int?): String = when (reason) {
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
        strings: BriefStrings,
        now: Long = System.currentTimeMillis(),
    ): BriefSnapshot {
        previous ?: return refreshed
        if (!previous.username.equals(refreshed.username, ignoreCase = true)) return refreshed
        if (previous.engineVersion != refreshed.engineVersion) return refreshed
        if (previous.contextFingerprint != refreshed.contextFingerprint) return refreshed
        // AI copy written in another app language must not survive a language change.
        if (previous.language != refreshed.language) return refreshed
        // Age-peer comparisons can change without any number appearing in the
        // copy (e.g. "more replies" becomes insufficient data). Numeric-fact
        // matching cannot validate these claims against a new analytics snapshot.
        val postEvidenceTypes = setOf(
            BriefCardType.POST, BriefCardType.WORST_POST, BriefCardType.POST_CONTEXT,
            BriefCardType.POST_FOLLOW_THROUGH, BriefCardType.POSTING_GUIDE,
        )
        if (previous.analyticsCachedAt != refreshed.analyticsCachedAt &&
            (previous.cards + refreshed.cards).any { it.type in postEvidenceTypes }
        ) return refreshed
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
                current.copy(title = cached.title, body = cached.body, narrativeGenerated = cached.narrativeGenerated)
            } else {
                current
            }
        }
        val aiGeneratedAt = previous.aiGeneratedAt.takeIf { it > 0L } ?: previous.generatedAt
        val previousSummary = BriefEditorialSummary.from(previous, strings)
        val refreshedSummary = BriefEditorialSummary.from(refreshed, strings)
        val summaryFactsStillMatch = previous.headline.isNotBlank() && previous.subheading.isNotBlank() &&
            numericFacts("${previousSummary.title} ${previousSummary.body}") ==
            numericFacts("${refreshedSummary.title} ${refreshedSummary.body}")
        return refreshed.copy(
            generatedAt = previous.generatedAt,
            summaryGenerated = summaryFactsStillMatch && previous.summaryGenerated && previous.shortDescription.isNotBlank(),
            cards = mergedCards,
            headline = if (summaryFactsStillMatch) previous.headline else refreshed.headline,
            subheading = if (summaryFactsStillMatch) previous.subheading else refreshed.subheading,
            shortDescription = if (summaryFactsStillMatch) {
                previous.shortDescription
            } else {
                refreshed.shortDescription
            },
            providerUsed = previous.providerUsed,
            providerMessage = previous.providerMessage,
            aiGeneratedAt = aiGeneratedAt,
        )
    }
}
