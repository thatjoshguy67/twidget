package com.tjg.twidget.ui

import android.content.Context

/**
 * Notifies visible activities when theme mode or accent changes so they can
 * [android.app.Activity.recreate] and pick up new theme resources.
 */
object TwidgetThemeChanges {
    private val listeners = linkedSetOf<(Context) -> Unit>()

    fun addListener(listener: (Context) -> Unit): AutoCloseable {
        synchronized(this) { listeners += listener }
        return AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    fun notifyChanged(context: Context) {
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { it(context.applicationContext) }
    }
}
