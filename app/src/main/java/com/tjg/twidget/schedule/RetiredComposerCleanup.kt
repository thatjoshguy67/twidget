package com.tjg.twidget.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.data.TwidgetStore

/** One-time, local upgrade cleanup. Remote Buffer posts and retained draft archives are untouched. */
internal object RetiredComposerCleanup {
    fun run(context: Context) {
        val preferences = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean("composer_retired_v1", false)) return
        val work = WorkManager.getInstance(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        ScheduleStore(context).snapshot().forEach { post ->
            work.cancelUniqueWork("buffer-publish-check-${post.id}").result.get()
            work.cancelUniqueWork("postpone-publish-check-${post.id}").result.get()
            val reminder = Intent().setClassName(context, "com.tjg.twidget.schedule.ScheduleReminderReceiver")
                .setAction("com.tjg.twidget.action.SCHEDULE_REMINDER")
            val requestCode = post.id.fold(17) { hash, character -> hash * 31 + character.code } and Int.MAX_VALUE
            PendingIntent.getBroadcast(context, requestCode, reminder,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
        }
        context.getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("scheduled_post_reminders")
            deleteNotificationChannel("buffer_post_status")
        }
        SecureCredentialStore.write(context, mapOf(
            SecureCredentialStore.BUFFER_ACCESS_TOKEN to "",
            SecureCredentialStore.BUFFER_REFRESH_TOKEN to "",
            SecureCredentialStore.BUFFER_TOKEN_EXPIRY to "",
            SecureCredentialStore.LEGACY_POSTPONE_API_KEY to "",
        ))
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("buffer_") || it.startsWith("schedule_") || it == "postpone_api_key" }
            .forEach(editor::remove)
        check(editor.putBoolean("composer_retired_v1", true).commit())
    }
}
