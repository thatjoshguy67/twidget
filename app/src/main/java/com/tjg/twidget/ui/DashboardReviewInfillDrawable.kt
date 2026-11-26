package com.tjg.twidget.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import com.tjg.twidget.R
import org.xmlpull.v1.XmlPullParser

/** Drawable adapter for Samsung's Guiding Light renderer, shared by both button sizes. */
@androidx.annotation.Keep
class DashboardReviewInfillDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var renderer: SamsungGuidingLightRenderer? = null

    override fun inflate(resources: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(resources, parser, attrs, theme)
        paint.color = resources.getColor(R.color.oneui_card_bg, theme)
        if (Build.VERSION.SDK_INT >= 33) {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            renderer = runCatching { SamsungGuidingLightRenderer(resources, theme, night) }
                .onFailure { Log.e("SamsungGuidingLight", "Could not initialise Samsung's button renderer", it) }.getOrNull()
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val effect = renderer
        if (Build.VERSION.SDK_INT >= 33 && effect != null) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            effect.draw(canvas, bounds.width(), bounds.height(), paint.alpha, paint.colorFilter)
            canvas.restoreToCount(save)
        } else {
            // RuntimeShader is unavailable before Android 13: use the ordinary pill, not a fake effect.
            val radius = minOf(bounds.width(), bounds.height()) / 2f
            canvas.drawRoundRect(android.graphics.RectF(bounds), radius, radius, paint)
        }
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, minOf(bounds.width(), bounds.height()) / 2f)
        outline.alpha = paint.alpha / 255f
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun getAlpha(): Int = paint.alpha
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
