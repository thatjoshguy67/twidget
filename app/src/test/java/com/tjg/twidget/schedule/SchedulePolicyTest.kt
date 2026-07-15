package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePolicyTest {
    private val now = 1_000_000L

    @Test
    fun acceptsValidFutureThread() {
        val issues = SchedulePolicy.validate(
            thread = listOf(ScheduleThreadItem(text = "Hello")),
            scheduledAt = now + 60_000,
            nowMillis = now,
        )

        assertTrue(issues.isEmpty())
    }

    @Test
    fun enforcesTextMediaAndFutureLimitsPerItem() {
        val issues = SchedulePolicy.validate(
            thread = listOf(
                ScheduleThreadItem(
                    text = "x".repeat(281),
                    media = List(5) { PublicUrlMedia("https://example.com/$it.jpg") },
                )
            ),
            scheduledAt = now,
            nowMillis = now,
        )

        assertEquals(
            setOf(
                ScheduleValidationCode.TEXT_TOO_LONG,
                ScheduleValidationCode.TOO_MANY_MEDIA,
                ScheduleValidationCode.NOT_IN_FUTURE,
            ),
            issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun requiresContentAndAtLeastOneThreadItem() {
        val emptyThread = SchedulePolicy.validate(emptyList(), now + 1, now)
        val emptyItem = SchedulePolicy.validate(
            listOf(ScheduleThreadItem(text = "  ")),
            now + 1,
            now,
        )

        assertEquals(ScheduleValidationCode.EMPTY_THREAD, emptyThread.single().code)
        assertEquals(ScheduleValidationCode.EMPTY_ITEM, emptyItem.single().code)
    }

    @Test
    fun premiumLimitAllowsLongerPostsButStillRejectsStandardPosts() {
        val text = "x".repeat(281)
        val accepted = SchedulePolicy.validate(
            listOf(ScheduleThreadItem(text = text)),
            now + 1,
            now,
            SchedulePolicy.PREMIUM_TEXT_LENGTH,
        )
        val rejected = SchedulePolicy.validate(
            listOf(ScheduleThreadItem(text = text)),
            now + 1,
            now,
            SchedulePolicy.STANDARD_TEXT_LENGTH,
        )

        assertTrue(accepted.isEmpty())
        assertEquals(ScheduleValidationCode.TEXT_TOO_LONG, rejected.single().code)
    }
}
