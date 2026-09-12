package com.tjg.twidget.brief

import com.tjg.twidget.R
import dev.oneuiproject.oneui.R as OneUiIconR

object BriefVisuals {
    fun categoryIcon(category: BriefContentCategory): Int = when (category) {
        BriefContentCategory.TOP_TWEET -> R.drawable.ic_streak_fire
        BriefContentCategory.WORST_TWEET -> OneUiIconR.drawable.ic_oui_delete_outline
        BriefContentCategory.FOLLOWERS -> OneUiIconR.drawable.ic_oui_community
        BriefContentCategory.TOP_FOLLOWERS -> OneUiIconR.drawable.ic_oui_diamond
        BriefContentCategory.TWEET_ACTIVITY -> OneUiIconR.drawable.ic_oui_send
        BriefContentCategory.SCHEDULED_TWEETS -> OneUiIconR.drawable.ic_oui_time_outline
        BriefContentCategory.SCHEDULE_HEALTH -> OneUiIconR.drawable.ic_oui_calendar_task
        BriefContentCategory.POST_FOLLOW_THROUGH -> OneUiIconR.drawable.ic_oui_repeat
        BriefContentCategory.POSTING_GUIDANCE -> OneUiIconR.drawable.ic_oui_star_outline
        BriefContentCategory.ACCOUNT_GOALS -> R.drawable.ic_milestone_goals
    }
}
