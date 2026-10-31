package com.tjg.twidget.widget

import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetWidgetSettings

/** ColorOS adapter; existing One UI/Material artwork is reused when selected on its native provider. */
internal object ColorOsFollowersViews {
    fun create(context: Context, width: Int, height: Int, settings: TwidgetWidgetSettings,
        stats: ProfileStats, delta: Long, tap: PendingIntent?, nativeBlur: Boolean, bitmapBudgetCap: Long = 5_000_000L): RemoteViews {
        return createStyled(context, width, height,
            com.tjg.twidget.widget.coloros.ColorOsWidgetSupport.systemAppearance(settings), stats, delta, tap, nativeBlur, bitmapBudgetCap)
    }

    private fun createStyled(context: Context, width: Int, height: Int, settings: TwidgetWidgetSettings,
        stats: ProfileStats, delta: Long, tap: PendingIntent?, nativeBlur: Boolean, bitmapBudgetCap: Long): RemoteViews {
        val colorOs = settings.style == WidgetStyle.COLOR_OS
        if (colorOs && height > 110 && settings.fontFamily == com.tjg.twidget.data.TwidgetStore.FONT_SYSTEM)
            return ColorOsNativeFollowerViews.create(context, width, height, settings, stats, delta, tap, nativeBlur)
        val density = context.resources.displayMetrics.density
        val locale = com.tjg.twidget.core.AppLocales.resolve(settings.language)
        val localized = com.tjg.twidget.core.AppLocales.wrap(context, settings.language)
        val count = com.tjg.twidget.core.AppLocales.integer(stats.followersCount, locale)
        val noun = localized.resources.getQuantityString(com.tjg.twidget.R.plurals.widget_count_followers,
            com.tjg.twidget.brief.BriefStrings.quantity(stats.followersCount))
        val change = if (settings.showDelta && delta != 0L) com.tjg.twidget.data.TwidgetStore.signedNumber(delta, locale) else ""
        return ColorOsBitmapViews.create(context, width, height, settings,
            "$count $noun $change @${stats.userName}".trim(), tap, colorOs, bitmapBudgetCap,
            nativeBlur = colorOs && nativeBlur) { dark, budget ->
            WidgetArtworkRenderer.render(context, (width * density).toInt(), (height * density).toInt(),
                stats, settings, TwidgetWidget.layoutModeForAosp(width, height), dark, delta,
                drawBackground = !colorOs, bitmapBudgetBytes = budget)
        }
    }

    fun createResponsive(context: Context, width: Int, height: Int, settings: TwidgetWidgetSettings,
        stats: ProfileStats, delta: Long, tap: PendingIntent?, nativeBlur: Boolean): RemoteViews =
        ColorOsResponsiveViews.wrap(context, width, height,
            create(context, width, height, settings, stats, delta, tap, nativeBlur)) { w, h ->
            create(context, w, h, settings, stats, delta, tap, nativeBlur, 1_000_000L)
        }
}
