package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.brief.BriefBlueBackground
import com.tjg.twidget.brief.BriefBlueTheme
import java.time.ZonedDateTime

/** Translucent colour layers live above, never replace, the launcher's rounded blur surface. */
internal object BriefWidgetDynamicBackground {
    val frameIds = intArrayOf(R.id.brief_widget_background_0, R.id.brief_widget_background_1,
        R.id.brief_widget_background_2, R.id.brief_widget_background_3)

    fun enabled(settings: TwidgetWidgetSettings) = settings.briefBackgroundEnabled && settings.briefDynamic
    fun effectiveSettings(settings: TwidgetWidgetSettings) = if (enabled(settings))
        settings.copy(colorMode = com.tjg.twidget.data.TwidgetStore.COLOR_MODE_SYSTEM) else settings

    fun surfaceColor(context: Context, settings: TwidgetWidgetSettings, dark: Boolean): Int =
        ColorUtils.setAlphaComponent(WidgetColors.resolve(context, settings, dark).background,
            if (settings.briefBackgroundEnabled) settings.tintAlpha else 0)
    fun frameCount() = 1
    fun budget(total: Long, variants: Int): Long = minOf(1_048_576L, total / (variants * 3L))

    fun bytes(width: Int, height: Int, perThemeBudget: Long, frames: Int = frameCount()): Long {
        val dimensions = widgetBitmapSize(width, height, perThemeBudget / frames)
        return dimensions.width.toLong() * dimensions.height * 4L * frames
    }

    fun render(context: Context, settings: TwidgetWidgetSettings, width: Int, height: Int,
        dark: Boolean, phase: Float, budgetBytes: Long, background: Int? = null,
        now: ZonedDateTime = ZonedDateTime.now(), clipToSurface: Boolean = true): Bitmap {
        val (bitmap, canvas) = widgetArtworkSurface(width, height, budgetBytes)
        val corner = radius(context, height.toFloat())
        background?.let { colour ->
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour })
        }
        draw(canvas, settings, width.toFloat(), height.toFloat(), dark, phase, corner, now, clipToSurface, context.resources.displayMetrics.density)
        return bitmap
    }

    private fun radius(context: Context, height: Float): Float =
        if (height / context.resources.displayMetrics.density <= 110) height / 2f else 26 * context.resources.displayMetrics.density

    @Suppress("UNUSED_PARAMETER")
    fun draw(canvas: Canvas, settings: TwidgetWidgetSettings, width: Float, height: Float,
        dark: Boolean, phase: Float, radius: Float, now: ZonedDateTime = ZonedDateTime.now(), clipToSurface: Boolean = true, density: Float = 1f) {
        if (!enabled(settings)) return
        val path = Path().apply { addRoundRect(RectF(0f, 0f, width, height), radius, radius, Path.Direction.CW) }
        val saved = canvas.save()
        if (clipToSurface) canvas.clipPath(path)
        BriefBlueBackground(BriefBlueTheme.current(now), dark, density = density).apply {
            setBounds(0, 0, width.toInt(), height.toInt())
            setMotionProgress(.25f)
            alpha = (settings.tintAlpha * .7f).toInt().coerceIn(0, 255)
            draw(canvas)
        }
        canvas.restoreToCount(saved)
    }

    fun preview(context: Context, settings: TwidgetWidgetSettings, width: Int, height: Int): View {
        val now = ZonedDateTime.now()
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageBitmap(render(context, settings, width, height, widgetUsesDarkTheme(settings.colorMode),
                .25f, budget(2_000_000L, 1), now = now, clipToSurface = false))
        }
    }
}
