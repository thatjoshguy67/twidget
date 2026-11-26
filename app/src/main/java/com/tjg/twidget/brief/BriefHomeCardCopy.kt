package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.data.StreakSnapshot

/** Compact entry points describe their subject; measurements belong to the Brief and stat cards. */
internal object BriefHomeCardCopy {
    fun description(context: Context, type: BriefCardType, streak: StreakSnapshot): String = context.getString(when (type) {
        BriefCardType.STREAK -> when {
            streak.streak <= 0 -> R.string.brief_home_description_restart
            streak.activeToday -> R.string.brief_home_description_streak_active
            else -> R.string.brief_home_description_streak_due
        }
        BriefCardType.INACTIVITY -> R.string.brief_home_description_restart
        BriefCardType.MILESTONE -> R.string.brief_home_description_goal
        BriefCardType.TOP_FOLLOWER -> R.string.brief_home_description_followers
        BriefCardType.GROWTH, BriefCardType.SLOWDOWN, BriefCardType.FOLLOWER_CONTEXT,
        BriefCardType.AUDIENCE_MOMENTUM -> R.string.brief_home_description_audience
        BriefCardType.POST, BriefCardType.WORST_POST -> R.string.brief_home_description_post
        BriefCardType.POSTING_GUIDE -> R.string.brief_home_description_timing
        BriefCardType.CONTENT_PATTERN, BriefCardType.CONTENT_EXPERIMENT, BriefCardType.TOPIC_SPOTLIGHT,
        BriefCardType.CONVERSATION_PULSE -> R.string.brief_home_description_patterns
        else -> R.string.brief_home_description_overview
    })
}
