package com.tjg.twidget.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.tjg.twidget.R
import com.tjg.twidget.main.AboutActivity

internal object UpdateNotificationPolicy {
    fun shouldNotify(
        availableVersion: String,
        lastNotifiedVersion: String?,
        reminderVersion: String?,
        remindAfter: Long,
        now: Long,
    ): Boolean = lastNotifiedVersion != availableVersion ||
        (reminderVersion == availableVersion && now >= remindAfter)
}

object UpdateNotificationHelper {
    private const val CHANNEL_ID = "app_updates"
    private const val CHANNEL_NAME = "App updates"
    private const val PREFS = "twidget_update_notifications"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    private const val KEY_REMINDER_VERSION = "reminder_version"
    private const val KEY_REMIND_AFTER = "remind_after"
    private const val NOTIFICATION_ID = 0x7550
    private const val REMIND_LATER_MILLIS = 24L * 60L * 60L * 1_000L

    fun showIfNeeded(
        context: Context,
        release: AppRelease,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val version = release.version.toString()
        val preferences = prefs(context)
        if (!UpdateNotificationPolicy.shouldNotify(
                availableVersion = version,
                lastNotifiedVersion = preferences.getString(KEY_LAST_NOTIFIED_VERSION, null),
                reminderVersion = preferences.getString(KEY_REMINDER_VERSION, null),
                remindAfter = preferences.getLong(KEY_REMIND_AFTER, Long.MAX_VALUE),
                now = now,
            )
        ) {
            return false
        }

        ensureChannel(context)
        val openAbout = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, AboutActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remindLater = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 1,
            Intent(context, UpdateReminderReceiver::class.java)
                .setAction(UpdateReminderReceiver.ACTION_REMIND_LATER)
                .putExtra(UpdateReminderReceiver.EXTRA_VERSION, version),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val installNow = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID + 2,
            AboutActivity.installUpdateIntent(context, version),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_twidget_notification)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_body, version))
            .setStyle(Notification.BigTextStyle().bigText(
                context.getString(R.string.update_notification_body, version),
            ))
            .setContentIntent(openAbout)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .addAction(Notification.Action.Builder(
                R.drawable.ic_twidget_notification,
                context.getString(R.string.update_remind_later),
                remindLater,
            ).build())
            .addAction(Notification.Action.Builder(
                R.drawable.ic_twidget_notification,
                context.getString(R.string.update_install_now),
                installNow,
            ).build())
            .build()
        return try {
            notificationManager(context).notify(NOTIFICATION_ID, notification)
            preferences.edit()
                .putString(KEY_LAST_NOTIFIED_VERSION, version)
                .remove(KEY_REMINDER_VERSION)
                .remove(KEY_REMIND_AFTER)
                .apply()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun remindLater(context: Context, version: String, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString(KEY_REMINDER_VERSION, version)
            .putLong(KEY_REMIND_AFTER, now + REMIND_LATER_MILLIS)
            .apply()
        cancel(context)
    }

    fun cancel(context: Context) {
        notificationManager(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        notificationManager(context).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.update_notification_channel_description)
            },
        )
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class UpdateReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND_LATER) return
        val version = intent.getStringExtra(EXTRA_VERSION)?.takeIf(String::isNotBlank) ?: return
        UpdateNotificationHelper.remindLater(context, version)
        UpdateCheckWorker.scheduleReminder(context)
    }

    companion object {
        const val ACTION_REMIND_LATER = "com.tjg.twidget.action.REMIND_UPDATE_LATER"
        const val EXTRA_VERSION = "com.tjg.twidget.extra.UPDATE_VERSION"
    }
}
