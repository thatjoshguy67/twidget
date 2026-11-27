package com.tjg.twidget.main

import com.tjg.twidget.data.ProfileStats
import org.junit.Assert.*
import org.junit.Test

class OnboardingAccountSessionTest {
    private fun profile(handle: String) = ProfileStats("Display name", handle, 10, 1, 2, 0)

    @Test fun lookupRemainsAPreviewUntilAcceptedAndConfirmationIsIdempotent() {
        val session = OnboardingAccountSession()
        val saved = mutableListOf<ProfileStats>()
        assertNull(session.confirm { saved.add(it) })
        session.candidate = profile("example")
        assertFalse(session.confirmed)
        assertTrue(saved.isEmpty())
        assertEquals(session.candidate, session.confirm { saved.add(it) })
        session.confirm { saved.add(it) }
        assertEquals(listOf(session.candidate), saved)
    }

    @Test fun choosingAnotherHandleRequiresAnotherExplicitConfirmation() {
        val session = OnboardingAccountSession().apply { candidate = profile("first") }
        val saved = mutableListOf<String>()
        session.confirm { saved.add(it.userName) }
        session.candidate = profile("second")
        assertFalse(session.confirmed)
        assertEquals(listOf("first"), saved)
        session.confirm { saved.add(it.userName) }
        assertEquals(listOf("first", "second"), saved)
    }

    @Test fun failedConfirmationCanBeRetried() {
        val session = OnboardingAccountSession().apply { candidate = profile("example") }
        try { session.confirm { error("storage unavailable") }; fail() } catch (_: IllegalStateException) { }
        assertFalse(session.confirmed)
        session.confirm { }
        assertTrue(session.confirmed)
    }
}
