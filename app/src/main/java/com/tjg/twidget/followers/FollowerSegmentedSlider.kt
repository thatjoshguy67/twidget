package com.tjg.twidget.followers

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import androidx.appcompat.widget.SeslSeekBar
import com.tjg.twidget.R
import kotlin.math.abs

/** Widget settings visual assets with continuous thumb travel and milestone markers. */
internal class FollowerSegmentedSlider(context: Context, private val scale: FollowerThresholdScale) : FrameLayout(context) {
    val seekBar = SeslSeekBar(context)
    private val ticks = mutableListOf<View>()
    private val thumb = View(context)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        addView(View(context).apply {
            setBackgroundResource(R.drawable.widget_setting_seek_bar_segment_track)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(-1, resources.getDimensionPixelSize(R.dimen.widget_setting_seekbar_track_material_height), Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(20); marginEnd = dp(18)
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(24), 0, dp(22), 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        repeat(scale.milestones.size) { index ->
            if (index > 0) row.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
            val tick = View(context).apply { setBackgroundResource(R.drawable.widget_setting_seek_bar_tick_mark) }
            ticks += tick
            row.addView(tick, LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.widget_setting_seek_bar_tickmark_drawable_width),
                resources.getDimensionPixelSize(R.dimen.widget_setting_seek_bar_tickmark_drawable_height)))
        }
        addView(row, LayoutParams(-1, -1))
        addView(thumb.apply {
            setBackgroundResource(R.drawable.widget_setting_seek_bar_thumb)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(20), dp(20), Gravity.LEFT or Gravity.CENTER_VERTICAL))
        addView(seekBar.apply {
            max = scale.maxProgress
            isEnabled = max > 0
            setPaddingRelative(dp(28), 0, dp(26), 0)
            // Like WidgetOpacityControl, the native seekbar handles input and
            // accessibility while the widget drawables provide the visible surface.
            alpha = 0f
            progressDrawable?.alpha = 0
        }, LayoutParams(-1, -1))
        if (scale.maximum == 0L) alpha = .4f
        val onLayout = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePosition() }
        ticks.forEach { it.addOnLayoutChangeListener(onLayout) }
        thumb.addOnLayoutChangeListener(onLayout)
    }

    /** A small physical snap zone, independent of the account's numeric range. */
    fun snapTolerance(): Int {
        val travel = (seekBar.width - seekBar.paddingLeft - seekBar.paddingRight).coerceAtLeast(1)
        return (scale.maxProgress.toDouble() * dp(6) / travel).toInt()
    }

    fun updatePosition() {
        val first = ticks.first()
        val last = ticks.last()
        if (first.width == 0 || thumb.width == 0) return
        val fraction = if (seekBar.max > 0) seekBar.progress.toDouble() / seekBar.max else 0.0
        val center = first.x + first.width / 2f + (last.x - first.x) * fraction.toFloat()
        thumb.translationX = center - thumb.width / 2f
        ticks.forEach { tick ->
            tick.alpha = if (abs(tick.x + tick.width / 2f - center) < thumb.width / 2f) 0f else 1f
        }
    }
}
