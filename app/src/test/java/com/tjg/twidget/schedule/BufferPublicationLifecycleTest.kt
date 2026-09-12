package com.tjg.twidget.schedule

import com.tjg.twidget.brief.BriefSchedulePolicy
import org.junit.Assert.*
import org.junit.Test

class BufferPublicationLifecycleTest {
    private val dueAt = 1_000_000L

    private fun scheduled() = ScheduledPost(
        provider = ScheduleProvider.BUFFER,
        status = ScheduleStatus.SCHEDULED,
        accountId = "account",
        accountUsername = "channel",
        scheduledAt = dueAt,
        remotePostId = "remote-post",
        thread = listOf(ScheduleThreadItem(text = "Post")),
    )

    @Test
    fun overduePostCanBeCancelledWhileBufferHasNotConfirmedPublication() {
        val pending = ScheduleJsonCodec.decode(ScheduleJsonCodec.encode(
            BufferScheduleFallbackPolicy.reconcile(scheduled(), dueAt + 1),
        ))

        assertEquals(ScheduleStatus.AWAITING_CONFIRMATION, pending.status)
        assertNull(pending.publishedAt)
        assertTrue(BufferScheduleFallbackPolicy.requiresRemoteCancellation(pending))
        assertTrue(BufferScheduleFallbackPolicy.needsConfirmation(pending))
        assertFalse(BriefSchedulePolicy.isRelevant(pending, dueAt + 1))
    }

    @Test
    fun missingOrSendingPostRemainsPendingAndLaterSentNotifiesOnce() {
        val now = dueAt + 1
        val pending = BufferScheduleFallbackPolicy.reconcile(scheduled(), now)
        assertFalse(BufferScheduleSync.shouldRemoveMissing(pending, emptySet(), now))
        val stillPending = pending.copy(status = BufferScheduleSync.resolvedStatus("sending", dueAt, now)!!)
        assertTrue(BufferScheduleFallbackPolicy.needsConfirmation(stillPending))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferPublished(pending, stillPending.status))

        val sent = stillPending.copy(
            status = BufferScheduleSync.resolvedStatus("sent", dueAt, now)!!,
            publishedAt = dueAt,
        )
        assertTrue(ScheduleNotificationPolicy.shouldNotifyBufferPublished(stillPending, sent.status))
        val persisted = ScheduleJsonCodec.decode(ScheduleJsonCodec.encode(sent))
        assertEquals(sent, persisted)
        assertFalse(BufferScheduleFallbackPolicy.needsConfirmation(persisted))
        assertFalse(BufferScheduleFallbackPolicy.requiresRemoteCancellation(persisted))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferPublished(persisted, sent.status))
    }

    @Test
    fun delayedErrorIsStillQueriedAfterDaysOfflineAndNotifiesOnce() {
        val now = dueAt + 3 * 24 * 60 * 60 * 1000L
        val pending = BufferScheduleFallbackPolicy.reconcile(scheduled(), now)
        assertEquals(dueAt, BufferScheduleSync.terminalConfirmationTime(pending, now))
        val failed = pending.copy(status = BufferScheduleSync.resolvedStatus("error", dueAt, now)!!)
        assertTrue(ScheduleNotificationPolicy.shouldNotifyBufferFailed(pending, failed.status))
        assertFalse(BufferScheduleFallbackPolicy.needsConfirmation(failed))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferFailed(failed, failed.status))
    }

    @Test
    fun oldBetaPublishedRecordMustBeConfirmedBeforeSkippingRemoteCancellation() {
        val legacy = ScheduleJsonCodec.encode(scheduled().copy(
            status = ScheduleStatus.PUBLISHED,
            publishedAt = dueAt,
        )).replace(",\"bufferPublicationConfirmed\":true", "")
        val migrated = ScheduleJsonCodec.decode(legacy)

        assertEquals(ScheduleStatus.AWAITING_CONFIRMATION, migrated.status)
        assertNull(migrated.publishedAt)
        assertTrue(BufferScheduleFallbackPolicy.needsConfirmation(migrated))
        assertTrue(BufferScheduleFallbackPolicy.requiresRemoteCancellation(migrated))
        assertEquals(migrated, ScheduleJsonCodec.decode(ScheduleJsonCodec.encode(migrated)))
    }

    @Test
    fun legacyLocalPublicationsAndCancelledBufferPostsRemainTerminal() {
        val local = scheduled().copy(provider = ScheduleProvider.LOCAL_REMINDER, status = ScheduleStatus.PUBLISHED)
        val cancelled = scheduled().copy(status = ScheduleStatus.CANCELLED)
        for (post in listOf(local, cancelled)) {
            val legacy = ScheduleJsonCodec.encode(post).replace(",\"bufferPublicationConfirmed\":false", "")
            assertEquals(post, ScheduleJsonCodec.decode(legacy))
            assertFalse(BufferScheduleFallbackPolicy.needsConfirmation(post))
        }
    }

    @Test
    fun pendingPostCanBeRescheduledOrBecomeTerminal() {
        for (status in listOf(ScheduleStatus.SCHEDULED, ScheduleStatus.PUBLISHED, ScheduleStatus.NEEDS_ACTION, ScheduleStatus.CANCELLED)) {
            assertTrue(ScheduleStateTransitions.canMove(ScheduleStatus.AWAITING_CONFIRMATION, status))
        }
    }
}
