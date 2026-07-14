package com.tjg.twidget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

object TopFollowersNotificationHelper {
    private const val CHANNEL_ID = "top_followers_scans"

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
        val open = PendingIntent.getActivity(
            context,
            username.lowercase(Locale.US).hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val leading = state.top.firstOrNull()
        val detail = leading?.let {
            context.getString(R.string.top_followers_notification_leader, it.name, TwidgetStore.compactNumber(it.followers))
        } ?: context.getString(R.string.top_followers_complete, state.scanned, state.pages)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(context.getString(R.string.top_followers_notification_title, username))
            .setContentText(detail)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        return runCatching {
            manager.notify(username.lowercase(Locale.US).hashCode() xor 0x746f70, notification)
            true
        }.getOrDefault(false)
    }
}
