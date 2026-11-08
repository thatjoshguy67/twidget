package com.tjg.twidget.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.DrawableWrapper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ListView
import androidx.appcompat.widget.SeslProgressBar
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.oneui.floatingactioncontainer.FloatingGroupLayout
import com.google.android.material.oneui.dividerbuttonlayout.DividerButtonLayout
import com.google.android.material.oneui.responsivepane.ResponsiveDrawerLayout
import com.google.android.material.oneui.floatingdock.FloatingPaneView
import com.google.android.material.snackbar.SnackbarContentLayout
import com.google.android.material.tabs.TabLayout
import com.tjg.twidget.R

/** The decorative subset of One UI 9.5's SeslStrokeDrawable, on the existing SESL9 geometry. */
internal object SeslSurfaceHighlight {
    private data class Foreground(val original: Drawable?, val combined: Drawable, val radius: Float, val light: Boolean)

    /** Explicit opt-in for app-owned equivalents of the floating SESL capsules. */
    fun mark(view: View, radius: Float) {
        view.setTag(R.id.sesl_surface_radius, radius)
    }

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
            view is BottomNavigationView && view.background != null ->
                view.resources.getDimension(com.google.android.material.R.dimen.sesl_bottom_navigation_icon_only_mode_background_radius)
            view is DividerButtonLayout || view is ResponsiveDrawerLayout ||
                view.id == dev.oneuiproject.oneui.design.R.id.drawer_panel ->
                view.resources.getDimension(com.google.android.material.R.dimen.sesl_divider_button_layout_background_radius)
            view is FloatingPaneView ->
                view.resources.getDimension(com.google.android.material.R.dimen.sesl_floating_pane_background_corner_radius)
            view is SnackbarContentLayout ->
                view.resources.getDimension(com.google.android.material.R.dimen.sesl_design_snackbar_suggest_background_radius)
            view is TabLayout && view.background != null ->
                view.resources.getDimension(com.google.android.material.R.dimen.sesl_tablayout_item_radius)
            view.id == androidx.appcompat.R.id.search_plate_frame ||
                (view.id == androidx.appcompat.R.id.search_button && view.background != null) ->
                view.resources.getDimension(androidx.appcompat.R.dimen.sesl_search_plate_radius_size)
            view.id == R.id.onboarding_back ||
                view.id == R.id.brief_footer_info || view.id == R.id.brief_footer_settings ->
                28f * view.resources.displayMetrics.density
            view.id == com.google.android.material.R.id.design_bottom_sheet ->
                28f * view.resources.displayMetrics.density
            view.getTag(R.id.sesl_surface_radius) is Float -> view.getTag(R.id.sesl_surface_radius) as Float
            else -> 0f
        }
        if (radius > 0f) {
            if (themed) remove(view) else attach(view, radius, if (view is SnackbarContentLayout) false else null)
        }
        // Platform ListPopupWindow is also used by settings selectors. Style the
        // whole popup surface, including title/prompt and background padding.
        if (view is ListView && (view is androidx.appcompat.widget.MenuPopupWindow.MenuDropDownListView ||
                (view.rootView.layoutParams as? WindowManager.LayoutParams)?.type in
                    WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW)) {
            val surface = popupSurface(view)
            if (themed) remove(surface) else attach(surface,
                view.resources.getDimension(androidx.appcompat.R.dimen.sesl_menu_popup_corner_radius))
        }
        if (view is SeslProgressBar) fixCircularTrack(view)
        if (view is RecyclerView) {
            view.seslGetGoToTopView()?.let { overlay ->
                if (themed) remove(overlay) else attach(overlay, 26f * view.resources.displayMetrics.density)
            }
            applyIndexTip(view, themed)
        }
        if (view is ViewGroup) view.children.forEach { visit(it, themed) }
    }

    // Index tips live in ViewGroupOverlay and cannot be found by walking children.
    // These two fields are kept in R8, just like the existing fast-scroller font bridge.
    private val indexTipField by lazy { runCatching {
        RecyclerView::class.java.getDeclaredField("mIndexTipController").apply { isAccessible = true }
    }.getOrNull() }

    private fun applyIndexTip(list: RecyclerView, themed: Boolean) {
        val controller = indexTipField?.get(list) ?: return
        val tip = runCatching {
            controller.javaClass.getDeclaredField("mView").apply { isAccessible = true }.get(controller) as? View
        }.getOrNull() ?: return
        if (themed) remove(tip) else attach(tip, 26f * list.resources.displayMetrics.density)
    }

    private fun popupSurface(list: View): View {
        var surface = list
        var parent = list.parent as? View
        while (parent != null && parent !== list.rootView) {
            surface = parent
            if (parent.background != null) return parent
            parent = parent.parent as? View
        }
        return surface
    }

    internal fun attach(view: View, radius: Float, lightOverride: Boolean? = null) {
        val previous = view.getTag(R.id.sesl_surface_highlight) as? Foreground
        val light = lightOverride ?: TypedValue().let {
            view.context.theme.resolveAttribute(androidx.appcompat.R.attr.isLightTheme, it, true) && it.data != 0
        }
        if (previous != null && view.foreground === previous.combined &&
            previous.radius == radius && previous.light == light) return
        val original = if (view.foreground === previous?.combined) previous?.original else view.foreground
        val drawer = if (view.id == dev.oneuiproject.oneui.design.R.id.drawer_panel)
            generateSequence(view.parent as? View) { it.parent as? View }.filterIsInstance<SlidingPaneLayout>().firstOrNull()
        else null
        val ltrCorners = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
        val rtlCorners = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        val squareCorners = FloatArray(8)
        val rim = SeslRimDrawable(view.resources.displayMetrics.density, radius, light,
            cornerRadii = drawer?.let { pane -> {
                if (!pane.seslGetRoundedCornerOn()) squareCorners
                else if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) rtlCorners else ltrCorners
            } },
            omitEdge = drawer?.let { { if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 else -1 } },
        ) {
            if (view.id == androidx.appcompat.R.id.search_plate_frame)
                view.findViewById<View>(androidx.appcompat.R.id.search_plate)?.background
            else view.background
        }
        val combined = if (original == null) rim else LayerDrawable(arrayOf(original, rim)).apply {
            setPaddingMode(LayerDrawable.PADDING_MODE_STACK)
        }
        view.foreground = combined
        view.setTag(R.id.sesl_surface_highlight, Foreground(original, combined, radius, light))
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
    private val cornerRadii: (() -> FloatArray?)? = null,
    private val omitEdge: (() -> Int)? = null,
    private val background: () -> Drawable? = { null },
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width }
    private val outline = Outline()
    private val outlineRect = Rect()
    private val rect = RectF()
    private val path = Path()
    private val insetRadii = FloatArray(8)
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
        if (outline.getRect(outlineRect) && !outlineRect.isEmpty && outline.radius > 0f) {
            rect.set(outlineRect)
            radius = outline.radius
        }
        val gradient = gradientBackground(background())
        if (gradient != null && !gradient.bounds.isEmpty) {
            rect.set(gradient.bounds)
            if (gradient.cornerRadius > 0f) radius = gradient.cornerRadius
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
        path.rewind()
        val radii = cornerRadii?.invoke() ?: gradient?.cornerRadii
        if (gradient?.shape == GradientDrawable.OVAL) {
            path.addOval(rect, Path.Direction.CW)
        } else if (radii != null) {
            for (index in insetRadii.indices) insetRadii[index] = (radii[index] - width / 2f).coerceAtLeast(0f)
            path.addRoundRect(rect, insetRadii, Path.Direction.CW)
        } else {
            path.addRoundRect(rect, (radius - width / 2f).coerceAtLeast(0f),
                (radius - width / 2f).coerceAtLeast(0f), Path.Direction.CW)
        }
        val save = canvas.save()
        // Legacy SlidingPaneLayout masks only the trailing corners; its leading
        // edge joins the window. Do not invent an inset border along that edge.
        when (omitEdge?.invoke()) {
            -1 -> canvas.clipRect(bounds.left + width, bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
            1 -> canvas.clipRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right - width, bounds.bottom.toFloat())
        }
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    private fun gradientBackground(drawable: Drawable?, depth: Int = 0): GradientDrawable? {
        if (depth > 8) return null
        return when (drawable) {
            is GradientDrawable -> drawable
            is DrawableWrapper -> gradientBackground(drawable.drawable, depth + 1)
            is LayerDrawable -> {
                // Prefer the ripple mask when present; otherwise follow its fill.
                gradientBackground(drawable.findDrawableByLayerId(android.R.id.mask), depth + 1)
                    ?: (0 until drawable.numberOfLayers).firstNotNullOfOrNull {
                        gradientBackground(drawable.getDrawable(it), depth + 1)
                    }
            }
            else -> null
        }
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun getAlpha(): Int = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
