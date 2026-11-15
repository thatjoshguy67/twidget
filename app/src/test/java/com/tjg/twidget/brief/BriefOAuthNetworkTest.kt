package com.tjg.twidget.brief

import java.net.UnknownHostException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class BriefOAuthNetworkTest {
    @Test fun retriesDnsBeforeSendingTheAuthorizationCode() {
        var calls = 0
        val delays = mutableListOf<Long>()
        val result = BriefOAuthNetwork.resolveAndRequest(pause = { delays += it }) {
            if (++calls < 3) throw UnknownHostException()
            "connected"
        }
        assertEquals("connected", result)
        assertEquals(listOf(1000L, 2000L), delays)
        assertEquals(3, calls)
    }
    @Test fun persistentDnsFailureStopsAfterThreeAttempts() {
        var calls = 0
        try {
            BriefOAuthNetwork.resolveAndRequest(pause = {}) { calls++; throw UnknownHostException() }
            fail()
        } catch (_: UnknownHostException) { assertEquals(3, calls) }
    }
    @Test fun uncertainTokenExchangeIsNeverReplayed() {
        var calls = 0
        try {
            BriefOAuthNetwork.resolveAndRequest(pause = { fail() }) { calls++; throw SocketTimeoutException() }
            fail()
        } catch (_: SocketTimeoutException) { assertEquals(1, calls) }
    }
}
