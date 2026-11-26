package com.tjg.twidget.data

import org.junit.Assert.*
import org.junit.Test

class AccountHandleMigrationTest {
    @Test fun onlyExactAccountKeysMove() {
        assertEquals("profile_new", AccountHandleMigration.mainKey("profile_old", "old", "new"))
        assertEquals("chart_range_new_followers", AccountHandleMigration.mainKey("chart_range_old_followers", "old", "new"))
        assertEquals("today_open_new", AccountHandleMigration.mainKey("today_open_Old", "Old", "new"))
        assertNull(AccountHandleMigration.mainKey("profile_older", "old", "new"))
        assertNull(AccountHandleMigration.mainKey("chart_range_old_extra_followers", "old", "new"))
        assertEquals("new", AccountHandleMigration.companionKey("twidget_milestone_celebrations", "old", "old", "new"))
        assertEquals("new_goals", AccountHandleMigration.companionKey("twidget_account_goals", "old_goals", "old", "new"))
        assertNull(AccountHandleMigration.companionKey("twidget_account_goals", "old_extra_goals", "old", "new"))
        assertNull(AccountHandleMigration.companionKey("twidget_daily_streak", "old_extra_version", "old", "new"))
        assertNull(AccountHandleMigration.mainKey("x_api_token", "old", "new"))
        assertNull(AccountHandleMigration.mainKey("widget_account_1", "old", "new"))
    }
}
