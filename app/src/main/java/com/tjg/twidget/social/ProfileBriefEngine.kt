package com.tjg.twidget.social

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefCard
import com.tjg.twidget.brief.BriefCardType
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefSnapshot
import java.text.NumberFormat

/** A profile-scoped Brief combines provider evidence without comparing unlike metrics. */
data class ProfileBrief(val profileId: String, val membershipVersion: Long, val name: String, val cards: List<BriefCard>)

object ProfileBriefEngine {
    private const val PREFS = "social_brief_content"
    fun enabled(context: Context, key: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, true)
    fun setEnabled(context: Context, key: String, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    fun metricEnabled(context: Context, platform: SocialPlatform, metric: SocialMetric): Boolean {
        val legacy = metric !in setOf(SocialMetric.STARS, SocialMetric.FORKS) || enabled(context, "github_repositories")
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("${platform.storageId}:${metric.storageId}", legacy)
    }
    fun setMetricEnabled(context: Context, platform: SocialPlatform, metric: SocialMetric, enabled: Boolean) =
        setEnabled(context, "${platform.storageId}:${metric.storageId}", enabled)

    fun rebuild(context: Context, profileId: String, xSnapshot: BriefSnapshot? = null): ProfileBrief = SocialRepository(context).use { repository ->
        val catalog = repository.catalog()
        val profile = catalog.profiles.first { it.id == profileId }
        val observations = profile.accountIds.flatMap(repository::observations)
        val cards = mutableListOf<BriefCard>()
        val now = System.currentTimeMillis()
        val members = profile.accountIds.map(catalog.accountsById::getValue)
        members.filter { it.platform == SocialPlatform.X && enabled(context, it.platform.storageId) }.forEach { account ->
            // Keep the established X editorial selection, goals and rich post evidence.
            cards += (xSnapshot ?: BriefEngine.rebuild(context, account.handle)).cards.map {
                it.copy(sourceAttribution = "Twitter/X · @${account.handle}")
            }
        }
        ProfileBriefHighlights.select(members, observations, now) { account, metric ->
            enabled(context, account.platform.storageId) && metricEnabled(context, account.platform, metric)
        }.forEach { highlight ->
            val account = catalog.accountsById.getValue(highlight.accountId)
            val metricName = context.getString(highlight.metric.labelRes).let {
                if (java.util.Locale.getDefault().language == "en") it.lowercase(java.util.Locale.ENGLISH) else it
            }
            val explanation = context.getString(if (highlight.delta > 0) R.string.social_brief_gain else R.string.social_brief_loss,
                account.platform.label, NumberFormat.getIntegerInstance().format(kotlin.math.abs(highlight.delta)), metricName)
            cards += BriefCard("${account.id}:${highlight.metric.storageId}", BriefCardType.SUMMARY,
                explanation, explanation, highlight.score, sourceAttribution = "${account.platform.label} · @${account.handle}")
        }
        ProfileBrief(profile.id, profile.membershipVersion, profile.displayName(catalog.accountsById), cards.sortedByDescending { it.score }.take(6)).also {
            ProfileBriefCache.write(context, it, SocialWidgetCache.signature(profile, observations))
        }
    }

    /** Feed all platform cards into the established Brief renderer, retaining rich X evidence. */
    fun snapshot(context: Context, profileId: String, xSnapshot: BriefSnapshot? = null): BriefSnapshot {
        val catalog = SocialRepository(context).use { it.catalog() }
        val profile = catalog.profiles.first { it.id == profileId }
        val x = profile.accountIds.map(catalog.accountsById::getValue).firstOrNull { it.platform == SocialPlatform.X && enabled(context, "x") }
        val base = xSnapshot ?: x?.let { BriefEngine.rebuild(context, it.handle) }
        val brief = rebuild(context, profileId, base)
        return base?.copy(cards = brief.cards) ?: BriefSnapshot(
            username = "", generatedAt = System.currentTimeMillis(), sourceSyncedAt = 0,
            analyticsCachedAt = 0, followerScanCompletedAt = 0, followers = 0, following = 0,
            posts = 0, followersToday = 0, followersWeek = 0, cards = brief.cards,
            headline = brief.name, subheading = brief.cards.firstOrNull()?.body.orEmpty(),
            topFollowerRanks = emptyMap(),
        )
    }
}
