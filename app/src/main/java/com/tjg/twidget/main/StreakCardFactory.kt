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
    fun create(
        context: Context,
        snapshot: StreakSnapshot,
        titleOverride: String? = null,
        detailOverride: String? = null,
    ): LinearLayout {
        val state = StreakCardPolicy.state(snapshot)
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val endColor = when (state) {
            StreakCardState.SAFE -> if (night) 0xFF003263.toInt() else 0xFFC3E1FF.toInt()
            StreakCardState.RECORD -> if (night) 0xFF004C45.toInt() else 0xFFB2FBF5.toInt()
            StreakCardState.NEEDS_ACTIVITY, StreakCardState.EXPIRING ->
                if (night) 0xFF601F00.toInt() else 0xFFFFD5C0.toInt()
            StreakCardState.REVIVE -> if (night) 0xFF470000.toInt() else 0xFFFFC8C8.toInt()
        }
        val labelRes = when (state) {
            StreakCardState.SAFE -> R.string.streak_card_steady
            StreakCardState.RECORD -> R.string.streak_card_record
            StreakCardState.NEEDS_ACTIVITY, StreakCardState.EXPIRING -> R.string.streak_card_save
            StreakCardState.REVIVE -> R.string.streak_card_restart
        }
        val detail = detailOverride ?: when (state) {
            StreakCardState.SAFE, StreakCardState.RECORD -> context.getString(R.string.streak_safe_until_tomorrow)
            StreakCardState.NEEDS_ACTIVITY -> context.getString(R.string.streak_post_to_continue)
            StreakCardState.EXPIRING -> context.getString(R.string.streak_expiring_soon)
            StreakCardState.REVIVE -> context.getString(R.string.streak_revive)
        }
        val dayCount = snapshot.streak.coerceAtLeast(0)
        val title = titleOverride
            ?: context.resources.getQuantityString(R.plurals.streak_days, dayCount, dayCount)

        return (android.view.LayoutInflater.from(context).inflate(
            R.layout.metric_card_small_stat, null, false,
        ) as LinearLayout).apply {
            minimumHeight = dp(context, DashboardCardSize.HALF.heightDp)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(if (night) 0xFF171719.toInt() else Color.WHITE, endColor)).apply {
                cornerRadius = dp(context, 28).toFloat()
            }
            findViewById<ImageView>(R.id.metric_platform_icon).apply {
                setImageResource(R.drawable.ic_streak_fire)
                imageTintList = ColorStateList.valueOf(Color.rgb(132, 132, 135))
            }
            findViewById<TextView>(R.id.metric_label).apply {
                text = context.getString(labelRes)
                setTextColor(Color.rgb(132, 132, 135))
                typeface = com.tjg.twidget.ui.TwidgetFonts.oneUiSans(context, 700)
                maxLines = 1
                ellipsize = null
                layoutParams.height = dp(context, 19)
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
                )
            }
            (findViewById<ImageView>(R.id.metric_platform_icon).layoutParams as LinearLayout.LayoutParams)
                .marginEnd = dp(context, 6)
            findViewById<com.tjg.twidget.ui.CardValueTextView>(R.id.followers_value).apply {
                text = dayCount.toString()
                setPadding(0, 0, 0, 0)
                configure(80f, 130)
                setTextColor(if (night) Color.WHITE else Color.BLACK)
            }
            findViewById<TextView>(R.id.stat_detail).apply {
                text = context.resources.getQuantityString(R.plurals.dashboard_streak_days, dayCount)
                setTextColor(Color.rgb(132, 132, 135))
            }
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    findViewById<TextView>(R.id.metric_label).apply {
                        val available = (right - left - paddingLeft - paddingRight - dp(context, 22)).coerceAtLeast(1)
                        maxWidth = available
                    }
                }
            }
            contentDescription = "${context.getString(labelRes)}. $title. $detail"
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
