package com.tjg.twidget.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import com.tjg.twidget.R
import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Adapted from PDI 7.4.02.1's stationary Guiding Light button treatment:
 * rounded-edge lighting with a broad reflected infill for compact dashboard pills.
 * Adapted to Twidget's palette. A cached raster keeps the treatment available
 * on API 26+ without Samsung's private shader library or a running animator.
 */
@androidx.annotation.Keep
class DashboardReviewInfillDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var raster: Bitmap? = null
    private var surface = Color.WHITE
    private var colours = intArrayOf(Color.CYAN, Color.BLUE)
    private var intensity = .27f

    override fun inflate(resources: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(resources, parser, attrs, theme)
        surface = resources.getColor(R.color.oneui_card_bg, theme)
        colours = intArrayOf(
            resources.getColor(R.color.brief_insight_normal_start, theme),
            resources.getColor(R.color.brief_insight_normal_end, theme),
        )
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        intensity = if (night) .42f else .27f
        raster = null
    }

    override fun onBoundsChange(bounds: Rect) {
        raster = null
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val image = raster ?: render(bounds.width(), bounds.height()).also { raster = it }
        canvas.drawBitmap(image, null, bounds, paint)
    }

    private fun render(width: Int, height: Int): Bitmap {
        // Bound the cache for very large font settings without changing the pill's aspect ratio.
        val scale = min(1f, min(768f / width, 192f / height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        val radius = min(w, h) / 2f
        val straightHalf = max(0f, w / 2f - radius)
        val straightHalfY = max(0f, h / 2f - radius)
        val falloff = radius * .9f
        for (y in 0 until h) for (x in 0 until w) {
            // Signed distance to a capsule, matching the reference's rounded-rectangle light boundary.
            val dx = max(abs(x + .5f - w / 2f) - straightHalf, 0f)
            val dy = max(abs(y + .5f - h / 2f) - straightHalfY, 0f)
            val distance = hypot(dx, dy) - radius
            val coverage = (.5f - distance).coerceIn(0f, 1f)
            val depth = max(-distance, 0f)
            val edgeLight = exp(-depth / falloff.coerceAtLeast(1f))
            // Keep colour through the centre: a dark onboarding stop used to separate the two ends.
            val light = intensity * (.5f + .5f * edgeLight)
            val progress = x.toFloat() / (w - 1).coerceAtLeast(1)
            val blend = progress * progress * (3f - 2f * progress)
            val colour = ColorUtils.blendARGB(colours[0], colours[1], blend)
            pixels[y * w + x] = ColorUtils.setAlphaComponent(
                ColorUtils.blendARGB(surface, colour, light), (coverage * 255).toInt())
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, min(bounds.width(), bounds.height()) / 2f)
        outline.alpha = paint.alpha / 255f
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun getAlpha(): Int = paint.alpha
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
