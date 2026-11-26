package com.tjg.twidget.brief

import android.content.Context
import java.util.Locale

/** Updating measured cards must not start another model run for every profile sync. */
internal object BriefGenerationCadence {
    const val REUSE_MS = 24 * 60 * 60 * 1000L

    internal fun scope(source: BriefSnapshot): String {
        val context = source.contextFingerprint.split('|')
        val structured = context.size >= 9
        return listOf(source.username.trim().trimStart('@').lowercase(Locale.US), source.language,
            source.contentSettingsFingerprint, source.posts,
            if (structured) context.take(4) else emptyList<String>(), // Goals and actual posting activity.
            if (structured) context.takeLast(3) else emptyList<String>(), // Provider, model, language.
        ).joinToString("|")
    }

    internal fun fresh(at: Long, now: Long): Boolean = at > 0L && now >= at && now - at < REUSE_MS

    internal fun completedOverview(source: BriefSnapshot): Boolean =
        source.providerUsed != BriefProviderUsed.TEMPLATE && source.summaryGenerated &&
            source.headline.isNotBlank() && source.subheading.isNotBlank() && source.shortDescription.isNotBlank()

    internal fun completedAt(source: BriefSnapshot): Long =
        source.summaryGeneratedAt.takeIf { it > 0L } ?: source.aiGeneratedAt.takeIf { it > 0L } ?: source.generatedAt

    fun isCovered(context: Context, source: BriefSnapshot, now: Long = System.currentTimeMillis()): Boolean =
        !BriefSettingsStore.contentRegenerationPending(context) && fresh(BriefStore.overviewGeneratedAt(context, source), now)
}
