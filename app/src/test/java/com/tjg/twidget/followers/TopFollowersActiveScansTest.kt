package com.tjg.twidget.followers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersActiveScansTest {
    @Test
    fun activeStateIsProcessLocalAndUsernameInsensitive() {
        TopFollowersActiveScans.finished("SammyGurus")
        assertFalse(TopFollowersActiveScans.isActive("sammygurus"))

        TopFollowersActiveScans.started("@SammyGurus")
        assertTrue(TopFollowersActiveScans.isActive("sammygurus"))

        TopFollowersActiveScans.finished("SAMMYGURUS")
        assertFalse(TopFollowersActiveScans.isActive("@sammygurus"))
    }
}
