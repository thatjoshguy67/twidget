package com.tjg.twidget.update

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadControllerTest {
    @After
    fun resetController() {
        UpdateDownloadController.finish()
    }

    @Test
    fun onlyOneDownloadCanBeActive() {
        assertTrue(UpdateDownloadController.tryBegin())
        assertFalse(UpdateDownloadController.tryBegin())

        UpdateDownloadController.finish()

        assertTrue(UpdateDownloadController.tryBegin())
    }

    @Test
    fun stopCancelsActiveDownload() {
        assertTrue(UpdateDownloadController.tryBegin())

        UpdateDownloadController.stop()

        assertFalse(UpdateDownloadController.awaitPermissionToContinue())
    }
}
