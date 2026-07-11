package com.tjg.twidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

class TwidgetWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    // Covers widgets added straight from the picker without opening the app.
    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        const val LAYOUT_MODE_LARGE = 0
        const val LAYOUT_MODE_COMPACT_2X1 = 1
        const val LAYOUT_MODE_COMPACT_STRIP = 2
        const val LAYOUT_MODE_COMPACT_SQUARE = 3

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TwidgetWidget::class.java))
            ids.forEach { updateWidget(context, manager, it) }
            LockScreenFollowerViews.updateAll(context)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 260)
            // AOSP launchers use minWidth/maxHeight for the portrait allocation
            // and maxWidth/minHeight for landscape. Rendering minWidth/minHeight
            // unconditionally creates a landscape-shaped bitmap that Pixel
            // Launcher stretches vertically. Keep Samsung's established sizing
            // path unchanged because One UI supplies its own span metadata and
            // hosts the RemoteViews blur implementation.
            val artworkWidth = if (!TwidgetFonts.hasSystemOneUiSans &&
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            ) options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth) else minWidth
            val artworkHeight = if (!TwidgetFonts.hasSystemOneUiSans &&
                context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            ) options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight) else minHeight
            val mode = if (TwidgetFonts.hasSystemOneUiSans) {
                layoutMode(options)
            } else {
                layoutModeForAosp(artworkWidth, artworkHeight)
            }
            val widgetSettings = TwidgetStore.widgetSettings(context, appWidgetId)
            val account = widgetSettings.accountUsername.ifBlank { TwidgetStore.settings(context).username }
            val stats = TwidgetStore.currentStats(context, account)
            val delta = TwidgetStore.followersDelta(context, account)

            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !TwidgetFonts.hasSystemOneUiSans) {
                val responsiveViews = responsiveSpecs(artworkWidth, artworkHeight).associate { spec ->
                    SizeF(spec.minWidth.toFloat(), spec.minHeight.toFloat()) to createRemoteViews(
                        context = context,
                        appWidgetId = appWidgetId,
                        width = spec.renderWidth,
                        height = spec.renderHeight,
                        mode = spec.mode,
                        widgetSettings = widgetSettings,
                        account = account,
                        stats = stats,
                        delta = delta,
                        drawArtworkBackground = true,
                    )
                }
                RemoteViews(responsiveViews)
            } else {
                createRemoteViews(
                    context = context,
                    appWidgetId = appWidgetId,
                    width = artworkWidth,
                    height = artworkHeight,
                    mode = mode,
                    widgetSettings = widgetSettings,
                    account = account,
                    stats = stats,
                    delta = delta,
                    drawArtworkBackground = !TwidgetFonts.hasSystemOneUiSans,
                )
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun createRemoteViews(
            context: Context,
            appWidgetId: Int,
            width: Int,
            height: Int,
            mode: Int,
            widgetSettings: TwidgetWidgetSettings,
            account: String,
            stats: ProfileStats,
            delta: Long,
            drawArtworkBackground: Boolean,
        ): RemoteViews {
            // Launchers can replace RemoteViews font families—even Samsung's
            // own `sec` family—so every size renders its text as artwork.
            return RemoteViews(context.packageName, layoutResource(mode, renderAsArtwork = true)).apply {
                val dark = isDark(context, widgetSettings)
                val base = if (dark) 16 else 255
                setInt(
                    android.R.id.background,
                    "setBackgroundColor",
                    if (drawArtworkBackground) Color.TRANSPARENT else Color.argb(widgetSettings.tintAlpha, base, base, base),
                )
                // A full update must re-hide the spinner explicitly: the launcher
                // keeps the VISIBLE state a tap-refresh partial update set, so
                // relying on the layout's gone default leaves it stuck spinning.
                setViewVisibility(R.id.widget_loading, View.GONE)
                setImageViewBitmap(
                    R.id.widget_artwork,
                    WidgetArtworkRenderer.render(
                        context = context,
                        widthPx = dp(context, width),
                        heightPx = dp(context, height),
                        stats = stats,
                        settings = widgetSettings,
                        mode = mode,
                        dark = dark,
                        delta = delta,
                        drawBackground = drawArtworkBackground,
                    ),
                )
                setOnClickPendingIntent(android.R.id.background, tapIntent(context, appWidgetId, widgetSettings.tapAction, account))
            }
        }

        data class ResponsiveSpec(
            val minWidth: Int,
            val minHeight: Int,
            val renderWidth: Int,
            val renderHeight: Int,
            val mode: Int,
        )

        fun responsiveSpecs(currentWidth: Int? = null, currentHeight: Int? = null): List<ResponsiveSpec> = listOf(
            ResponsiveSpec(110, 40, 179, 72, LAYOUT_MODE_COMPACT_2X1),
            ResponsiveSpec(231, 40, 360, 72, LAYOUT_MODE_COMPACT_STRIP),
            ResponsiveSpec(110, 111, 179, 179, LAYOUT_MODE_LARGE),
            ResponsiveSpec(231, 111, 360, 210, LAYOUT_MODE_LARGE),
        ).map { spec ->
            if (currentWidth != null && currentHeight != null &&
                currentWidth >= spec.minWidth && currentHeight >= spec.minHeight &&
                (spec.minWidth > 110 || currentWidth < 231) &&
                (spec.minHeight > 40 || currentHeight < 111)
            ) {
                spec.copy(renderWidth = currentWidth, renderHeight = currentHeight)
            } else {
                spec
            }
        }

        // Whichever layout a widget is currently showing — used for both the
        // full render and the tap-refresh spinner so the partial update targets
        // the right view hierarchy.
        private fun layoutResource(mode: Int, renderAsArtwork: Boolean): Int = when {
            mode == LAYOUT_MODE_LARGE -> R.layout.widget_blur
            mode == LAYOUT_MODE_COMPACT_SQUARE || renderAsArtwork -> R.layout.widget_compact_square
            mode == LAYOUT_MODE_COMPACT_2X1 -> R.layout.widget_compact_2x1
            else -> R.layout.widget_compact_strip
        }

        fun spinnerLayout(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): Int {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 260)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val mode = if (TwidgetFonts.hasSystemOneUiSans) {
                layoutMode(options)
            } else {
                layoutModeForAosp(
                    if (landscape) options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth) else minWidth,
                    if (landscape) minHeight else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight),
                )
            }
            return layoutResource(mode, renderAsArtwork = true)
        }

        fun layoutMode(minWidth: Int, minHeight: Int): Int = when {
            minHeight <= 100 && minWidth <= 170 -> LAYOUT_MODE_COMPACT_2X1
            minHeight <= 100 -> LAYOUT_MODE_COMPACT_STRIP
            minWidth <= 230 && minHeight <= 230 -> LAYOUT_MODE_COMPACT_SQUARE
            else -> LAYOUT_MODE_LARGE
        }

        // Pixel Launcher cells are much wider than they are tall: its 2x2 is
        // about 179x99dp while a wide one-row widget is about 360x48dp. Both
        // 2x1 and 2x2 use Twidget's centered count artwork; only wider one-row
        // allocations use the handle-bearing strip.
        fun layoutModeForAosp(width: Int, height: Int): Int = when {
            width <= 230 && height <= 110 -> LAYOUT_MODE_COMPACT_2X1
            height <= 110 -> LAYOUT_MODE_COMPACT_STRIP
            else -> LAYOUT_MODE_LARGE
        }

        fun layoutMode(options: Bundle): Int {
            val columns = options.getInt("semAppWidgetColumnSpan", 0)
            val rows = options.getInt("semAppWidgetRowSpan", 0)
            val samsungSize = options.getInt("semWidgetSize", 0)
            if (columns > 0 || rows > 0) {
                return when {
                    rows <= 1 && columns <= 2 -> LAYOUT_MODE_COMPACT_2X1
                    rows <= 1 -> LAYOUT_MODE_COMPACT_STRIP
                    columns <= 2 && rows <= 2 -> LAYOUT_MODE_COMPACT_SQUARE
                    else -> LAYOUT_MODE_LARGE
                }
            }
            return when (samsungSize) {
                2 -> LAYOUT_MODE_COMPACT_2X1
                4 -> LAYOUT_MODE_COMPACT_STRIP
                8 -> LAYOUT_MODE_COMPACT_SQUARE
                else -> layoutMode(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 260),
                )
            }
        }

        private fun tapIntent(context: Context, appWidgetId: Int, tapAction: String, accountUsername: String): PendingIntent {
            return when (tapAction) {
                TwidgetStore.TAP_PROFILE -> PendingIntent.getActivity(
                    context,
                    2000 + appWidgetId,
                    Intent(Intent.ACTION_VIEW, Uri.parse("twitter://user?screen_name=${accountUsername.trimStart('@')}")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                TwidgetStore.TAP_APP -> PendingIntent.getActivity(
                    context,
                    3000 + appWidgetId,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                else -> PendingIntent.getBroadcast(
                    context,
                    1000 + appWidgetId,
                    Intent(context, WidgetRefreshReceiver::class.java)
                        .setAction(WidgetRefreshReceiver.ACTION_REFRESH)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

        private fun isDark(context: Context, settings: TwidgetWidgetSettings): Boolean =
            when (settings.colorMode) {
                TwidgetStore.COLOR_MODE_DARK -> true
                TwidgetStore.COLOR_MODE_SYSTEM ->
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                TwidgetStore.COLOR_MODE_LIGHT -> false
                else -> Color.red(settings.tintColor) < 128
            }

        private fun fullNumber(value: Long): String =
            java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        private fun warmProfileImageCache(context: Context, manager: AppWidgetManager, appWidgetId: Int, url: String) {
            if (url.isBlank()) return
            AppExecutors.execute {
                if (ProfileImageLoader.downloadToCache(context, url) != null) {
                    updateWidget(context, manager, appWidgetId)
                }
            }
        }

        fun followersInWords(value: Long): String {
            if (value < 0L) return fullNumber(value)
            return numberWords(value)
        }

        private fun numberWords(value: Long): String {
            if (value == 0L) return "Zero"
            if (value < 1_000L) return hundreds(value)
            val scales = listOf(
                1_000_000_000_000_000_000L to "Quintillion",
                1_000_000_000_000_000L to "Quadrillion",
                1_000_000_000_000L to "Trillion",
                1_000_000_000L to "Billion",
                1_000_000L to "Million",
                1_000L to "Thousand",
            )
            val (scale, name) = scales.first { value >= it.first }
            val leading = value / scale
            val remainder = value % scale
            return buildString {
                append(numberWords(leading))
                append(' ')
                append(name)
                if (remainder > 0) {
                    append(", ")
                    append(numberWords(remainder))
                }
            }
        }

        private fun hundreds(value: Long): String {
            val hundred = value / 100
            val remainder = value % 100
            return when {
                hundred > 0 && remainder > 0 -> "${ones(hundred)} Hundred and ${tens(remainder)}"
                hundred > 0 -> "${ones(hundred)} Hundred"
                else -> tens(remainder)
            }
        }

        private fun tens(value: Long): String {
            val names = mapOf(
                10L to "Ten", 11L to "Eleven", 12L to "Twelve", 13L to "Thirteen", 14L to "Fourteen",
                15L to "Fifteen", 16L to "Sixteen", 17L to "Seventeen", 18L to "Eighteen", 19L to "Nineteen",
                20L to "Twenty", 30L to "Thirty", 40L to "Forty", 50L to "Fifty", 60L to "Sixty",
                70L to "Seventy", 80L to "Eighty", 90L to "Ninety"
            )
            names[value]?.let { return it }
            val ten = value / 10 * 10
            val one = value % 10
            return listOfNotNull(names[ten], ones(one).takeIf { one > 0 }).joinToString(" ")
        }

        private fun ones(value: Long): String = when (value) {
            1L -> "One"
            2L -> "Two"
            3L -> "Three"
            4L -> "Four"
            5L -> "Five"
            6L -> "Six"
            7L -> "Seven"
            8L -> "Eight"
            9L -> "Nine"
            else -> "Zero"
        }
    }
}

/**
 * Private receiver for the custom tap-to-refresh action. Keeping it separate
 * from the exported AppWidgetProvider prevents other apps from triggering
 * arbitrary network refreshes with an explicit broadcast.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val manager = AppWidgetManager.getInstance(context)
            manager.partiallyUpdateAppWidget(
                appWidgetId,
                RemoteViews(
                    context.packageName,
                    TwidgetWidget.spinnerLayout(context, manager, appWidgetId),
                ).apply {
                    setViewVisibility(R.id.widget_loading, View.VISIBLE)
                },
            )
        }
        val pending = goAsync()
        AppExecutors.execute(onRejected = {
            // Re-render immediately so a saturated executor cannot leave the
            // partially-updated loading RemoteViews stuck on screen.
            try {
                TwidgetWidget.updateAll(context)
            } finally {
                pending.finish()
            }
        }) {
            try {
                runCatching {
                    val account = TwidgetStore.widgetSettings(context, appWidgetId).accountUsername
                        .ifBlank { TwidgetStore.settings(context).username }
                    TwidgetStore.saveStats(context, RettiwtClient.refresh(context, account))
                }
                TwidgetWidget.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.tjg.twidget.action.REFRESH"
    }
}
