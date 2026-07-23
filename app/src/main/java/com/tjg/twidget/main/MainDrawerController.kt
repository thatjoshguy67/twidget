package com.tjg.twidget.main

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsImportActivity
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.settings.SettingsActivity
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.VerifiedBadge
import com.tjg.twidget.ui.startAddAccountActivity
import com.tjg.twidget.ui.startLeftSidePopOverActivity
import dev.oneuiproject.oneui.R as OneUiIconR
import dev.oneuiproject.oneui.design.R as OneUiDesignR
import dev.oneuiproject.oneui.layout.DrawerLayout
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import dev.oneuiproject.oneui.navigation.widget.DrawerNavigationView

internal class MainDrawerController(
    private val activity: EdgeToEdgeActivity,
    private val drawerLayoutId: Int,
    private val drawerNavigationId: Int,
    private val accounts: () -> List<String>,
    private val selectedAccount: () -> String,
    private val onAccountSelected: (String) -> Unit,
    private val isEditMode: () -> Boolean = { false },
    private val exitEditMode: () -> Unit = {},
    private val isSchedulePage: () -> Boolean = { false },
    private val openSchedule: () -> Unit,
) {
    private val drawerAccountItemIds = mutableMapOf<Int, String>()
    private val drawerAvatarItemIds = mutableSetOf<Int>()
    private val downloadingDrawerAvatarUrls = mutableSetOf<String>()
    private var showingEditNavigation = false

    fun setupDrawerChrome() {
        activity.findViewById<NavDrawerLayout>(drawerLayoutId).apply {
            closeNavRailOnBack = true
            setHideNavRailDrawerOnCollapse(false)
        }
        activity.findViewById<DrawerNavigationView>(drawerNavigationId)
            .setNavigationItemSelectedListener { item -> handleDrawerItemSelected(item) }
        activity.findViewById<DrawerLayout>(drawerLayoutId).setupHeaderButton(
            requireNotNull(AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_settings_outline)),
            activity.getColor(R.color.oneui_text_secondary),
            activity.getString(R.string.settings),
        ) {
            closeDrawerOnCompactScreens()
            activity.startLeftSidePopOverActivity(Intent(activity, SettingsActivity::class.java))
        }
    }

    fun buildDrawer() {
        val drawerNav = activity.findViewById<DrawerNavigationView>(drawerNavigationId)
        val menu = drawerNav.drawerMenu()
        val drawerAccounts = accounts()

        drawerAccountItemIds.clear()
        drawerAvatarItemIds.clear()
        menu.clear()
        drawerAccounts.forEachIndexed { index, account ->
            val stats = TwidgetStore.currentStats(activity, account)
            val itemId = DRAWER_ACCOUNT_ITEM_BASE + index
            drawerAccountItemIds[itemId] = account
            menu.add(
                DRAWER_GROUP_ACCOUNTS,
                itemId,
                index,
                VerifiedBadge.decorate(activity, stats.fullName.ifBlank { account }, stats.isVerified, stats.isPrivate, dp(17)),
            ).apply {
                val (icon, isAvatar) = drawerAccountIcon(stats)
                setIcon(icon)
                if (isAvatar) drawerAvatarItemIds += itemId
                isCheckable = true
                isChecked = account.equals(selectedAccount(), ignoreCase = true)
                contentDescription = stats.fullName.ifBlank { account }
            }
        }

        menu.setGroupCheckable(DRAWER_GROUP_ACCOUNTS, true, true)
        menu.add(
            DRAWER_GROUP_ACTIONS,
            DRAWER_ITEM_ADD_ACCOUNT,
            drawerAccounts.size,
            activity.getString(R.string.add_account),
        ).apply {
            setIcon(OneUiIconR.drawable.ic_oui_add)
            contentDescription = activity.getString(R.string.add_account)
        }
        menu.add(
            DRAWER_GROUP_ACTIONS,
            DRAWER_ITEM_SCHEDULE,
            drawerAccounts.size + 1,
            activity.getString(R.string.schedule_title),
        ).apply {
            setIcon(OneUiIconR.drawable.ic_oui_time_outline)
            isCheckable = true
            isChecked = isSchedulePage()
            contentDescription = activity.getString(R.string.schedule_title)
        }
        drawerNav.refreshDrawerMenu()
        drawerNav.post {
            applyDrawerIconTints(drawerNav)
            attachDrawerAccountLongPresses(drawerNav)
        }
    }

    fun renderHeader() {
        val stats = TwidgetStore.currentStats(activity, selectedAccount())
        activity.findViewById<DrawerLayout>(drawerLayoutId).apply {
            if (isEditMode()) {
                renderEditModeNavigation(this)
                setTitle(activity.getString(R.string.edit_dashboard))
                setSubtitle("")
            } else {
                renderDrawerNavigation(this)
                val name = stats.fullName.ifBlank { "@${stats.userName}" }
                setTitle(VerifiedBadge.decorate(activity, name, stats.isVerified, stats.isPrivate, dp(26)))
                setSubtitle("@${stats.userName} · ${TwidgetStore.lastSyncedText(activity, stats)}")
            }
        }
    }

    private fun renderEditModeNavigation(layout: DrawerLayout) {
        if (!showingEditNavigation) {
            layout.setExpanded(expanded = false, animate = true)
            layout.isExpandable = false
            showingEditNavigation = true
        }
        layout.showNavigationButton = true
        val closeIcon = AppCompatResources.getDrawable(activity, R.drawable.ic_dashboard_edit_close)
        if (layout is NavDrawerLayout && layout.isLargeScreenMode) {
            layout.setNavigationButtonIcon(
                AppCompatResources.getDrawable(activity, OneUiDesignR.drawable.oui_des_ic_ab_drawer),
            )
            layout.setNavigationButtonTooltip(
                activity.getString(OneUiDesignR.string.oui_des_navigation_drawer),
            )
            layout.findViewById<ImageView>(OneUiDesignR.id.navRailDrawerButton)?.alpha = 1f
        } else {
            layout.setNavigationButtonIcon(closeIcon)
            layout.setNavigationButtonTooltip(activity.getString(R.string.done))
        }
        layout.findViewById<Toolbar>(OneUiDesignR.id.toolbarlayout_main_toolbar).apply {
            navigationIcon = closeIcon
            setNavigationOnClickListener { exitEditMode() }
        }
    }

    private fun renderDrawerNavigation(layout: DrawerLayout) {
        if (showingEditNavigation) {
            layout.isExpandable = true
            layout.setExpanded(expanded = true, animate = true)
            showingEditNavigation = false
        }
        layout.showNavigationButton = true
        val drawerIcon = AppCompatResources.getDrawable(activity, OneUiDesignR.drawable.oui_des_ic_ab_drawer)
        layout.setNavigationButtonIcon(drawerIcon)
        layout.setNavigationButtonTooltip(
            activity.getString(OneUiDesignR.string.oui_des_navigation_drawer),
        )
        layout.findViewById<Toolbar>(OneUiDesignR.id.toolbarlayout_main_toolbar).apply {
            if (layout is NavDrawerLayout && layout.isLargeScreenMode) {
                // NavDrawerLayout renders the drawer affordance in the navigation rail on
                // large screens. Restoring it here as well leaves two hamburger buttons.
                navigationIcon = null
                setNavigationOnClickListener(null)
            } else {
                navigationIcon = drawerIcon
                setNavigationOnClickListener { layout.setDrawerOpen(true, true) }
            }
        }
    }

    private fun attachDrawerAccountLongPresses(drawerNav: DrawerNavigationView) {
        drawerAccountItemIds.forEach { (itemId, account) ->
            drawerNav.findViewById<View>(itemId)?.setOnLongClickListener {
                closeDrawerOnCompactScreens()
                activity.startActivity(
                    Intent(activity, AnalyticsImportActivity::class.java)
                        .putExtra(AnalyticsImportActivity.EXTRA_USERNAME, account),
                )
                true
            }
        }
    }

    private fun drawerAccountIcon(stats: ProfileStats): Pair<Drawable, Boolean> {
        val iconSize = activity.resources.getDimensionPixelSize(OneUiDesignR.dimen.oui_des_drawer_menu_item_icon_size)
        ProfileImageLoader.cachedCircularBitmap(activity, stats.profileImage, iconSize)?.let { bitmap ->
            return BitmapDrawable(activity.resources, bitmap).apply {
                setTintList(null)
                clearColorFilter()
            } to true
        }

        queueDrawerAvatarDownload(stats.profileImage)
        val fallback = requireNotNull(AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_samsung_account))
        return fallback to false
    }

    private fun queueDrawerAvatarDownload(url: String) {
        if (url.isBlank()) return
        synchronized(downloadingDrawerAvatarUrls) {
            if (!downloadingDrawerAvatarUrls.add(url)) return
        }
        AppExecutors.execute(onRejected = {
            synchronized(downloadingDrawerAvatarUrls) {
                downloadingDrawerAvatarUrls.remove(url)
            }
        }) {
            val loaded = ProfileImageLoader.downloadToCache(activity.applicationContext, url) != null
            synchronized(downloadingDrawerAvatarUrls) {
                downloadingDrawerAvatarUrls.remove(url)
            }
            if (loaded) activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) buildDrawer()
            }
        }
    }

    private fun applyDrawerIconTints(drawerNav: DrawerNavigationView) {
        val normalTint = AppCompatResources.getColorStateList(
            activity,
            OneUiDesignR.color.oui_des_drawer_menu_item_text_color_selector,
        )
        drawerAccountItemIds.keys.forEach { itemId ->
            val iconView = drawerNav.findViewById<View>(itemId)
                ?.findViewById<ImageView>(OneUiDesignR.id.drawer_menu_item_icon)
                ?: return@forEach
            if (itemId in drawerAvatarItemIds) {
                applyDrawerAvatarAppearance(iconView)
                ensureDrawerAvatarRenderGuard(iconView)
            } else {
                removeDrawerAvatarRenderGuard(iconView)
                iconView.imageTintList = normalTint
                iconView.alpha = DRAWER_STANDARD_ICON_ALPHA
            }
        }
        listOf(
            DRAWER_ITEM_ADD_ACCOUNT,
            DRAWER_ITEM_SCHEDULE,
        ).forEach { itemId ->
            drawerNav.findViewById<View>(itemId)
                ?.findViewById<ImageView>(OneUiDesignR.id.drawer_menu_item_icon)
                ?.also { iconView ->
                    removeDrawerAvatarRenderGuard(iconView)
                    iconView.imageTintList = normalTint
                    iconView.alpha = DRAWER_STANDARD_ICON_ALPHA
                }
        }
    }

    private fun applyDrawerAvatarAppearance(iconView: ImageView) {
        iconView.alpha = 1f
        iconView.imageTintList = null
        iconView.clearColorFilter()
        iconView.drawable?.mutate()?.let { avatar ->
            DrawableCompat.setTintList(avatar, null)
            avatar.clearColorFilter()
            iconView.setImageDrawable(avatar)
        }
    }

    private fun ensureDrawerAvatarRenderGuard(iconView: ImageView) {
        if (iconView.getTag(R.id.drawer_avatar_render_guard) is DrawerAvatarRenderGuard) return
        DrawerAvatarRenderGuard(iconView).also { guard ->
            iconView.setTag(R.id.drawer_avatar_render_guard, guard)
        }
    }

    private fun removeDrawerAvatarRenderGuard(iconView: ImageView) {
        (iconView.getTag(R.id.drawer_avatar_render_guard) as? DrawerAvatarRenderGuard)?.dispose()
        iconView.setTag(R.id.drawer_avatar_render_guard, null)
    }

    private class DrawerAvatarRenderGuard(
        private val iconView: ImageView,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private var observer: ViewTreeObserver? = null

        init {
            iconView.addOnAttachStateChangeListener(this)
            if (iconView.isAttachedToWindow) attach()
        }

        override fun onPreDraw(): Boolean {
            if (iconView.getTag(R.id.drawer_avatar_render_guard) !== this) {
                dispose()
                return true
            }
            if (iconView.alpha != 1f) iconView.alpha = 1f
            if (iconView.imageTintList != null) iconView.imageTintList = null
            if (iconView.colorFilter != null) iconView.clearColorFilter()
            iconView.drawable?.let { avatar ->
                if (avatar.colorFilter != null) {
                    DrawableCompat.setTintList(avatar, null)
                    avatar.clearColorFilter()
                }
            }
            return true
        }

        override fun onViewAttachedToWindow(view: View) = attach()

        override fun onViewDetachedFromWindow(view: View) = detach()

        fun dispose() {
            detach()
            iconView.removeOnAttachStateChangeListener(this)
        }

        private fun attach() {
            detach()
            iconView.viewTreeObserver.takeIf(ViewTreeObserver::isAlive)?.let { currentObserver ->
                currentObserver.addOnPreDrawListener(this)
                observer = currentObserver
            }
        }

        private fun detach() {
            observer?.takeIf(ViewTreeObserver::isAlive)?.removeOnPreDrawListener(this)
            observer = null
        }
    }

    private fun handleDrawerItemSelected(item: MenuItem): Boolean {
        drawerAccountItemIds[item.itemId]?.let { account ->
            onAccountSelected(account)
            closeDrawerOnCompactScreens()
            return true
        }

        if (item.itemId == DRAWER_ITEM_ADD_ACCOUNT) {
            closeDrawerOnCompactScreens()
            activity.startAddAccountActivity()
            return true
        }
        if (item.itemId == DRAWER_ITEM_SCHEDULE) {
            closeDrawerOnCompactScreens()
            openSchedule()
            return true
        }
        return false
    }

    private fun closeDrawerOnCompactScreens() {
        val drawer = activity.findViewById<NavDrawerLayout>(drawerLayoutId)
        if (!drawer.isLargeScreenMode) {
            drawer.setDrawerOpen(false, animate = true)
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

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
        private const val DRAWER_GROUP_ACCOUNTS = 1
        private const val DRAWER_GROUP_ACTIONS = 2
        private const val DRAWER_ACCOUNT_ITEM_BASE = 10_000
        private const val DRAWER_ITEM_ADD_ACCOUNT = 20_000
        private const val DRAWER_ITEM_SCHEDULE = 20_001
        private const val DRAWER_STANDARD_ICON_ALPHA = 0.7f
    }
}
