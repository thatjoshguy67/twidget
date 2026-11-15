package com.tjg.twidget.brief

/** Bounded successful runs, separate from the latest measured snapshot. */
internal object BriefRunCache {
    const val MAX_RUNS = 4

    fun recent(runs: List<BriefSnapshot>, now: Long = System.currentTimeMillis()): List<BriefSnapshot> =
        runs.filter { it.hasGeneratedNarrative && BriefAiCachePolicy.isFresh(it, now) }
            .sortedByDescending { it.aiGeneratedAt }
            .distinctBy { identity(it) }
            .take(MAX_RUNS)

    fun find(runs: List<BriefSnapshot>, source: BriefSnapshot, mode: BriefProviderMode,
        strings: BriefStrings, now: Long = System.currentTimeMillis()): BriefSnapshot? =
        recent(runs, now).firstNotNullOfOrNull { run ->
            if (identity(run, includeProvider = false) != identity(source, includeProvider = false) ||
                !providerMatches(mode, run.providerUsed)) null
            else BriefAiCachePolicy.retain(run, source, strings, now).takeIf { it.hasGeneratedNarrative }
        }

    fun providerMatches(mode: BriefProviderMode, provider: BriefProviderUsed): Boolean = when (mode) {
        BriefProviderMode.AUTO -> provider != BriefProviderUsed.TEMPLATE
        BriefProviderMode.LOCAL -> provider == BriefProviderUsed.LOCAL
        BriefProviderMode.CLOUD -> provider == BriefProviderUsed.CLOUD
    }

    private fun identity(run: BriefSnapshot, includeProvider: Boolean = true): List<Any> = listOf(
        run.username.trim().trimStart('@').lowercase(java.util.Locale.US), run.engineVersion,
        run.language, run.contextFingerprint, run.sourceSyncedAt, run.analyticsCachedAt,
        run.followerScanCompletedAt, run.followers, run.following, run.posts,
        run.followersToday, run.followersWeek, run.upcomingTweets, run.topFollowerRanks,
        run.cards.map { listOf(it.id, it.type, it.action, it.actionData, it.metrics, it.sourceAttribution, it.chartPoints, it.activityPoints) },
        if (includeProvider) run.providerUsed.name else "",
    )
}
