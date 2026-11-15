package com.tjg.twidget.brief

import java.net.UnknownHostException

internal object BriefOAuthNetwork {
    /** DNS failures occur before sending a code or rotating refresh token; other failures are not replayed. */
    fun <T> resolveAndRequest(pause: (Long) -> Unit = { Thread.sleep(it) }, request: () -> T): T {
        for (attempt in 0..2) {
            try { return request() } catch (error: UnknownHostException) {
                if (attempt == 2) throw error
                pause((attempt + 1) * 1_000L)
            }
        }
        error("Unreachable")
    }
}
