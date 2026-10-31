package com.tjg.twidget.widget

import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings

/** ColorOS Followers surface, with native blur or an untagged fallback background. */
internal object ColorOsNativeFollowerViews {
    fun layout(height: Int, nativeBlur: Boolean = true) = if (nativeBlur) {
        if (height <= 110) R.layout.widget_coloros_host_blur_compact else R.layout.widget_coloros_host_blur_paragraph_live
    } else {
        if (height <= 110) R.layout.widget_coloros_followers_fallback_compact else R.layout.widget_coloros_followers_fallback
    }

    /** The framework selects these against its live viewport, not ColorOS's virtual size options. */
    fun createResponsive(context: Context, width: Int, height: Int, settings: TwidgetWidgetSettings,
        stats: ProfileStats, delta: Long, onTap: PendingIntent?, nativeBlur: Boolean = true): RemoteViews {
        return ColorOsResponsiveViews.wrap(context, width, height,
            create(context, width, height, settings, stats, delta, onTap, nativeBlur)) { w, h ->
            create(context, w, h, settings, stats, delta, onTap, nativeBlur)
        }
    }

    fun create(context: Context, width: Int, height: Int, settings: TwidgetWidgetSettings,
        stats: ProfileStats, delta: Long, onTap: PendingIntent?, nativeBlur: Boolean = true): RemoteViews {
        val compact = height <= 110
        val localized = AppLocales.wrap(context, settings.language)
        val locale = AppLocales.resolve(settings.language)
        val number = AppLocales.integer(stats.followersCount, locale)
        val count = if (compact) number else TwidgetWidget.followersInWords(stats.followersCount, locale)
        val noun = localized.resources.getQuantityString(R.plurals.widget_count_followers,
            com.tjg.twidget.brief.BriefStrings.quantity(stats.followersCount))
        val change = if (settings.showDelta && delta != 0L) TwidgetStore.signedNumber(delta, locale) else ""
        val visibleChange = change.isNotEmpty() && (!compact || width >= 180)
        return RemoteViews(context.packageName, layout(height, nativeBlur)).apply {
            if (Build.VERSION.SDK_INT >= 31) {
                // The ColorOS optimiser enlarges match-parent roots before layout,
                // but blur-allowlisted providers skip its compensating scale-down.
                // Explicit host dimensions bypass that measurement multiplier.
                // These bounds are confined to ColorOS surfaces and their previews.
                setViewLayoutWidth(android.R.id.background, width.coerceAtLeast(1).toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutHeight(android.R.id.background, height.coerceAtLeast(1).toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }
            if (compact) {
                setTextViewText(R.id.coloros_native_count, count)
                setTextViewText(R.id.coloros_native_label, noun)
                setTextViewText(R.id.coloros_native_delta, change)
                setViewVisibility(R.id.coloros_native_delta, if (visibleChange) View.VISIBLE else View.GONE)
            } else {
                val tokens = count.split(Regex("\\s+")).filter { it.isNotBlank() }
                ColorOsSystemHero.bind(this, context,
                    if (nativeBlur) settings.copy(fontFamily = TwidgetStore.FONT_SYSTEM) else settings, tokens + noun + listOfNotNull(change.takeIf { it.isNotEmpty() }),
                    tokens.size, delta, width, height)
            }
            setTextViewText(R.id.coloros_native_account, "@${stats.userName}")
            setImageViewResource(R.id.coloros_native_logo,
                if (settings.logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x)
            setViewVisibility(R.id.widget_loading, View.GONE)
            setContentDescription(android.R.id.background, "$number $noun $change @${stats.userName}".trim())
            if (onTap != null) setOnClickPendingIntent(android.R.id.background, onTap)
            fun colors(dark: Boolean) = WidgetColors.resolve(context, settings, dark)
            val systemTheme = settings.colorMode == TwidgetStore.COLOR_MODE_SYSTEM
            val dark = widgetUsesDarkTheme(settings.colorMode)
            fun textColor(id: Int, pick: (Boolean) -> Int) {
                if (Build.VERSION.SDK_INT >= 31 && systemTheme) {
                    setColorStateList(id, "setTextColor", ColorStateList.valueOf(pick(false)), ColorStateList.valueOf(pick(true)))
                } else setTextColor(id, pick(dark))
            }
            if (compact) {
                textColor(R.id.coloros_native_count) { colors(it).primary }
                textColor(R.id.coloros_native_label) { colors(it).secondary }
                textColor(R.id.coloros_native_delta) { colors(it).deltaColor(delta, it) }
            }
            textColor(R.id.coloros_native_account) { colors(it).secondary }
            if (Build.VERSION.SDK_INT >= 31) {
                setWidgetBackgroundTint(context, settings, R.id.coloros_blur_target)
                if (systemTheme) setColorStateList(R.id.coloros_native_logo, "setImageTintList",
                    ColorStateList.valueOf(colors(false).secondary), ColorStateList.valueOf(colors(true).secondary))
                else setColorStateList(R.id.coloros_native_logo, "setImageTintList", ColorStateList.valueOf(colors(dark).secondary))
            } else {
                setInt(R.id.coloros_blur_target, "setBackgroundColor", colors(dark).background)
                setInt(R.id.coloros_native_logo, "setColorFilter", colors(dark).secondary)
            }
        }
    }
}
