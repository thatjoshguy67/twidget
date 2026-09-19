package com.tjg.twidget.main

import android.content.Context
import org.json.JSONArray

/** IDs identify an account's card, not its position or mutable display name. */
internal data class DashboardCardSpec(
    val id: String,
    val label: String,
    val size: DashboardCardSize,
    val legacy: DashboardCardType? = null,
    val account: com.tjg.twidget.social.PlatformAccount? = null,
    val metric: com.tjg.twidget.social.SocialMetric? = null,
)

internal object ProfileDashboardLayout {
    private fun prefs(context: Context) = context.getSharedPreferences("profile_dashboard_layouts", Context.MODE_PRIVATE)
    private fun decode(raw: String?): List<String>? = raw?.let { runCatching {
        val array = JSONArray(it)
        List(array.length()) { i -> array.getString(i) }
    }.getOrNull() }

    // Previously hidden cards stay hidden. Newly connected accounts introduce only their new cards.
    fun resolve(saved: List<String>?, known: Set<String>, available: List<String>, defaults: List<String>): List<String> =
        if (saved == null) defaults.filter { it in available }.distinct()
        else (saved.filter { it in available } + defaults.filter { it !in known && it in available }).distinct()

    fun read(context: Context, profileId: String, available: List<String>, defaults: List<String>): List<String> {
        val store = prefs(context)
        val saved = decode(store.getString(profileId, null))
        val known = decode(store.getString("$profileId:known", null)).orEmpty().toSet()
        val resolved = resolve(saved, known, available, defaults)
        store.edit().putString(profileId, JSONArray(resolved).toString())
            .putString("$profileId:known", JSONArray((known + available).toList()).toString()).apply()
        return resolved
    }
    fun save(context: Context, profileId: String, cards: List<String>) {
        prefs(context).edit().putString(profileId, JSONArray(cards.distinct()).toString()).apply()
    }
    fun reset(context: Context, profileId: String) {
        prefs(context).edit().remove(profileId).remove("$profileId:known").apply()
    }
}
