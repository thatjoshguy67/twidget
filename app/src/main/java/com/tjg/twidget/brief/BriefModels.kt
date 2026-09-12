package com.tjg.twidget.brief

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
        fun from(snapshot: BriefSnapshot): BriefEditorialSummary {
            val generatedTitle = snapshot.headline.trim().takeIf(String::isNotBlank)
            val generatedBody = snapshot.subheading.trim().takeIf(String::isNotBlank)
            if (generatedTitle != null && generatedBody != null) {
                return BriefEditorialSummary(
                    generatedTitle,
                    generatedBody,
                    snapshot.shortDescription.trim().takeIf(String::isNotBlank)
                        ?: conciseFallback(snapshot),
                )
            }
            return from(
                cards = snapshot.cards,
                followersToday = snapshot.followersToday,
                followersWeek = snapshot.followersWeek,
                upcomingTweets = snapshot.upcomingTweets.size,
            )
        }

        internal fun from(
            cards: List<BriefCard>,
            followersToday: Long = 0L,
            followersWeek: Long = 0L,
            upcomingTweets: Int = 0,
        ): BriefEditorialSummary {
            val types = cards.mapTo(linkedSetOf(), BriefCard::type)
            val hasGoal = cards.any {
                it.type == BriefCardType.MILESTONE &&
                    it.actionData != BRIEF_MILESTONE_SETUP_ACTION
            }
            val title = when {
                hasGoal && (followersToday > 0L || followersWeek > 0L) -> "Moving closer"
                followersToday > 0L || followersWeek > 0L -> "Momentum is building"
                followersToday < 0L || followersWeek < 0L -> "A moment to reset"
                BriefCardType.POSTING_GUIDE in types || BriefCardType.SCHEDULE_GUIDE in types -> "Your next move"
                cards.isNotEmpty() -> "Your week at a glance"
                else -> "Your Twidget Brief"
            }
            val hasFollowerTrendCard = BriefCardType.GROWTH in types || BriefCardType.SLOWDOWN in types
            val facts = buildList {
                if (!hasFollowerTrendCard) {
                    followerOverview(followersToday, followersWeek)?.let(::add)
                }
                if (hasGoal) {
                    add(
                        if (followersToday > 0L || followersWeek > 0L) {
                            "That progress brings your goal closer."
                        } else {
                            "Your goal is still in view."
                        },
                    )
                }
                when {
                    BriefCardType.POST in types && BriefCardType.WORST_POST in types ->
                        add("One recent tweet stood out, while another gives you something to learn from.")
                    BriefCardType.POST in types -> add("One recent tweet stood out from your usual performance.")
                    BriefCardType.WORST_POST in types -> add("One recent tweet gives you something to learn from.")
                }
                if (upcomingTweets > 0) {
                    add(
                        if (upcomingTweets == 1) "One scheduled tweet is ready ahead."
                        else "$upcomingTweets scheduled tweets are ready ahead.",
                    )
                }
                if (BriefCardType.TOP_FOLLOWER in types) {
                    add("There is a meaningful change in your top followers.")
                }
                when {
                    cards.any { it.type == BriefCardType.STREAK && it.id == "start-streak" } ->
                        add("It may be time to restart your posting rhythm.")
                    BriefCardType.STREAK in types -> add("Your posting rhythm is active.")
                }
                when {
                    BriefCardType.SCHEDULE_GUIDE in types ->
                        add("Your schedule has a useful next step waiting.")
                    BriefCardType.POST_FOLLOW_THROUGH in types ->
                        add("A recently scheduled tweet offers a lesson for your next post.")
                    BriefCardType.POSTING_GUIDE in types ->
                        add("Your recent tweets point to a practical next step.")
                }
            }
            val body = facts.joinToString(" ")
                .ifBlank {
                    if (hasFollowerTrendCard) "See how your audience changed over the last week."
                    else "Twidget is watching for your next meaningful account update."
                }
            return BriefEditorialSummary(
                title = title,
                body = body,
                shortDescription = conciseFallback(
                    followersToday = followersToday,
                    followersWeek = followersWeek,
                    types = types,
                    hasGoal = hasGoal,
                    upcomingTweets = upcomingTweets,
                ),
            )
        }

        private fun conciseFallback(snapshot: BriefSnapshot): String {
            val types = snapshot.cards.mapTo(linkedSetOf(), BriefCard::type)
            return conciseFallback(
                followersToday = snapshot.followersToday,
                followersWeek = snapshot.followersWeek,
                types = types,
                hasGoal = snapshot.cards.any {
                    it.type == BriefCardType.MILESTONE &&
                        it.actionData != BRIEF_MILESTONE_SETUP_ACTION
                },
                upcomingTweets = snapshot.upcomingTweets.size,
            )
        }

        private fun conciseFallback(
            followersToday: Long,
            followersWeek: Long,
            types: Set<BriefCardType>,
            hasGoal: Boolean,
            upcomingTweets: Int,
        ): String {
            val followerSentence = followerOverview(followersToday, followersWeek)
            val supportingSentence = when {
                hasGoal -> "Your goal is still in view."
                BriefCardType.POST in types && BriefCardType.WORST_POST in types ->
                    "Recent tweets brought a win and a lesson."
                BriefCardType.POST in types -> "One recent tweet stood out."
                BriefCardType.WORST_POST in types -> "One recent tweet offers a lesson."
                upcomingTweets == 1 -> "One tweet is scheduled next."
                upcomingTweets > 1 -> "$upcomingTweets tweets are scheduled next."
                BriefCardType.TOP_FOLLOWER in types -> "Your top followers have changed."
                BriefCardType.STREAK in types -> "Your posting rhythm is active."
                BriefCardType.SCHEDULE_GUIDE in types -> "Your schedule has a useful next step."
                BriefCardType.POST_FOLLOW_THROUGH in types -> "Your latest tweet offers a useful lesson."
                BriefCardType.POSTING_GUIDE in types -> "Your recent tweets suggest a next step."
                else -> "Watching for your next meaningful update."
            }
            if (followerSentence == null) return supportingSentence
            val followerContext = when {
                hasGoal && (followersToday > 0L || followersWeek > 0L) ->
                    "That progress brings your goal closer."
                else -> supportingSentence.takeUnless {
                    it == "Watching for your next meaningful update."
                }
            }
            val expanded = listOfNotNull(followerSentence, followerContext).joinToString(" ")
            return expanded.takeIf { it.length <= MAX_SHORT_DESCRIPTION_LENGTH } ?: followerSentence
        }

        private fun followerOverview(today: Long, week: Long): String? = when {
            today > 0L && week >= today ->
                "You gained ${followers(today)} today and ${followers(week)} this week."
            today > 0L -> "You gained ${followers(today)} today."
            today < 0L && week > 0L ->
                "You are down ${followers(-today)} today, but still up ${followers(week)} this week."
            week > 0L -> "Your audience grew by ${followers(week)} this week."
            today < 0L -> "You are down ${followers(-today)} today."
            week < 0L -> "Your audience is down ${followers(-week)} this week."
            else -> null
        }

        private fun followers(value: Long): String = "${format(value)} ${if (value == 1L) "follower" else "followers"}"

        private fun format(value: Long): String = java.text.NumberFormat
            .getIntegerInstance()
            .format(value)

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
    val providerMessage: String = "Built on device from your Twidget data",
    val aiGeneratedAt: Long = 0L,
)
