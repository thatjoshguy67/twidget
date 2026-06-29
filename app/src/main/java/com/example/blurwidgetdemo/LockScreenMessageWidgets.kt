package com.example.blurwidgetdemo

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

class LockScreenMessageSmallWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        LockScreenMessageViews.update(context, manager, appWidgetIds, R.layout.lockscreen_message_1x1)
    }
}

class LockScreenMessageWideWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        LockScreenMessageViews.update(context, manager, appWidgetIds, R.layout.lockscreen_message_2x1)
    }
}

class LockScreenMessageServiceBoxReceiver : BroadcastReceiver() {
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
        const val PAGE_MESSAGE_1X1 = "blur_widget_message_1x1"
        const val PAGE_MESSAGE_2X1 = "blur_widget_message_2x1"
        private val PAGE_IDS = listOf(PAGE_MESSAGE_1X1, PAGE_MESSAGE_2X1)

        private fun sendResponse(context: Context, pageId: String) {
            val message = LockScreenMessageViews.randomMessage()
            context.sendBroadcast(
                Intent(ACTION_RESPONSE_SERVICEBOX_REMOTEVIEWS).apply {
                    setPackage(SYSTEMUI_PACKAGE)
                    putExtra(EXTRA_PACKAGE, context.packageName)
                    putExtra(EXTRA_PAGE_ID, pageId)
                    putExtra(EXTRA_SHOW, true)
                    putExtra(EXTRA_ORIGIN, LockScreenMessageViews.serviceBoxViews(context, pageId, message))
                    putExtra(EXTRA_AOD, LockScreenMessageViews.serviceBoxViews(context, pageId, message))
                }
            )
        }
    }
}

object LockScreenMessageViews {
    private val MESSAGES = listOf(
        "Clear",
        "Glass",
        "Soft",
        "Calm",
        "Glow",
        "Breathe",
        "Easy"
    )

    fun update(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray, layoutId: Int) {
        appWidgetIds.forEach { appWidgetId ->
            manager.updateAppWidget(appWidgetId, messageViews(context, layoutId, randomMessage()))
        }
    }

    fun serviceBoxViews(context: Context, pageId: String, message: String): RemoteViews {
        val layoutId = when (pageId) {
            LockScreenMessageServiceBoxReceiver.PAGE_MESSAGE_1X1 -> R.layout.lockscreen_message_1x1
            else -> R.layout.lockscreen_message_2x1
        }
        return messageViews(context, layoutId, message)
    }

    fun randomMessage(): String = MESSAGES.random()

    private fun messageViews(context: Context, layoutId: Int, message: String): RemoteViews =
        RemoteViews(context.packageName, layoutId).apply {
            setTextViewText(R.id.lockscreen_message, message)
            setTextColor(R.id.lockscreen_message, Color.WHITE)
        }
}
