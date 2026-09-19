package com.tjg.twidget.social

import android.content.Context
import com.tjg.twidget.brief.BriefCard
import com.tjg.twidget.brief.BriefCardType
import com.tjg.twidget.brief.BriefEditorialSummary
import org.json.JSONArray
import org.json.JSONObject

/** Profile-specific cache cannot be reused after changing members, sources or content consent. */
object ProfileBriefCache {
    private const val PREFS = "social_brief_cache"
    fun write(context: Context, brief: ProfileBrief, signature: String) {
        val value = JSONObject().put("id", brief.profileId).put("version", brief.membershipVersion).put("name", brief.name)
            .put("signature", signature).put("content", contentSignature(context)).put("at", System.currentTimeMillis())
            .put("cards", JSONArray(brief.cards.map { card -> JSONObject().put("id", card.id).put("title", card.title)
                .put("body", card.body).put("score", card.score).put("source", card.sourceAttribution) }))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(brief.profileId, value.toString()).apply()
    }
    fun readDefault(context: Context): ProfileBrief? = runCatching {
        if (!SocialWidgetCache.defaultUsesSocial(context)) return null
        val id = SocialWidgetCache.defaultProfileId(context)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null) ?: return null
        val value = JSONObject(raw)
        if (value.getString("signature") != SocialWidgetCache.defaultSignature(context) || value.getString("content") != contentSignature(context) ||
            System.currentTimeMillis() - value.getLong("at") !in 0..(24 * 60 * 60 * 1000L)) return null
        val cards = value.getJSONArray("cards")
        ProfileBrief(id, value.getLong("version"), value.getString("name"), List(cards.length()) { index ->
            val card = cards.getJSONObject(index)
            BriefCard(card.getString("id"), BriefCardType.SUMMARY, card.getString("title"), card.getString("body"), card.getInt("score"), sourceAttribution = card.getString("source"))
        })
    }.getOrNull()
    fun summary(context: Context): BriefEditorialSummary? = readDefault(context)?.let {
        BriefEditorialSummary(it.name, it.cards.firstOrNull()?.body.orEmpty())
    }
    private fun contentSignature(context: Context) = context.getSharedPreferences("social_brief_content", Context.MODE_PRIVATE).all.toSortedMap().toString() +
        com.tjg.twidget.brief.BriefSettingsStore.enabledContent(context).sortedBy { it.storageId }.toString()
}
