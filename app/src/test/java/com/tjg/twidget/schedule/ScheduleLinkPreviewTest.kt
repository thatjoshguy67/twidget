package com.tjg.twidget.schedule

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleLinkPreviewTest {
    @Test
    fun extractsTheFirstHttpUrlAndDropsSentencePunctuation() {
        assertEquals(
            "https://example.com/story",
            ScheduleLinkPreviewParser.firstUrl("Read https://example.com/story, then let me know."),
        )
        assertNull(ScheduleLinkPreviewParser.firstUrl("No link here"))
    }

    @Test
    fun parsesOpenGraphMetadataRegardlessOfAttributeOrder() {
        val preview = ScheduleLinkPreviewParser.parse(
            "https://example.com/articles/weekly",
            """
                <html><head>
                <meta content="Weekly &amp; useful" property="og:title">
                <meta content="/images/weekly.jpg" name="twitter:image">
                <title>Fallback title</title>
                </head></html>
            """.trimIndent(),
        )

        assertEquals("Weekly & useful", preview.title)
        assertEquals("https://example.com/images/weekly.jpg", preview.imageUrl)
        assertEquals("https://example.com", preview.displayUrl)
    }

    @Test
    fun rejectsPrivateAndCarrierGradeNetworkAddresses() {
        assertFalse(ScheduleLinkPreviewLoader.isPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(ScheduleLinkPreviewLoader.isPublicAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse(ScheduleLinkPreviewLoader.isPublicAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(ScheduleLinkPreviewLoader.isPublicAddress(InetAddress.getByName("fc00::1")))
        assertTrue(ScheduleLinkPreviewLoader.isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }
}
