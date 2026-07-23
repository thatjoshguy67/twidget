package com.tjg.twidget.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tjg.twidget.core.AppExecutors

class ScheduleReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REMIND) return
        val id = intent.getStringExtra(EXTRA_SCHEDULE_ID)?.takeIf { it.isNotBlank() } ?: return
        val pendingResult = goAsync()
        AppExecutors.execute(
            onRejected = { pendingResult.finish() },
        ) {
            try {
                val store = ScheduleStore(context)
                val post = store.get(id) ?: return@execute
                if (post.provider != ScheduleProvider.LOCAL_REMINDER ||
                    post.status != ScheduleStatus.SCHEDULED
                ) {
                    return@execute
                }
                val ready = post.copy(
                    status = ScheduleStatus.NEEDS_ACTION,
                    updatedAt = System.currentTimeMillis(),
                    errorMessage = null,
                )
                store.upsert(ready)
                ScheduleNotificationHelper.showReminder(context, ready)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "com.tjg.twidget.action.SCHEDULE_REMINDER"
        const val EXTRA_SCHEDULE_ID = "com.tjg.twidget.extra.SCHEDULE_ID"
    }
}
