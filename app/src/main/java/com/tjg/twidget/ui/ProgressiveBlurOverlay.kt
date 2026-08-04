package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.view.SemBlurCompat
import kotlin.math.roundToInt

/**
 * App-local progressive scroll-edge blur using Samsung [SemBlurCompat] directly so CI can
 * compile against the published oneui-design artifact before fork blur APIs land upstream.
 */
class ProgressiveBlurOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    enum class Edge { TOP, BOTTOM }

    var edge: Edge = Edge.TOP
        set(value) {
            field = value
            updateBackgroundFade()
        }

    @ColorInt
    var fadeColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            updateBackgroundFade()
        }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)

        BLUR_LAYERS.forEach { _ ->
            addView(
                BlurLayerView(context),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        addView(
            BackgroundFadeView(context),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun applyBlurLayers(): Boolean {
        if (height <= 0) return false

        var blurApplied = false
        for (index in 0 until BLUR_LAYERS.size) {
            val layerView = getChildAt(index) as? BlurLayerView ?: continue
            val layer = BLUR_LAYERS[index]
            layerView.layoutBlurBand(edge, layer, height)
            if (layerView.applyLayerBlur(layer.blurDp)) {
                blurApplied = true
            }
        }

        getChildAt(BLUR_LAYERS.size)?.visibility = if (blurApplied) VISIBLE else GONE
        return blurApplied
    }

    fun clearBlurLayers() {
        for (index in 0 until BLUR_LAYERS.size) {
            (getChildAt(index) as? BlurLayerView)?.clearBlur()
        }
        getChildAt(BLUR_LAYERS.size)?.visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0 && fadeColor != Color.TRANSPARENT) {
            applyBlurLayers()
        }
    }

    private fun updateBackgroundFade() {
        (getChildAt(BLUR_LAYERS.size) as? BackgroundFadeView)?.setFade(edge, fadeColor)
    }

    private data class BlurLayerSpec(val blurDp: Int, val stops: List<Int>)

    private class BlurLayerView(context: Context) : View(context) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        fun layoutBlurBand(edge: Edge, layer: BlurLayerSpec, overlayHeight: Int) {
            if (overlayHeight <= 0) return
            val band = blurBandFraction(layer.stops, edge)
            val bandHeight = (overlayHeight * band.heightFraction).toInt().coerceAtLeast(1)
            val topMargin = (overlayHeight * band.topFraction).toInt().coerceAtLeast(0)
            layoutParams = (layoutParams as LayoutParams).apply {
                width = LayoutParams.MATCH_PARENT
                height = bandHeight
                gravity = Gravity.TOP
                this.topMargin = topMargin
            }
        }

        fun applyLayerBlur(blurDp: Int): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val radiusPx = dp(context, blurDp)
            if (SemBlurCompat.setBlurEffect(
                    this,
                    Color.TRANSPARENT,
                    radiusPx,
                    SemBlurCompat.BLUR_MODE_WINDOW_CAPTURED,
                    0f,
                )
            ) {
                return true
            }
            return SemBlurCompat.setBlurEffect(
                this,
                Color.TRANSPARENT,
                radiusPx,
                SemBlurCompat.BLUR_MODE_WINDOW,
                0f,
            )
        }

        fun clearBlur() {
            SemBlurCompat.setBlurInfoClear(this)
        }

        private data class BlurBand(val topFraction: Float, val heightFraction: Float)

        private fun blurBandFraction(stops: List<Int>, edge: Edge): BlurBand {
            val mapped = if (edge == Edge.TOP) {
                stops.map { 100 - it }.reversed()
            } else {
                stops
            }
            val start = mapped.first() / 100f
            val end = mapped.last() / 100f
            return BlurBand(start, (end - start).coerceAtLeast(0.05f))
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).roundToInt()
    }

    private class BackgroundFadeView(context: Context) : View(context) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            visibility = GONE
        }

        fun setFade(edge: Edge, @ColorInt fadeColor: Int) {
            val orientation = if (edge == Edge.TOP) {
                GradientDrawable.Orientation.TOP_BOTTOM
            } else {
                GradientDrawable.Orientation.BOTTOM_TOP
            }
            background = GradientDrawable(
                orientation,
                intArrayOf(fadeColor, Color.TRANSPARENT),
            )
        }
    }

    private companion object {
        private val BLUR_LAYERS = listOf(
            BlurLayerSpec(1, listOf(0, 10, 30, 40)),
            BlurLayerSpec(2, listOf(10, 20, 40, 50)),
            BlurLayerSpec(4, listOf(15, 30, 50, 60)),
            BlurLayerSpec(8, listOf(20, 40, 60, 70)),
            BlurLayerSpec(16, listOf(40, 60, 80, 90)),
            BlurLayerSpec(32, listOf(60, 80)),
            BlurLayerSpec(64, listOf(70, 100)),
        )
    }
}
