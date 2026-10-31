package com.tjg.twidget.widget.coloros

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build

/** Detect the vendor framework, not the phone manufacturer (including guest ROMs). */
internal object ColorOsWidgetSupport {
    /** ColorOS material and contrast follow the system; layout choices stay per widget. */
    fun systemAppearance(settings: com.tjg.twidget.data.TwidgetWidgetSettings) =
        if (available && settings.style == com.tjg.twidget.widget.WidgetStyle.COLOR_OS)
            settings.copy(tintAlpha = 205, colorMode = com.tjg.twidget.data.TwidgetStore.COLOR_MODE_SYSTEM)
        else settings
    val isColorOs: Boolean by lazy {
        listOf("com.oplus.os.OplusBuild", "com.oppo.os.OppoBuild").any {
            runCatching { Class.forName(it, false, ColorOsWidgetSupport::class.java.classLoader) }.isSuccess
        }
    }
    val available: Boolean get() = Build.VERSION.SDK_INT >= 31 && isColorOs

    fun followersProvider(context: Context): ComponentName = if (available)
        ComponentName(context, ColorOsWidgetBlurProbe.PROVIDER)
    else ComponentName(context, com.tjg.twidget.TwidgetWidget::class.java)

    /** Picker metadata changes preserve provider identities and already-bound widget ids. */
    fun reconcilePicker(context: Context) {
        if (Build.VERSION.SDK_INT < 28) return
        runCatching { reconcilePickerInternal(context) }
            .onFailure { android.util.Log.w("ColorOsWidgets", "Unable to refresh widget picker metadata", it) }
    }

    @androidx.annotation.RequiresApi(28)
    private fun reconcilePickerInternal(context: Context) {
        val prefs = context.getSharedPreferences("coloros_widget_picker", Context.MODE_PRIVATE)
        val nativeAvailable = available
        // Fresh One UI/Pixel installations never have their provider metadata changed.
        // If an OS migration shares widget data, undo only our own earlier overrides.
        if (!nativeAvailable && !prefs.getBoolean("adapted", false)) return
        val manager = AppWidgetManager.getInstance(context)
        val providers = manager.installedProviders.associateBy { it.provider }
        val legacy = ComponentName(context, com.tjg.twidget.TwidgetWidget::class.java)
        val native = ComponentName(context, ColorOsWidgetBlurProbe.PROVIDER)
        if (!nativeAvailable) {
            if (providers.containsKey(legacy)) manager.updateAppWidgetProviderInfo(legacy, null)
            if (providers.containsKey(native)) manager.updateAppWidgetProviderInfo(native, null)
            prefs.edit().putBoolean("adapted", false).apply()
            return
        }
        if (!providers.containsKey(native) || !providers.containsKey(legacy)) return
        fun update(component: ComponentName, hidden: Boolean, key: String) {
            val info = providers.getValue(component)
            if ((info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER != 0) != hidden)
                manager.updateAppWidgetProviderInfo(component, key)
        }
        // Record ownership before changing metadata, including interrupted upgrades.
        prefs.edit().putBoolean("adapted", true).commit()
        // Do not disable a receiver: Android would remove its placed widgets.
        update(native, false, VISIBLE_INFO)
        update(legacy, true, HIDDEN_INFO)
    }

    const val VISIBLE_INFO = "com.tjg.twidget.coloros.visible_provider"
    const val HIDDEN_INFO = "com.tjg.twidget.coloros.hidden_provider"
}
