package com.tjg.twidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

class TwidgetWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // Partial update flips the loading spinner on in the widget's
                // existing layout; the full re-render below re-hides it.
                val manager = AppWidgetManager.getInstance(context)
                manager.partiallyUpdateAppWidget(
                    appWidgetId,
                    RemoteViews(context.packageName, spinnerLayout(context, manager, appWidgetId)).apply {
                        setViewVisibility(R.id.widget_loading, View.VISIBLE)
                    },
                )
            }
            val pending = goAsync()
            Thread {
                runCatching {
                    val account = TwidgetStore.widgetSettings(context, appWidgetId).accountUsername
                        .ifBlank { TwidgetStore.settings(context).username }
                    TwidgetStore.saveStats(context, RettiwtClient.refresh(context, account))
                }
                updateAll(context)
                pending.finish()
            }.start()
        }
    }

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
        private const val ACTION_REFRESH = "com.tjg.twidget.action.REFRESH"
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
            val mode = layoutMode(options)
            val widgetSettings = TwidgetStore.widgetSettings(context, appWidgetId)
            // Launchers ignore @font references when inflating RemoteViews, so
            // Google Sans Flex widgets are rendered as artwork bitmaps instead.
            val useGsf = widgetSettings.fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX
            val renderAsArtwork = useGsf || mode == LAYOUT_MODE_LARGE || mode == LAYOUT_MODE_COMPACT_SQUARE
            val layoutResource = layoutResource(mode, useGsf)
            val account = widgetSettings.accountUsername.ifBlank { TwidgetStore.settings(context).username }
            val stats = TwidgetStore.currentStats(context, account)
            val delta = TwidgetStore.followersDelta(context, account)
            val followersText = when (mode) {
                LAYOUT_MODE_LARGE, LAYOUT_MODE_COMPACT_SQUARE -> followersInWords(stats.followersCount)
                else -> fullNumber(stats.followersCount)
            }
            val views = RemoteViews(context.packageName, layoutResource).apply {
                val dark = isDark(context, widgetSettings)
                val base = if (dark) 16 else 255
                val primary = if (dark) Color.WHITE else Color.BLACK
                setInt(android.R.id.background, "setBackgroundColor", Color.argb(widgetSettings.tintAlpha, base, base, base))
                // A full update must re-hide the spinner explicitly: the launcher
                // keeps the VISIBLE state a tap-refresh partial update set, so
                // relying on the layout's gone default leaves it stuck spinning.
                setViewVisibility(R.id.widget_loading, View.GONE)
                if (renderAsArtwork) {
                    val artwork = WidgetArtworkRenderer.render(
                        context = context,
                        widthPx = dp(context, minWidth),
                        heightPx = dp(context, minHeight),
                        stats = stats,
                        settings = widgetSettings,
                        mode = mode,
                        dark = dark,
                        delta = delta,
                    )
                    setImageViewBitmap(R.id.widget_artwork, artwork)
                } else {
                    setTextViewText(R.id.widget_followers_value, followersText)
                    setTextViewText(R.id.widget_followers_label, context.getString(R.string.followers))
                    setTextViewText(R.id.widget_delta, if (!widgetSettings.showDelta || delta == 0L) "" else TwidgetStore.signedNumber(delta))
                    setTextColor(R.id.widget_followers_value, primary)
                    setTextColor(R.id.widget_followers_label, primary)
                    if (mode == LAYOUT_MODE_COMPACT_STRIP) {
                        setImageViewResource(R.id.widget_logo_icon, logoIcon(widgetSettings.logo))
                        setInt(R.id.widget_logo_icon, "setColorFilter", primary)
                        setTextViewText(R.id.widget_handle, "@${stats.userName}")
                        setTextColor(R.id.widget_handle, primary)
                    }
                    setTextColor(R.id.widget_delta, if (delta < 0) Color.rgb(229, 57, 53) else Color.rgb(46, 125, 50))
                    setViewVisibility(
                        R.id.widget_delta,
                        if (widgetSettings.showDelta &&
                            (mode == LAYOUT_MODE_COMPACT_2X1 || mode == LAYOUT_MODE_COMPACT_STRIP) &&
                            delta != 0L
                        ) View.VISIBLE else View.GONE
                    )
                    setTextViewTextSize(R.id.widget_followers_value, TypedValue.COMPLEX_UNIT_SP, valueTextSize(mode, minWidth, minHeight, followersText))
                    setTextViewTextSize(R.id.widget_followers_label, TypedValue.COMPLEX_UNIT_SP, labelTextSize(mode, minWidth))
                    setTextViewTextSize(R.id.widget_delta, TypedValue.COMPLEX_UNIT_SP, when (mode) {
                        LAYOUT_MODE_COMPACT_SQUARE -> 14f
                        else -> 15f
                    })
                }
                setOnClickPendingIntent(android.R.id.background, tapIntent(context, appWidgetId, widgetSettings.tapAction, account))
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        // Whichever layout a widget is currently showing — used for both the
        // full render and the tap-refresh spinner so the partial update targets
        // the right view hierarchy.
        private fun layoutResource(mode: Int, useGsf: Boolean): Int = when {
            mode == LAYOUT_MODE_LARGE -> R.layout.widget_blur
            mode == LAYOUT_MODE_COMPACT_SQUARE || useGsf -> R.layout.widget_compact_square
            mode == LAYOUT_MODE_COMPACT_2X1 -> R.layout.widget_compact_2x1
            else -> R.layout.widget_compact_strip
        }

        fun spinnerLayout(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): Int {
            val mode = layoutMode(appWidgetManager.getAppWidgetOptions(appWidgetId))
            val useGsf = TwidgetStore.widgetSettings(context, appWidgetId).fontFamily ==
                TwidgetStore.FONT_GOOGLE_SANS_FLEX
            return layoutResource(mode, useGsf)
        }

        fun layoutMode(minWidth: Int, minHeight: Int): Int = when {
            minHeight <= 100 && minWidth <= 170 -> LAYOUT_MODE_COMPACT_2X1
            minHeight <= 100 -> LAYOUT_MODE_COMPACT_STRIP
            minWidth <= 230 && minHeight <= 230 -> LAYOUT_MODE_COMPACT_SQUARE
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
                    Intent(context, TwidgetWidget::class.java)
                        .setAction(ACTION_REFRESH)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

        private fun valueTextSize(mode: Int, minWidth: Int, minHeight: Int, text: String): Float = when (mode) {
            LAYOUT_MODE_COMPACT_2X1 -> if (minWidth > 150) 24f else 22f
            LAYOUT_MODE_COMPACT_STRIP -> when {
                text.length >= 7 && minWidth < 250 -> 18f
                text.length >= 7 -> 22f
                minWidth >= 320 -> 30f
                minWidth >= 250 -> 26f
                else -> 22f
            }
            LAYOUT_MODE_COMPACT_SQUARE -> when {
                minWidth >= 190 && minHeight >= 170 -> 19f
                minWidth >= 160 && minHeight >= 140 -> 17f
                else -> 15f
            }
            else -> when {
                minWidth >= 520 && minHeight >= 250 -> 42f
                minWidth >= 430 && minHeight >= 220 -> 38f
                minWidth >= 340 && minHeight >= 180 -> 34f
                minWidth >= 280 -> 30f
                text.length > 32 -> 25f
                else -> 27f
            }
        }

        private fun labelTextSize(mode: Int, minWidth: Int): Float = when (mode) {
            LAYOUT_MODE_COMPACT_2X1 -> 16f
            LAYOUT_MODE_COMPACT_STRIP -> if (minWidth >= 250) 22f else 18f
            LAYOUT_MODE_LARGE -> 13f
            else -> 14f
        }

        private fun logoIcon(logo: String): Int =
            if (logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x

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
            Thread {
                if (ProfileImageLoader.downloadToCache(context, url) != null) {
                    updateWidget(context, manager, appWidgetId)
                }
            }.start()
        }

        fun followersInWords(value: Long): String {
            if (value in 0L..999_999L) return numberWords(value)
            return fullNumber(value)
        }

        private fun numberWords(value: Long): String {
            val thousands = value / 1_000
            val remainder = value % 1_000
            return buildString {
                if (thousands > 0) {
                    append(hundreds(thousands))
                    append(" Thousand")
                }
                if (remainder > 0) {
                    if (isNotEmpty()) append(", ")
                    append(hundreds(remainder))
                }
                if (isEmpty()) append("Zero")
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
