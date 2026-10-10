package com.tjg.twidget.followers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Legacy work may survive an app upgrade. It must finish without making API calls. */
class TopFollowersScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val username = inputData.getString("username").orEmpty().trim().trimStart('@')
        if (username.isNotBlank()) TopFollowersStore.stopScan(applicationContext, username)
        return Result.success()
    }
}
