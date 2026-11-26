package com.tjg.twidget.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ViewFlipper
import androidx.core.graphics.ColorUtils
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetWidgetSettings
import kotlin.math.cos
import kotlin.math.sin

/** Translucent colour layers live above, never replace, the launcher's rounded blur surface. */
internal object BriefWidgetDynamicBackground {
    val frameIds = intArrayOf(R.id.brief_widget_background_0, R.id.brief_widget_background_1,
        R.id.brief_widget_background_2, R.id.brief_widget_background_3)

    fun enabled(settings: TwidgetWidgetSettings) = settings.style == WidgetStyle.ONE_UI && settings.briefDynamic
    fun frameCount() = if (ValueAnimator.areAnimatorsEnabled()) 4 else 1
    fun budget(total: Long, variants: Int): Long = minOf(1_048_576L, total / (variants * 3L))

    fun bytes(width: Int, height: Int, perThemeBudget: Long, frames: Int = frameCount()): Long {
        val dimensions = widgetBitmapSize(width, height, perThemeBudget / frames)
        return dimensions.width.toLong() * dimensions.height * 4L * frames
    }

    fun render(context: Context, settings: TwidgetWidgetSettings, width: Int, height: Int,
        dark: Boolean, phase: Float, budgetBytes: Long, background: Int? = null): Bitmap {
        val (bitmap, canvas) = widgetArtworkSurface(width, height, budgetBytes)
        val corner = radius(context, height.toFloat())
        background?.let { colour ->
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour })
        }
        draw(canvas, settings, width.toFloat(), height.toFloat(), dark, phase, corner)
        return bitmap
    }

    private fun radius(context: Context, height: Float): Float =
        if (height / context.resources.displayMetrics.density <= 110) height / 2f else 26 * context.resources.displayMetrics.density

    fun draw(canvas: Canvas, settings: TwidgetWidgetSettings, width: Float, height: Float,
        dark: Boolean, phase: Float, radius: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply { addRoundRect(RectF(0f, 0f, width, height), radius, radius, Path.Direction.CW) }
        val saved = canvas.save()
        canvas.clipPath(path)
        val colours = intArrayOf(settings.briefPrimaryColor, settings.briefSecondaryColor, settings.briefPrimaryColor)
        colours.forEachIndexed { index, colour ->
            val angle = phase * Math.PI * 2 + index * 2.1
            val x = width * (.5f + sin(angle).toFloat() * .5f)
            val y = height * (.5f + cos(angle).toFloat() * .45f)
            val safeColour = ColorUtils.blendARGB(if (dark) Color.BLACK else Color.WHITE, colour, if (dark) .65f else .7f)
            paint.shader = RadialGradient(x, y, maxOf(width * .75f, height * .9f),
                intArrayOf(safeColour, safeColour and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            paint.alpha = (settings.tintAlpha * .7f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width, height, paint)
        }
        canvas.restoreToCount(saved)
    }

    fun preview(context: Context, settings: TwidgetWidgetSettings, width: Int, height: Int): View {
        val count = frameCount()
        val perFrame = budget(2_000_000L, 1) / count
        fun image(frame: Int) = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageBitmap(render(context, settings, width, height, widgetUsesDarkTheme(settings.colorMode), frame / 4f, perFrame))
        }
        if (count == 1) return image(0)
        return ViewFlipper(context).apply {
            repeat(count) { addView(image(it), android.view.ViewGroup.LayoutParams(-1, -1)) }
            flipInterval = 4000
            inAnimation = AnimationUtils.loadAnimation(context, R.anim.widget_brief_dynamic_in)
            outAnimation = AnimationUtils.loadAnimation(context, R.anim.widget_brief_dynamic_out)
            isAutoStart = true
        }
    }
}
