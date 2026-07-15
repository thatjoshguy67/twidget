package com.tjg.twidget.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNoticeTextTest {
    @Test
    fun releaseMarkdownBecomesReadableCardText() {
        val markdown = """
            ## Highlights
            - **Better analytics** with [range filters](https://example.com)
            - Calendar view
        """.trimIndent()

        assertEquals(
            "Highlights\n• Better analytics with range filters\n• Calendar view",
            ReleaseNoticeText.plainText(markdown),
        )
    }

    @Test
    fun latestNoticeIsUnseenUntilItsTagHasBeenRecorded() {
        assertTrue(ReleaseNoticesStore.hasUnseen("v1.1.0", null))
        assertTrue(ReleaseNoticesStore.hasUnseen("v1.1.0", "v1.0.0"))
        assertFalse(ReleaseNoticesStore.hasUnseen("v1.1.0", "v1.1.0"))
        assertFalse(ReleaseNoticesStore.hasUnseen(null, "v1.0.0"))
    }

    @Test
    fun fullChangelogKeepsEverySectionAndBullet() {
        val markdown = """
            ### Added
            - First feature
            - Second feature

            ### Fixed
            - Important fix
        """.trimIndent()

        assertEquals(
            "Added\n• First feature\n• Second feature\n\nFixed\n• Important fix",
            ReleaseNoticeText.plainText(markdown),
        )
    }
}
