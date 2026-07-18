package com.tjg.twidget.followers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.ForegroundInfo
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MainActivity
import java.util.Locale

object TopFollowersNotificationHelper {
    private const val CHANNEL_ID = "top_followers_scans"
    private const val PROGRESS_CHANNEL_ID = "top_followers_scan_progress"

    fun progressForegroundInfo(context: Context, username: String, state: TopFollowersState): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            PROGRESS_CHANNEL_ID,
            context.getString(R.string.top_followers_progress_notifications),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.top_followers_progress_notifications_description) })

        val total = TwidgetStore.currentStats(context, username).let {
            it.followersCount.takeIf { count -> it.followersKnown && count > 0 }
        }
        val notificationMax = total?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        val notificationProgress = notificationMax?.let { state.scanned.coerceIn(0, it) }
        val notification = Notification.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(context.getString(R.string.top_followers_progress_title, username))
            .setContentText(context.getString(R.string.top_followers_scanning, state.scanned, state.pages))
            .setContentIntent(openAppIntent(context, username))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(notificationMax ?: 0, notificationProgress ?: 0, total == null)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(progressNotificationId(username), notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(progressNotificationId(username), notification)
        }
    }

    fun showComplete(context: Context, username: String, state: TopFollowersState): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.top_followers_notifications),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.top_followers_notifications_description) })
        val leading = state.top.firstOrNull()
        val detail = leading?.let {
            context.getString(R.string.top_followers_notification_leader, it.name, TwidgetStore.compactNumber(it.followers))
        } ?: context.getString(R.string.top_followers_complete, state.scanned, state.pages)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(context.getString(R.string.top_followers_notification_title, username))
            .setContentText(detail)
            .setContentIntent(openAppIntent(context, username))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        return runCatching {
            manager.notify(completionNotificationId(username), notification)
            true
        }.getOrDefault(false)
    }

    private fun openAppIntent(context: Context, username: String): PendingIntent = PendingIntent.getActivity(
        context,
        username.lowercase(Locale.US).hashCode(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(TopFollowersScanWorker.EXTRA_USERNAME, username),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotificationId(username: String) =
        username.lowercase(Locale.US).hashCode() xor 0x70726f67

    private fun completionNotificationId(username: String) =
        username.lowercase(Locale.US).hashCode() xor 0x746f70
}
