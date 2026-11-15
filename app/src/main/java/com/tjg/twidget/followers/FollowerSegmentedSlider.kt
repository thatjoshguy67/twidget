package com.tjg.twidget.followers

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import androidx.appcompat.widget.SeslSeekBar
import com.tjg.twidget.R

/** Widget settings' segment track, ticks and thumb with a data-dependent number of stops. */
internal class FollowerSegmentedSlider(context: Context, stops: Int) : FrameLayout(context) {
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
        repeat(stops) { index ->
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
            max = stops - 1
            isEnabled = stops > 1
            setPaddingRelative(dp(24), 0, dp(22), 0)
            // Like WidgetOpacityControl, the native seekbar handles input and
            // accessibility while the widget drawables provide the visible surface.
            alpha = 0f
            progressDrawable?.alpha = 0
        }, LayoutParams(-1, -1))
        if (stops <= 1) alpha = .4f
        val onLayout = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePosition() }
        ticks.forEach { it.addOnLayoutChangeListener(onLayout) }
        thumb.addOnLayoutChangeListener(onLayout)
    }

    fun updatePosition() {
        val selected = seekBar.progress.coerceIn(ticks.indices)
        ticks.forEachIndexed { index, tick -> tick.alpha = if (index == selected) 0f else 1f }
        val tick = ticks[selected]
        if (tick.width > 0 && thumb.width > 0) {
            thumb.translationX = tick.x + tick.width / 2f - thumb.width / 2f
        }
    }
}
