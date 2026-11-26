package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.data.TwidgetStore

/** Debug-only display choices never enter Brief content or AI fingerprints. */
internal data class BriefCardPreviewParameters(val allVariants: Boolean, val values: Map<String, String>) {
    fun index(title: String): Int = values[when (title) {
        "Posting streak" -> "streak"; "Goals" -> "goal"; "Tweet cards" -> "tweet"; "Tweet review states" -> "review"
        else -> ""
    }]?.toIntOrNull() ?: 0

    companion object {
        const val PREFIX = "debug_brief_preview_"
        val choices = linkedMapOf(
            "streak" to ("Posting streak" to listOf("Safe", "Record", "Needs activity", "Expiring", "Restart")),
            "goal" to ("Goal state" to listOf("Set up", "Progress", "Reached", "Slowing", "Unavailable")),
            "tweet" to ("Tweet format" to listOf("Original", "Reply", "Previous year", "Quote", "Images")),
            "review" to ("Review state" to listOf("Complete", "Quote", "Loading", "Generating", "Refreshing", "Stale", "Missing", "Error")),
        )
        fun read(context: Context): BriefCardPreviewParameters {
            val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
            return BriefCardPreviewParameters(prefs.getBoolean(PREFIX + "all", true),
                choices.keys.associateWith { prefs.getString(PREFIX + it, "0") ?: "0" })
        }
        fun background(context: Context) = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX + "background", "auto")?.let { value -> BriefBlueTheme.entries.firstOrNull { it.name == value } }
    }
}
