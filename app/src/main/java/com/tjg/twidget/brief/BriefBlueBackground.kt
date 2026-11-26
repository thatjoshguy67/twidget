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

/** Fixed, softly blended layers, rebuilt only for bounds/theme changes. No perpetual draw loop. */
internal class BriefBlueBackground(private val theme: BriefBlueTheme, private val night: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layers = mutableListOf<Pair<Shader, Int>>()
    private var opacity = 255
    private var filter: ColorFilter? = null

    override fun onBoundsChange(bounds: Rect) {
        layers.clear()
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        val top = if (night) 0xFF003B66.toInt() else 0xFFA1D5FF.toInt()
        val bottom = if (night) 0xFF202C3E.toInt() else 0xFFF1F1F3.toInt()
        layers += LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP) to 255
        val phase = theme.ordinal
        val colors = if (night) intArrayOf(0xFF126CC0.toInt(), 0xFF2664AA.toInt(), 0xFF1D4078.toInt())
            else intArrayOf(0xFF5BAEFF.toInt(), 0xFF9BCBFF.toInt(), 0xFFB2DFFC.toInt())
        val positions = when (theme) {
            BriefBlueTheme.MORNING -> listOf(.05f to .05f, 1f to .27f, .25f to .64f)
            BriefBlueTheme.AFTERNOON -> listOf(.9f to .1f, .05f to .38f, .9f to .65f)
            BriefBlueTheme.EVENING -> listOf(.15f to .26f, 1f to .05f, .7f to .72f)
            BriefBlueTheme.NIGHT -> listOf(.9f to .05f, .1f to .3f, .5f to .7f)
            BriefBlueTheme.WEEKEND -> listOf(.5f to .03f, 0f to .42f, 1f to .5f)
        }
        positions.forEachIndexed { index, (x, y) ->
            val color = colors[(index + phase) % colors.size]
            layers += RadialGradient(x * w, y * h, maxOf(w * .85f, h * .4f),
                intArrayOf(color, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP) to if (night) 90 else 130
        }
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        layers.forEach { (shader, strength) ->
            paint.shader = shader; paint.alpha = opacity * strength / 255; paint.colorFilter = filter
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
