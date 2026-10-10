package com.tjg.twidget.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings

/** Let the launcher choose the theme even while the app process is stopped. */
internal fun widgetArtworkVariants(settings: TwidgetWidgetSettings): Int =
    if (Build.VERSION.SDK_INT >= 31 && settings.colorMode == TwidgetStore.COLOR_MODE_SYSTEM) 2 else 1

/**
 * Apps targeting API 37 crash when one [RemoteViews] update holds more bitmap memory than
 * 1.5 x screen width x screen height x 4 bytes. Stay at three quarters of that so the other
 * views' bitmaps fit too.
 */
internal fun remoteViewsBitmapBudget(context: Context, cap: Long): Long {
    val metrics = context.resources.displayMetrics
    val platformLimit = metrics.widthPixels.toLong() * metrics.heightPixels * 4L * 3L / 2L
    return minOf(cap, platformLimit * 3L / 4L)
}

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

/** Scale the canvas so text, spacing and layout selection still use the requested widget size. */
internal fun widgetArtworkSurface(width: Int, height: Int, budgetBytes: Long): Pair<Bitmap, Canvas> {
    val size = widgetBitmapSize(width, height, budgetBytes)
    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply {
        scale(size.width.toFloat() / width, size.height.toFloat() / height)
    }
    return bitmap to canvas
}
