package com.tjg.twidget.brief

/** Resolve entry-point artwork from the subject and its state, without parsing translated copy. */
internal object BriefHomeIconPolicy {
    fun artworkType(type: BriefCardType, today: Long, week: Long, streak: Int): BriefCardType = when (type) {
        BriefCardType.SUMMARY, BriefCardType.FOLLOWER_CONTEXT, BriefCardType.AUDIENCE_MOMENTUM,
        BriefCardType.GROWTH -> when {
            today < 0 || week < 0 -> BriefCardType.INACTIVITY
            today > 0 || week > 0 -> BriefCardType.GROWTH
            else -> type
        }
        BriefCardType.STREAK -> if (streak <= 0) BriefCardType.INACTIVITY else type
        BriefCardType.SLOWDOWN -> BriefCardType.INACTIVITY
        else -> type
    }
}
