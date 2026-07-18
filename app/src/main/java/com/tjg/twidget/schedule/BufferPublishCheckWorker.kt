package com.tjg.twidget.schedule

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class BufferPublishCheckWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val id = inputData.getString(KEY_SCHEDULE_ID)?.takeIf(String::isNotBlank) ?: return Result.failure()
        val store = ScheduleStore(applicationContext)
        val before = store.get(id) ?: return Result.success()
        if (before.provider != ScheduleProvider.BUFFER || before.status != ScheduleStatus.SCHEDULED) {
            return Result.success()
        }
        val sync = BufferScheduleSync(applicationContext).sync()
        if (!sync.isSuccess) return Result.retry()
        val post = store.get(id) ?: return Result.success()
        return when (post.status) {
            ScheduleStatus.PUBLISHED -> {
                ScheduleNotificationHelper.showBufferPublished(applicationContext, post)
                Result.success()
            }
            ScheduleStatus.NEEDS_ACTION -> {
                ScheduleNotificationHelper.showReminder(applicationContext, post)
                Result.success()
            }
            ScheduleStatus.SCHEDULED -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val KEY_SCHEDULE_ID = "schedule_id"
        private const val VERIFY_DELAY_MS = 2 * 60 * 1000L

        fun enqueue(context: Context, post: ScheduledPost) {
            if (post.provider != ScheduleProvider.BUFFER || post.status != ScheduleStatus.SCHEDULED || post.scheduledAt == null) return
            val delay = (post.scheduledAt + VERIFY_DELAY_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<BufferPublishCheckWorker>()
                .setInputData(workDataOf(KEY_SCHEDULE_ID to post.id))
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "buffer-publish-check-${post.id}", ExistingWorkPolicy.REPLACE, request,
            )
        }

        fun cancel(context: Context, scheduleId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork("buffer-publish-check-$scheduleId")
        }

        fun cancelLegacyPostponeWork(context: Context, scheduleId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork("postpone-publish-check-$scheduleId")
        }
    }
}
