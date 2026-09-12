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

    @Test
    fun styledMarkdownClassifiesReleaseNoteBlocks() {
        val heading = ReleaseNoticeMarkdown.parseLine("### Added")
        val bullet = ReleaseNoticeMarkdown.parseLine("  - Nested improvement")
        val ordered = ReleaseNoticeMarkdown.parseLine("2. Second step")
        val quote = ReleaseNoticeMarkdown.parseLine("> Important note")
        val paragraph = ReleaseNoticeMarkdown.parseLine("Regular paragraph")

        assertEquals(ReleaseNoticeMarkdown.LineKind.HEADING, heading.kind)
        assertEquals(3, heading.level)
        assertEquals("Added", heading.content)
        assertEquals(ReleaseNoticeMarkdown.LineKind.BULLET, bullet.kind)
        assertEquals(1, bullet.depth)
        assertEquals(ReleaseNoticeMarkdown.LineKind.ORDERED, ordered.kind)
        assertEquals("2.", ordered.marker)
        assertEquals(ReleaseNoticeMarkdown.LineKind.QUOTE, quote.kind)
        assertEquals("Important note", quote.content)
        assertEquals(ReleaseNoticeMarkdown.LineKind.PARAGRAPH, paragraph.kind)
    }

    @Test
    fun styledMarkdownJoinsSourceWrappedListItemsAndParagraphs() {
        val markdown = """
            Intro text wraps in the
            source but remains one paragraph.

            - A longer release-note item
              continues across source lines
              without becoming new paragraphs.
            - The next item remains separate.
        """.trimIndent()

        val blocks = ReleaseNoticeMarkdown.parseBlocks(markdown)

        assertEquals(4, blocks.size)
        assertEquals(ReleaseNoticeMarkdown.LineKind.PARAGRAPH, blocks[0].kind)
        assertEquals("Intro text wraps in the source but remains one paragraph.", blocks[0].content)
        assertEquals(ReleaseNoticeMarkdown.LineKind.BLANK, blocks[1].kind)
        assertEquals(ReleaseNoticeMarkdown.LineKind.BULLET, blocks[2].kind)
        assertEquals(
            "A longer release-note item continues across source lines without becoming new paragraphs.",
            blocks[2].content,
        )
        assertEquals(ReleaseNoticeMarkdown.LineKind.BULLET, blocks[3].kind)
    }
}
