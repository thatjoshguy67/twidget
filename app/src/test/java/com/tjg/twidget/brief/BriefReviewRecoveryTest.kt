package com.tjg.twidget.brief

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BriefReviewRecoveryTest {
    private val target = BriefReviewTarget("example", postUrl = "https://x.com/example/status/123")
    private val subject = BriefReviewSubject(target, BriefCoachInput("published", "Hello", true, 0,
        BriefCoachReport(emptyList(), emptyList(), emptyList(), emptyList()), emptyList(), 1L, "en"))

    @Test fun staleMissingAnalyticsIsRefreshedBeforeGivingUp() = runBlocking {
        var refreshed = false
        var loads = 0
        val found = BriefReviewSubjects.recoverMissing(target, load = {
            loads++; if (refreshed) subject else null
        }, needsRefresh = { true }, refresh = { refreshed = true })
        assertEquals(subject, found)
        assertEquals(2, loads)
    }

    @Test fun validCacheAndDraftsDoNotTriggerRecoveryFetches() = runBlocking {
        var refreshes = 0
        assertEquals(subject, BriefReviewSubjects.recoverMissing(target, { subject }, { true }, { refreshes++ }))
        assertNull(BriefReviewSubjects.recoverMissing(target, { null }, { false }, { refreshes++ }))
        assertNull(BriefReviewSubjects.recoverMissing(target.copy(draftId = "draft", postUrl = null), { null }, { true }, { refreshes++ }))
        assertEquals(0, refreshes)
    }

    @Test fun analyticsFailureIsActionableAndCancellationPropagates() = runBlocking {
        try {
            BriefReviewSubjects.recoverMissing(target, { null }, { true }, { throw java.io.IOException("offline") })
            fail("Expected analytics error")
        } catch (error: BriefReviewException) { assertEquals(BriefReviewFailure.ANALYTICS, error.reason) }
        try {
            BriefReviewSubjects.recoverMissing(target, { null }, { true }, { throw CancellationException("left page") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertNull(BriefReviewSubjects.recoverMissing(target, { null }, { true }, { }))
    }
    @Test fun existingEvidenceSurvivesFailedAnalyticsRefreshWithoutChangingItsTimestamp() = runBlocking {
        var failures = 0
        val current = BriefReviewSubjects.refreshAvailable(subject,
            refresh = { throw java.io.IOException("offline") }, reload = { null }, onFailure = { failures++ })
        assertSame(subject, current)
        assertEquals(1L, current.input.observedAt)
        assertEquals(1, failures)
    }

    @Test fun successfulRefreshUsesNewEvidenceAndCancellationStillPropagates() = runBlocking {
        val newer = subject.copy(input = subject.input.copy(observedAt = 2L))
        assertSame(newer, BriefReviewSubjects.refreshAvailable(subject, {}, { newer }))
        try {
            BriefReviewSubjects.refreshAvailable(subject, { throw CancellationException("cancelled") }, { subject })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

}
