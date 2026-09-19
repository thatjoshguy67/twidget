package com.tjg.twidget.notices

import com.tjg.twidget.update.ReleaseNotice
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LatestReleaseNoticeTest {
    private fun notice(tag: String) = ReleaseNotice(tag, tag, "Changes", "https://github.com/thatjoshguy67/twidget/releases/tag/$tag", false, "")

    @Test fun emptyCacheFetchesTheLatestChangelog() {
        val latest = notice("v1.3.0")
        assertEquals(latest, LatestReleaseNotice.load(null) { latest })
    }

    @Test fun staleCacheDoesNotPreventFetchingTheLatestChangelog() {
        val latest = notice("v1.3.0")
        assertEquals(latest, LatestReleaseNotice.load(notice("v1.2.0")) { latest })
    }

    @Test fun offlineReaderRetainsCachedChangelog() {
        val cached = notice("v1.3.0")
        assertEquals(cached, LatestReleaseNotice.load(cached) { throw IOException("offline") })
    }

    @Test fun offlineWithoutCacheReportsFailureForRetry() {
        assertThrows(IOException::class.java) { LatestReleaseNotice.load(null) { throw IOException("offline") } }
    }

    @Test fun emptyReleaseFeedDoesNotInventANotice() {
        assertNull(LatestReleaseNotice.load(null) { null })
    }
}
