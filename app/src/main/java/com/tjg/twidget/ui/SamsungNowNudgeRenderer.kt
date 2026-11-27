package com.tjg.twidget.ui

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import com.tjg.twidget.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Samsung Keyboard's AIPresenceView with its NUDGE preset (rk0.a / ni0.o).
 *
 * Now Nudge selects this filled effect instead of NowNudgeEffectView's Guiding
 * Light rim. The AGSL, six brushes, compositing, design coordinates and centre
 * attenuation are copied from Keyboard. Only the brush colours are replaced
 * with Twidget's existing Brief teal, sky blue and blue colour resources.
 * Buttons retain the original resting frame of BORDER_TRAVEL (progress = 0).
 */
@RequiresApi(33)
internal class SamsungNowNudgeRenderer(resources: Resources, night: Boolean, private val palette: IntArray? = null, private val cornerRadius: Float? = null, bottomWeighted: Boolean = false, private val raisedSplash: Boolean = false) {
    private val shader = RuntimeShader(resources.openRawResource(R.raw.samsung_now_nudge_presence)
        .bufferedReader().use { it.readText() })
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@SamsungNowNudgeRenderer.shader }
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) 0xa317171a.toInt() else 0xfffcfcff.toInt()
    }
    private val bounds = RectF()
    private var width = 0
    private var height = 0
    private val density = resources.displayMetrics.density

    init {
        shader.setFloatUniform("uCornerSplash", 0f)
        shader.setFloatUniform("uSplashHeight", if (raisedSplash) .64f else .78f)
        shader.setFloatUniform("uBottomWeight", if (bottomWeighted) 1f else 0f)
        val posRad = FloatArray(80)
        val colours = FloatArray(80)
        val softness = FloatArray(20)
        val angles = FloatArray(40)
        NUDGE_BRUSHES.forEachIndexed { index, brush ->
            val colour = palette?.get(when (brush.colour) {
                R.color.brief_insight_normal_start -> 0
                R.color.brief_insight_normal_end -> 2
                else -> 1
            }) ?: resources.getColor(brush.colour, null)
            val offset = index * 4
            posRad[offset] = (brush.x + DESIGN_WIDTH / 2f) * density
            posRad[offset + 1] = (brush.y + DESIGN_HEIGHT / 2f) * density
            posRad[offset + 2] = brush.radiusX * density
            posRad[offset + 3] = brush.radiusY * density
            if (raisedSplash) {
                // Broad, staggered pools overlap across the card rather than forming three columns.
                val pools = arrayOf(
                    floatArrayOf(-12f, 21f, 105f, 66f),
                    floatArrayOf(45f, 47f, 105f, 57f),
                    floatArrayOf(141f, 29f, 112f, 74f),
                    floatArrayOf(90f, 43f, 105f, 62f),
                    floatArrayOf(63f, 39f, 98f, 57f),
                    floatArrayOf(25f, -32f, 110f, 64f),
                )
                pools[index].forEachIndexed { component, value -> posRad[offset + component] = value * density }
            }
            colours[offset] = Color.red(colour) / 255f
            colours[offset + 1] = Color.green(colour) / 255f
            colours[offset + 2] = Color.blue(colour) / 255f
            colours[offset + 3] = Color.alpha(colour) / 255f * brush.opacity
            softness[index] = 1f - brush.softness
            val radians = -Math.toRadians(brush.angle.toDouble())
            angles[index * 2] = cos(radians).toFloat()
            angles[index * 2 + 1] = sin(radians).toFloat()
        }
        shader.setIntUniform("uBrushCount", NUDGE_BRUSHES.size)
        shader.setFloatUniform("uBrushPosRad", posRad)
        shader.setFloatUniform("uBrushColor", colours)
        shader.setFloatUniform("uBrushSoftness", softness)
        shader.setFloatUniform("uBrushAngleCosSin", angles)
        shader.setFloatUniform("uClipCenter", DESIGN_WIDTH * density / 2f, DESIGN_HEIGHT * density / 2f)
        shader.setFloatUniform("uClipHalfSize", DESIGN_WIDTH * density / 2f, DESIGN_HEIGHT * density / 2f)
        shader.setFloatUniform("uCornerRadius", 26f * density)
        shader.setFloatUniform("uInnerMargin", 0f)
        shader.setFloatUniform("uCenterMarginRatio", .698f, .1f)
        shader.setFloatUniform("uCenterMarginStrength", if (raisedSplash) .72f else .845f)
        shader.setFloatUniform("uCenterMarginRange", .546f)
        shader.setIntUniform("animMode", 2) // AIPresenceView.AnimationMode.BORDER_TRAVEL

        // AIPresenceView.w/g/f at progress 0: sorted perimeter samples contain
        // each rectangle corner twice; the original and animated points coincide.
        val left = -10f
        val top = -10f
        val right = DESIGN_WIDTH * density + 10f
        val bottom = DESIGN_HEIGHT * density + 10f
        val points = floatArrayOf(
            left, top, left, top, right, top, right, top,
            right, bottom, right, bottom, left, bottom, left, bottom, left, top,
        )
        shader.setFloatUniform("uMVCpts", points)
        shader.setFloatUniform("uMVCpts_anim", points)
    }

    fun setPalette(palette: IntArray) {
        val colours = FloatArray(80)
        NUDGE_BRUSHES.forEachIndexed { index, brush ->
            val baseColour = palette[when (brush.colour) {
                R.color.brief_insight_normal_start -> 0
                R.color.brief_insight_normal_end -> 2
                else -> 1
            }]
            val colour = if (raisedSplash) {
                // Keep each pool chromatic; mixing warm and cool RGB values made the overlap grey.
                val hsv = FloatArray(3)
                Color.colorToHSV(baseColour, hsv)
                hsv[1] = (hsv[1] * 1.25f).coerceIn(.48f, .85f)
                hsv[2] = (hsv[2] * 1.08f).coerceAtMost(1f)
                hsv[0] = (hsv[0] + if (index % 2 == 0) 5f else 355f) % 360f
                Color.HSVToColor(hsv)
            } else baseColour
            val offset = index * 4
            colours[offset] = Color.red(colour) / 255f
            colours[offset + 1] = Color.green(colour) / 255f
            colours[offset + 2] = Color.blue(colour) / 255f
            colours[offset + 3] = Color.alpha(colour) / 255f * brush.opacity
        }
        shader.setFloatUniform("uBrushColor", colours)
    }

    fun draw(canvas: Canvas, width: Int, height: Int, alpha: Int, filter: ColorFilter?) {
        if (width != this.width || height != this.height) {
            this.width = width
            this.height = height
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            shader.setFloatUniform("uResolution", DESIGN_WIDTH * density / width, DESIGN_HEIGHT * density / height)
            shader.setFloatUniform("uOriginalResolution", width.toFloat(), height.toFloat())
        }
        val radius = (cornerRadius ?: (minOf(width, height) / 2f)).coerceAtMost(minOf(width, height) / 2f)
        val layer = canvas.saveLayerAlpha(bounds, alpha)
        background.colorFilter = filter
        // Keyboard's two sibling views both carry suggested_replies_button_bg.
        canvas.drawRoundRect(bounds, radius, radius, background)
        if (canvas.isHardwareAccelerated) {
            canvas.drawRoundRect(bounds, radius, radius, background)
            fill.colorFilter = filter
            canvas.drawRoundRect(bounds, radius, radius, fill)
        }
        canvas.restoreToCount(layer)
    }

    private data class Brush(
        val x: Float, val y: Float, val radiusX: Float, val radiusY: Float,
        @androidx.annotation.ColorRes val colour: Int,
        val softness: Float, val opacity: Float, val angle: Float,
    )

    private companion object {
        const val DESIGN_WIDTH = 133.867f
        const val DESIGN_HEIGHT = 36f

        // AIPresencePresets.NUDGE, in Samsung's front-to-back brush order.
        // Original cyan -> Brief teal; coral -> Brief blue; violet/blue -> Brief sky blue.
        val NUDGE_BRUSHES = arrayOf(
            Brush(-79.20001f, -10.799988f, 70f, 111f, R.color.brief_insight_normal_start, 1.762f, .799f, -3.2400055f),
            Brush(-61.200012f, 58.799988f, 85f, 85f, R.color.brief_insight_normal_start, 1.074f, .8f, -80.64f),
            Brush(145.20001f, 98.400024f, 110f, 110f, R.color.brief_insight_normal_end, 1.1f, 1f, -115.56f),
            Brush(97.20001f, 25.200012f, 100f, 100f, R.color.brief_insight_normal_end, .754f, .6f, 0f),
            Brush(0f, 49.200012f, 80f, 80f, R.color.brief_insight_normal_center, 1.058f, .8f, -2.5200043f),
            Brush(0f, -62.400024f, 130f, 112f, R.color.brief_insight_normal_center, .846f, .7f, 0f),
        )
    }
}
