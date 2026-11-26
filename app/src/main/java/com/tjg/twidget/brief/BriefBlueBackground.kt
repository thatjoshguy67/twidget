package com.tjg.twidget.brief

import android.graphics.*
import android.graphics.drawable.Drawable
import java.time.DayOfWeek
import java.time.ZonedDateTime

internal enum class BriefBlueTheme { MORNING, AFTERNOON, EVENING, NIGHT, WEEKEND;
    companion object {
        fun current(now: ZonedDateTime = ZonedDateTime.now()): BriefBlueTheme = when {
            now.hour < 5 || now.hour >= 23 -> NIGHT
            now.hour >= 17 -> EVENING
            now.hour >= 12 -> AFTERNOON
            now.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> WEEKEND
            else -> MORNING
        }
    }
}

/** Distinct blue light pools. Cached shaders move without allocating new gradients each frame. */
internal class BriefBlueBackground(private val theme: BriefBlueTheme, private val night: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layers = mutableListOf<Pair<Shader, Int>>()
    private val motionMatrix = Matrix()
    private var motion = 0f
    fun setMotionProgress(value: Float) { motion = value; invalidateSelf() }
    private var opacity = 255
    private var filter: ColorFilter? = null

    override fun onBoundsChange(bounds: Rect) {
        layers.clear()
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        val top = if (night) 0xFF002340.toInt() else 0xFFBDDFFF.toInt()
        val bottom = if (night) 0xFF152A45.toInt() else 0xFFEEF5FF.toInt()
        layers += LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP) to 255
        val phase = theme.ordinal
        val colors = if (night) intArrayOf(0xFF007CDD.toInt(), 0xFF1762CE.toInt(), 0xFF149BC5.toInt())
            else intArrayOf(0xFF3B9EFF.toInt(), 0xFF73CFFF.toInt(), 0xFF7CA9FF.toInt())
        val positions = when (theme) {
            BriefBlueTheme.MORNING -> listOf(.05f to .05f, 1f to .27f, .25f to .64f)
            BriefBlueTheme.AFTERNOON -> listOf(.9f to .1f, .05f to .38f, .9f to .65f)
            BriefBlueTheme.EVENING -> listOf(.15f to .26f, 1f to .05f, .7f to .72f)
            BriefBlueTheme.NIGHT -> listOf(.9f to .05f, .1f to .3f, .5f to .7f)
            BriefBlueTheme.WEEKEND -> listOf(.5f to .03f, 0f to .42f, 1f to .5f)
        }
        positions.forEachIndexed { index, (x, y) ->
            val color = colors[(index + phase) % colors.size]
            layers += RadialGradient(x * w, y * h, maxOf(w * .65f, h * .28f),
                intArrayOf(color, color, color and 0x00FFFFFF), floatArrayOf(0f, .18f, 1f), Shader.TileMode.CLAMP) to if (night) 160 else 190
        }
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        layers.forEachIndexed { index, (shader, strength) ->
            if (index > 0) {
                val angle = motion * Math.PI * 2 + index * 2.1
                motionMatrix.setTranslate(
                    (kotlin.math.sin(angle) * bounds.width() * .20).toFloat(),
                    (kotlin.math.cos(angle) * bounds.height() * .10).toFloat(),
                )
                shader.setLocalMatrix(motionMatrix)
            }
            paint.shader = shader; paint.alpha = opacity * strength / 255; paint.colorFilter = filter
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
