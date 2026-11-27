package com.tjg.twidget.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.ColorUtils

/** The report-button material, sized to the card bounds and coloured by streak state. */
internal class StreakMaterialDrawable(context: Context, colour: Int, private val radius: Float) : Drawable() {
    private val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    private val renderer = if (Build.VERSION.SDK_INT >= 33) runCatching {
        val hsv = FloatArray(3)
        Color.colorToHSV(colour, hsv)
        val isRed = hsv[0] < 12f || hsv[0] > 348f
        hsv[1] = if (isRed) 1f else .78f
        hsv[2] = if (isRed) 1f else if (night) .85f else .72f
        val primary = Color.HSVToColor(hsv)
        hsv[0] = (hsv[0] + if (isRed) 354f else 18f) % 360f
        val secondary = Color.HSVToColor(hsv)
        SamsungNowNudgeRenderer(context.resources, night,
            intArrayOf(primary, ColorUtils.blendARGB(primary, secondary, .5f), secondary), radius, bottomWeighted = true)
    }.getOrNull() else null

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val effect = renderer
        if (Build.VERSION.SDK_INT >= 33 && effect != null && canvas.isHardwareAccelerated) {
            effect.draw(canvas, bounds.width(), bounds.height(), paint.alpha, paint.colorFilter)
        } else {
            canvas.drawRoundRect(RectF(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat()), radius, radius, paint)
        }
        canvas.restoreToCount(save)
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radius); outline.alpha = paint.alpha / 255f }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun getAlpha() = paint.alpha
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
