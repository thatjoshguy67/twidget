package com.tjg.twidget.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SeslProgressBar
import androidx.core.view.children
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.oneui.floatingactioncontainer.FloatingGroupLayout
import com.tjg.twidget.R

/** The decorative subset of One UI 9.5's SeslStrokeDrawable, on the existing SESL9 geometry. */
internal object SeslSurfaceHighlight {
    private data class Foreground(val original: Drawable?, val combined: Drawable)

    /** Called by the shared window observer, including lazily inflated menus and dialogs. */
    fun applyTo(root: View) {
        val themed = !Settings.System.getString(root.context.contentResolver,
            "current_sec_active_themepackage").isNullOrEmpty()
        visit(root, themed)
    }

    // SESL9 has no public popup surface hook. This pinned AppCompat view type
    // identifies menu windows without styling spinner dropdowns or ordinary lists.
    @android.annotation.SuppressLint("RestrictedApi")
    private fun visit(view: View, themed: Boolean) {
        val radius = when {
            view is FloatingGroupLayout.SeslProjectionView.SeslProjectionBackgroundView ->
                view.resources.getDimension(com.google.android.material.R.dimen.sesl_projection_bg_radius)
            view.id == androidx.appcompat.R.id.parentPanel ->
                view.resources.getDimension(androidx.appcompat.R.dimen.sesl_dialog_background_corner_radius)
            view is androidx.appcompat.widget.MenuPopupWindow.MenuDropDownListView ->
                view.resources.getDimension(androidx.appcompat.R.dimen.sesl_menu_popup_corner_radius)
            view is BottomNavigationView && view.background != null ->
                view.resources.getDimension(com.google.android.material.R.dimen.sesl_bottom_navigation_icon_only_mode_background_radius)
            view.id == androidx.appcompat.R.id.search_plate && view.parent is ViewGroup ->
                view.resources.getDimension(androidx.appcompat.R.dimen.sesl_search_plate_radius_size)
            else -> 0f
        }
        if (radius > 0f) {
            if (themed) remove(view) else attach(view, radius)
        }
        if (view is SeslProgressBar) fixCircularTrack(view)
        if (view is ViewGroup) view.children.forEach { visit(it, themed) }
    }

    internal fun attach(view: View, radius: Float) {
        val previous = view.getTag(R.id.sesl_surface_highlight) as? Foreground
        if (previous != null && view.foreground === previous.combined) return
        val original = view.foreground
        val light = TypedValue().let {
            view.context.theme.resolveAttribute(androidx.appcompat.R.attr.isLightTheme, it, true) && it.data != 0
        }
        val rim = SeslRimDrawable(view.resources.displayMetrics.density, radius, light) { view.background }
        val combined = if (original == null) rim else LayerDrawable(arrayOf(original, rim)).apply {
            setPaddingMode(LayerDrawable.PADDING_MODE_STACK)
        }
        view.foreground = combined
        view.setTag(R.id.sesl_surface_highlight, Foreground(original, combined))
    }

    internal fun remove(view: View) {
        val previous = view.getTag(R.id.sesl_surface_highlight) as? Foreground ?: return
        if (view.foreground === previous.combined) view.foreground = previous.original
        view.setTag(R.id.sesl_surface_highlight, null)
    }

    private fun fixCircularTrack(view: SeslProgressBar) {
        // MODE_CIRCLE has a distinctive layer type. Leave spinners, gradient-circle mode
        // and explicit app tints alone. The public tint API reapplies after drawable replacement.
        if (view.isIndeterminate || view.progressBackgroundTintList != null) return
        val track = (view.progressDrawable as? LayerDrawable)
            ?.findDrawableByLayerId(android.R.id.background) ?: return
        if (track.javaClass.name != "androidx.appcompat.widget.SeslProgressBar\$CirCleProgressDrawable") return
        val light = TypedValue().let {
            view.context.theme.resolveAttribute(androidx.appcompat.R.attr.isLightTheme, it, true) && it.data != 0
        }
        view.progressBackgroundTintList = ColorStateList.valueOf(view.context.getColor(if (light)
            androidx.appcompat.R.color.sesl_progress_control_color_background_light
        else androidx.appcompat.R.color.sesl_progress_control_color_background_dark))
    }
}

/** Static 1dp inner rim: OEM colors, 270-degree gradient and stops [0, .75]. */
internal class SeslRimDrawable(
    private val width: Float,
    private val fallbackRadius: Float,
    private val light: Boolean,
    private val background: () -> Drawable? = { null },
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width }
    private val outline = Outline()
    private val outlineRect = Rect()
    private val rect = RectF()
    private var shaderTop = Float.NaN
    private var shaderBottom = Float.NaN
    private var opacity = 255

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds)
        var radius = fallbackRadius
        // SESL can replace its background/radius during a floating transition. Read its
        // current outline at draw time, without caching a View or imposing a new shape.
        outline.setEmpty()
        background()?.getOutline(outline)
        if (outline.getRect(outlineRect) && !outlineRect.isEmpty) {
            rect.set(outlineRect)
            radius = outline.radius
        }
        if (radius <= 0f) return
        if (shaderTop != rect.top || shaderBottom != rect.bottom) {
            shaderTop = rect.top
            shaderBottom = rect.bottom
            paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                intArrayOf(if (light) 0xB3FFFFFF.toInt() else 0x1AFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, .75f), Shader.TileMode.CLAMP)
        }
        rect.inset(width / 2f, width / 2f)
        paint.alpha = opacity
        canvas.drawRoundRect(rect, (radius - width / 2f).coerceAtLeast(0f),
            (radius - width / 2f).coerceAtLeast(0f), paint)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun getAlpha(): Int = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
