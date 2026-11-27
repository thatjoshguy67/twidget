package com.tjg.twidget.brief

import java.time.ZonedDateTime

internal enum class BriefHomeGlowMood { NEUTRAL, GROWTH, RESET, HIGHLIGHT, GOAL }
internal data class BriefHomeGlowSpec(val color: Int, val offset: Float)

/** Content supplies the dominant hue; the local day part gently changes its tone and placement. */
internal object BriefHomeGlowPolicy {
    fun mood(type: BriefCardType, today: Long, week: Long, streak: Int): BriefHomeGlowMood = when (type) {
        BriefCardType.STREAK -> if (streak > 0) BriefHomeGlowMood.GROWTH else BriefHomeGlowMood.RESET
        BriefCardType.SLOWDOWN, BriefCardType.INACTIVITY, BriefCardType.WORST_POST -> BriefHomeGlowMood.RESET
        BriefCardType.FOLLOWER_CONTEXT, BriefCardType.AUDIENCE_MOMENTUM, BriefCardType.SUMMARY -> when {
            week < 0 || today < 0 -> BriefHomeGlowMood.RESET
            week > 0 || today > 0 -> BriefHomeGlowMood.GROWTH
            else -> BriefHomeGlowMood.NEUTRAL
        }
        BriefCardType.GROWTH, BriefCardType.TOP_FOLLOWER -> BriefHomeGlowMood.GROWTH
        BriefCardType.MILESTONE -> BriefHomeGlowMood.GOAL
        BriefCardType.POST, BriefCardType.TOPIC_SPOTLIGHT -> BriefHomeGlowMood.HIGHLIGHT
        else -> BriefHomeGlowMood.NEUTRAL
    }

    fun spec(mood: BriefHomeGlowMood, dark: Boolean, now: ZonedDateTime = ZonedDateTime.now()): BriefHomeGlowSpec {
        val content = when (mood) {
            BriefHomeGlowMood.GROWTH -> 0xFF4CCB88.toInt()
            BriefHomeGlowMood.RESET -> 0xFFF49A66.toInt()
            BriefHomeGlowMood.GOAL -> 0xFFE9BD72.toInt()
            BriefHomeGlowMood.HIGHLIGHT -> 0xFFAE9EEA.toInt()
            BriefHomeGlowMood.NEUTRAL -> 0xFF6CA7DB.toInt()
        }
        val (accent, shift) = when (BriefBlueTheme.current(now)) {
            BriefBlueTheme.MORNING -> 0xFFFFD08A.toInt() to -.08f
            BriefBlueTheme.AFTERNOON -> 0xFF6BBCE6.toInt() to .07f
            BriefBlueTheme.EVENING -> 0xFFB69ADE.toInt() to .025f
            BriefBlueTheme.NIGHT -> 0xFF7F8EB6.toInt() to -.025f
            BriefBlueTheme.WEEKEND -> 0xFF83C9B5.toInt() to .045f
        }
        val color = blend(content, accent, .28f).let { if (dark) it else blend(it, 0xFFC2CAD4.toInt(), .35f) }
        val offset = shift + when (mood) {
            BriefHomeGlowMood.RESET -> -.025f; BriefHomeGlowMood.HIGHLIGHT -> .025f; else -> 0f
        }
        return BriefHomeGlowSpec(color, offset)
    }

    fun blend(from: Int, to: Int, progress: Float): Int {
        fun channel(shift: Int): Int {
            val a = from ushr shift and 255
            val b = to ushr shift and 255
            return (a + (b - a) * progress.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        }
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
