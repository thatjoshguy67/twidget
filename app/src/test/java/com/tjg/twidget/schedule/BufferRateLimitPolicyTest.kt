package com.tjg.twidget.schedule

import com.tjg.twidget.core.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BufferRateLimitPolicyTest {
    @Test
    fun retryAfterHeaderControls429Cooldown() {
        val response = HttpTransport.Response(
            code = 429,
            body = "{}",
            headers = mapOf("Retry-After" to listOf("591")),
        )

        assertEquals(591L, BufferRequestThrottle.BufferRateLimitPolicy.retryAfterSeconds(response))
    }

    @Test
    fun exhaustedLongestRateLimitWindowControlsCooldown() {
        val response = HttpTransport.Response(
            code = 200,
            body = "{}",
            headers = mapOf(
                "RateLimit" to listOf(
                    "\"100-in-15min\";r=0;t=800",
                    "\"250-in-1day\";r=0;t=7200",
                    "\"7500-in-30days\";r=12;t=50000",
                ),
            ),
        )

        assertEquals(7200L, BufferRequestThrottle.BufferRateLimitPolicy.retryAfterSeconds(response))
    }

    @Test
    fun availableQuotaDoesNotCreateCooldown() {
        val response = HttpTransport.Response(
            code = 200,
            body = "{}",
            headers = mapOf("RateLimit" to listOf("\"100-in-15min\";r=42;t=800")),
        )

        assertNull(BufferRequestThrottle.BufferRateLimitPolicy.retryAfterSeconds(response))
    }
}
