package com.tjg.twidget.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.widget.RefreshWorker

class ScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        AppExecutors.execute(onRejected = { pendingResult.finish() }) {
            try {
                LocalReminderScheduler(context).rescheduleAll()
                // WorkManager stores each request's worker class name, so
                // re-register the periodic refresh in case a persisted request
                // predates a class move (such as the feature-package reorg).
                RefreshWorker.schedule(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
