package com.tjg.twidget.followers

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatRadioButton
import com.tjg.twidget.R
import com.tjg.twidget.ui.FloatingRevealLayout
import com.tjg.twidget.ui.SeslKeyboardFocus
import com.tjg.twidget.ui.SeslSurfaceHighlight
import com.tjg.twidget.ui.TwidgetFonts

internal class FollowerFilterCapsule @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onFiltersChanged: ((FollowerFilters) -> Unit)? = null
    var onExpansionChanged: ((Boolean) -> Unit)? = null
    var filters = FollowerFilters(); private set
    var expanded = false; private set
    private val toggle = AppCompatButton(context).apply { id = R.id.follower_filter_toggle; isAllCaps = false }
    private val reveal = FloatingRevealLayout(context)
    private val count = TextView(context).apply { textSize = 13f; setTextColor(context.getColor(R.color.oneui_text_secondary)) }
    private val sortGroup = RadioGroup(context).apply { orientation = VERTICAL }
    private val minGroup = RadioGroup(context).apply { orientation = HORIZONTAL }
    private var binding = false
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        elevation = dp(4).toFloat()
        background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(context.getColor(R.color.oneui_card_bg)) }
        setPadding(dp(8), 0, dp(8), 0)
        SeslSurfaceHighlight.mark(this, dp(28).toFloat())
        addView(toggle, LayoutParams(-1, dp(56)))
        toggle.setOnClickListener { setExpanded(!expanded) }
        val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(8), 0, dp(8), dp(12)) }
        fun label(res: Int) = TextView(context).apply { setText(res); textSize = 14f; setTextColor(context.getColor(R.color.oneui_text_primary)) }
        fun chip(group: RadioGroup, id: Int, text: Int) { group.addView(AppCompatRadioButton(context).apply {
            this.id = id; setText(text); textSize = 14f
            minimumHeight = dp(48)
        }, if (group.orientation == HORIZONTAL) RadioGroup.LayoutParams(0, -2, 1f)
            else RadioGroup.LayoutParams(-1, -2)) }
        body.addView(label(R.string.floating_sort)); body.addView(sortGroup)
        chip(sortGroup, R.id.follower_sort_count, R.string.floating_sort_count)
        chip(sortGroup, R.id.follower_sort_name, R.string.floating_sort_name)
        body.addView(label(R.string.floating_min_followers)); body.addView(minGroup)
        chip(minGroup, R.id.follower_min_any, R.string.floating_all)
        chip(minGroup, R.id.follower_min_10k, R.string.floating_10k)
        chip(minGroup, R.id.follower_min_100k, R.string.floating_100k)
        body.addView(count)
        body.addView(AppCompatButton(context).apply {
            setText(R.string.floating_reset); isAllCaps = false
            setOnClickListener { setFilters(FollowerFilters()); onFiltersChanged?.invoke(this@FollowerFilterCapsule.filters) }
        }, LayoutParams(-1, -2))
        reveal.addView(ScrollView(context).apply { isFillViewport = false; addView(body) })
        addView(reveal, LayoutParams(-1, -2))
        sortGroup.setOnCheckedChangeListener { _, selected ->
            if (!binding) changed(filters.copy(sort = if (selected == R.id.follower_sort_name) FollowerSort.NAME else FollowerSort.FOLLOWERS))
        }
        minGroup.setOnCheckedChangeListener { _, selected ->
            if (!binding) changed(filters.copy(minimum = when { selected == R.id.follower_min_100k -> 100_000; selected == R.id.follower_min_10k -> 10_000; else -> 0 }))
        }
        setFilters(filters)
        TwidgetFonts.applyTo(this)
        SeslKeyboardFocus.observeWindow(this)
        SeslSurfaceHighlight.applyTo(this)
    }
    private fun changed(value: FollowerFilters) { setFilters(value); onFiltersChanged?.invoke(value) }
    fun setFilters(value: FollowerFilters) {
        filters = value; binding = true
        sortGroup.check(if (value.sort == FollowerSort.NAME) R.id.follower_sort_name else R.id.follower_sort_count)
        minGroup.check(when (value.minimum) { 100_000L -> R.id.follower_min_100k; 10_000L -> R.id.follower_min_10k; else -> R.id.follower_min_any })
        binding = false; updateLabel()
    }
    private fun updateLabel() {
        toggle.text = if (filters.activeCount == 0) context.getString(R.string.floating_filters)
            else context.getString(R.string.floating_filters_active, filters.activeCount)
        androidx.core.view.ViewCompat.setStateDescription(toggle, context.getString(
            if (expanded) R.string.floating_collapse_filters else R.string.floating_expand_filters))
    }
    fun setExpanded(value: Boolean, animate: Boolean = true) {
        if (expanded == value) return
        expanded = value
        if (value && !isInTouchMode) toggle.requestFocus()
        if (!value && reveal.hasFocus()) toggle.requestFocus()
        reveal.reveal(value, animate)
        updateLabel(); requestLayout(); onExpansionChanged?.invoke(value)
    }
    fun showResultCount(value: Int) { count.text = context.getString(R.string.floating_results, value) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val width = minOf(available, dp(if (expanded) 360 else 168))
        val heightLimit = minOf(MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: Int.MAX_VALUE,
            (resources.displayMetrics.heightPixels * .65f).toInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.AT_MOST))
    }
}
