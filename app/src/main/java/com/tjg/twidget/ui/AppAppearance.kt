package com.tjg.twidget.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.tjg.twidget.data.TwidgetStore

/** App colours and typography are independent of widget appearance. */
object AppAppearance {
    private const val KEY = "app_night_mode"
    private const val FONT_KEY = "app_font_family"
    private const val LOGO_KEY = "app_logo_style"

    fun logo(context: Context): String = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        .getString(LOGO_KEY, TwidgetStore.LOGO_TWITTER)
        .takeIf { it == TwidgetStore.LOGO_X } ?: TwidgetStore.LOGO_TWITTER

    fun logoDrawable(context: Context): Int = if (logo(context) == TwidgetStore.LOGO_X)
        com.tjg.twidget.R.drawable.ic_logo_x else com.tjg.twidget.R.drawable.ic_logo_twitter

    fun setLogo(context: Context, logo: String) {
        require(logo == TwidgetStore.LOGO_X || logo == TwidgetStore.LOGO_TWITTER)
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val ids = manager.installedProviders.filter { it.provider.packageName == context.packageName }
            .flatMap { manager.getAppWidgetIds(it.provider).asIterable() }
        setLogoForWidgets(context, logo, ids)
    }

    internal fun setLogoForWidgets(context: Context, logo: String, ids: List<Int>) {
        require(logo == TwidgetStore.LOGO_X || logo == TwidgetStore.LOGO_TWITTER)
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        // Snapshot inherited logos before changing the default; explicit choices stay intact.
        ids.forEach { id ->
            if (!prefs.contains("widget_logo_$id"))
                edit.putString("widget_logo_$id", TwidgetStore.widgetSettings(context, id).logo)
        }
        edit.putString(LOGO_KEY, logo).putString("widget_logo", logo).apply()
    }

    enum class Font(val value: String) {
        DEFAULT("default"), GOOGLE_SANS_FLEX("google_sans_flex"), SYSTEM("system")
    }

    fun font(context: Context): Font {
        val value = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
            .getString(FONT_KEY, null)
        return Font.entries.firstOrNull { it.value == value } ?: Font.DEFAULT
    }

    fun setFont(context: Context, font: Font) {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
            .edit().putString(FONT_KEY, font.value).apply()
    }

    fun mode(context: Context): Int = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        .getInt(KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        .takeIf { it in setOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES) }
        ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun apply(context: Context) {
        if (!context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).contains(LOGO_KEY))
            setLogo(context, logo(context))
        AppCompatDelegate.setDefaultNightMode(mode(context))
    }
}
