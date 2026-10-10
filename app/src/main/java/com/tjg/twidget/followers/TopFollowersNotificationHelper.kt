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
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MainActivity
import java.util.Locale

object TopFollowersNotificationHelper {
    private const val CHANNEL_ID = "top_followers_scans"
    fun showNewHighRankingFollower(
        context: Context,
        username: String,
        follower: TopFollower,
        rank: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.top_followers_notifications),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.top_followers_notifications_description) })
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_twidget_notification)
            .setContentTitle(context.getString(R.string.top_followers_new_high_ranking_title, username))
            .setContentText(context.getString(
                R.string.top_followers_new_high_ranking_detail,
                follower.username,
                rank,
            ))
            .setContentIntent(openAppIntent(context, username))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_SOCIAL)
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
            .putExtra(TopFollowersBridgeSyncWorker.EXTRA_USERNAME, username),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun completionNotificationId(username: String) =
        username.lowercase(Locale.US).hashCode() xor 0x746f70
}
