package com.tjg.twidget.brief

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tjg.twidget.core.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefOAuthConnectivityInstrumentedTest {
    @Test fun publicDiscoveryIsReachableFromTheAppProcess() {
        // No credentials, token exchange or changes to the user's settings.
        val addresses = java.net.InetAddress.getAllByName("auth.openai.com")
        check(addresses.isNotEmpty())
        assertEquals(200, HttpTransport.get("https://auth.openai.com/.well-known/openid-configuration").code)
    }
}
