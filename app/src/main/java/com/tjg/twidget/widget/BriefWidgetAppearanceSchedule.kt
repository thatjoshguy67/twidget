package com.tjg.twidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import java.time.ZonedDateTime

/** Local appearance work stays independent of network refresh and Brief generation. */
internal object BriefWidgetAppearanceSchedule {
    const val ACTION = "com.tjg.twidget.BRIEF_COLOUR_TRANSITION"

    fun nextChange(now: ZonedDateTime): ZonedDateTime = (0L..1L).flatMap { day ->
        listOf(5, 12, 17, 23).map { hour -> now.toLocalDate().plusDays(day).atTime(hour, 0).atZone(now.zone) }
    }.first { it.isAfter(now) }

    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 9372,
        Intent(context, BriefWidgetAppearanceReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val dynamic = manager.getAppWidgetIds(ComponentName(context, TwidgetBriefWidget::class.java))
            .any { BriefWidgetDynamicBackground.enabled(TwidgetStore.widgetSettings(context, it, WidgetKind.BRIEF)) }
        if (!dynamic) { cancel(context); return }
        // No exact-alarm permission and no wake-up just to recolour a sleeping home screen.
        // Android may defer this window in idle; it is delivered when the device becomes active.
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC,
            nextChange(ZonedDateTime.now()).toInstant().toEpochMilli(), 10 * 60_000L, pending(context))
    }

    fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
}

class BriefWidgetAppearanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(BriefWidgetAppearanceSchedule.ACTION, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val result = goAsync()
        AppExecutors.execute {
            try {
                TwidgetBriefWidget.updateAll(context)
                BriefWidgetAppearanceSchedule.schedule(context)
            } finally { result.finish() }
        }
    }
}
