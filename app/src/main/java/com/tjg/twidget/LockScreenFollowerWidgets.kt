package com.tjg.twidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.RemoteViews

class LockScreenFollowerSmallWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        LockScreenFollowerViews.update(context, manager, appWidgetIds, R.layout.lockscreen_message_1x1)
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
    }
}

class LockScreenFollowerWideWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        LockScreenFollowerViews.update(context, manager, appWidgetIds, R.layout.lockscreen_message_2x1)
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
    }
}

class LockScreenFollowerServiceBoxReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REQUEST_SERVICEBOX_REMOTEVIEWS) return

        val requestedPageId = intent.getStringExtra(EXTRA_PAGE_ID)
        val pageIds = when {
            requestedPageId == null -> PAGE_IDS
            requestedPageId in PAGE_IDS -> listOf(requestedPageId)
            else -> return
        }
        pageIds.forEach { sendResponse(context, it) }
    }

    companion object {
        private const val ACTION_REQUEST_SERVICEBOX_REMOTEVIEWS =
            "com.samsung.android.intent.action.REQUEST_SERVICEBOX_REMOTEVIEWS"
        private const val ACTION_RESPONSE_SERVICEBOX_REMOTEVIEWS =
            "com.samsung.android.intent.action.RESPONSE_SERVICEBOX_REMOTEVIEWS"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_PAGE_ID = "pageId"
        private const val EXTRA_SHOW = "show"
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_AOD = "aod"
        const val PAGE_FOLLOWER_1X1 = "twidget_follower_1x1"
        const val PAGE_FOLLOWER_2X1 = "twidget_follower_2x1"
        private val PAGE_IDS = listOf(PAGE_FOLLOWER_1X1, PAGE_FOLLOWER_2X1)

        fun refresh(context: Context) {
            PAGE_IDS.forEach { sendResponse(context, it) }
        }

        private fun sendResponse(context: Context, pageId: String) {
            context.sendBroadcast(
                Intent(ACTION_RESPONSE_SERVICEBOX_REMOTEVIEWS).apply {
                    setPackage(SYSTEMUI_PACKAGE)
                    putExtra(EXTRA_PACKAGE, context.packageName)
                    putExtra(EXTRA_PAGE_ID, pageId)
                    putExtra(EXTRA_SHOW, true)
                    putExtra(EXTRA_ORIGIN, LockScreenFollowerViews.serviceBoxViews(context, pageId, monotone = false))
                    putExtra(EXTRA_AOD, LockScreenFollowerViews.serviceBoxViews(context, pageId, monotone = true))
                }
            )
        }
    }
}

object LockScreenFollowerViews {
    fun update(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray, layoutId: Int) {
        appWidgetIds.forEach { appWidgetId ->
            manager.updateAppWidget(appWidgetId, messageViews(context, layoutId, appWidgetId = appWidgetId))
        }
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(android.content.ComponentName(context, LockScreenFollowerSmallWidget::class.java))
            .takeIf { it.isNotEmpty() }
            ?.let { update(context, manager, it, R.layout.lockscreen_message_1x1) }
        manager.getAppWidgetIds(android.content.ComponentName(context, LockScreenFollowerWideWidget::class.java))
            .takeIf { it.isNotEmpty() }
            ?.let { update(context, manager, it, R.layout.lockscreen_message_2x1) }
    }

    fun serviceBoxViews(context: Context, pageId: String, monotone: Boolean = false): RemoteViews {
        val layoutId = when (pageId) {
            LockScreenFollowerServiceBoxReceiver.PAGE_FOLLOWER_1X1 -> R.layout.lockscreen_message_1x1
            else -> R.layout.lockscreen_message_2x1
        }
        return messageViews(context, layoutId, monotone)
    }

    // In-app preview of the lock screen artwork (onboarding, widget settings);
    // white text, meant to be shown over a dark wallpaper-like backdrop.
    fun previewArt(context: Context, wide: Boolean, settings: TwidgetWidgetSettings? = null): Bitmap =
        renderArt(context, wide, monotone = false, widgetSettings = settings ?: TwidgetStore.widgetSettings(context))

    // Samsung's facewidget host recolors TextViews (setAppWidgetColorFilter) on
    // the lock screen, which turned the counts invisible on its white capsule.
    // Bitmap content is left alone, so the text is rendered into an ImageView
    // the same way the home-screen widgets draw their artwork.
    private fun messageViews(context: Context, layoutId: Int, monotone: Boolean = false, appWidgetId: Int = 0): RemoteViews =
        RemoteViews(context.packageName, layoutId).apply {
            val wide = layoutId == R.layout.lockscreen_message_2x1
            setViewVisibility(R.id.lockscreen_logo_icon, android.view.View.GONE)
            setViewVisibility(R.id.lockscreen_follower_count, android.view.View.GONE)
            setViewVisibility(R.id.lockscreen_follower_delta, android.view.View.GONE)
            setViewVisibility(R.id.lockscreen_art, android.view.View.VISIBLE)
            setImageViewBitmap(
                R.id.lockscreen_art,
                renderArt(context, wide, monotone, TwidgetStore.widgetSettings(context, appWidgetId)),
            )
            setOnClickPendingIntent(
                R.id.lockscreen_follower_root,
                configPendingIntent(context, appWidgetId, wide),
            )
        }

    private fun configPendingIntent(context: Context, appWidgetId: Int, wide: Boolean): PendingIntent {
        val requestCode = when {
            appWidgetId > 0 -> 5000 + appWidgetId
            wide -> 5102
            else -> 5101
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, WidgetConfigActivity::class.java)
                .setComponent(ComponentName(context, WidgetConfigActivity::class.java))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(WidgetConfigActivity.EXTRA_LOCKSCREEN_WIDGET, true)
                .putExtra(WidgetConfigActivity.EXTRA_LOCKSCREEN_WIDE, wide),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun renderArt(context: Context, wide: Boolean, monotone: Boolean, widgetSettings: TwidgetWidgetSettings): Bitmap {
        val density = context.resources.displayMetrics.density
        // The 1x1 art is a tight square so fitCenter scales it (and the count)
        // as large as the widget cell allows.
        val width = ((if (wide) 118 else 52) * density).toInt()
        val height = ((if (wide) 44 else 52) * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val account = widgetSettings.accountUsername.ifBlank { TwidgetStore.settings(context).username }
        val stats = TwidgetStore.currentStats(context, account)
        val delta = TwidgetStore.followersDelta(context, account)
        val count = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(stats.followersCount)
        val deltaText = when {
            !widgetSettings.showDelta -> ""
            delta == 0L -> context.getString(R.string.followers).lowercase()
            else -> TwidgetStore.signedNumber(delta)
        }
        // Transparent widget over the wallpaper, so text is white like the
        // system lock-screen widgets; AOD (monotone) drops the delta colors.
        val primary = Color.WHITE
        val deltaColor = when {
            monotone -> primary
            delta > 0 -> Color.rgb(105, 220, 118)
            delta < 0 -> Color.rgb(255, 105, 97)
            else -> Color.argb(200, 255, 255, 255)
        }

        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = primary
            typeface = Typeface.create("sec", Typeface.BOLD)
        }
        val deltaPaint = Paint(countPaint).apply { color = deltaColor }

        val logo = androidx.core.content.ContextCompat.getDrawable(
            context,
            if (widgetSettings.logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x,
        )?.mutate()?.apply {
            setTint(primary)
        }

        if (wide) {
            val iconSize = (22 * density).toInt()
            val pad = (2 * density).toInt()
            logo?.setBounds(pad, (height - iconSize) / 2, pad + iconSize, (height + iconSize) / 2)
            logo?.draw(canvas)

            countPaint.textSize = fitTextSize(count, countPaint, width - iconSize - pad * 3f, 19f * density)
            deltaPaint.textSize = 13f * density
            val countY = if (deltaText.isEmpty()) centeredBaseline(height.toFloat(), countPaint) else height * 0.46f
            canvas.drawText(count, width - pad - countPaint.measureText(count), countY, countPaint)
            if (deltaText.isNotEmpty()) {
                canvas.drawText(deltaText, width - pad - deltaPaint.measureText(deltaText), countY + 15f * density, deltaPaint)
            }
        } else {
            val iconSize = (16 * density).toInt()
            logo?.setBounds((width - iconSize) / 2, 0, (width + iconSize) / 2, iconSize)
            logo?.draw(canvas)

            countPaint.textSize = fitTextSize(count, countPaint, width.toFloat(), 18f * density)
            deltaPaint.textSize = fitTextSize(deltaText, deltaPaint, width.toFloat(), 12f * density)
            // fitTextSize only constrains width; the column must also fit the
            // bitmap height or the delta clips at the bottom edge. Shrink both
            // sizes together until it does, keeping at least a 1.5dp gap
            // above and below the count.
            fun columnHeight(): Float {
                val cm = countPaint.fontMetrics
                val dm = deltaPaint.fontMetrics
                val dh = if (deltaText.isEmpty()) 0f else dm.descent - dm.ascent
                return iconSize + (cm.descent - cm.ascent) + dh
            }
            while (columnHeight() > height - 3f * density && countPaint.textSize > 9f * density) {
                countPaint.textSize -= 0.5f * density
                deltaPaint.textSize = (countPaint.textSize * 0.68f).coerceAtLeast(8f * density)
            }
            val countMetrics = countPaint.fontMetrics
            val deltaMetrics = deltaPaint.fontMetrics
            val countHeight = countMetrics.descent - countMetrics.ascent
            val deltaHeight = if (deltaText.isEmpty()) 0f else deltaMetrics.descent - deltaMetrics.ascent
            val gap = ((height - iconSize - countHeight - deltaHeight) / 2f).coerceAtLeast(0f)
            val countY = if (deltaText.isEmpty()) {
                iconSize + (height - iconSize) / 2f - (countMetrics.ascent + countMetrics.descent) / 2f
            } else {
                iconSize + gap - countMetrics.ascent
            }
            canvas.drawText(count, (width - countPaint.measureText(count)) / 2f, countY, countPaint)
            if (deltaText.isNotEmpty()) {
                val deltaY = countY + countMetrics.descent + gap - deltaMetrics.ascent
                canvas.drawText(deltaText, (width - deltaPaint.measureText(deltaText)) / 2f, deltaY, deltaPaint)
            }
        }
        return bitmap
    }

    private fun centeredBaseline(height: Float, paint: Paint): Float =
        height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

    private fun fitTextSize(text: String, paint: Paint, maxWidth: Float, startSize: Float): Float {
        var size = startSize
        val min = startSize * 0.55f
        while (size > min) {
            paint.textSize = size
            if (paint.measureText(text) <= maxWidth) break
            size -= 1f
        }
        return size
    }
}
