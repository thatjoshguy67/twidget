package com.tjg.twidget

import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.content.res.AppCompatResources
import dev.oneuiproject.oneui.layout.DrawerLayout
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import dev.oneuiproject.oneui.navigation.widget.DrawerNavigationView
import dev.oneuiproject.oneui.R as OneUiIconR

/** Keeps Scheduling inside the same account-aware navigation shell as Home. */
internal class ScheduleDrawerController(
    private val activity: ScheduleActivity,
) {
    private val accountItemIds = mutableMapOf<Int, String>()

    fun setup() {
        activity.findViewById<NavDrawerLayout>(R.id.schedule_root).apply {
            closeNavRailOnBack = true
            setHideNavRailDrawerOnCollapse(false)
        }
        activity.findViewById<DrawerNavigationView>(R.id.schedule_drawer_nav)
            .setNavigationItemSelectedListener(::handleItem)
        activity.findViewById<DrawerLayout>(R.id.schedule_root).setupHeaderButton(
            requireNotNull(
                AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_settings_outline),
            ),
            activity.getColor(R.color.oneui_text_secondary),
            activity.getString(R.string.settings),
        ) {
            closeOnCompactScreens()
            activity.startLeftSidePopOverActivity(Intent(activity, SettingsActivity::class.java))
        }
        rebuild()
    }

    fun rebuild() {
        val navigation = activity.findViewById<DrawerNavigationView>(R.id.schedule_drawer_nav)
        val menu = navigation.drawerMenu()
        accountItemIds.clear()
        menu.clear()

        activity.scheduleAccounts().forEachIndexed { index, account ->
            val stats = TwidgetStore.currentStats(activity, account)
            val itemId = ACCOUNT_ITEM_BASE + index
            accountItemIds[itemId] = account
            menu.add(
                GROUP_ACCOUNTS,
                itemId,
                index,
                VerifiedBadge.decorate(
                    activity,
                    stats.fullName.ifBlank { account },
                    stats.isVerified,
                    stats.isPrivate,
                    activity.dp(17),
                ),
            ).apply {
                setIcon(OneUiIconR.drawable.ic_oui_samsung_account)
                isCheckable = true
                isChecked = account.equals(activity.selectedScheduleAccount(), ignoreCase = true)
                contentDescription = stats.fullName.ifBlank { account }
            }
        }
        menu.setGroupCheckable(GROUP_ACCOUNTS, true, true)

        val actionOrder = activity.scheduleAccounts().size
        menu.add(GROUP_ACTIONS, ITEM_ADD_ACCOUNT, actionOrder, activity.getString(R.string.add_account)).apply {
            setIcon(OneUiIconR.drawable.ic_oui_add)
        }
        menu.add(GROUP_ACTIONS, ITEM_SCHEDULE, actionOrder + 1, activity.getString(R.string.schedule_title)).apply {
            setIcon(OneUiIconR.drawable.ic_oui_time_outline)
            isCheckable = true
            isChecked = !activity.isViewingScheduleTrash()
        }
        menu.add(GROUP_ACTIONS, ITEM_TRASH, actionOrder + 2, activity.getString(R.string.schedule_trash)).apply {
            setIcon(OneUiIconR.drawable.ic_oui_delete_outline)
            isCheckable = true
            isChecked = activity.isViewingScheduleTrash()
        }
        navigation.refreshDrawerMenu()
    }

    private fun handleItem(item: MenuItem): Boolean {
        accountItemIds[item.itemId]?.let { account ->
            activity.selectScheduleAccount(account)
            closeOnCompactScreens()
            return true
        }
        when (item.itemId) {
            ITEM_ADD_ACCOUNT -> activity.startAddAccountActivity()
            ITEM_SCHEDULE -> activity.openSchedulePage()
            ITEM_TRASH -> activity.openTrashPopover()
            else -> return false
        }
        closeOnCompactScreens()
        return true
    }

    private fun closeOnCompactScreens() {
        activity.findViewById<NavDrawerLayout>(R.id.schedule_root).apply {
            if (!isLargeScreenMode) setDrawerOpen(false, animate = true)
        }
    }

    private fun DrawerNavigationView.drawerMenu(): Menu {
        val field = DrawerNavigationView::class.java.getDeclaredField("navDrawerMenu")
        field.isAccessible = true
        return field.get(this) as Menu
    }

    private fun DrawerNavigationView.refreshDrawerMenu() {
        val field = DrawerNavigationView::class.java.getDeclaredField("menuPresenter")
        field.isAccessible = true
        val presenter = field.get(this)
        val method = presenter.javaClass.getDeclaredMethod("updateMenuView", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(presenter, false)
    }

    private companion object {
        private const val GROUP_ACCOUNTS = 1
        private const val GROUP_ACTIONS = 2
        private const val ACCOUNT_ITEM_BASE = 30_000
        private const val ITEM_ADD_ACCOUNT = 40_000
        private const val ITEM_SCHEDULE = 40_001
        private const val ITEM_TRASH = 40_002
    }
}
