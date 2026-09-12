package com.tjg.twidget.notices

import com.tjg.twidget.update.ReleaseNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingReleaseNotesTest {
    private val changelog = """
        # Changelog

        ## [1.2.0-beta.2] - 2026-08-09
        Beta notes.

        ## [1.2.0] - 2026-09-12
        Meet Your Brief.

        ### Your Brief

        - **Home-screen widget.** Text that
          adapts to the available space.

        ### Scheduling

        - Await publication confirmation.

        [1.2.0]: https://example.com/compare

        ## [1.1.1] - 2026-07-26
        Older notes.
    """.trimIndent()

    @Test
    fun debugBuildPreviewsItsStableVersionAndPreservesFeatureFormatting() {
        val notice = UpcomingReleaseNotes.parse("1.2.0-debug.68", changelog)!!
        assertEquals("upcoming-1.2.0", notice.tag)
        assertEquals("Twidget v1.2.0", notice.title)
        assertTrue(notice.upcoming)
        assertFalse(notice.prerelease)
        assertEquals("", notice.publishedAt)
        assertTrue(notice.body.startsWith("Meet Your Brief."))
        assertTrue(notice.body.contains("### Your Brief"))
        assertTrue(notice.body.contains("**Home-screen widget.** Text that\n  adapts"))
        assertTrue(notice.body.contains("### Scheduling"))
        assertFalse(notice.body.contains("Beta notes"))
        assertFalse(notice.body.contains("Older notes"))
        assertFalse(notice.body.contains("[1.2.0]:"))
    }

    @Test
    fun stableAndBetaBuildsNeverGetUpcomingNotes() {
        for (version in listOf("1.2.0", "1.2.0-beta.2", "invalid")) {
            assertNull(UpcomingReleaseNotes.parse(version, changelog))
        }
    }

    @Test
    fun missingOrEmptyVersionSectionDoesNotShowAnEmptyPreview() {
        assertNull(UpcomingReleaseNotes.parse("1.3.0-debug.1", changelog))
        assertNull(UpcomingReleaseNotes.parse("1.2.0-debug.1", "## [1.2.0]\n\n## [1.1.1]\nOlder"))
        assertNull(UpcomingReleaseNotes.parse("1.2.0-debug.1", "## [1.2.0]\n[1.2.0]: https://example.com"))
    }

    @Test
    fun previewIsAvailableBeforeAnyPublishedNoticesHaveBeenDownloaded() {
        val preview = UpcomingReleaseNotes.parse("1.2.0-debug.1", changelog)!!
        assertEquals(listOf(preview), UpcomingReleaseNotes.merge(preview, emptyList()))
    }

    @Test
    fun previewStaysAboveBetaAndOlderReleasesAfterRefresh() {
        val preview = UpcomingReleaseNotes.parse("1.2.0-debug.1", changelog)!!
        val published = listOf(release("twidget-v1.2.0-beta.2", beta = true), release("twidget-v1.1.1"))
        assertEquals(listOf(preview) + published, UpcomingReleaseNotes.merge(preview, published))
    }

    @Test
    fun publishedStableReleaseReplacesTheUpcomingPreview() {
        val preview = UpcomingReleaseNotes.parse("1.2.0-debug.1", changelog)!!
        for (tag in listOf("twidget-v1.2.0", "v1.2.0", "twidget-v1.3.0")) {
            val published = listOf(release(tag))
            assertEquals(published, UpcomingReleaseNotes.merge(preview, published))
        }
    }

    @Test
    fun buildsWithoutPreviewKeepThePublishedFeedUnchanged() {
        val published = listOf(release("twidget-v1.2.0"))
        assertEquals(published, UpcomingReleaseNotes.merge(null, published))
    }

    private fun release(tag: String, beta: Boolean = false) = ReleaseNotice(
        tag = tag,
        title = tag,
        body = "Published notes",
        url = "https://github.com/thatjoshguy67/twidget/releases/tag/$tag",
        prerelease = beta,
        publishedAt = "2026-09-12T00:00:00Z",
    )
}
