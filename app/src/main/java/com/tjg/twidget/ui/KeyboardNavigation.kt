package com.tjg.twidget.ui

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.View
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.WindowCallbackWrapper
import com.tjg.twidget.R

/** Screen-scoped shortcuts leave text editing and normal platform focus traversal intact. */
internal class KeyboardNavigation private constructor(private val activity: Activity) : InputManager.InputDeviceListener {
    private data class Action(val label: Int, val key: Int, val modifiers: Int, val enabled: () -> Boolean, val run: () -> Unit)
    private val actions = linkedMapOf<String, Action>()
    private val input = activity.getSystemService(InputManager::class.java)
    private var help: AlertDialog? = null
    private fun wrap(callback: android.view.Window.Callback) = object : WindowCallbackWrapper(callback) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (handle(event)) return true
            return super.dispatchKeyEvent(event)
        }
        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = handle(event) || super.dispatchKeyShortcutEvent(event)
        override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
            val prepared = super.onPreparePanel(featureId, view, menu)
            if (featureId == 0) {
                val item = menu.findItem(R.id.floating_shortcuts_action) ?: menu.add(Menu.NONE,
                    R.id.floating_shortcuts_action, Menu.CATEGORY_SECONDARY, R.string.floating_shortcut_hint).apply {
                    setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    setOnMenuItemClickListener { showHelp(); true }
                }
                item.isVisible = hasKeyboard()
            }
            return prepared
        }
        override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
            super.onProvideKeyboardShortcuts(data, menu, deviceId)
            data.add(KeyboardShortcutGroup(activity.getString(R.string.app_name), actions.values.filter { it.enabled() }.map {
                KeyboardShortcutInfo(activity.getString(it.label), it.key, it.modifiers)
            } + KeyboardShortcutInfo(activity.getString(R.string.floating_shortcuts), KeyEvent.KEYCODE_SLASH, KeyEvent.META_CTRL_ON)))
        }
    }
    private var wrapper = wrap(activity.window.callback)
    private fun ensureInstalled() {
        // AppCompat can install its toolbar callback after Activity.onCreate.
        if (activity.window.callback !== wrapper) {
            wrapper = wrap(activity.window.callback)
            activity.window.callback = wrapper
        }
    }
    init {
        activity.window.callback = wrapper
        input.registerInputDeviceListener(this, android.os.Handler(activity.mainLooper))
        SeslKeyboardFocus.observeWindow(activity.window.decorView)
    }
    private fun hasKeyboard(): Boolean = activity.resources.configuration.let {
        it.keyboard == Configuration.KEYBOARD_QWERTY && it.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
    } || input.inputDeviceIds.any { id -> input.getInputDevice(id)?.let { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true }
    private fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_SLASH && event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            if (event.repeatCount == 0) showHelp()
            return true
        }
        val action = actions.values.firstOrNull { event.keyCode == it.key && event.hasModifiers(it.modifiers) && it.enabled() }
        if (action != null) { if (event.repeatCount == 0) action.run(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.hasNoModifiers() && activity is ComponentActivity) {
            if (event.repeatCount == 0) activity.onBackPressedDispatcher.onBackPressed()
            return true
        }
        return false
    }
    private fun showHelp() {
        if (help?.isShowing == true) return
        val text = activity.getString(R.string.floating_shortcut_navigation) + "\n\n" +
            actions.values.filter { it.enabled() }.joinToString("\n") { activity.getString(it.label) }
        help = AlertDialog.Builder(activity).setTitle(R.string.floating_shortcuts).setMessage(text)
            .setPositiveButton(android.R.string.ok, null).show().also {
                it.window?.decorView?.let(SeslKeyboardFocus::observeWindow)
                it.setOnDismissListener { help = null }
            }
    }
    override fun onInputDeviceAdded(deviceId: Int) { activity.invalidateOptionsMenu() }
    override fun onInputDeviceRemoved(deviceId: Int) { activity.invalidateOptionsMenu() }
    override fun onInputDeviceChanged(deviceId: Int) { activity.invalidateOptionsMenu() }
    private fun dispose() {
        input.unregisterInputDeviceListener(this); help?.dismiss()
        if (activity.window.callback === wrapper) activity.window.callback = wrapper.wrapped
        if (activity.window.decorView.getTag(R.id.floating_keyboard_controller) === this) {
            activity.window.decorView.setTag(R.id.floating_keyboard_controller, null)
        }
    }
    companion object {
        fun of(activity: Activity): KeyboardNavigation {
            val decor = activity.window.decorView
            val existing = decor.getTag(R.id.floating_keyboard_controller) as? KeyboardNavigation
            if (existing != null && existing.activity !== activity) existing.dispose()
            return (existing?.takeIf { it.activity === activity } ?: KeyboardNavigation(activity).also {
                decor.setTag(R.id.floating_keyboard_controller, it)
            }).also { it.ensureInstalled() }
        }
        fun register(activity: Activity, id: String, label: Int, key: Int, modifiers: Int = KeyEvent.META_CTRL_ON,
            enabled: () -> Boolean = { true }, run: () -> Unit) {
            of(activity).actions[id] = Action(label, key, modifiers, enabled, run)
        }
        fun install(application: Application) {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) { of(activity) }
                override fun onActivityStarted(activity: Activity) { of(activity); SeslKeyboardFocus.observeWindow(activity.window.decorView) }
                override fun onActivityResumed(activity: Activity) { of(activity) }
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    (activity.window.decorView.getTag(R.id.floating_keyboard_controller) as? KeyboardNavigation)?.takeIf { it.activity === activity }?.dispose()
                }
            })
        }
    }
}
