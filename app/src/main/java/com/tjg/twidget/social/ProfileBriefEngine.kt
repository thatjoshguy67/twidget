package com.tjg.twidget.social

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefCard
import com.tjg.twidget.brief.BriefCardType
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefSettingsStore
import java.text.NumberFormat

/** A profile-scoped Brief combines provider evidence without comparing unlike metrics. */
data class ProfileBrief(val profileId: String, val membershipVersion: Long, val name: String, val cards: List<BriefCard>)

object ProfileBriefEngine {
    private const val PREFS = "social_brief_content"
    fun enabled(context: Context, key: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, true)
    fun setEnabled(context: Context, key: String, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    fun rebuild(context: Context, profileId: String): ProfileBrief = SocialRepository(context).use { repository ->
        val catalog = repository.catalog()
        val profile = catalog.profiles.first { it.id == profileId }
        val observations = profile.accountIds.flatMap(repository::observations)
        val cards = mutableListOf<BriefCard>()
        val now = System.currentTimeMillis()
        if (profile.linked && enabled(context, "combined_audience")) {
            val total = AudienceAggregation.total(profile, catalog.accountsById, observations, now, 24 * 60 * 60 * 1000L)
            cards += BriefCard("profile:${profile.id}:${profile.membershipVersion}:audience", BriefCardType.SUMMARY,
                context.getString(R.string.social_all_audience),
                (total.value?.let { (if (total.approximate) "≈ " else "") + NumberFormat.getIntegerInstance().format(it) }
                    ?: context.getString(R.string.social_partial)) + ". " + context.getString(R.string.social_audience_note), 90,
                sourceAttribution = profile.accountIds.joinToString(" · ") { catalog.accountsById.getValue(it).platform.label })
        }
        profile.accountIds.map(catalog.accountsById::getValue).forEach { account ->
            if (!enabled(context, account.platform.storageId)) return@forEach
            if (account.platform == SocialPlatform.X) {
                // Preserve the mature X ranking, goals and scheduled-post analysis with its own account scope.
                cards += BriefEngine.rebuild(context, account.handle).cards.map { card ->
                    card.copy(id = "${account.id}:${card.id}", sourceAttribution = "Twitter/X · @${account.handle}")
                }
            } else {
                observations.filter { it.accountId == account.id && !it.estimated }.groupBy { it.metric }.forEach { (metric, samples) ->
                    if (metric in setOf(SocialMetric.STARS, SocialMetric.FORKS) && !enabled(context, "github_repositories")) return@forEach
                    val latest = samples.filter { it.observedAt <= now }.maxByOrNull { it.observedAt } ?: return@forEach
                    val baseline = samples.filter { it.observedAt <= now - 24 * 60 * 60 * 1000L && now - it.observedAt <= 48 * 60 * 60 * 1000L && it.value != null }.maxByOrNull { it.observedAt }
                    val fresh = now - latest.observedAt <= 24 * 60 * 60 * 1000L
                    val delta = if (fresh && latest.value != null && baseline?.value != null && latest.precision == MetricPrecision.EXACT && baseline.precision == MetricPrecision.EXACT) latest.value - baseline.value else null
                    val value = if (fresh) latest.displayValue(context) else context.getString(R.string.social_partial)
                    cards += BriefCard("${account.id}:${metric.storageId}", BriefCardType.SUMMARY,
                        "${account.platform.label} · ${context.getString(metric.labelRes)}",
                        value + if (delta == null) "" else " · " + context.getString(R.string.social_daily_change, (if (delta > 0) "+" else "") + NumberFormat.getIntegerInstance().format(delta)),
                        if (metric == account.platform.audienceMetric) 85 else 60, sourceAttribution = "${account.platform.label} · @${account.handle}")
                }
            }
        }
        ProfileBrief(profile.id, profile.membershipVersion, profile.displayName(catalog.accountsById), cards.sortedByDescending { it.score }).also {
            ProfileBriefCache.write(context, it, SocialWidgetCache.signature(profile, observations))
        }
    }
}
