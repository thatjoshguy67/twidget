package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats

/** Fixed synthetic evidence uses the same eligibility and rendering paths as real insights. */
internal object BriefInsightPreview {
    fun snapshot(strings: BriefStrings, base: BriefSnapshot, now: Long = base.generatedAt.takeIf { it > 14 * 86_400_000L } ?: 1_800_000_000_000L): BriefSnapshot {
        val day = 86_400_000L
        val account = base.username
        val posts = (1..6).map { index ->
            val image = index <= 3
            PostSummary("https://x.com/$account/status/preview-$index",
                if (image) "A sample update about #Android" else "A sample text update",
                if (image) 300 else 100, 10, 4, 2, 1, 17, now - (2 * day + (index - 1) * day / 5), "", "Preview", account, "",
                media = if (image) listOf(PostMedia("photo", "", "", 100, 100)) else emptyList())
        }
        val analytics = PostAnalytics(account, 130, 6, windowDays = 7,
            totalViews = 1200, avgViews = 200.0, medianViews = 200.0, avgViewsPerFollower = 1.5,
            totalEngagements = 102, avgEngagements = 17.0, medianEngagements = 17.0,
            avgEngagementsPerFollower = .13, engagementRate = .085, best = null, worst = null,
            cachedAt = now, recentPosts = posts)
        val stats = ProfileStats("Preview", account, 130, 20, 6, 0, syncedAt = now)
        val history = listOf(100L, 102, 101, 104, 105, 108, 107, 110, 113, 112, 116, 118, 121, 124).mapIndexed { index, count ->
            HistorySample("", count, 20, index.toLong(), 0, now - (14 - index) * day)
        }
        val cards = listOfNotNull(
            BriefInsightPolicy.conversation(analytics, strings, now),
            BriefInsightPolicy.contentPattern(analytics, strings, now),
            BriefInsightPolicy.momentum(stats, history, strings, now),
            BriefInsightPolicy.recap(stats, history, analytics, strings, now),
            BriefInsightPolicy.topic(analytics, strings, now),
            BriefInsightPolicy.experiment(analytics, strings, now),
        ).map { it.copy(action = BriefCardAction.NONE, actionData = "") }
        return base.copy(cards = cards, followers = 130, following = 20, posts = 6,
            followersToday = 0, followersWeek = 20, generatedAt = now,
            headline = strings.text(R.string.debug_insights_preview),
            subheading = strings.text(R.string.debug_insights_preview_summary),
            shortDescription = strings.text(R.string.debug_insights_preview_summary),
            summaryGenerated = false, providerUsed = BriefProviderUsed.TEMPLATE,
            providerMessage = strings.text(R.string.brief_debug_synthetic_provider_message))
    }
}
