package com.tjg.twidget.schedule

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import com.tjg.twidget.R

object ScheduleDeepLink {
    const val SCHEDULE_ACTIVITY_CLASS = "com.tjg.twidget.schedule.ScheduleActivity"
    const val ACTION_OPEN_SCHEDULE = "com.tjg.twidget.action.OPEN_SCHEDULE"
    const val ACTION_OPEN_CHECKLIST = "com.tjg.twidget.action.OPEN_SCHEDULE_CHECKLIST"
    const val EXTRA_SCHEDULE_ID = "com.tjg.twidget.extra.SCHEDULE_ID"
}

object ScheduleNotificationHelper {
    const val CHANNEL_ID = "scheduled_post_reminders"
    private const val CHANNEL_NAME = "Scheduled tweet reminders"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders to finish and publish locally scheduled tweets"
                enableLights(true)
                lightColor = Color.BLUE
            },
        )
    }

    /**
     * Returns false when Android notification permission is unavailable or denied.
     * The schedule remains NEEDS_ACTION so the app can surface it in-app.
     */
    fun showReminder(context: Context, post: ScheduledPost): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return try {
            manager.notify(notificationId(post.id), buildNotification(context, post))
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun cancel(context: Context, scheduleId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId(scheduleId))
    }

    private fun buildNotification(context: Context, post: ScheduledPost): Notification {
        val first = post.thread.firstOrNull()
        val preview = first?.text?.takeIf { it.isNotBlank() } ?: "Media post ready to publish"
        val open = schedulePendingIntent(context, post.id, ScheduleDeepLink.ACTION_OPEN_SCHEDULE, 0)
        val checklist = schedulePendingIntent(context, post.id, ScheduleDeepLink.ACTION_OPEN_CHECKLIST, 1)
        val composeIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(XComposeIntents.buildAppComposeUri(first?.text.orEmpty())),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val compose = PendingIntent.getActivity(
            context,
            LocalReminderScheduler.stableRequestCode(post.id) xor 0x52c0,
            composeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Scheduled tweet is ready")
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_agenda,
                    "Open checklist",
                    checklist,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    "Compose on X",
                    compose,
                ).build()
            )
            .build()
    }

    private fun schedulePendingIntent(
        context: Context,
        scheduleId: String,
        action: String,
        discriminator: Int,
    ): PendingIntent {
        val intent = Intent(action)
            .setClassName(context, ScheduleDeepLink.SCHEDULE_ACTIVITY_CLASS)
            .putExtra(ScheduleDeepLink.EXTRA_SCHEDULE_ID, scheduleId)
        return PendingIntent.getActivity(
            context,
            LocalReminderScheduler.stableRequestCode(scheduleId) xor (0x3100 + discriminator),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(scheduleId: String): Int =
        LocalReminderScheduler.stableRequestCode(scheduleId)
}
