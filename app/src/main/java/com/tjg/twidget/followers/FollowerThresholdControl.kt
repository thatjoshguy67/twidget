package com.tjg.twidget.followers

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SeslSeekBar
import androidx.core.view.ViewCompat
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales

/** Dialog-local draft; moving the slider never changes the list until OK. */
internal class FollowerThresholdControl(
    context: Context,
    followers: List<RankedTopFollower>,
    query: String,
    initial: Long,
) : LinearLayout(context) {
    var minimum = initial.coerceAtLeast(0); private set
    private val scale = FollowerThresholdScale(followers.map { it.follower.followers }, minimum)
    private val matchingCounts = TopFollowersBrowserPolicy.filter(followers, query).map { it.follower.followers }.sorted()
    private val total = followers.size
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val valueLabel = AppCompatTextView(context)
    private val resultLabel = AppCompatTextView(context)
    private val slider = SeslSeekBar(context)
    private val buttons = mutableListOf<Pair<Long, AppCompatButton>>()
    private var settingValue = false

    init {
        orientation = VERTICAL
        addView(AppCompatTextView(context).apply {
            setText(R.string.floating_min_followers)
            setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
            setPadding(0, dp(12), 0, dp(4))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        addView(valueLabel.apply {
            id = R.id.follower_min_value
            setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
        })
        addView(slider.apply {
            id = R.id.follower_min_slider
            max = scale.values.lastIndex
            isEnabled = max > 0
            contentDescription = context.getString(R.string.floating_min_followers)
        }, LayoutParams(-1, dp(48)))
        val presets = LinearLayout(context).apply { orientation = HORIZONTAL }
        scale.presets.forEach { value ->
            val button = AppCompatButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = if (value == 0L) context.getString(R.string.floating_all) else AppLocales.compact(value) + "+"
                contentDescription = label(value)
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { select(value) }
            }
            buttons += value to button
            presets.addView(button, LayoutParams(-2, -2))
        }
        addView(HorizontalScrollView(context).apply {
            id = R.id.follower_min_presets
            isHorizontalScrollBarEnabled = false
            addView(presets)
        }, LayoutParams(-1, -2))
        addView(resultLabel.apply { id = R.id.follower_min_results; setPadding(0, dp(4), 0, dp(8)) })
        slider.setOnSeekBarChangeListener(object : SeslSeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeslSeekBar, progress: Int, fromUser: Boolean) {
                if (!settingValue) { minimum = scale.values[progress]; updateLabels() }
            }
            override fun onStartTrackingTouch(seekBar: SeslSeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeslSeekBar) = Unit
        })
        select(minimum)
    }

    fun reset() = select(0)
    private fun select(value: Long) {
        minimum = value
        settingValue = true
        slider.progress = scale.position(value)
        settingValue = false
        updateLabels()
    }
    private fun label(value: Long) = if (value == 0L) context.getString(R.string.floating_all)
        else java.text.NumberFormat.getIntegerInstance(AppLocales.applicationLocale()).format(value) + "+"

    private fun updateLabels() {
        valueLabel.text = label(minimum)
        ViewCompat.setStateDescription(slider, valueLabel.text)
        // Binary search keeps live previews cheap even for large archived lists.
        var low = 0
        var high = matchingCounts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (matchingCounts[middle] < minimum) low = middle + 1 else high = middle
        }
        resultLabel.text = context.getString(R.string.floating_matching_followers, matchingCounts.size - low, total)
        buttons.forEach { (value, button) ->
            button.isSelected = value == minimum
            button.setTextColor(context.getColor(if (value == minimum) R.color.oneui_accent else R.color.oneui_text_primary))
        }
    }
}
