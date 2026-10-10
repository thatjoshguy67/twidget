package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus

internal const val BRIEF_MILESTONE_SETUP_ACTION = "setup_account_goals"

enum class BriefProviderMode(val storageId: String) {
    AUTO("auto"),
    LOCAL("local"),
    CLOUD("cloud");

    companion object {
        fun fromStorageId(value: String?): BriefProviderMode =
            entries.firstOrNull { it.storageId == value } ?: AUTO
    }
}

enum class BriefProviderUsed { TEMPLATE, LOCAL, CLOUD }

enum class BriefCardType {
    SUMMARY,
    GROWTH,
    SLOWDOWN,
    INACTIVITY,
    MILESTONE,
    POST,
    WORST_POST,
    TOP_FOLLOWER,
    STREAK,
    SCHEDULE_GUIDE,
    POST_FOLLOW_THROUGH,
    POSTING_GUIDE,
}

enum class BriefCardAction {
    NONE,
    OPEN_SCHEDULER,
    COMPOSE_TWEET,
    OPEN_POST,
}

/**
 * Signals used to decide when a card is useful, independently of its base importance.
 * All values are deliberately data driven so the language model never has to invent rank.
 */
data class BriefRankSignals(
    val contextRelevance: Double = 0.5,
    val timeRelevance: Double = 0.5,
    val occurredAt: Long = 0L,
    val freshForMillis: Long = 0L,
    val validUntil: Long = 0L,
    val maintainUntil: Long = 0L,
)

enum class BriefContentCategory(val storageId: String) {
    TOP_TWEET("top_tweet"),
    WORST_TWEET("worst_tweet"),
    FOLLOWERS("followers"),
    TOP_FOLLOWERS("top_followers"),
    TWEET_ACTIVITY("tweet_activity"),
    SCHEDULED_TWEETS("scheduled_tweets"),
    SCHEDULE_HEALTH("schedule_health"),
    POST_FOLLOW_THROUGH("post_follow_through"),
    POSTING_GUIDANCE("posting_guidance"),
    ACCOUNT_GOALS("account_goals");

    fun includes(type: BriefCardType): Boolean = when (this) {
        TOP_TWEET -> type == BriefCardType.POST
        WORST_TWEET -> type == BriefCardType.WORST_POST
        FOLLOWERS -> type in setOf(
            BriefCardType.SUMMARY,
            BriefCardType.GROWTH,
            BriefCardType.SLOWDOWN,
        )
        TOP_FOLLOWERS -> type == BriefCardType.TOP_FOLLOWER
        TWEET_ACTIVITY -> type in setOf(BriefCardType.INACTIVITY, BriefCardType.STREAK)
        SCHEDULED_TWEETS -> false
        SCHEDULE_HEALTH -> type == BriefCardType.SCHEDULE_GUIDE
        POST_FOLLOW_THROUGH -> type == BriefCardType.POST_FOLLOW_THROUGH
        POSTING_GUIDANCE -> type == BriefCardType.POSTING_GUIDE
        ACCOUNT_GOALS -> type == BriefCardType.MILESTONE
    }

    fun usesScheduledPostData(): Boolean = this in setOf(
        SCHEDULED_TWEETS,
        SCHEDULE_HEALTH,
        POST_FOLLOW_THROUGH,
    )

    companion object {
        fun forCard(type: BriefCardType): BriefContentCategory? =
            entries.firstOrNull { it.includes(type) }
    }
}

data class BriefCard(
    val id: String,
    val type: BriefCardType,
    val title: String,
    val body: String,
    val score: Int,
    val action: BriefCardAction = BriefCardAction.NONE,
    val actionData: String = "",
    val sourceAttribution: String = "",
    val rankSignals: BriefRankSignals = BriefRankSignals(),
    val rankingScore: Int = -1,
)

data class BriefUpcomingTweet(
    val id: String,
    val provider: ScheduleProvider,
    val status: ScheduleStatus,
    val scheduledAt: Long,
    val preview: String,
    val threadCount: Int,
    val mediaCount: Int,
    val errorMessage: String = "",
)

data class BriefEditorialSummary(
    val title: String,
    val body: String,
    val shortDescription: String = body,
) {
    companion object {
        fun from(snapshot: BriefSnapshot, strings: BriefStrings): BriefEditorialSummary {
            val generatedTitle = snapshot.headline.trim().takeIf(String::isNotBlank)
            val generatedBody = snapshot.subheading.trim().takeIf(String::isNotBlank)
            if (snapshot.language.equals(strings.languageTag, ignoreCase = true) &&
                generatedTitle != null && generatedBody != null
            ) {
                return BriefEditorialSummary(
                    generatedTitle,
                    generatedBody,
                    snapshot.shortDescription.trim().takeIf(String::isNotBlank)
                        ?: conciseFallback(snapshot, strings),
                )
            }
            return from(
                cards = snapshot.cards,
                strings = strings,
                followersToday = snapshot.followersToday,
                followersWeek = snapshot.followersWeek,
                upcomingTweets = snapshot.upcomingTweets.size,
            )
        }

        internal fun from(
            cards: List<BriefCard>,
            strings: BriefStrings,
            followersToday: Long = 0L,
            followersWeek: Long = 0L,
            upcomingTweets: Int = 0,
        ): BriefEditorialSummary {
            val types = cards.mapTo(linkedSetOf(), BriefCard::type)
            val hasGoal = cards.any {
                it.type == BriefCardType.MILESTONE &&
                    it.actionData != BRIEF_MILESTONE_SETUP_ACTION
            }
            val title = strings.text(
                when {
                    hasGoal && (followersToday > 0L || followersWeek > 0L) -> R.string.brief_summary_title_moving_closer
                    followersToday > 0L || followersWeek > 0L -> R.string.brief_summary_title_momentum
                    followersToday < 0L || followersWeek < 0L -> R.string.brief_summary_title_reset
                    BriefCardType.POSTING_GUIDE in types || BriefCardType.SCHEDULE_GUIDE in types ->
                        R.string.brief_summary_title_next_move
                    cards.isNotEmpty() -> R.string.brief_summary_title_week
                    else -> R.string.brief_summary_title_default
                },
            )
            val hasFollowerTrendCard = BriefCardType.GROWTH in types || BriefCardType.SLOWDOWN in types
            val facts = buildList {
                if (!hasFollowerTrendCard) {
                    followerOverview(followersToday, followersWeek, strings)?.let(::add)
                }
                if (hasGoal) {
                    add(
                        strings.text(
                            if (followersToday > 0L || followersWeek > 0L) {
                                R.string.brief_summary_goal_closer
                            } else {
                                R.string.brief_summary_goal_in_view
                            },
                        ),
                    )
                }
                when {
                    BriefCardType.POST in types && BriefCardType.WORST_POST in types ->
                        add(strings.text(R.string.brief_summary_post_both))
                    BriefCardType.POST in types -> add(strings.text(R.string.brief_summary_post_standout))
                    BriefCardType.WORST_POST in types -> add(strings.text(R.string.brief_summary_post_lesson))
                }
                if (upcomingTweets > 0) {
                    add(strings.quantityText(R.plurals.brief_summary_upcoming, upcomingTweets, upcomingTweets))
                }
                if (BriefCardType.TOP_FOLLOWER in types) {
                    add(strings.text(R.string.brief_summary_top_followers))
                }
                when {
                    cards.any { it.type == BriefCardType.STREAK && it.id == "start-streak" } ->
                        add(strings.text(R.string.brief_summary_streak_restart))
                    BriefCardType.STREAK in types -> add(strings.text(R.string.brief_summary_streak_active))
                }
                when {
                    BriefCardType.SCHEDULE_GUIDE in types ->
                        add(strings.text(R.string.brief_summary_schedule_step))
                    BriefCardType.POST_FOLLOW_THROUGH in types ->
                        add(strings.text(R.string.brief_summary_follow_through))
                    BriefCardType.POSTING_GUIDE in types ->
                        add(strings.text(R.string.brief_summary_posting_guide))
                }
            }
            val body = facts.joinToString(" ")
                .ifBlank {
                    strings.text(
                        if (hasFollowerTrendCard) R.string.brief_summary_body_trend
                        else R.string.brief_summary_body_watching,
                    )
                }
            return BriefEditorialSummary(
                title = title,
                body = body,
                shortDescription = conciseFallback(
                    followersToday = followersToday,
                    followersWeek = followersWeek,
                    types = types,
                    restartStreak = cards.any { it.type == BriefCardType.STREAK && it.id == "start-streak" },
                    hasGoal = hasGoal,
                    upcomingTweets = upcomingTweets,
                    strings = strings,
                ),
            )
        }

        private fun conciseFallback(snapshot: BriefSnapshot, strings: BriefStrings): String {
            val types = snapshot.cards.mapTo(linkedSetOf(), BriefCard::type)
            return conciseFallback(
                followersToday = snapshot.followersToday,
                followersWeek = snapshot.followersWeek,
                types = types,
                restartStreak = snapshot.cards.any { it.type == BriefCardType.STREAK && it.id == "start-streak" },
                hasGoal = snapshot.cards.any {
                    it.type == BriefCardType.MILESTONE &&
                        it.actionData != BRIEF_MILESTONE_SETUP_ACTION
                },
                upcomingTweets = snapshot.upcomingTweets.size,
                strings = strings,
            )
        }

        private fun conciseFallback(
            followersToday: Long,
            followersWeek: Long,
            types: Set<BriefCardType>,
            restartStreak: Boolean,
            hasGoal: Boolean,
            upcomingTweets: Int,
            strings: BriefStrings,
        ): String {
            val followerSentence = followerOverview(followersToday, followersWeek, strings)
            val watching = strings.text(R.string.brief_short_watching)
            val supportingSentence = when {
                hasGoal -> strings.text(R.string.brief_summary_goal_in_view)
                BriefCardType.POST in types && BriefCardType.WORST_POST in types ->
                    strings.text(R.string.brief_short_post_both)
                BriefCardType.POST in types -> strings.text(R.string.brief_short_post_standout)
                BriefCardType.WORST_POST in types -> strings.text(R.string.brief_short_post_lesson)
                upcomingTweets > 0 ->
                    strings.quantityText(R.plurals.brief_short_upcoming, upcomingTweets, upcomingTweets)
                BriefCardType.TOP_FOLLOWER in types -> strings.text(R.string.brief_short_top_followers)
                restartStreak -> strings.text(R.string.brief_summary_streak_restart)
                BriefCardType.STREAK in types -> strings.text(R.string.brief_summary_streak_active)
                BriefCardType.SCHEDULE_GUIDE in types -> strings.text(R.string.brief_short_schedule_step)
                BriefCardType.POST_FOLLOW_THROUGH in types -> strings.text(R.string.brief_short_follow_through)
                BriefCardType.POSTING_GUIDE in types -> strings.text(R.string.brief_short_posting_guide)
                else -> watching
            }
            if (followerSentence == null) return supportingSentence
            val followerContext = when {
                hasGoal && (followersToday > 0L || followersWeek > 0L) ->
                    strings.text(R.string.brief_summary_goal_closer)
                else -> supportingSentence.takeUnless { it == watching }
            }
            val expanded = listOfNotNull(followerSentence, followerContext).joinToString(" ")
            return expanded.takeIf { it.length <= MAX_SHORT_DESCRIPTION_LENGTH } ?: followerSentence
        }

        private fun followerOverview(today: Long, week: Long, strings: BriefStrings): String? = when {
            today > 0L && week >= today ->
                strings.text(R.string.brief_followers_gained_today_week, strings.followers(today), strings.followers(week))
            today > 0L -> strings.text(R.string.brief_followers_gained_today, strings.followers(today))
            today < 0L && week > 0L ->
                strings.text(R.string.brief_followers_down_today_up_week, strings.followers(-today), strings.followers(week))
            week > 0L -> strings.text(R.string.brief_followers_grew_week, strings.followers(week))
            today < 0L -> strings.text(R.string.brief_followers_down_today, strings.followers(-today))
            week < 0L -> strings.text(R.string.brief_followers_down_week, strings.followers(-week))
            else -> null
        }

        private const val MAX_SHORT_DESCRIPTION_LENGTH = 100
    }
}

internal object BriefLayoutPolicy {
    const val LARGE_SCREEN_MIN_WIDTH_DP = 600
    const val MAX_CONTENT_WIDTH_DP = 1200

    fun columnCount(screenWidthDp: Int): Int =
        if (screenWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP) 2 else 1

    fun shortestColumn(columnHeights: IntArray): Int = columnHeights
        .withIndex()
        .minByOrNull { it.value }
        ?.index
        ?: 0
}

data class BriefSnapshot(
    val username: String,
    val generatedAt: Long,
    val sourceSyncedAt: Long,
    val analyticsCachedAt: Long,
    val followerScanCompletedAt: Long,
    val followers: Long,
    val following: Long,
    val posts: Long,
    val followersToday: Long,
    val followersWeek: Long,
    val cards: List<BriefCard>,
    val headline: String = "",
    val subheading: String = "",
    val shortDescription: String = "",
    val upcomingTweets: List<BriefUpcomingTweet> = emptyList(),
    val topFollowerRanks: Map<String, Int>,
    val engineVersion: Int = 0,
    val contextFingerprint: String = "",
    val providerUsed: BriefProviderUsed = BriefProviderUsed.TEMPLATE,
    val providerMessage: String = "",
    val aiGeneratedAt: Long = 0L,
    /** BCP-47 tag of the app language the template copy was written in. */
    val language: String = "",
)
