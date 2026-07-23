package com.tjg.twidget.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAccountPopupTest {
    @Test
    fun `default account menu always includes analytics import`() {
        val actions = accountPopupActions(isDefault = true)

        assertEquals(listOf(AccountPopupAction.IMPORT_ANALYTICS, AccountPopupAction.DELETE), actions)
    }

    @Test
    fun `non-default account menu keeps all three actions`() {
        val actions = accountPopupActions(isDefault = false)

        assertEquals(AccountPopupAction.SET_DEFAULT, actions.first())
        assertTrue(AccountPopupAction.IMPORT_ANALYTICS in actions)
        assertEquals(AccountPopupAction.DELETE, actions.last())
    }
}
