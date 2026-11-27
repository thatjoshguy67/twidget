package com.tjg.twidget.brief

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.tjg.twidget.data.TwidgetStore

/** Display-only overrides: never alter the clock, stored Brief or generation fingerprints. */
internal object BriefBackdropDebug {
    const val PERIOD_KEY = "debug_brief_backdrop_period"
    const val APPEARANCE_KEY = "debug_brief_backdrop_appearance"
    const val EXTRA_SAVED_PREVIEW = "brief_saved_backdrop_preview"

    fun period(context: Context): BriefBlueTheme? {
        if (!TwidgetStore.debugMenuUnlocked(context)) return null
        val value = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).getString(PERIOD_KEY, "auto")
        return BriefBlueTheme.entries.firstOrNull { it.name == value }
    }

    fun nightMode(context: Context): Int {
        if (!TwidgetStore.debugMenuUnlocked(context)) return AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
        return when (context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).getString(APPEARANCE_KEY, "auto")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
        }
    }
}
