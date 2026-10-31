package com.tjg.twidget.widget

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetWidgetSettings

/** Theme-aware artwork that shares its bitmap cache across ColorOS resize variants. */
internal object ColorOsLayeredArtwork {
    fun bind(views: RemoteViews, settings: TwidgetWidgetSettings, render: (Boolean) -> Bitmap) {
        if (Build.VERSION.SDK_INT >= 31 && widgetArtworkVariants(settings) == 2) {
            // Bitmap actions share the RemoteViews bitmap cache across size variants.
            // Day/night Icon actions count each copy separately against the host budget.
            views.setImageViewBitmap(R.id.coloros_native_hero,
                render(false))
            views.setImageViewBitmap(R.id.coloros_native_hero_night,
                render(true))
            // setColorInt exposes RemoteViews' day/night integer action. Visibility is
            // also an integer setter; this keeps theme selection in the host process.
            views.setColorInt(R.id.coloros_native_hero, "setVisibility", View.VISIBLE, View.GONE)
            views.setColorInt(R.id.coloros_native_hero_night, "setVisibility", View.GONE, View.VISIBLE)
        } else {
            views.setImageViewBitmap(R.id.coloros_native_hero,
                render(widgetUsesDarkTheme(settings.colorMode)))
            views.setViewVisibility(R.id.coloros_native_hero, View.VISIBLE)
            views.setViewVisibility(R.id.coloros_native_hero_night, View.GONE)
        }
    }
}
