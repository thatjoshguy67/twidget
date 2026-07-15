package com.tjg.twidget.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

enum class ReminderSchedulePrecision {
    EXACT,
    INEXACT,
}

class LocalReminderScheduler(private val context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(post: ScheduledPost): ReminderSchedulePrecision {
        require(post.provider == ScheduleProvider.LOCAL_REMINDER) { "Only local reminders use AlarmManager" }
        val triggerAt = requireNotNull(post.scheduledAt) { "A reminder requires a scheduled time" }
        require(triggerAt > System.currentTimeMillis()) { "A reminder must be scheduled in the future" }
        val operation = pendingIntent(post.id)

        if (canUseExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
                return ReminderSchedulePrecision.EXACT
            } catch (_: SecurityException) {
                // Permission can change between checking and scheduling.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        return ReminderSchedulePrecision.INEXACT
    }

    fun cancel(scheduleId: String) {
        alarmManager.cancel(pendingIntent(scheduleId))
        pendingIntent(scheduleId).cancel()
    }

    fun rescheduleAll(store: ScheduleStore = ScheduleStore(appContext)): Int {
        val now = System.currentTimeMillis()
        var count = 0
        store.list()
            .filter {
                it.provider == ScheduleProvider.LOCAL_REMINDER &&
                    it.status == ScheduleStatus.SCHEDULED &&
                    (it.scheduledAt ?: Long.MIN_VALUE) > now
            }
            .forEach { post ->
                runCatching { schedule(post) }.onSuccess { count++ }
            }
        return count
    }

    private fun canUseExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(scheduleId: String): PendingIntent {
        val intent = Intent(appContext, ScheduleReminderReceiver::class.java)
            .setAction(ScheduleReminderReceiver.ACTION_REMIND)
            .putExtra(ScheduleReminderReceiver.EXTRA_SCHEDULE_ID, scheduleId)
        return PendingIntent.getBroadcast(
            appContext,
            stableRequestCode(scheduleId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        internal fun stableRequestCode(scheduleId: String): Int =
            scheduleId.fold(17) { hash, character -> hash * 31 + character.code } and Int.MAX_VALUE
    }
}
