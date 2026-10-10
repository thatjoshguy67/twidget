package com.tjg.twidget.update

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PlayUpdateBoundaryTest {
    @Test fun allUpdateChannelsAreDisabledWithoutNetworkAccess() {
        UpdateChannel.entries.forEach { channel ->
            assertNull(AppUpdateManager.findUpdate("1.0.0", channel))
            assertEquals(AppReleaseCheck(null, emptyList()), AppUpdateManager.checkReleases("1.0.0", channel))
        }
    }

    @Test fun apkDownloadIsRejectedBeforeCreatingFilesOrOpeningTheNetwork() {
        val directory = File(System.getProperty("java.io.tmpdir"), "play-updater-${System.nanoTime()}")
        val release = AppRelease(AppVersion(99, 0, 0, null, null), "update.apk", "https://invalid.example/update.apk", false)
        assertThrows(IllegalStateException::class.java) { AppUpdateManager.download(release, directory) }
        assertFalse(directory.exists())
    }
}
