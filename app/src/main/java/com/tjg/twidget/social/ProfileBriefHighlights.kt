package com.tjg.twidget.social

import kotlin.math.abs
import kotlin.math.ceil

internal data class SocialBriefHighlight(val accountId: String, val metric: SocialMetric, val delta: Long, val score: Int)

/** A Brief is selective: fresh comparable movement, not a catalogue of current totals. */
internal object ProfileBriefHighlights {
    fun select(accounts: List<PlatformAccount>, observations: List<MetricObservation>, now: Long,
        enabled: (PlatformAccount, SocialMetric) -> Boolean = { _, _ -> true },
    ): List<SocialBriefHighlight> = accounts.filter { it.platform != SocialPlatform.X }.mapNotNull { account ->
        observations.filter { it.accountId == account.id && !it.estimated && it.observedAt <= now }
            .groupBy { it.metric }.mapNotNull { (metric, samples) ->
                if (!enabled(account, metric) || metric !in setOf(account.platform.audienceMetric, SocialMetric.STARS, SocialMetric.FORKS, SocialMetric.VIEWS)) return@mapNotNull null
                val latest = samples.maxByOrNull { it.observedAt } ?: return@mapNotNull null
                val previous = samples.filter { now - it.observedAt in DAY..2 * DAY }.maxByOrNull { it.observedAt } ?: return@mapNotNull null
                if (now - latest.observedAt > DAY || latest.observedAt <= previous.observedAt || latest.source != previous.source ||
                    latest.precision != MetricPrecision.EXACT || previous.precision != MetricPrecision.EXACT) return@mapNotNull null
                val value = latest.value ?: return@mapNotNull null
                val baseline = previous.value ?: return@mapNotNull null
                val delta = value - baseline
                // Views need substantial movement; audience and repository engagement have smaller floors.
                val minimum = if (metric == SocialMetric.VIEWS) 100L else if (metric == SocialMetric.FORKS) 2L else 3L
                val threshold = maxOf(minimum, ceil(baseline * 0.01).toLong())
                if (abs(delta) < threshold) return@mapNotNull null
                val score = (if (metric == account.platform.audienceMetric) 82 else 72) +
                    (abs(delta).toDouble() / maxOf(baseline, 1L) * 100).toInt().coerceAtMost(12)
                SocialBriefHighlight(account.id, metric, delta, score)
            }.maxByOrNull { it.score }
    }.sortedByDescending { it.score }.take(2)

    private const val DAY = 86400000L
}
