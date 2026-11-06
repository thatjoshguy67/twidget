package com.tjg.twidget.schedule

import android.content.Context

/** Removes retained local composer archives with their owning account. Never contacts Buffer. */
object ScheduleAccountCleanup {
    fun removeAccountSchedules(context: Context, username: String) {
        val appContext = context.applicationContext
        val store = ScheduleStore(appContext)
        // Include trash: removing an account must also release its retained local media.
        val posts = store.snapshot().filter {
            listOf(it.accountId.orEmpty(), it.accountUsername).any { account ->
                normalizeLegacyScheduleAccount(account) == normalizeLegacyScheduleAccount(username)
            }
        }
        store.removeAll(posts.mapTo(hashSetOf()) { it.id })
    }
}
