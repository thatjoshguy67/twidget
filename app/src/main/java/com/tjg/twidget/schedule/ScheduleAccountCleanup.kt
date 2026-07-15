package com.tjg.twidget.schedule

import android.content.Context
import com.tjg.twidget.core.AppExecutors

object ScheduleAccountCleanup {
    fun removeAccountSchedules(context: Context, username: String) {
        val appContext = context.applicationContext
        val store = ScheduleStore(appContext)
        val posts = store.listForAccount(username)
        posts.filter { it.provider == ScheduleProvider.LOCAL_REMINDER }.forEach { post ->
            LocalReminderScheduler(appContext).cancel(post.id)
            ScheduleNotificationHelper.cancel(appContext, post.id)
            store.remove(post.id)
        }
        posts.filter { it.provider == ScheduleProvider.POSTPONE }.forEach { post ->
            AppExecutors.execute(onRejected = { store.remove(post.id) }) {
                if (!post.remotePostId.isNullOrBlank()) {
                    ScheduleCoordinator(appContext).cancel(post.id)
                }
                store.remove(post.id)
            }
        }
    }
}
