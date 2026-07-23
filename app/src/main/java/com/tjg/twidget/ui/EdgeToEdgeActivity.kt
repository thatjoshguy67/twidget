package com.tjg.twidget.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
    private var fontRoot: ViewGroup? = null
    private val fontLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        fontRoot?.let(TwidgetFonts::applyTo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply after AppCompat installs the themed decor so the parent One UI
        // theme cannot restore an opaque navigation-bar colour afterwards.
        applyEdgeToEdge()
    }

    override fun onContentChanged() {
        fontRoot?.viewTreeObserver?.removeOnGlobalLayoutListener(fontLayoutListener)
        super.onContentChanged()
        fontRoot = findViewById<ViewGroup>(android.R.id.content)?.also { root ->
            TwidgetFonts.applyTo(root)
            root.viewTreeObserver.addOnGlobalLayoutListener(fontLayoutListener)
        }
    }

    override fun onStart() {
        super.onStart()
        TwidgetAppVisibility.activityStarted()
    }

    override fun onStop() {
        TwidgetAppVisibility.activityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        fontRoot?.viewTreeObserver?.removeOnGlobalLayoutListener(fontLayoutListener)
        fontRoot = null
        super.onDestroy()
    }

    /**
     * Insets fixed chrome but leaves the navigation edge available to scrolling
     * content. Callers can use [onNavigationBarInset] to move bottom controls
     * above button or gesture navigation without padding the whole window.
     */
    protected fun applyEdgeToEdgeInsets(
        root: View,
        onNavigationBarInset: (Int) -> Unit = {},
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0
            view.setPadding(safe.left, safe.top, safe.right, ime.bottom)
            // IME insets already include the navigation region on Samsung and
            // several other OEM keyboards. Do not add it to floating chrome twice.
            onNavigationBarInset(if (imeVisible) 0 else safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
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
