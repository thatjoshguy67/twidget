package com.tjg.twidget.schedule

import android.content.Context
import com.tjg.twidget.core.HttpTransport

internal object BufferRequestThrottle {
    private const val PREFS_NAME = "twidget_buffer_request_throttle"
    private const val KEY_BLOCKED_UNTIL = "blocked_until"
    private const val KEY_LAST_SYNC_STARTED = "last_sync_started"
    private const val DEFAULT_RETRY_SECONDS = 15 * 60L
    private const val AUTOMATIC_SYNC_INTERVAL_MS = 15 * 60 * 1000L
    private const val MANUAL_SYNC_INTERVAL_MS = 30 * 1000L

    @Synchronized
    fun beginSync(context: Context, userInitiated: Boolean, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val preferences = prefs(context)
        val lastStarted = preferences.getLong(KEY_LAST_SYNC_STARTED, 0L)
        val interval = if (userInitiated) MANUAL_SYNC_INTERVAL_MS else AUTOMATIC_SYNC_INTERVAL_MS
        if (lastStarted > 0L && nowMillis - lastStarted in 0 until interval) return false
        return preferences.edit().putLong(KEY_LAST_SYNC_STARTED, nowMillis).commit()
    }

    fun blockingMessage(context: Context, nowMillis: Long = System.currentTimeMillis()): String? {
        val remaining = prefs(context).getLong(KEY_BLOCKED_UNTIL, 0L) - nowMillis
        if (remaining <= 0L) return null
        return "Buffer request limit reached. Try again in ${formatWait(remaining)}."
    }

    fun observe(context: Context, response: HttpTransport.Response, nowMillis: Long = System.currentTimeMillis()) {
        val retrySeconds = BufferRateLimitPolicy.retryAfterSeconds(response)
        if (retrySeconds == null) return
        val blockedUntil = nowMillis + retrySeconds.coerceAtLeast(1L) * 1000L
        val preferences = prefs(context)
        if (blockedUntil > preferences.getLong(KEY_BLOCKED_UNTIL, 0L)) {
            preferences.edit().putLong(KEY_BLOCKED_UNTIL, blockedUntil).apply()
        }
    }

    private fun formatWait(remainingMillis: Long): String {
        val totalMinutes = ((remainingMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
        return when {
            totalMinutes >= 24 * 60 -> {
                val days = (totalMinutes + 24 * 60 - 1) / (24 * 60)
                "$days day${if (days == 1L) "" else "s"}"
            }
            totalMinutes >= 60 -> {
                val hours = (totalMinutes + 59) / 60
                "$hours hour${if (hours == 1L) "" else "s"}"
            }
            else -> "$totalMinutes minute${if (totalMinutes == 1L) "" else "s"}"
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal object BufferRateLimitPolicy {
        private val remainingPattern = Regex("(?:^|;)\\s*r=(\\d+)", RegexOption.IGNORE_CASE)
        private val resetPattern = Regex("(?:^|;)\\s*t=(\\d+)", RegexOption.IGNORE_CASE)

        fun retryAfterSeconds(response: HttpTransport.Response): Long? {
            val explicit = response.headerValues("Retry-After")
                .firstNotNullOfOrNull { it.trim().toLongOrNull() }
            if (response.code == 429) return explicit ?: DEFAULT_RETRY_SECONDS

            return response.headerValues("RateLimit")
                .mapNotNull { value ->
                    val remaining = remainingPattern.find(value)?.groupValues?.get(1)?.toLongOrNull()
                    val reset = resetPattern.find(value)?.groupValues?.get(1)?.toLongOrNull()
                    reset?.takeIf { remaining == 0L }
                }
                .maxOrNull()
        }
    }
}
