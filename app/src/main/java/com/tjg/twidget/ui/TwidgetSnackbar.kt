package com.tjg.twidget.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar

/** SESL owns the pill, action button, wrapping, animation and accessibility timeout. */
internal class TwidgetSnackbar(private val activity: AppCompatActivity) : DefaultLifecycleObserver {
    private var current: Snackbar? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    fun show(
        message: CharSequence,
        anchor: View? = null,
        actionText: CharSequence? = null,
        action: (() -> Unit)? = null,
    ): Snackbar? {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
        dismiss()
        return Snackbar.seslMake(
            activity.findViewById(android.R.id.content), message,
            Snackbar.LENGTH_LONG, Snackbar.SESL_SNACKBAR_TYPE_SUGGESTION,
        ).apply {
            anchor?.takeIf { it.isShown }?.let {
                setAnchorView(it)
                setAnchorViewLayoutListenerEnabled(true)
            }
            if (actionText != null && action != null) setAction(actionText) { action() }
            TwidgetFonts.applyTo(view)
            current = this
            show()
        }
    }

    fun dismiss() {
        current?.dismiss()
        current = null
    }

    override fun onStop(owner: LifecycleOwner) = dismiss()
}
