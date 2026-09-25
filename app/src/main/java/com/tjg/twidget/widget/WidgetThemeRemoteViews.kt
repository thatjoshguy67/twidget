package com.tjg.twidget.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings

/** Let the launcher choose the theme even while the app process is stopped. */
internal fun widgetArtworkVariants(settings: TwidgetWidgetSettings): Int =
    if (Build.VERSION.SDK_INT >= 31 && settings.colorMode == TwidgetStore.COLOR_MODE_SYSTEM) 2 else 1

internal fun RemoteViews.setWidgetArtwork(
    viewId: Int,
    settings: TwidgetWidgetSettings,
    render: (dark: Boolean) -> Bitmap,
) {
    if (Build.VERSION.SDK_INT >= 31 && widgetArtworkVariants(settings) == 2) {
        setIcon(viewId, "setImageIcon", Icon.createWithBitmap(render(false)), Icon.createWithBitmap(render(true)))
    } else {
        setImageViewBitmap(viewId, render(widgetUsesDarkTheme(settings.colorMode)))
    }
}

@RequiresApi(31)
internal fun RemoteViews.setWidgetBackgroundTint(context: Context, settings: TwidgetWidgetSettings) {
    fun tint(dark: Boolean) = ColorStateList.valueOf(WidgetColors.resolve(context, settings, dark).background)
    if (widgetArtworkVariants(settings) == 2) {
        setColorStateList(android.R.id.background, "setBackgroundTintList", tint(false), tint(true))
    } else {
        setColorStateList(android.R.id.background, "setBackgroundTintList", tint(widgetUsesDarkTheme(settings.colorMode)))
    }
}

/** A slower in-flight render must not overwrite a newer launcher allocation. */
@Suppress("DEPRECATION")
internal fun widgetSizeOptionsMatch(first: android.os.Bundle, second: android.os.Bundle): Boolean {
    val keys = listOf(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
        android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
        android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
        android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
    return keys.all { first.getInt(it) == second.getInt(it) } &&
        first.getParcelableArrayList<android.util.SizeF>(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES) ==
        second.getParcelableArrayList<android.util.SizeF>(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES)
}
