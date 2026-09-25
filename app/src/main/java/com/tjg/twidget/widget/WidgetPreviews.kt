package com.tjg.twidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tjg.twidget.BuildConfig
import com.tjg.twidget.R
import com.tjg.twidget.brief.*
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings

/** Generated picker previews share the live Canvas renderer; launchers cannot replace the fonts. */
internal object WidgetPreviews {
    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val settings = TwidgetStore.widgetSettings(context)
        val style = settings.style
        val dark = widgetUsesDarkTheme(settings.colorMode)
        val colors = WidgetColors.resolve(context, settings, dark)
        val fingerprint = "${BuildConfig.VERSION_NAME}:$settings:$dark:$colors:${context.resources.configuration.locales.toLanguageTags()}"
        val prefs = context.getSharedPreferences("widget_previews", Context.MODE_PRIVATE)
        val manager = AppWidgetManager.getInstance(context)
        for (brief in listOf(false, true)) {
            val key = if (brief) "brief" else "followers"
            if (prefs.getString(key, null) == fingerprint) continue
            val provider = ComponentName(context, if (brief) TwidgetBriefWidget::class.java else com.tjg.twidget.TwidgetWidget::class.java)
            // Android rate-limits preview publication. Keep the XML fallback until a later launch can retry.
            val accepted = runCatching {
                manager.setWidgetPreview(provider, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                    views(context, settings, brief))
            }.getOrDefault(false)
            if (accepted) prefs.edit().putString(key, fingerprint).apply()
        }
    }

    @RequiresApi(35)
    internal fun views(context: Context, defaults: TwidgetWidgetSettings, brief: Boolean): RemoteViews {
        val density = context.resources.displayMetrics.density
        val settings = defaults.copy(accountUsername = "twidget", showDelta = false)
        val style = settings.style
        val title = context.getString(R.string.brief_preview_title)
        val body = context.getString(R.string.brief_preview_body)
        val snapshot = BriefSnapshot("twidget", 0, 0, 0, 0, 7671, 0, 0, 0, 0,
            listOf(BriefCard("preview", BriefCardType.POST, title, body, 0)),
            headline = title, subheading = body, shortDescription = body, topFollowerRanks = emptyMap(),
            language = BriefStrings.from(context).languageTag)
        return RemoteViews(listOf(162 to 76, 352 to 76, 162 to 176, 352 to 176).associate { (w, h) ->
            fun render(artworkDark: Boolean): android.graphics.Bitmap {
                val colors = WidgetColors.resolve(context, settings, artworkDark)
                return if (brief) BriefWidgetArtworkRenderer.render(context,
                    (w * density).toInt(), (h * density).toInt(), "", snapshot, artworkDark, settings.fontFamily,
                    style = style, background = colors.background)
                else WidgetArtworkRenderer.render(context, (w * density).toInt(), (h * density).toInt(),
                    ProfileStats("Twidget", "twidget", 7671, 0, 0, 0), settings,
                    TwidgetWidget.layoutModeForAosp(w, h), artworkDark, drawBackground = true)
            }
            SizeF(w.toFloat(), h.toFloat()) to RemoteViews(context.packageName, R.layout.widget_generated_preview).apply {
                setWidgetArtwork(R.id.widget_preview_artwork, settings, ::render)
            }
        })
    }
}
