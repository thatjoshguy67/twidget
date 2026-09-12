package com.tjg.twidget.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.data.StreakSnapshot
import java.time.LocalTime

internal enum class StreakCardState {
    SAFE,
    NEEDS_ACTIVITY,
    EXPIRING,
    REVIVE,
}

internal object StreakCardPolicy {
    private val expiryWarningStarts = LocalTime.of(23, 50)

    fun state(snapshot: StreakSnapshot, localTime: LocalTime = LocalTime.now()): StreakCardState = when {
        snapshot.streak <= 0 -> StreakCardState.REVIVE
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
        val colors = when (state) {
            StreakCardState.SAFE -> intArrayOf(Color.rgb(144, 255, 199), Color.rgb(205, 255, 230))
            StreakCardState.NEEDS_ACTIVITY -> intArrayOf(Color.rgb(255, 189, 157), Color.rgb(233, 204, 190))
            StreakCardState.EXPIRING -> intArrayOf(Color.rgb(255, 157, 157), Color.rgb(233, 190, 190))
            StreakCardState.REVIVE -> intArrayOf(Color.rgb(180, 218, 255), Color.WHITE)
        }
        val detail = detailOverride ?: when (state) {
            StreakCardState.SAFE -> context.getString(R.string.streak_safe_until_tomorrow)
            StreakCardState.NEEDS_ACTIVITY -> context.getString(R.string.streak_post_to_continue)
            StreakCardState.EXPIRING -> context.getString(R.string.streak_expiring_soon)
            StreakCardState.REVIVE -> context.getString(R.string.streak_revive)
        }
        val dayCount = snapshot.streak.coerceAtLeast(0)
        val title = titleOverride
            ?: context.resources.getQuantityString(R.plurals.streak_days, dayCount, dayCount)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, DashboardCardSize.HALF.heightDp)
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(context, 196).toFloat()
                setGradientCenter(0f, 0.5f)
                cornerRadius = dp(context, 28).toFloat()
                this.colors = colors
            }
            contentDescription = "$title. $detail"

            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_streak_fire)
                imageTintList = ColorStateList.valueOf(Color.BLACK)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(context).apply {
                    text = title
                    includeFontPadding = false
                    setTextColor(Color.BLACK)
                    textSize = 24f
                    typeface = Typeface.create("sec", Typeface.BOLD)
                    maxLines = 1
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))

                addView(TextView(context).apply {
                    text = detail
                    includeFontPadding = false
                    setTextColor(Color.BLACK)
                    textSize = 12f
                    maxLines = 3
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(context, 5)
                })
            }, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                marginStart = dp(context, 10)
            })
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
