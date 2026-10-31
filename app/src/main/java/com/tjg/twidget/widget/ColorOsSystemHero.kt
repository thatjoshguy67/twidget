package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings

/** A transparent count layer, independent of the launcher's native blur surface and footer. */
internal object ColorOsSystemHero {
    fun render(context: Context, settings: TwidgetWidgetSettings, words: List<String>,
        countWords: Int, delta: Long, width: Int, height: Int, dark: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val w = ((width - 24) * density).toInt().coerceAtLeast(2)
        val h = ((height - 46) * density).toInt().coerceAtLeast(2)
        val budget = remoteViewsBitmapBudget(context, 8_000_000L) / widgetArtworkVariants(settings)
        val (bitmap, canvas) = widgetArtworkSurface(w, h, budget)
        val colors = WidgetColors.resolve(context, settings, dark)
        // Fit and draw the paragraph together. Vendor host text adjustments must not
        // truncate individual words or change the shared system-font emphasis rules.
        WidgetArtworkRenderer.followerHero(context, settings.copy(fontFamily = TwidgetStore.FONT_SYSTEM),
            words, countWords, colors.primary, colors.secondary, colors.deltaColor(delta, dark))
            .draw(canvas, w.toFloat(), h.toFloat(), 0f,
                (if (width - 24 < 230) 4f else 6f) * density, 4f * density)
        return bitmap
    }

    fun bind(views: RemoteViews, context: Context, settings: TwidgetWidgetSettings, words: List<String>,
        countWords: Int, delta: Long, width: Int, height: Int) {
        if (Build.VERSION.SDK_INT >= 31 && widgetArtworkVariants(settings) == 2) {
            // Bitmap actions share the RemoteViews bitmap cache across size variants.
            // Day/night Icon actions count each copy separately against the host budget.
            views.setImageViewBitmap(R.id.coloros_native_hero,
                render(context, settings, words, countWords, delta, width, height, false))
            views.setImageViewBitmap(R.id.coloros_native_hero_night,
                render(context, settings, words, countWords, delta, width, height, true))
            // setColorInt exposes RemoteViews' day/night integer action. Visibility is
            // also an integer setter; this keeps theme selection in the host process.
            views.setColorInt(R.id.coloros_native_hero, "setVisibility", View.VISIBLE, View.GONE)
            views.setColorInt(R.id.coloros_native_hero_night, "setVisibility", View.GONE, View.VISIBLE)
        } else {
            views.setImageViewBitmap(R.id.coloros_native_hero,
                render(context, settings, words, countWords, delta, width, height, widgetUsesDarkTheme(settings.colorMode)))
            views.setViewVisibility(R.id.coloros_native_hero, View.VISIBLE)
            views.setViewVisibility(R.id.coloros_native_hero_night, View.GONE)
        }
    }
}
