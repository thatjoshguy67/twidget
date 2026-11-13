package com.tjg.twidget.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.tjg.twidget.R
import kotlin.math.roundToInt

/** Figma's 214-unit row, measured with real Android children rather than scaling a rendered card. */
internal object DashboardCardDesign {
    interface Scalable { fun applyCardDesignScale(scale: Float) }
    private data class Baseline(val width: Int, val height: Int, val margins: IntArray?, val padding: IntArray,
        val minimumWidth: Int, val minimumHeight: Int, val textSp: Float?, val autoSizes: IntArray,
        val translationX: Float, val translationY: Float)

    fun apply(root: View, heightPx: Int, widthPx: Int = root.width) {
        if (heightPx <= 0) return
        val density = root.resources.displayMetrics.density
        val scale = heightPx / (214f * density)
        val nativeStatSpacing = root.findViewById<View>(R.id.dashboard_stat_header) != null
        val nativePostSpacing = root.findViewById<View>(R.id.dashboard_post_heading) != null
        fun visit(view: View) {
            val params = view.layoutParams
            val metrics = view.resources.displayMetrics
            val baseline = view.getTag(R.id.dashboard_design_baseline) as? Baseline ?: Baseline(
                params?.width ?: -1, params?.height ?: -1,
                (params as? ViewGroup.MarginLayoutParams)?.let { intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin, it.marginStart, it.marginEnd) },
                intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom),
                view.minimumWidth, view.minimumHeight,
                (view as? TextView)?.let {
                    if (android.os.Build.VERSION.SDK_INT >= 34) TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, it.textSize, metrics)
                    else @Suppress("DEPRECATION") (it.textSize / metrics.scaledDensity)
                },
                (view as? TextView)?.let { TextViewCompat.getAutoSizeTextAvailableSizes(it) } ?: intArrayOf(),
                view.translationX, view.translationY,
            ).also { view.setTag(R.id.dashboard_design_baseline, it) }
            fun px(value: Int) = if (nativePostSpacing) value else (value * scale).roundToInt()
            params?.apply {
                if (baseline.width > 0) width = px(baseline.width)
                if (baseline.height > 0) height = px(baseline.height)
                if (this is ViewGroup.MarginLayoutParams) baseline.margins?.let {
                    setMargins(px(it[0]), px(it[1]), px(it[2]), px(it[3]))
                    marginStart = px(it[4]); marginEnd = px(it[5])
                }
            }
            // Avatar loading owns its fallback inset and removes it when the real photo arrives.
            // Reapplying that captured inset would put a square photo inside the fallback circle.
            val dynamicAvatar = view is android.widget.ImageView &&
                (view.getTag(R.id.profile_image_request) as? String)?.startsWith("profile:") == true
            if (!dynamicAvatar) {
                val editorial = view.getTag(R.id.app_font_role) == TwidgetFonts.Role.DASHBOARD_EDITORIAL
                val inset = if (editorial) (12f * density).roundToInt() else 0
                fun padding(value: Int) = if (view === root && nativeStatSpacing) value else px(value)
                view.setPadding(maxOf(inset, padding(baseline.padding[0])), padding(baseline.padding[1]),
                    maxOf(inset, padding(baseline.padding[2])), padding(baseline.padding[3]))
            }
            view.minimumWidth = if (nativeStatSpacing) baseline.minimumWidth else px(baseline.minimumWidth)
            view.minimumHeight = if (nativeStatSpacing) baseline.minimumHeight else px(baseline.minimumHeight)
            view.translationX = baseline.translationX * scale
            view.translationY = baseline.translationY * scale
            if (view is CardValueTextView) {
                val smallValue = (baseline.textSp ?: 60f) <= 24f
                view.applyDesignScale(if (smallValue) 1f else scale)
                if (smallValue && baseline.height > 0) params?.height = baseline.height
            } else if (view is TextView && baseline.textSp != null) {
                // Labels and body copy retain native readable sizes. Large display lettering
                // alone fits proportionally to the card's available row height.
                val textScale = if (baseline.textSp >= 30f ||
                    view.getTag(R.id.dashboard_design_proportional_text) == true ||
                    view.getTag(R.id.app_font_role) == TwidgetFonts.Role.DASHBOARD_EDITORIAL) scale else 1f
                fun lineHeight() {
                    (view.getTag(R.id.dashboard_design_line_height) as? Float)?.let { height ->
                        TextViewCompat.setLineHeight(view, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, height * textScale, metrics).roundToInt())
                    }
                }
                if (baseline.autoSizes.isNotEmpty()) {
                    val sizes = baseline.autoSizes.map { (it * textScale).roundToInt().coerceAtLeast(1) }.distinct().toIntArray()
                    if (!sizes.contentEquals(TextViewCompat.getAutoSizeTextAvailableSizes(view))) {
                        TextViewCompat.setAutoSizeTextTypeWithDefaults(view, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizes.last().toFloat())
                        lineHeight()
                        TextViewCompat.setAutoSizeTextTypeUniformWithPresetSizes(view, sizes, TypedValue.COMPLEX_UNIT_PX)
                    }
                } else {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseline.textSp * textScale)
                    lineHeight()
                }
            }
            (view as? Scalable)?.applyCardDesignScale(if (nativePostSpacing && view is DashboardShadowLayout) 1f else scale)
            fun radius(drawable: Drawable?, cardSurface: Boolean) {
                val owned = drawable?.mutate()
                when (owned) {
                    is GradientDrawable -> if (cardSurface && owned.cornerRadius > 0f) {
                        val radius = 28f * density
                        if (owned.cornerRadius != radius) owned.cornerRadius = radius
                    }
                    is LayerDrawable -> for (index in 0 until owned.numberOfLayers) radius(owned.getDrawable(index), cardSurface)
                    is Scalable -> owned.applyCardDesignScale(1f)
                }
            }
            radius(view.background, view === root)
            radius(view.foreground, view === root)
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        root.findViewById<View>(R.id.brief_dashboard_icon)?.let { icon ->
            val compact = widthPx > 0 && widthPx / density < 340f
            val iconSize = ((if (compact) 48f else 60f) * density * scale).roundToInt()
            icon.layoutParams.width = iconSize
            icon.layoutParams.height = iconSize
            (icon.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin =
                ((if (compact) 8f else 12f) * density * scale).roundToInt()
            (root.findViewById<View>(R.id.brief_dashboard_message)?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
                ((if (compact) 8f else 12f) * density * scale).roundToInt()
        }
        // Keep the count and plot useful when a two-column-wide graph becomes
        // narrower on the portrait Fold. Landscape retains the Figma proportions.
        root.findViewById<View>(R.id.dashboard_graph_summary)?.let { summary ->
            val compact = widthPx > 0 && widthPx / density < 340f
            if (compact) {
                val horizontal = (10f * density).roundToInt()
                root.setPadding(horizontal, (4f * density).roundToInt(), horizontal, (6f * density).roundToInt())
            }
            summary.layoutParams.width = minOf((158f * density * scale).roundToInt(), (widthPx * .34f).roundToInt())
            summary.visibility = if (compact) View.GONE else View.VISIBLE
            root.findViewById<TextView>(R.id.dashboard_graph_heading)?.apply {
                visibility = if (compact) View.VISIBLE else View.GONE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 18f)
                TwidgetFonts.dashboardHeading(this)
                if (compact) (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (4f * density).roundToInt()
            }
            (summary.parent as? ViewGroup)?.let { body ->
                (0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<MetricChartView>().firstOrNull()?.let { chart ->
                    chart.graphOnlyLayout = compact
                    val headingGravity = if (root.hasOnClickListeners() || chart.onChartTapListener != null)
                        android.view.Gravity.START else android.view.Gravity.CENTER_HORIZONTAL
                    root.findViewById<TextView>(R.id.dashboard_graph_heading)?.gravity =
                        headingGravity
                    root.findViewById<TextView>(R.id.metric_label)?.gravity = android.view.Gravity.START
                    (summary as? ViewGroup)?.let { column ->
                        for (index in 0 until column.childCount) {
                            val text = column.getChildAt(index) as? TextView ?: continue
                            text.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                            if (text.id != R.id.metric_label && text !is CardValueTextView) {
                                TwidgetFonts.setRole(text, TwidgetFonts.Role.CHART_VALUE)
                            }
                        }
                    }
                    (chart.layoutParams as ViewGroup.MarginLayoutParams).marginStart = if (compact) 0 else (10 * density * scale).roundToInt()
                }
            }
            root.findViewById<TextView>(R.id.metric_label)?.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                TwidgetFonts.dashboardHeading(this)
            }
        }
        DashboardCardDensity.apply(root, widthPx)
    }
}
