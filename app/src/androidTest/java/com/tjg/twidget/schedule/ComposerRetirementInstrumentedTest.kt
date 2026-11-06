package com.tjg.twidget.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.data.TwidgetStore
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ComposerRetirementInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun upgradeCancelsLocalWorkClearsConnectorTokensAndPreservesDraftArchive() {
        val stores = listOf(TwidgetStore.PREFS, ScheduleStore.PREFS_NAME, "twidget_secure_credentials")
            .map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val saved = stores.map { it to it.all.toMap() }
        val draft = ScheduledPost(provider = ScheduleProvider.BUFFER, accountUsername = "retired_composer_test",
            scheduledAt = null, thread = listOf(ScheduleThreadItem(text = "Retained unpublished draft")))
        val work = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<RetiredComposerTestWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        val reminder = Intent().setClassName(context, "com.tjg.twidget.schedule.ScheduleReminderReceiver")
            .setAction("com.tjg.twidget.action.SCHEDULE_REMINDER")
        val code = draft.id.fold(17) { hash, character -> hash * 31 + character.code } and Int.MAX_VALUE
        try {
            ScheduleStore(context).create(draft)
            stores.first().edit().remove("composer_retired_v1").putString("schedule_cloudinary_cloud_name", "fixture").commit()
            SecureCredentialStore.write(context, mapOf(SecureCredentialStore.BUFFER_ACCESS_TOKEN to "fixture-token"))
            work.enqueueUniqueWork("buffer-publish-check-${draft.id}", androidx.work.ExistingWorkPolicy.REPLACE, request).result.get()
            val pending = PendingIntent.getBroadcast(context, code, reminder,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1), pending)

            RetiredComposerCleanup.run(context)

            assertEquals(draft, ScheduleStore(context).get(draft.id))
            assertEquals("", SecureCredentialStore.read(context, SecureCredentialStore.BUFFER_ACCESS_TOKEN))
            assertFalse(stores.first().contains("schedule_cloudinary_cloud_name"))
            assertEquals(androidx.work.WorkInfo.State.CANCELLED, requireNotNull(work.getWorkInfoById(request.id).get()).state)
            assertNull(PendingIntent.getBroadcast(context, code, reminder,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
            RetiredComposerCleanup.run(context)
            assertEquals(draft, ScheduleStore(context).get(draft.id))
        } finally {
            work.cancelUniqueWork("buffer-publish-check-${draft.id}").result.get()
            saved.forEach { (preferences, values) -> restore(preferences, values) }
        }
    }

    @Test fun retiredActivitiesAndExactAlarmPermissionAreAbsentFromTheInstalledApp() {
        for (name in listOf("schedule.ScheduleActivity", "schedule.ScheduleComposeActivity",
            "schedule.BufferOAuthCallbackActivity", "settings.SettingsScheduleActivity")) {
            try {
                context.packageManager.getActivityInfo(ComponentName(context.packageName, "com.tjg.twidget.$name"), 0)
                fail("Retired activity remains registered: $name")
            } catch (_: PackageManager.NameNotFoundException) { /* Expected. */ }
        }
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("android.permission.SCHEDULE_EXACT_ALARM" in permissions)
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(editor.commit())
    }
}

class RetiredComposerTestWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}
