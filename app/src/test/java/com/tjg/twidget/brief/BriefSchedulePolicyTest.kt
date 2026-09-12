package com.tjg.twidget.brief

import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduleThreadItem
import com.tjg.twidget.schedule.ScheduledPost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefSchedulePolicyTest {
    private val now = 1_000_000L

    @Test
    fun futureScheduledPostIsUpcoming() {
        assertTrue(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.SCHEDULED, now + 1), now))
    }

    @Test
    fun dueOrOverdueScheduledPostIsNotUpcoming() {
        assertFalse(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.SCHEDULED, now), now))
        assertFalse(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.SCHEDULED, now - 1), now))
    }

    @Test
    fun scheduledPostWithoutATimeIsNotUpcoming() {
        assertFalse(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.SCHEDULED, null), now))
    }

    @Test
    fun overdueActionablePostRemainsRelevant() {
        assertTrue(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.NEEDS_ACTION, now - 1), now))
        assertTrue(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.FAILED, now - 1), now))
    }

    @Test
    fun terminalAndDraftPostsAreNotRelevant() {
        assertFalse(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.PUBLISHED, now + 1), now))
        assertFalse(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.DRAFT, now + 1), now))
        assertFalse(BriefSchedulePolicy.isRelevant(post(ScheduleStatus.CANCELLED, now + 1), now))
    }

    private fun post(status: ScheduleStatus, scheduledAt: Long?) = ScheduledPost(
        provider = ScheduleProvider.BUFFER,
        status = status,
        accountUsername = "buffer-channel",
        scheduledAt = scheduledAt,
        thread = listOf(ScheduleThreadItem(text = "Post")),
    )
}
