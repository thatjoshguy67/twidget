package com.tjg.twidget.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tjg.twidget.R
import dev.oneuiproject.oneui.utils.applyEdgeToEdge

/**
 * Keeps every app window edge-to-edge on all supported Android versions.
 * One UI layouts consume the relevant insets themselves; custom layouts apply
 * their own padding while their backgrounds continue beneath both system bars.
 */
abstract class EdgeToEdgeActivity : AppCompatActivity() {
    private lateinit var createdFont: AppAppearance.Font
    private lateinit var createdLogo: String

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppPaletteManager.attachResources(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        createdFont = AppAppearance.font(this)
        createdLogo = AppAppearance.logo(this)
        super.onCreate(savedInstanceState)
        // Apply after AppCompat installs the themed decor so the parent One UI
        // theme cannot restore an opaque navigation-bar colour afterwards.
        applyEdgeToEdge()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onContentChanged() {
        super.onContentChanged()
        findViewById<ViewGroup>(android.R.id.content)?.let { root ->
            SeslToolbarCompatibility.install(root)
            TwidgetFonts.observeWindow(root.rootView)
        }
    }

    override fun onStart() {
        super.onStart()
        TwidgetAppVisibility.activityStarted()
    }

    override fun onResume() {
        super.onResume()
        // Refresh screens already in the back stack, including their Canvas text.
        if (createdFont != AppAppearance.font(this) || createdLogo != AppAppearance.logo(this)) recreate()
    }

    override fun onStop() {
        TwidgetAppVisibility.activityStopped()
        super.onStop()
    }

    /**
     * Keeps content clear of system navigation and the keyboard. Callers with
     * floating controls can use [onNavigationBarInset] to handle the navigation
     * edge themselves while keeping their scrolling viewport full height.
     */
    private val navigationPadding = java.util.WeakHashMap<View, Int>()

    private fun scrollingViews(view: View): List<View> {
        if (view is androidx.core.widget.NestedScrollView ||
            view is android.widget.ScrollView ||
            view is androidx.recyclerview.widget.RecyclerView) return listOf(view)
        return (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).flatMap { scrollingViews(group.getChildAt(it)) }
        }.orEmpty()
    }

    protected fun applyEdgeToEdgeInsets(
        root: View,
        padRootForIme: Boolean = true,
        clipToStatusBarAndCutout: Boolean = false,
        onNavigationBarInset: ((Int) -> Unit)? = null,
    ) {
        var safeViewport = androidx.core.graphics.Insets.NONE
        fun updateViewportClip() {
            if (!clipToStatusBarAndCutout) return
            // The floating toolbar allows scrolling behind itself, but dashboard
            // cards and the drawer must stop at the system status/cutout boundary.
            // Keep the existing edge-to-edge navigation treatment at the bottom.
            root.clipBounds = android.graphics.Rect(safeViewport.left, safeViewport.top,
                (root.width - safeViewport.right).coerceAtLeast(safeViewport.left), root.height)
        }
        if (clipToStatusBarAndCutout) root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateViewportClip() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            safeViewport = safe
            updateViewportClip()
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0
            val topPadding = if (SeslToolbarCompatibility.applyTopInset(view, safe.top)) 0 else safe.top
            // Screens with floating controls handle their navigation inset in the callback.
            // Scroll containers keep an end inset while their viewport draws behind navigation.
            val scrollers = if (onNavigationBarInset == null) scrollingViews(view) else emptyList()
            val bottomPadding = if (padRootForIme) ime.bottom else 0
            scrollers.forEach { scroll ->
                val original = navigationPadding.getOrPut(scroll) { scroll.paddingBottom }
                (scroll as ViewGroup).clipToPadding = false
                scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight,
                    original + (safe.bottom - bottomPadding).coerceAtLeast(0))
            }
            view.setPadding(safe.left, topPadding, safe.right, bottomPadding)
            // The drawer surface stops above system navigation even when the
            // page scrolls behind it. Root padding may already cover part or all
            // of this space (including the keyboard), so avoid counting it twice.
            SeslToolbarCompatibility.applyDrawerBottomInset(
                view,
                (maxOf(safe.bottom, ime.bottom) - bottomPadding).coerceAtLeast(0),
            )
            // IME insets already include the navigation region on Samsung and
            // several other OEM keyboards. Do not add it to floating chrome twice.
            onNavigationBarInset?.invoke(if (imeVisible && padRootForIme) 0 else safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Pinned footers extend their surface beneath navigation while protecting buttons. */
    protected fun applyFooterScreenInsets(root: View, footer: View) {
        val bottom = footer.paddingBottom
        applyEdgeToEdgeInsets(root) { inset ->
            footer.setPadding(footer.paddingLeft, footer.paddingTop, footer.paddingRight, bottom + inset)
        }
    }

    /** Preference lists own navigation padding so their viewport reaches the window edge. */
    protected fun applyPreferenceScreenInsets(root: View) {
        applyEdgeToEdgeInsets(root, onNavigationBarInset = {})
    }

    /** Keeps bottom-floating chrome above gesture and button navigation bars. */
    protected fun View.updateBottomMarginForNavigationBar(
        baseMargin: Int,
        navigationBarInset: Int,
    ) {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val targetMargin = baseMargin + navigationBarInset
        if (params.bottomMargin == targetMargin) return
        params.bottomMargin = targetMargin
        layoutParams = params
    }
}

internal object TwidgetAppVisibility {
    private var visibleActivities = 0
    private val listeners = linkedSetOf<(Boolean) -> Unit>()

    fun activityStarted() {
        val listenersToNotify = synchronized(this) {
            val becameVisible = visibleActivities == 0
            visibleActivities++
            if (becameVisible) listeners.toList() else emptyList()
        }
        listenersToNotify.forEach { it(true) }
    }

    fun activityStopped() {
        val listenersToNotify = synchronized(this) {
            val wasVisible = visibleActivities > 0
            visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
            if (wasVisible && visibleActivities == 0) listeners.toList() else emptyList()
        }
        listenersToNotify.forEach { it(false) }
    }

    fun addVisibilityListener(listener: (Boolean) -> Unit): AutoCloseable {
        synchronized(this) { listeners += listener }
        return AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    @Synchronized fun isVisible(): Boolean = visibleActivities > 0
}
