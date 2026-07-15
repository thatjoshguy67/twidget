package com.tjg.twidget.schedule

import android.content.Context

object ScheduleChecklistProgress {
    private const val PREFS = "schedule_checklist_progress"

    fun completed(context: Context, postId: String): Set<String> =
        preferences(context).getStringSet(key(postId), emptySet()).orEmpty()

    fun markCompleted(context: Context, postId: String, itemId: String): Set<String> {
        val completed = completed(context, postId).toMutableSet().apply { add(itemId) }
        preferences(context).edit().putStringSet(key(postId), completed).apply()
        return completed
    }

    fun clear(context: Context, postId: String) {
        preferences(context).edit().remove(key(postId)).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(postId: String) = "completed_$postId"
}
