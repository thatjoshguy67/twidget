package com.tjg.twidget.widget

import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefSnapshot
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.brief.BriefStrings
import com.tjg.twidget.brief.TwidgetBriefActivity
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.TwidgetWidgetSettings

/** Brief keeps its existing content/tap behavior on an untagged ColorOS fallback surface. */
internal object ColorOsBriefViews {
    fun createResponsive(context: Context, id: Int, width: Int, height: Int, account: String,
        snapshot: BriefSnapshot?, settings: TwidgetWidgetSettings): RemoteViews =
        ColorOsResponsiveViews.wrap(context, width, height,
            create(context, id, width, height, account, snapshot, settings)) { w, h ->
            create(context, id, w, h, account, snapshot, settings, 1_000_000L)
        }

    fun create(context: Context, id: Int, width: Int, height: Int, account: String,
        snapshot: BriefSnapshot?, settings: TwidgetWidgetSettings, bitmapBudgetCap: Long = 5_000_000L): RemoteViews {
        val localized = AppLocales.wrap(context, settings.language)
        val strings = BriefStrings.from(context, settings.language)
        val summary = BriefStore.presentation(context, account, snapshot, strings)?.summary()
        val description = listOfNotNull(summary?.title, summary?.body).joinToString(". ")
            .ifBlank { localized.getString(R.string.brief_widget_empty_title) }
        val tap = if (account.isBlank()) null else PendingIntent.getActivity(context, 9321 + id,
            TwidgetBriefActivity.intent(context, account), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val density = context.resources.displayMetrics.density
        return ColorOsBitmapViews.create(context, width, height, settings, description, tap, true, bitmapBudgetCap) { dark, budget ->
            BriefWidgetArtworkRenderer.render(localized, (width * density).toInt(), (height * density).toInt(),
                account, snapshot, dark, settings.fontFamily, strings, settings.style,
                background = null, bitmapBudgetBytes = budget)
        }
    }
}
