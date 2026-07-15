package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class XComposeIntentsTest {
    @Test
    fun webComposeUrlUsesUtf8QueryEncoding() {
        assertEquals(
            "https://x.com/intent/post?text=Hello%20%26%20caf%C3%A9%20%F0%9F%9A%80%0Anext",
            XComposeIntents.buildWebComposeUrl("Hello & café 🚀\nnext"),
        )
    }

    @Test
    fun queryValueDoesNotUseFormEncodingForSpaces() {
        assertEquals("a%20b%2Bc", XComposeIntents.encodeQueryValue("a b+c"))
    }

    @Test
    fun appComposeUriUsesTwitterDeepLink() {
        assertEquals(
            "twitter://post?message=Hello%20world",
            XComposeIntents.buildAppComposeUri("Hello world").toString(),
        )
    }
}
