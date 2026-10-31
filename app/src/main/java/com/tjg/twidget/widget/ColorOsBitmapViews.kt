package com.tjg.twidget.widget

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetWidgetSettings

/** Reuses existing artwork with ColorOS resize handling and an optional fallback surface. */
internal object ColorOsBitmapViews {
    fun create(context: Context, width: Int, height: Int, settings: TwidgetWidgetSettings,
        description: String, onTap: PendingIntent?, paintSurface: Boolean, bitmapBudgetCap: Long = 5_000_000L,
        render: (Boolean, Long) -> Bitmap): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_coloros_bitmap_surface).apply {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                setViewLayoutWidth(android.R.id.background, width.coerceAtLeast(1).toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutHeight(android.R.id.background, height.coerceAtLeast(1).toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                if (paintSurface) setWidgetBackgroundTint(context, settings, R.id.coloros_blur_target)
                else setViewVisibility(R.id.coloros_blur_target, View.GONE)
            }
            val budget = remoteViewsBitmapBudget(context, bitmapBudgetCap) / widgetArtworkVariants(settings)
            ColorOsLayeredArtwork.bind(this, settings) { render(it, budget) }
            setContentDescription(android.R.id.background, description)
            if (onTap != null) setOnClickPendingIntent(android.R.id.background, onTap)
        }
}
