package com.tjg.twidget.widget.coloros

import org.junit.Assert.*
import org.junit.Test

class ColorOsCardProtocolTest {
    private val rpk = "com.tjg.twidget.coloroscards"

    @Test fun bothSizesAcceptOpaqueHostSessionIds() {
        for (path in ColorOsCardProtocol.paths)
            assertNull(ColorOsCardProtocol.error("onCardUpdate", rpk, path, "session&com.android.systemui"))
    }

    @Test fun crossCardRequestsAndMissingIdentityAreRejected() {
        assertNotNull(ColorOsCardProtocol.error("onCardCreate", "other.app", "/cards/compact", "1"))
        assertNotNull(ColorOsCardProtocol.error("onCardCreate", rpk, "/cards/../wide", "1"))
        assertNotNull(ColorOsCardProtocol.error("onCardCreate", rpk, "/cards/compact", null))
        assertNotNull(ColorOsCardProtocol.error("onCardCreate", rpk, "/cards/compact", "a".repeat(513)))
        assertNotNull(ColorOsCardProtocol.error("onCardCreate", rpk, null, "1"))
    }

    @Test fun unknownCommandsCannotAccessData() {
        assertNotNull(ColorOsCardProtocol.error("readCredentials", rpk, "/cards/compact", "1"))
    }

    @Test fun teardownRetainsTheSameIdentityContract() {
        for (method in listOf("onCardDestroy", "onCardSubscribe", "onCardUnsubscribe"))
            assertNull(ColorOsCardProtocol.error(method, rpk, "/cards/wide", "1"))
    }
}
