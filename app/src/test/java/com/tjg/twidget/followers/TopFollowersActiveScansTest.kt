package com.tjg.twidget.followers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersActiveScansTest {
    @Test
    fun activeStateIsProcessLocalAndUsernameInsensitive() {
        TopFollowersActiveScans.finished("SammyGurus", "old")
        assertFalse(TopFollowersActiveScans.isActive("sammygurus"))

        TopFollowersActiveScans.started("@SammyGurus", "new")
        assertTrue(TopFollowersActiveScans.isActive("sammygurus"))

        TopFollowersActiveScans.finished("SAMMYGURUS", "old")
        assertTrue(TopFollowersActiveScans.isActive("@sammygurus"))

        TopFollowersActiveScans.finished("SAMMYGURUS", "new")
        assertFalse(TopFollowersActiveScans.isActive("@sammygurus"))
    }
}
