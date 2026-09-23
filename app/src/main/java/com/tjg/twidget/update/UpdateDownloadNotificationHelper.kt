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
import android.os.Handler
import android.os.Looper
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors

/** Ongoing download status. Android 16 can promote this into the system's Live Update surface. */
object UpdateDownloadNotificationHelper {
    private const val CHANNEL_ID = "app_update_download"
    private const val NOTIFICATION_ID = 0x7551
    private const val COMPLETED_NOTIFICATION_MILLIS = 500L
    @Volatile private var lastProgress: UpdateDownloadProgress? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var completionSequence = 0L

    fun show(context: Context, progress: UpdateDownloadProgress) {
        lastProgress = progress
        post(context, progress, UpdateDownloadController.isPaused())
    }

    fun showPaused(context: Context) = post(context, lastProgress, true)

    fun showResumed(context: Context) = post(context, lastProgress, false)

    fun showCompleted(context: Context) {
        lastProgress = UpdateDownloadProgress(1L, 1L)
        val sequence = ++completionSequence
        post(context, lastProgress, false, completed = true)
        mainHandler.postDelayed({
            if (sequence == completionSequence) cancel(context)
        }, COMPLETED_NOTIFICATION_MILLIS)
    }

    fun cancel(context: Context) {
        notificationManager(context).cancel(NOTIFICATION_ID)
    }

    fun runDebugTest(context: Context) {
        if (!notificationsAvailable(context) || !UpdateDownloadController.tryBegin()) return
        val appContext = context.applicationContext
        AppExecutors.execute(
            onRejected = { UpdateDownloadController.finish() },
        ) {
            try {
                for (percent in 0..100 step 5) {
                    if (!UpdateDownloadController.awaitPermissionToContinue()) {
                        cancel(appContext)
                        return@execute
                    }
                    show(appContext, UpdateDownloadProgress(percent.toLong(), 100L))
                    Thread.sleep(350L)
                }
                cancel(appContext)
            } finally {
                UpdateDownloadController.finish()
            }
        }
    }

    private fun post(
        context: Context,
        progress: UpdateDownloadProgress?,
        paused: Boolean,
        completed: Boolean = false,
    ) {
        if (!notificationsAvailable(context)) return
        val percent = progress?.percent
        val text = when {
            completed -> context.getString(R.string.update_download_complete)
            paused && percent != null -> context.getString(R.string.update_download_paused_progress, percent)
            paused -> context.getString(R.string.update_download_paused)
            percent != null -> context.getString(R.string.update_download_progress, percent)
            else -> context.getString(R.string.update_downloading)
        }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_twidget_notification)
            .setContentTitle(context.getString(R.string.update_download_notification_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!completed)
            .setAutoCancel(completed)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, percent ?: 0, percent == null)

        // API 36 (Android 16): ask the system to surface this as a promoted ongoing/live update.
        if (Build.VERSION.SDK_INT >= 36 && notificationManager(context).canPostPromotedNotifications()) {
            builder.setRequestPromotedOngoing(true)
        }

        if (completed) {
            builder.setTimeoutAfter(COMPLETED_NOTIFICATION_MILLIS)
            try {
                notificationManager(context).notify(NOTIFICATION_ID, builder.build())
            } catch (_: SecurityException) { }
            return
        }

        val action = if (paused) {
            action(context, UpdateDownloadActionReceiver.ACTION_RESUME, R.string.update_resume, 1)
        } else {
            action(context, UpdateDownloadActionReceiver.ACTION_PAUSE, R.string.update_pause, 2)
        }
        builder.addAction(action)
        builder.addAction(action(context, UpdateDownloadActionReceiver.ACTION_STOP, R.string.update_stop, 3))
        try {
            notificationManager(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Permission can be revoked while a transfer is active.
        }
    }

    private fun action(context: Context, action: String, title: Int, requestCode: Int): Notification.Action {
        val intent = Intent(context, UpdateDownloadActionReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(R.drawable.ic_twidget_notification, context.getString(title), pendingIntent).build()
    }

    fun notificationsAvailable(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = notificationManager(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun ensureChannel(context: Context) {
        notificationManager(context).createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_download_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.update_download_notification_channel_description) })
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}

class UpdateDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE -> {
                UpdateDownloadController.pause()
                UpdateDownloadNotificationHelper.showPaused(context)
            }
            ACTION_RESUME -> {
                UpdateDownloadController.resume()
                UpdateDownloadNotificationHelper.showResumed(context)
            }
            ACTION_STOP -> {
                UpdateDownloadController.stop()
                UpdateDownloadNotificationHelper.cancel(context)
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.tjg.twidget.action.PAUSE_UPDATE_DOWNLOAD"
        const val ACTION_RESUME = "com.tjg.twidget.action.RESUME_UPDATE_DOWNLOAD"
        const val ACTION_STOP = "com.tjg.twidget.action.STOP_UPDATE_DOWNLOAD"
    }
}
