package com.tjg.twidget.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        Thread {
            try {
                LocalReminderScheduler(context).rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
