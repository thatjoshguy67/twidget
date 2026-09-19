package com.tjg.twidget.social

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import org.json.JSONObject

/** Small read-only projection for widget/launcher callbacks; SQL remains on the IO executor. */
object SocialWidgetCache {
    private const val PREFS = "social_widget_projection"
    fun preparePin(context: Context, accountId: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("pin_account", accountId).putLong("pin_until", System.currentTimeMillis() + 5 * 60 * 1000).apply()
    fun pendingPin(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("pin_account", "").orEmpty().takeIf { prefs.getLong("pin_until", 0) > System.currentTimeMillis() }.orEmpty()
    }
    fun clearPin(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pin_account").remove("pin_until").apply()
    fun defaultAccountId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("default_account", "").orEmpty()
    fun defaultProfileId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("default", "").orEmpty()
    fun defaultUsesSocial(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("social_default", false)
    fun defaultSignature(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("signature", "").orEmpty()
    fun signature(profile: SocialProfile, observations: List<MetricObservation>): String =
        "${profile.id}:${profile.membershipVersion}:${profile.accountIds}:${profile.nameAccountId}:${profile.customDisplayName}:${observations.filter { it.accountId in profile.accountIds }.hashCode()}"
    fun publish(context: Context, catalog: SocialCatalog, observations: List<MetricObservation>) {
        val root = JSONObject()
        catalog.accounts.forEach { account ->
            val samples = observations.filter { it.accountId == account.id && it.metric == account.platform.audienceMetric && !it.estimated }
            val latest = samples.maxByOrNull { it.observedAt }
            val baseline = latest?.let { last -> samples.filter { it.observedAt <= last.observedAt - 24 * 60 * 60 * 1000L && it.value != null }.maxByOrNull { it.observedAt } }
            root.put(account.id, JSONObject().put("name", account.displayName).put("handle", account.handle)
                .put("avatar", account.avatarUrl).put("platform", account.platform.storageId).put("url", account.platform.profileUrl(account))
                .put("value", latest?.value ?: JSONObject.NULL).put("at", latest?.observedAt ?: 0)
                .put("approximate", latest?.precision == MetricPrecision.ROUNDED)
                .put("delta", if (latest?.value != null && baseline?.value != null) latest.value - baseline.value else 0))
        }
        val profile = catalog.profiles.firstOrNull { it.id == catalog.defaultProfileId }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("accounts", root.toString())
            .putString("default", profile?.id.orEmpty())
            .putString("default_account", profile?.accountIds?.firstOrNull().orEmpty())
            .putString("signature", profile?.let { signature(it, observations) }.orEmpty())
            .putBoolean("social_default", profile?.let { it.linked || catalog.accountsById.getValue(it.accountIds.first()).platform != SocialPlatform.X } == true)
            .apply()
    }
    private fun entry(context: Context, settings: TwidgetWidgetSettings): JSONObject? = if (settings.socialAccountId.isBlank()) null else runCatching {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("accounts", "{}").orEmpty()).optJSONObject(settings.socialAccountId)
    }.getOrNull()
    fun stats(context: Context, settings: TwidgetWidgetSettings): ProfileStats {
        if (settings.socialAccountId.isBlank()) return TwidgetStore.currentStats(context, settings.accountUsername.ifBlank { TwidgetStore.settings(context).username })
        val data = entry(context, settings)
        return ProfileStats(data?.optString("name").orEmpty(), data?.optString("handle").orEmpty(), data?.optLong("value") ?: 0, 0, 0, 0,
            profileImage = data?.optString("avatar").orEmpty(), syncedAt = data?.optLong("at") ?: 0,
            followersKnown = data != null && !data.isNull("value"), followingKnown = false, postsKnown = false, likesKnown = false)
    }
    fun delta(context: Context, settings: TwidgetWidgetSettings): Long = if (settings.socialAccountId.isBlank())
        TwidgetStore.followersDelta(context, settings.accountUsername.ifBlank { TwidgetStore.settings(context).username }) else entry(context, settings)?.optLong("delta") ?: 0
    fun platform(context: Context, settings: TwidgetWidgetSettings): SocialPlatform = entry(context, settings)?.optString("platform")
        ?.let { runCatching { SocialPlatform.fromStorageId(it) }.getOrNull() } ?: SocialPlatform.X
    fun logo(context: Context, settings: TwidgetWidgetSettings): Int = platform(context, settings).let {
        if (it == SocialPlatform.X) if (settings.logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x else it.iconRes
    }
    fun label(context: Context, settings: TwidgetWidgetSettings): Int = platform(context, settings).audienceMetric.labelRes
    fun url(context: Context, settings: TwidgetWidgetSettings): String = entry(context, settings)?.optString("url")
        ?: "https://x.com/${android.net.Uri.encode(settings.accountUsername.ifBlank { TwidgetStore.settings(context).username })}"
    fun approximate(context: Context, settings: TwidgetWidgetSettings): Boolean = entry(context, settings)?.optBoolean("approximate") == true
}
