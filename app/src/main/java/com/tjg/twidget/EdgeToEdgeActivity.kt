package com.tjg.twidget

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.oneuiproject.oneui.utils.applyEdgeToEdge

/**
 * Keeps every app window edge-to-edge on all supported Android versions.
 * One UI layouts consume the relevant insets themselves; custom layouts apply
 * their own padding while their backgrounds continue beneath both system bars.
 */
abstract class EdgeToEdgeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply after AppCompat installs the themed decor so the parent One UI
        // theme cannot restore an opaque navigation-bar colour afterwards.
        applyEdgeToEdge()
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
            view.setPadding(safe.left, safe.top, safe.right, ime.bottom)
            onNavigationBarInset(safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
