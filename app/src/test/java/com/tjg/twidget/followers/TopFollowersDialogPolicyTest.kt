package com.tjg.twidget.followers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersDialogPolicyTest {
    @Test
    fun addKeyActionIsHiddenForPersonalKeys() {
        assertFalse(shouldShowAddApiKeyAction(TopFollowersScanSource.TWITTERAPIS))
        assertTrue(shouldShowAddApiKeyAction(null))
    }

    @Test
    fun scanDialogDescribesTheLinkedProvider() {
        assertTrue(selectTopFollowersScanDialogMode(TopFollowersScanSource.TWITTERAPIS) ==
            TopFollowersScanDialogMode.TWITTERAPIS)
        assertTrue(selectTopFollowersScanDialogMode(TopFollowersScanSource.X_API) ==
            TopFollowersScanDialogMode.X_API)
    }
}
