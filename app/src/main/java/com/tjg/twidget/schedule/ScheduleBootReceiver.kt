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
                // re-register persisted work in case a request predates a
                // class move (such as the feature-package reorg). enqueue()
                // skips posts that no longer need a publish check, and the
                // unique work names make both calls idempotent.
                RefreshWorker.schedule(context)
                ScheduleStore(context).list().forEach { post ->
                    BufferPublishCheckWorker.cancelLegacyPostponeWork(context, post.id)
                    BufferPublishCheckWorker.enqueue(context, post)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
