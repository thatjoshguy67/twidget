package com.tjg.twidget.followers

import com.tjg.twidget.providers.TwitterApisAccessSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersDialogPolicyTest {
    @Test
    fun addKeyActionIsHiddenForPersonalKeys() {
        assertFalse(shouldShowAddApiKeyAction(TwitterApisAccessSource.PERSONAL))
        assertTrue(shouldShowAddApiKeyAction(TwitterApisAccessSource.APP_DEFAULT))
        assertTrue(shouldShowAddApiKeyAction(null))
    }
}
