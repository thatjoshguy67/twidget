package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.analytics.TweetPerformanceDirection
import com.tjg.twidget.analytics.TweetPerformanceExplainer
import com.tjg.twidget.data.DailyStreakStore
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollower
import com.tjg.twidget.followers.TopFollowersState
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.main.MilestoneCopyFactory
import com.tjg.twidget.main.MilestoneGoalStore
import com.tjg.twidget.main.MilestoneMetric
import com.tjg.twidget.main.MilestoneMetricResolver
import com.tjg.twidget.main.MilestonePerformanceState
import com.tjg.twidget.main.MilestonePolicy
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduleStore
import com.tjg.twidget.schedule.ScheduledPost
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object BriefEngine {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val ENGINE_VERSION = 16

    fun rebuild(context: Context, username: String, force: Boolean = false): BriefSnapshot {
        val clean = username.trim().trimStart('@')
        val stats = TwidgetStore.currentStats(context, clean)
        val analytics = AnalyticsClient.cached(context, clean)
        val followerState = TopFollowersStore.read(context, clean)
        val content = BriefSettingsStore.enabledContent(context)
        val upcomingTweets = if (BriefContentCategory.SCHEDULED_TWEETS in content) {
            upcomingTweets(context, clean)
        } else {
            emptyList()
        }
        val previous = BriefStore.read(context, clean)
        val fingerprint = contextFingerprint(context, clean)
        if (!force && previous != null &&
            previous.engineVersion == ENGINE_VERSION &&
            BriefAiCachePolicy.isFresh(previous) &&
            previous.contextFingerprint == fingerprint &&
            previous.username.equals(clean, ignoreCase = true) &&
            previous.sourceSyncedAt == stats.syncedAt &&
            previous.analyticsCachedAt == (analytics?.cachedAt ?: 0L) &&
            previous.followerScanCompletedAt == followerState.completedAt
        ) {
            if (TwidgetStore.debugMenuUnlocked(context)) {
                BriefDebugLog.record(context, "cache hit", inspect(context, clean))
            }
            return previous
        }

        val evaluation = evaluate(context, clean, stats, analytics, followerState, previous, upcomingTweets)

        val editorial = BriefEditorialSummary.from(
            cards = evaluation.selected,
            followersToday = evaluation.report.followersToday,
            followersWeek = evaluation.report.followersWeek,
            upcomingTweets = upcomingTweets.size,
        )
        val rebuilt = BriefSnapshot(
            username = clean,
            generatedAt = System.currentTimeMillis(),
            sourceSyncedAt = stats.syncedAt,
            analyticsCachedAt = analytics?.cachedAt ?: 0L,
            followerScanCompletedAt = followerState.completedAt,
            followers = stats.followersCount,
            following = stats.followingsCount,
            posts = stats.statusesCount,
            followersToday = evaluation.report.followersToday,
            followersWeek = evaluation.report.followersWeek,
            cards = evaluation.selected,
            headline = editorial.title,
            subheading = editorial.body,
            shortDescription = editorial.shortDescription,
            upcomingTweets = upcomingTweets,
            topFollowerRanks = evaluation.currentRanks,
            engineVersion = ENGINE_VERSION,
            contextFingerprint = fingerprint,
        )
        val snapshot = BriefAiCachePolicy.retain(previous, rebuilt)
        BriefStore.write(context, snapshot)
        BriefDebugLog.record(context, if (force) "forced rebuild" else "rebuild", evaluation.report)
        return snapshot
    }

    fun inspect(context: Context, username: String): BriefEngineReport {
        val clean = username.trim().trimStart('@')
        val content = BriefSettingsStore.enabledContent(context)
        return evaluate(
            context = context,
            username = clean,
            stats = TwidgetStore.currentStats(context, clean),
            analytics = AnalyticsClient.cached(context, clean),
            followerState = TopFollowersStore.read(context, clean),
            previous = BriefStore.read(context, clean),
            upcomingTweets = if (BriefContentCategory.SCHEDULED_TWEETS in content) {
                upcomingTweets(context, clean)
            } else {
                emptyList()
            },
        ).report
    }

    private data class Evaluation(
        val report: BriefEngineReport,
        val selected: List<BriefCard>,
        val currentRanks: Map<String, Int>,
    )

    private fun evaluate(
        context: Context,
        username: String,
        stats: ProfileStats,
        analytics: PostAnalytics?,
        followerState: TopFollowersState,
        previous: BriefSnapshot?,
        upcomingTweets: List<BriefUpcomingTweet>,
    ): Evaluation {
        val history = TwidgetStore.fullHistory(context, username)
            .filterNot { it.estimated }
            .sortedBy { it.timestamp }
        val todayDelta = TwidgetStore.followersDelta(context, username)
        val weekDelta = deltaSince(history, stats.followersCount, 7, HistorySample::followers)
        val currentRanks = followerState.top.take(100).mapIndexed { index, follower ->
            followerKey(follower) to index + 1
        }.toMap()
        val activity = DailyStreakStore.snapshot(context, username)
        val content = BriefSettingsStore.enabledContent(context)
        val schedules = if (content.any(BriefContentCategory::usesScheduledPostData)) {
            ScheduleStore(context).listForAccount(username)
        } else {
            emptyList()
        }
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<BriefCard>()

        if (BriefContentCategory.FOLLOWERS in content) {
            growthCard(stats.followersCount, todayDelta, weekDelta)?.let(candidates::add)
            slowdownCard(history, stats.followersCount, todayDelta)?.let(candidates::add)
        }
        if (BriefContentCategory.TOP_TWEET in content) {
            postCard(analytics?.best, analytics, stats.followersCount)?.let(candidates::add)
        }
        if (BriefContentCategory.WORST_TWEET in content) {
            worstPostCard(analytics)?.let(candidates::add)
        }
        if (BriefContentCategory.ACCOUNT_GOALS in content) {
            candidates += milestoneCards(context, username, stats, history, analytics, todayDelta, weekDelta)
        }
        if (BriefContentCategory.TWEET_ACTIVITY in content) {
            activityCard(context, username, upcomingTweets, now)?.let(candidates::add)
        }
        if (BriefContentCategory.TOP_FOLLOWERS in content) {
            topFollowerCard(followerState.top, previous, followerState.completedAt)?.let(candidates::add)
        }
        if (BriefContentCategory.SCHEDULE_HEALTH in content) {
            BriefGuidePolicy.scheduleCard(schedules, now)?.let(candidates::add)
        }
        if (BriefContentCategory.POST_FOLLOW_THROUGH in content) {
            BriefGuidePolicy.followThroughCard(schedules, analytics, now)?.let(candidates::add)
        }
        if (BriefContentCategory.POSTING_GUIDANCE in content) {
            BriefGuidePolicy.postingCard(analytics, now)?.let(candidates::add)
        }

        if (candidates.isEmpty() && BriefContentCategory.FOLLOWERS in content) {
            candidates += BriefCard(
                id = "summary-steady",
                type = BriefCardType.SUMMARY,
                title = "Everything looks steady",
                body = "You have ${formatFollowers(stats.followersCount)}. Keep showing up and Twidget will watch for the next meaningful change.",
                score = 50,
                rankSignals = BriefRankSignals(contextRelevance = 0.35, timeRelevance = 0.35),
            )
        }
        val ranked = BriefRankingPolicy.order(
            candidates,
            BriefRankingPolicy.Context(now, previous?.cards?.map(BriefCard::id).orEmpty()),
        )
        // Eligibility rules decide whether a card belongs in the Brief. Do not
        // discard a relevant card merely because several other signals fired.
        val selected = ranked
        return Evaluation(
            report = BriefEngineReport(
                username = username,
                generatedAt = System.currentTimeMillis(),
                followers = stats.followersCount,
                following = stats.followingsCount,
                posts = stats.statusesCount,
                followersToday = todayDelta,
                followersWeek = weekDelta,
                historySamples = history.size,
                analyticsCachedAt = analytics?.cachedAt ?: 0L,
                standoutPostViews = analytics?.best?.views,
                quietPostViews = analytics?.worst?.views,
                postingStreak = activity.streak,
                originalActivityComplete = activity.activityComplete,
                upcomingTweets = upcomingTweets.size,
                followerScanCompletedAt = followerState.completedAt,
                followersScanned = followerState.scanned,
                rankedCandidates = ranked,
                selectedIds = selected.mapTo(linkedSetOf(), BriefCard::id),
            ),
            selected = selected,
            currentRanks = currentRanks,
        )
    }

    private fun growthCard(followers: Long, today: Long, week: Long): BriefCard? {
        val weeklyPercent = if (followers - week > 0) week * 100.0 / (followers - week) else 0.0
        if (today < 5 && week < 15 && weeklyPercent < 2.0) return null
        val headline = when {
            today >= 25 -> "You’re getting attention"
            weeklyPercent >= 5.0 -> "Your audience is taking off"
            else -> "Momentum is building"
        }
        val body = when {
            today > 0 -> "You gained ${formatFollowers(today)} today and ${formatFollowers(week.coerceAtLeast(today))} over the last week."
            else -> "You gained ${formatFollowers(week)} over the last week."
        }
        val score = BriefRankingPolicy.growth(today, week, weeklyPercent)
        return BriefCard(
            "growth",
            BriefCardType.GROWTH,
            headline,
            body,
            score,
            rankSignals = BriefRankSignals(contextRelevance = 0.9, timeRelevance = 0.95),
        )
    }

    private fun postCard(post: PostSummary?, analytics: PostAnalytics?, followers: Long): BriefCard? {
        post ?: return null
        analytics ?: return null
        val attentionByViews = post.views >= maxOf(10_000L, followers * 2)
        val attentionByLikes = post.likes >= maxOf(100L, followers / 50)
        if (!attentionByViews && !attentionByLikes) return null
        return BriefCard(
            id = "post-${post.timestamp.takeIf { it > 0L } ?: post.url.hashCode()}",
            type = BriefCardType.POST,
            title = "Why this tweet worked",
            body = "${standoutPostBody(post)} ${TweetPerformanceExplainer.explain(post, analytics, TweetPerformanceDirection.STRONG).body}",
            score = BriefRankingPolicy.post(post, followers),
            rankSignals = BriefRankSignals(
                contextRelevance = 0.78,
                timeRelevance = 0.35,
                occurredAt = post.timestamp,
                freshForMillis = analytics.windowDays * DAY_MS,
            ),
        )
    }

    private fun worstPostCard(analytics: PostAnalytics?): BriefCard? {
        analytics ?: return null
        val post = analytics.worst ?: return null
        if (!TweetPerformanceExplainer.quietTweetEligible(post, analytics)) return null
        val explanation = TweetPerformanceExplainer.explain(post, analytics, TweetPerformanceDirection.QUIET)
        return BriefCard(
            id = "worst-post-${post.timestamp.takeIf { it > 0L } ?: post.url.hashCode()}",
            type = BriefCardType.WORST_POST,
            title = explanation.title,
            body = explanation.body,
            score = BriefRankingPolicy.worstPost(post, analytics),
            rankSignals = BriefRankSignals(
                contextRelevance = 0.7,
                timeRelevance = 0.3,
                occurredAt = post.timestamp,
                freshForMillis = analytics.windowDays * DAY_MS,
            ),
        )
    }

    private fun standoutPostBody(post: PostSummary): String {
        val views = TwidgetStore.compactNumber(post.views)
        val likes = TwidgetStore.compactNumber(post.likes)
        return when {
            post.views > 0 && post.likes > 0 -> "This tweet got $views views and $likes likes."
            post.views > 0 -> "This tweet got $views views."
            else -> "This tweet got $likes likes."
        }
    }

    private fun slowdownCard(history: List<HistorySample>, followers: Long, today: Long): BriefCard? {
        val recent = deltaSince(history, followers, 3, HistorySample::followers)
        val priorEnd = history.lastOrNull { it.timestamp <= System.currentTimeMillis() - 3 * DAY_MS } ?: return null
        val prior = deltaSince(history.filter { it.timestamp <= priorEnd.timestamp }, priorEnd.followers, 3, HistorySample::followers)
        if (today >= 0 && (prior <= 5 || recent >= prior / 2)) return null
        return BriefCard(
            "slowdown",
            BriefCardType.SLOWDOWN,
            "Growth has slowed down",
            "Your recent pace is ${formatFollowers(abs(prior - recent))} behind the previous few days. A fresh post could help restart it.",
            BriefRankingPolicy.slowdown(recent, prior, today),
            rankSignals = BriefRankSignals(contextRelevance = 0.92, timeRelevance = 0.95),
        )
    }

    private fun milestoneCards(
        context: Context,
        username: String,
        stats: ProfileStats,
        history: List<HistorySample>,
        analytics: PostAnalytics?,
        todayDelta: Long,
        weekDelta: Long,
    ): List<BriefCard> {
        val imported = ImportedAnalyticsStore.all(context, username)
        val goals = MilestoneGoalStore.readAll(context, username)
        if (goals.isEmpty()) {
            return listOf(BriefCard(
                id = "milestone-setup",
                type = BriefCardType.MILESTONE,
                title = context.getString(R.string.milestone_account_goals),
                body = context.getString(R.string.milestone_setup_hint),
                score = 74,
                actionData = BRIEF_MILESTONE_SETUP_ACTION,
                rankSignals = BriefRankSignals(
                    contextRelevance = 0.72,
                    timeRelevance = 0.35,
                ),
            ))
        }
        return goals.mapNotNull { settings ->
            if (!settings.configured || settings.target <= 0.0) return@mapNotNull null
            val snapshot = MilestoneMetricResolver.resolve(
                context = context,
                account = username,
                metric = settings.metric,
                stats = stats,
                history = history,
                analytics = analytics,
                imported = imported,
            )
            // A goal remains useful while its backing scan or analytics import
            // is temporarily unavailable. Start it at zero until data arrives.
            val value = snapshot.value ?: 0.0
            val progress = MilestonePolicy.progress(value, settings.target) ?: return@mapNotNull null
            val state = MilestonePolicy.performanceState(snapshot.history)
            val target = formatGoal(settings.metric, settings.target)
            val message = MilestoneCopyFactory.message(
                context,
                "$username:${settings.metric.storageId}",
                state,
                progress,
                target,
                settings.metric.goalNoun,
            )
            val preciseBody = if (progress in 75..99) {
                BriefGoalCopy.remainingBody(settings.metric, value, settings.target)
            } else {
                message.body
            }
            BriefCard(
                id = "milestone-${settings.metric.storageId}-${settings.target}",
                type = BriefCardType.MILESTONE,
                title = message.title,
                body = preciseBody,
                score = BriefRankingPolicy.milestone(progress, state),
                actionData = settings.metric.storageId,
                rankSignals = BriefRankSignals(
                    contextRelevance = when {
                        progress >= 100 -> 1.0
                        state == MilestonePerformanceState.DECELERATING && todayDelta < 0L && weekDelta <= 0L -> 0.9
                        todayDelta > 0L || weekDelta > 0L || state == MilestonePerformanceState.ACCELERATING -> 0.65
                        else -> 0.55
                    },
                    timeRelevance = if (progress >= 100) 1.0 else 0.45,
                ),
            )
        }
    }

    private fun activityCard(
        context: Context,
        username: String,
        upcomingTweets: List<BriefUpcomingTweet>,
        now: Long,
    ): BriefCard? {
        val streak = DailyStreakStore.snapshot(context, username)
        val scheduledToday = upcomingTweets.firstOrNull { isScheduledToday(it, now) }
        val endOfDay = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            .toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val urgency = dailyUrgency(now)
        if (streak.streak <= 0 && !BriefActivityPolicy.shouldStart(streak)) return null
        if (streak.streak <= 0) {
            val body = when {
                scheduledToday?.provider == ScheduleProvider.BUFFER && scheduledToday.status == ScheduleStatus.SCHEDULED ->
                    "A tweet is scheduled to publish today and can start a new daily rhythm."
                scheduledToday?.provider == ScheduleProvider.LOCAL_REMINDER && scheduledToday.status == ScheduleStatus.NEEDS_ACTION ->
                    "A tweet is ready to post now. Publish it to start a new daily rhythm."
                scheduledToday != null ->
                    "You have a tweet queued today. Post it to start a new daily rhythm."
                else -> "Twidget hasn’t detected an original tweet for more than three days. Tweet today to begin."
            }
            return BriefCard(
                "start-streak",
                BriefCardType.STREAK,
                "Start a posting streak",
                body,
                84,
                rankSignals = BriefRankSignals(
                    contextRelevance = 0.82,
                    timeRelevance = urgency,
                    maintainUntil = endOfDay,
                ),
            )
        }
        return BriefCard(
            "streak",
            BriefCardType.STREAK,
            "${streak.streak}-day posting streak",
            when {
                streak.activeToday -> "You’ve already kept the streak alive today. Nice work."
                scheduledToday?.provider == ScheduleProvider.BUFFER && scheduledToday.status == ScheduleStatus.SCHEDULED ->
                    "A tweet is scheduled to publish today and keep your ${streak.streak}-day rhythm going."
                scheduledToday?.provider == ScheduleProvider.LOCAL_REMINDER && scheduledToday.status == ScheduleStatus.NEEDS_ACTION ->
                    "Your next tweet is ready now. Post it to keep your ${streak.streak}-day rhythm going."
                scheduledToday != null -> "You have a tweet queued today. Post it to keep your ${streak.streak}-day rhythm going."
                else -> "Tweet today to keep your ${streak.streak}-day rhythm going."
            },
            BriefRankingPolicy.streak(streak.streak, streak.activeToday),
            rankSignals = BriefRankSignals(
                contextRelevance = if (streak.activeToday) 0.15 else 0.98,
                timeRelevance = if (streak.activeToday) 0.15 else urgency,
                maintainUntil = if (streak.activeToday) 0L else endOfDay,
            ),
        )
    }

    private fun upcomingTweets(context: Context, username: String): List<BriefUpcomingTweet> {
        val now = System.currentTimeMillis()
        return ScheduleStore(context).listForAccount(username)
            .asSequence()
            .filter { BriefSchedulePolicy.isRelevant(it, now) }
            .sortedWith(compareBy({ scheduleUrgency(it.status) }, { it.scheduledAt ?: Long.MAX_VALUE }))
            .take(3)
            .map { post ->
                BriefUpcomingTweet(
                    id = post.id,
                    provider = post.provider,
                    status = post.status,
                    scheduledAt = post.scheduledAt ?: 0L,
                    preview = post.thread.firstOrNull()?.text.orEmpty().replace(Regex("\\s+"), " ").trim().take(140),
                    threadCount = post.thread.size,
                    mediaCount = post.thread.sumOf { it.media.size },
                    errorMessage = post.errorMessage.orEmpty().take(120),
                )
            }
            .toList()
    }

    private fun scheduleUrgency(status: ScheduleStatus): Int = when (status) {
        ScheduleStatus.NEEDS_ACTION, ScheduleStatus.FAILED -> 0
        ScheduleStatus.SCHEDULED -> 1
        else -> 2
    }

    private fun isScheduledToday(tweet: BriefUpcomingTweet, now: Long): Boolean = tweet.scheduledAt > 0L &&
        Instant.ofEpochMilli(tweet.scheduledAt).atZone(ZoneId.systemDefault()).toLocalDate() ==
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun dailyUrgency(now: Long): Double {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        return (0.25 + hour / 24.0 * 0.75).coerceIn(0.25, 1.0)
    }

    private fun topFollowerCard(
        top: List<TopFollower>,
        previous: BriefSnapshot?,
        completedAt: Long,
    ): BriefCard? {
        val follower = top.firstOrNull() ?: return null
        val oldRank = previous?.topFollowerRanks?.get(followerKey(follower))
        val newScan = previous != null && completedAt > previous.followerScanCompletedAt
        val title = when {
            newScan && oldRank == null -> "A new top follower"
            oldRank != null && oldRank > 1 -> "A follower moved up"
            previous == null -> "Your top follower"
            else -> return null
        }
        val movement = oldRank?.takeIf { it > 1 }?.let { " They moved from #$it to #1." }.orEmpty()
        return BriefCard(
            "top-follower-${followerKey(follower)}",
            BriefCardType.TOP_FOLLOWER,
            title,
            "${follower.name.ifBlank { "@${follower.username}" }} has ${formatFollowers(follower.followers)}.$movement",
            if (newScan && oldRank == null) 92 else 76,
            rankSignals = BriefRankSignals(
                contextRelevance = if (newScan) 0.85 else 0.55,
                timeRelevance = 0.4,
                occurredAt = completedAt,
                freshForMillis = 7 * DAY_MS,
                maintainUntil = completedAt + 12 * 60 * 60 * 1000L,
            ),
        )
    }

    private fun deltaSince(
        history: List<HistorySample>,
        current: Long,
        days: Int,
        selector: (HistorySample) -> Long,
    ): Long {
        val threshold = System.currentTimeMillis() - days * DAY_MS
        val baseline = history.lastOrNull { it.timestamp <= threshold } ?: history.firstOrNull() ?: return 0L
        return current - selector(baseline)
    }

    private fun followerKey(follower: TopFollower): String =
        follower.id.ifBlank { follower.username.lowercase(Locale.US) }

    private fun contextFingerprint(context: Context, username: String): String {
        val goals = MilestoneGoalStore.readAll(context, username)
        val streak = DailyStreakStore.snapshot(context, username)
        val now = System.currentTimeMillis()
        val schedule = ScheduleStore(context).listForAccount(username)
            .filter {
                BriefSchedulePolicy.isRelevant(it, now) ||
                    it.status == ScheduleStatus.DRAFT ||
                    it.status == ScheduleStatus.PUBLISHED
            }
            .joinToString(";") { "${it.id}:${it.status}:${it.scheduledAt}:${it.updatedAt}" }
        return listOf(
            goals.joinToString(";") { goal ->
                "${goal.metric.storageId}:${goal.target}:${goal.autoAdjust}"
            },
            streak.streak,
            streak.activeToday,
            streak.lastActiveDay,
            streak.activityCheckedAt,
            schedule,
            BriefRankingPolicy.contextBucket(now),
            BriefSettingsStore.contentFingerprint(context),
        ).joinToString("|")
    }

    private fun formatGoal(metric: MilestoneMetric, target: Double): String =
        if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            "${(target * 100).toInt()}%"
        } else {
            format(target.toLong())
        }

    private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    private fun formatFollowers(value: Long): String =
        "${format(value)} ${if (value == 1L) "follower" else "followers"}"
}

internal object BriefGoalCopy {
    fun remainingBody(metric: MilestoneMetric, current: Double, target: Double): String {
        val remaining = (target - current).coerceAtLeast(0.0)
        val amount = when (metric) {
            MilestoneMetric.FOLLOWERS -> count(remaining, "follower")
            MilestoneMetric.VERIFIED_FOLLOWERS -> count(remaining, "verified follower")
            MilestoneMetric.IMPRESSIONS -> count(remaining, "impression")
            MilestoneMetric.ENGAGEMENT_RATE -> {
                val points = remaining * 100.0
                val formatted = NumberFormat.getNumberInstance().apply {
                    maximumFractionDigits = 1
                    minimumFractionDigits = 0
                }.format(points)
                "$formatted percentage ${if (points == 1.0) "point" else "points"}"
            }
        }
        val targetLabel = if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            "${(target * 100).toInt()}%"
        } else {
            NumberFormat.getIntegerInstance().format(target.toLong())
        }
        return "You’re $amount away from your $targetLabel ${metric.goalNoun} goal."
    }

    private fun count(value: Double, noun: String): String {
        val roundedUp = kotlin.math.ceil(value).toLong()
        val formatted = NumberFormat.getIntegerInstance().format(roundedUp)
        return "$formatted ${if (roundedUp == 1L) noun else "${noun}s"}"
    }
}

internal object BriefSchedulePolicy {
    fun isRelevant(post: ScheduledPost, now: Long = System.currentTimeMillis()): Boolean =
        when (post.status) {
            ScheduleStatus.SCHEDULED -> post.scheduledAt?.let { it > now } == true
            ScheduleStatus.NEEDS_ACTION, ScheduleStatus.FAILED -> true
            else -> false
        }
}

internal object BriefRankingPolicy {
    private const val THEME_EXPOSURE_PENALTY = 5
    private const val CONSECUTIVE_THEME_PENALTY = 3

    internal data class Context(
        val now: Long = System.currentTimeMillis(),
        val previousOrder: List<String> = emptyList(),
    )

    private enum class Theme { PROGRESS, LEARN, PEOPLE, RHYTHM, PLAN }

    /**
     * Rank every eligible card without imposing a card-count limit. Importance is only half
     * the result; the rest describes whether the card is timely and useful in this session.
     */
    fun order(cards: List<BriefCard>, context: Context = Context()): List<BriefCard> {
        val previousPositions = context.previousOrder.withIndex().associate { it.value to it.index }
        val previousLead = context.previousOrder.firstOrNull()
        val remaining = cards.asSequence()
            .filter { it.rankSignals.validUntil <= 0L || context.now <= it.rankSignals.validUntil }
            .map { card ->
                val time = timeRelevance(card.rankSignals, context.now)
                val contextual = (
                    card.rankSignals.contextRelevance.coerceIn(0.0, 1.0) * 0.75 +
                        dayPartRelevance(theme(card.type), context.now) * 0.25 +
                        if (card.action != BriefCardAction.NONE) 0.08 else 0.0
                    ).coerceIn(0.0, 1.0)
                val maintained = card.id == previousLead && card.rankSignals.maintainUntil > context.now
                val result = (
                    card.score.coerceIn(0, 100) / 100.0 * 0.5 +
                        time * 0.25 +
                        contextual * 0.25 +
                        if (maintained) 0.04 else 0.0
                    ).coerceIn(0.0, 1.0)
                card.copy(rankingScore = (result * 100).roundToInt())
            }
            .toMutableList()
        val output = ArrayList<BriefCard>(remaining.size)
        val themeExposure = mutableMapOf<Theme, Int>()

        while (remaining.isNotEmpty()) {
            val lastTheme = output.lastOrNull()?.let { theme(it.type) }
            val next = remaining.sortedWith(
                compareByDescending<BriefCard> { card ->
                    val cardTheme = theme(card.type)
                    card.rankingScore -
                        themeExposure.getOrDefault(cardTheme, 0) * THEME_EXPOSURE_PENALTY -
                        if (cardTheme == lastTheme) CONSECUTIVE_THEME_PENALTY else 0
                }.thenBy { previousPositions[it.id] ?: Int.MAX_VALUE }
                    .thenByDescending { it.rankSignals.occurredAt }
                    .thenBy(BriefCard::id),
            ).first()
            remaining.remove(next)
            val nextTheme = theme(next.type)
            val effectiveScore = next.rankingScore -
                themeExposure.getOrDefault(nextTheme, 0) * THEME_EXPOSURE_PENALTY -
                if (nextTheme == lastTheme) CONSECUTIVE_THEME_PENALTY else 0
            output += next.copy(rankingScore = effectiveScore.coerceIn(0, 100))
            themeExposure[nextTheme] = themeExposure.getOrDefault(nextTheme, 0) + 1
        }
        return output
    }

    fun contextBucket(now: Long = System.currentTimeMillis()): String {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        return when (hour) {
            in 5..10 -> "morning"
            in 11..16 -> "afternoon"
            in 17..21 -> "evening"
            else -> "night"
        }
    }

    private fun timeRelevance(signals: BriefRankSignals, now: Long): Double {
        val configured = signals.timeRelevance.coerceIn(0.0, 1.0)
        if (signals.occurredAt <= 0L || signals.freshForMillis <= 0L) return configured
        val age = (now - signals.occurredAt).coerceAtLeast(0L)
        val freshness = (1.0 - age.toDouble() / signals.freshForMillis).coerceIn(0.0, 1.0)
        return maxOf(configured, freshness)
    }

    private fun dayPartRelevance(theme: Theme, now: Long): Double = when (contextBucket(now)) {
        "morning" -> when (theme) {
            Theme.PROGRESS -> 1.0
            Theme.PLAN -> 0.9
            Theme.LEARN -> 0.7
            Theme.PEOPLE -> 0.6
            Theme.RHYTHM -> 0.35
        }
        "afternoon" -> when (theme) {
            Theme.PLAN -> 1.0
            Theme.LEARN -> 0.9
            Theme.RHYTHM -> 0.65
            Theme.PROGRESS -> 0.6
            Theme.PEOPLE -> 0.6
        }
        "evening", "night" -> when (theme) {
            Theme.RHYTHM -> 1.0
            Theme.PLAN -> 0.85
            Theme.LEARN -> 0.75
            Theme.PEOPLE -> 0.6
            Theme.PROGRESS -> 0.5
        }
        else -> 0.5
    }

    private fun theme(type: BriefCardType): Theme = when (type) {
        BriefCardType.SUMMARY,
        BriefCardType.GROWTH,
        BriefCardType.SLOWDOWN,
        BriefCardType.MILESTONE,
        -> Theme.PROGRESS
        BriefCardType.POST,
        BriefCardType.WORST_POST,
        BriefCardType.POST_FOLLOW_THROUGH,
        -> Theme.LEARN
        BriefCardType.TOP_FOLLOWER -> Theme.PEOPLE
        BriefCardType.STREAK,
        BriefCardType.INACTIVITY,
        -> Theme.RHYTHM
        BriefCardType.SCHEDULE_GUIDE,
        BriefCardType.POSTING_GUIDE,
        -> Theme.PLAN
    }

    fun growth(today: Long, week: Long, weeklyPercent: Double): Int =
        (68 + today.coerceAtLeast(0).coerceAtMost(25) +
            (week.coerceAtLeast(0).coerceAtMost(50) / 5) +
            weeklyPercent.coerceIn(0.0, 10.0).toInt()).coerceAtMost(99).toInt()

    fun post(post: PostSummary, followers: Long): Int {
        val viewThreshold = maxOf(10_000L, followers * 2).coerceAtLeast(1L)
        val likeThreshold = maxOf(100L, followers / 50).coerceAtLeast(1L)
        val views = (post.views.toDouble() / viewThreshold).coerceIn(0.0, 4.0)
        val likes = (post.likes.toDouble() / likeThreshold).coerceIn(0.0, 4.0)
        return (72 + maxOf(views, likes) * 6).toInt().coerceAtMost(98)
    }

    fun worstPost(post: PostSummary, analytics: PostAnalytics): Int {
        val viewBaseline = analytics.medianViews.coerceAtLeast(1.0)
        val engagementBaseline = analytics.medianEngagements.coerceAtLeast(1.0)
        val viewGap = (1.0 - post.views / viewBaseline).coerceIn(0.0, 1.0)
        val engagementGap = (1.0 - post.engagements / engagementBaseline).coerceIn(0.0, 1.0)
        return (78 + maxOf(viewGap, engagementGap) * 10).toInt().coerceAtMost(88)
    }

    fun slowdown(recent: Long, prior: Long, today: Long): Int {
        val lostPace = (prior - recent).coerceAtLeast(0)
        return (68 + lostPace.coerceAtMost(20) + if (today < 0) 8 else 0)
            .coerceAtMost(96)
            .toInt()
    }

    fun milestone(progress: Int, state: MilestonePerformanceState): Int {
        val stateBoost = when (state) {
            MilestonePerformanceState.ACCELERATING -> 3
            MilestonePerformanceState.DECELERATING -> 7
            MilestonePerformanceState.NEUTRAL -> 0
        }
        return (52 + progress / 2 + stateBoost).coerceAtMost(100)
    }

    fun streak(days: Int, activeToday: Boolean): Int =
        (if (activeToday) 58 else 76) + days.coerceAtMost(20) / 2
}

internal object BriefActivityPolicy {
    fun shouldStart(
        snapshot: com.tjg.twidget.data.StreakSnapshot,
        today: LocalDate = LocalDate.now(),
    ): Boolean {
        if (snapshot.streak > 0) return false
        val lastDay = snapshot.lastActiveDay?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
        if (lastDay != null) return ChronoUnit.DAYS.between(lastDay, today) > 3
        if (!snapshot.activityComplete || snapshot.activityCheckedAt <= 0L || snapshot.activityWindowStartAt <= 0L) return false
        val windowStart = Instant.ofEpochMilli(snapshot.activityWindowStartAt)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        return ChronoUnit.DAYS.between(windowStart, today) > 3
    }
}
