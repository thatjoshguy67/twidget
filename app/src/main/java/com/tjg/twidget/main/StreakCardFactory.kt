package com.tjg.twidget.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.data.StreakSnapshot
import java.time.LocalTime

internal enum class StreakCardState {
    SAFE,
    RECORD,
    NEEDS_ACTIVITY,
    EXPIRING,
    REVIVE,
}

internal object StreakCardPolicy {
    private val expiryWarningStarts = LocalTime.of(23, 50)

    fun state(snapshot: StreakSnapshot, localTime: LocalTime = LocalTime.now()): StreakCardState = when {
        snapshot.streak <= 0 -> StreakCardState.REVIVE
        snapshot.activeToday && snapshot.longestStreak > 1 &&
            snapshot.streak >= snapshot.longestStreak &&
            snapshot.streak > snapshot.previousLongestStreak -> StreakCardState.RECORD
        snapshot.activeToday -> StreakCardState.SAFE
        !localTime.isBefore(expiryWarningStarts) -> StreakCardState.EXPIRING
        else -> StreakCardState.NEEDS_ACTIVITY
    }
}

internal object StreakCardFactory {
    /** The Brief has a wide treatment; the dashboard keeps its compact counter. */
    fun createBrief(context: Context, snapshot: StreakSnapshot): LinearLayout {
        val state = StreakCardPolicy.state(snapshot)
        val days = snapshot.streak.coerceAtLeast(0)
        val title = context.resources.getQuantityString(R.plurals.streak_days, days, days)
        return (android.view.LayoutInflater.from(context).inflate(R.layout.brief_card_streak, null, false) as LinearLayout).apply {
            background = background(context, state)
            findViewById<com.tjg.twidget.ui.CardValueTextView>(R.id.brief_streak_value).apply {
                text = title
                configure(80f, 109)
            }
            findViewById<TextView>(R.id.brief_streak_state).apply {
                text = context.getString(label(state))
                typeface = com.tjg.twidget.ui.TwidgetFonts.forApp(context, 700)
                setTextColor(Color.rgb(132, 132, 135))
            }
            contentDescription = "${context.getString(label(state))}. $title"
        }
    }

    private fun label(state: StreakCardState): Int = when (state) {
        StreakCardState.SAFE -> R.string.streak_card_steady
        StreakCardState.RECORD -> R.string.streak_card_record
        StreakCardState.NEEDS_ACTIVITY, StreakCardState.EXPIRING -> R.string.streak_card_save
        StreakCardState.REVIVE -> R.string.streak_card_restart
    }

    private fun background(context: Context, state: StreakCardState): GradientDrawable {
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val end = when (state) {
            StreakCardState.SAFE -> if (night) 0xFF003263.toInt() else 0xFFC3E1FF.toInt()
            StreakCardState.RECORD -> if (night) 0xFF004C45.toInt() else 0xFFB2FBF5.toInt()
            StreakCardState.NEEDS_ACTIVITY, StreakCardState.EXPIRING ->
                if (night) 0xFF601F00.toInt() else 0xFFFFD5C0.toInt()
            StreakCardState.REVIVE -> if (night) 0xFF470000.toInt() else 0xFFFFC8C8.toInt()
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(if (night) 0xFF171719.toInt() else Color.WHITE, end)).apply {
            cornerRadius = dp(context, 28).toFloat()
        }
    }

    fun create(
        context: Context,
        snapshot: StreakSnapshot,
        titleOverride: String? = null,
        detailOverride: String? = null,
        existing: LinearLayout? = null,
    ): LinearLayout {
        val state = StreakCardPolicy.state(snapshot)
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val labelRes = label(state)
        val detail = detailOverride ?: when (state) {
            StreakCardState.SAFE, StreakCardState.RECORD -> context.getString(R.string.streak_safe_until_tomorrow)
            StreakCardState.NEEDS_ACTIVITY -> context.getString(R.string.streak_post_to_continue)
            StreakCardState.EXPIRING -> context.getString(R.string.streak_expiring_soon)
            StreakCardState.REVIVE -> context.getString(R.string.streak_revive)
        }
        val dayCount = snapshot.streak.coerceAtLeast(0)
        val title = titleOverride
            ?: context.resources.getQuantityString(R.plurals.streak_days, dayCount, dayCount)

        return (existing ?: android.view.LayoutInflater.from(context).inflate(
            R.layout.metric_card_small_stat, null, false,
        ) as LinearLayout).apply {
            minimumHeight = 0
            setTag(R.id.dashboard_stat_streak, true)
            background = background(context, state)
            findViewById<ImageView>(R.id.metric_platform_icon).apply {
                setImageResource(R.drawable.ic_streak_fire)
                imageTintList = ColorStateList.valueOf(Color.rgb(132, 132, 135))
            }
            findViewById<TextView>(R.id.metric_label).apply {
                text = context.getString(R.string.dashboard_tweet_streak)
                setTextColor(Color.rgb(132, 132, 135))
                typeface = com.tjg.twidget.ui.TwidgetFonts.forApp(context, 700)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(this,
                    androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
                textSize = 14f
            }
            (findViewById<ImageView>(R.id.metric_platform_icon).layoutParams as LinearLayout.LayoutParams)
                .marginEnd = dp(context, 6)
            findViewById<com.tjg.twidget.ui.CardValueTextView>(R.id.followers_value).apply {
                text = dayCount.toString()
                setPadding(0, 0, 0, 0)
                configure(64f, 100, minimumWidth = 85)
                setTextColor(if (night) Color.WHITE else Color.BLACK)
            }
            findViewById<TextView>(R.id.stat_detail).apply {
                text = context.resources.getQuantityString(R.plurals.dashboard_streak_days, dayCount)
                setTextColor(Color.rgb(132, 132, 135))
            }
            contentDescription = "${context.getString(labelRes)}. $title. $detail"
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
