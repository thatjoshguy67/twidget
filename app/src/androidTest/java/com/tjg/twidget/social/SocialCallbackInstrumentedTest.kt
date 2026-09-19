package com.tjg.twidget.social

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialCallbackInstrumentedTest {
    @Test fun instagramBrowserSuffixDoesNotBlockCallback() {
        assertTrue(SocialConnections.validCallbackAddress(Uri.parse("twidget://oauth/instagram?state=test&ticket=test#_")))
        assertTrue(SocialConnections.validCallbackAddress(Uri.parse("twidget://oauth/instagram?state=test&ticket=test")))
        assertTrue(SocialConnections.validCallbackAddress(Uri.parse("twidget://oauth/github?state=test&ticket=test")))
    }

    @Test fun otherFragmentsAndAuthoritiesAreRejected() {
        listOf(
            "twidget://oauth/instagram#access_token=untrusted",
            "twidget://oauth/github#_",
            "twidget://oauth/instagram#%5F",
            "twidget://user@oauth/instagram#_",
            "twidget://oauth:443/instagram#_",
            "https://oauth/instagram#_",
            "twidget://evil/instagram#_",
        ).forEach { assertFalse(it, SocialConnections.validCallbackAddress(Uri.parse(it))) }
    }
}
