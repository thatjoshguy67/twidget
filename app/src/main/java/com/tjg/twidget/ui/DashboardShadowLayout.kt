package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.widget.FrameLayout

/** A bounded native thumbnail with the Figma shadow rendered outside its layout bounds. */
internal class DashboardShadowLayout(context: Context, private var radius: Float = 8f,
    private val blur: Float = 16.6f, private val offset: Float = 4f) : FrameLayout(context), DashboardCardDesign.Scalable {
    private var scale = -1f
    private var renderer: CardShadow.Renderer? = null
    init { clipChildren = false; clipToPadding = false }
    fun setCornerRadiusPx(radiusPx: Float) {
        val updated = radiusPx.coerceAtLeast(0f) / resources.displayMetrics.density
        if (radius == updated) return
        radius = updated
        val previousScale = scale
        scale = -1f
        if (previousScale >= 0f) applyCardDesignScale(previousScale)
    }
    override fun applyCardDesignScale(scale: Float) {
        if (this.scale == scale) return
        this.scale = scale
        renderer = CardShadow.renderer(context, radius * resources.displayMetrics.density * scale,
            blurDp = blur * scale, offsetDp = offset * scale, shadowColor = Color.argb(64, 0, 0, 0))
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is android.widget.ImageView) {
                child.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius * resources.displayMetrics.density * this@DashboardShadowLayout.scale)
                    }
                }
                child.clipToOutline = true
                child.invalidateOutline()
            }
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        if (renderer == null) applyCardDesignScale(1f)
        val padding = CardShadow.PADDING_DP * resources.displayMetrics.density
        val checkpoint = canvas.save()
        canvas.translate(-padding, -padding)
        renderer?.draw(canvas, (width + padding * 2).toInt(), (height + padding * 2).toInt())
        canvas.restoreToCount(checkpoint)
        super.dispatchDraw(canvas)
    }
}
