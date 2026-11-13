@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.tjg.twidget.main

import android.content.Intent
import android.content.res.ColorStateList
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
import com.tjg.twidget.ui.SeslSurfaceHighlight
import com.tjg.twidget.ui.VerifiedBadge
import com.tjg.twidget.ui.startAddAccountActivity
import com.tjg.twidget.ui.startLeftSidePopOverActivity
import dev.oneuiproject.oneui.R as OneUiIconR
import dev.oneuiproject.oneui.design.R as OneUiDesignR
import dev.oneuiproject.oneui.layout.Badge
import dev.oneuiproject.oneui.layout.ToolbarLayout
import com.google.android.material.oneui.responsivepane.ResponsivePaneLayout
import com.google.android.material.oneui.responsivepane.ResponsiveDrawerLayout
import com.google.android.material.oneui.responsivepane.model.PaneState
import com.google.android.material.oneui.responsivepane.helper.ResponsiveConfigBuilder
import com.google.android.material.oneui.responsivepane.helper.ResponsivePaneLayoutMode
import dev.oneuiproject.oneui.navigation.menu.DrawerMenuPresenter
import dev.oneuiproject.oneui.navigation.widget.DrawerMenuView
import androidx.appcompat.view.menu.MenuBuilder
import android.widget.FrameLayout
import android.view.LayoutInflater


// These pinned library internals supply the actual One UI drawer rows and their rail state.
@android.annotation.SuppressLint("RestrictedApi")
internal class MainDrawerController(
    private val activity: EdgeToEdgeActivity,
    private val drawerLayoutId: Int,
    private val drawerNavigationId: Int,
    private val accounts: () -> List<String>,
    private val selectedAccount: () -> String,
    private val onAccountSelected: (String) -> Unit,
    private val isEditMode: () -> Boolean = { false },
    private val exitEditMode: () -> Unit = {},
) {
    private val drawerAccountItemIds = mutableMapOf<Int, String>()
    private val drawerAvatarItemIds = mutableSetOf<Int>()
    private val downloadingDrawerAvatarUrls = mutableSetOf<String>()
    private var showingEditNavigation = false
    private var expandedBeforeEditing = false
    private var hiddenBeforeEditing = false
    private var lastHeader: List<Any?>? = null
    private var lastRenderedTitle: String? = null
    private var lastDrawerClosedState: Boolean? = null

    private lateinit var menu: MenuBuilder
    private lateinit var presenter: DrawerMenuPresenter
    private lateinit var menuView: DrawerMenuView
    private val root get() = activity.findViewById<View>(R.id.dashboard_root)
    private val phoneDrawer get() = root as? androidx.drawerlayout.widget.DrawerLayout
    private val pane get() = root as ResponsivePaneLayout
    private val isPhoneDrawer get() = phoneDrawer != null
    internal val isDrawerOpen get() = phoneDrawer?.isDrawerOpen(androidx.core.view.GravityCompat.START) ?: pane.isOpen

    fun setupDrawerChrome() {
        val nativeDrawer = activity.findViewById<FrameLayout>(com.google.android.material.R.id.responsive_pane_drawer)
        val night = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        nativeDrawer.setBackgroundResource(if (night) androidx.appcompat.R.drawable.sesl_menu_popup_background_dark
            else androidx.appcompat.R.drawable.sesl_menu_popup_background)
        (nativeDrawer.background?.mutate() as? android.graphics.drawable.LayerDrawable)?.let { surface ->
            for (index in 0 until surface.numberOfLayers) {
                (surface.getDrawable(index) as? android.graphics.drawable.GradientDrawable)
                    ?.setStroke(0, android.graphics.Color.TRANSPARENT)
            }
        }
        // The phone drawer slides as one surface. The tablet rail's rim instead
        // follows SESL 9.5's elevation concept, keeping the closed rail transparent.
        SeslSurfaceHighlight.setDrawerSlideOffset(nativeDrawer, if (isPhoneDrawer) 1f else 0f)
        val panel = LayoutInflater.from(activity).inflate(OneUiDesignR.layout.oui_des_layout_drawer_panel, nativeDrawer, false)
        // The SESL container owns the surface; reuse the library header and menu content.
        panel.background = null
        (panel.layoutParams as ViewGroup.MarginLayoutParams).topMargin = 0
        nativeDrawer.addView(panel, FrameLayout.LayoutParams(-1, -1))
        var slideRange = 0
        if (isPhoneDrawer) {
            val drawer = requireNotNull(phoneDrawer)
            drawer.setScrimColor(android.graphics.Color.TRANSPARENT)
            val edgeGap = activity.resources.getDimensionPixelSize(R.dimen.dashboard_drawer_surface_gap).toFloat() *
                if (nativeDrawer.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
            var moving = false
            drawer.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                    // DrawerLayout ignores start margins. Retain its direct blur
                    // surface and apply just the inset along its native slide.
                    nativeDrawer.translationX = edgeGap * slideOffset
                }
                override fun onDrawerStateChanged(newState: Int) {
                    // Canvas blur is captured once per native sliding transition, not per frame.
                    if (newState != androidx.drawerlayout.widget.DrawerLayout.STATE_IDLE && !moving) {
                        drawer.seslSetDrawerViewFloatingBlur(activity, nativeDrawer)
                    }
                    moving = newState != androidx.drawerlayout.widget.DrawerLayout.STATE_IDLE
                }
            })
        } else {
            val mode = if (activity.resources.configuration.screenWidthDp >= 960) ResponsivePaneLayoutMode.LARGE else ResponsivePaneLayoutMode.MEDIUM
            val large = activity.resources.configuration.screenWidthDp >= 600
            val closed = ResponsiveConfigBuilder.create(activity, mode, PaneState.CLOSED)
                .widthPx(if (large) activity.resources.getDimensionPixelSize(
                    com.google.android.material.R.dimen.sesl_responsive_pane_navigation_drawer_width_closed) else 0).build()
            pane.setOutsideTouchEnabled(false)
            pane.setPaneConfig(PaneState.CLOSED, closed.copy(nonBlurBGAlpha = 0f))
            pane.setPaneConfig(PaneState.OPENED, ResponsiveConfigBuilder.create(activity, mode, PaneState.OPENED)
                .widthPx(activity.resources.getDimensionPixelSize(com.google.android.material.R.dimen.sesl_responsive_pane_navigation_drawer_width)).build())
            val opened = pane.getPaneConfig(PaneState.OPENED)
            (nativeDrawer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).apply {
                matchConstraintMinWidth = closed.width
                matchConstraintMaxWidth = opened.width
                nativeDrawer.layoutParams = this
            }
            (nativeDrawer as ResponsiveDrawerLayout).maxWidth = opened.width
            nativeDrawer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!nativeDrawer.isBlurApplied()) nativeDrawer.applyBlurInfo(activity)
            }
            val content = activity.findViewById<View>(com.google.android.material.R.id.responsive_pane_content)
            (content.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).apply {
                marginStart = if (large) closed.width + dp(8) else 0
                content.layoutParams = this
            }
            slideRange = opened.width - closed.width
        }
        presenter = DrawerMenuPresenter { slideRange }
        menu = MenuBuilder(activity).apply {
            setGroupDividerEnabled(true)
            addMenuPresenter(presenter)
            setCallback(object : MenuBuilder.Callback {
                override fun onMenuItemSelected(menu: MenuBuilder, item: MenuItem) = handleDrawerItemSelected(item)
                override fun onMenuModeChange(menu: MenuBuilder) = Unit
            })
        }
        val host = panel.findViewById<FrameLayout>(OneUiDesignR.id.drawer_items_container)
        menuView = presenter.getMenuView(host)
        menuView.id = drawerNavigationId
        host.addView(menuView)
        val hamburger = panel.findViewById<View>(OneUiDesignR.id.navRailDrawerButton)
        hamburger.visibility = View.VISIBLE
        hamburger.contentDescription = activity.getString(OneUiDesignR.string.oui_des_navigation_drawer)
        hamburger.setOnClickListener { setDrawerOpen(!isDrawerOpen) }
        panel.findViewById<ImageView>(OneUiDesignR.id.oui_des_drawer_header_button).apply {
            setImageResource(OneUiIconR.drawable.ic_oui_settings_outline)
            contentDescription = activity.getString(R.string.settings)
            setOnClickListener {
                closeDrawerOnCompactScreens()
                activity.startLeftSidePopOverActivity(Intent(activity, SettingsActivity::class.java))
            }
        }
        if (!isPhoneDrawer) pane.addResponsivePaneListener(object : ResponsivePaneLayout.ResponsivePaneListener {
            override fun onDrawerSlide(drawer: View, slideOffset: Float) {
                presenter.adapter?.updateOffset(slideOffset)
                SeslSurfaceHighlight.setDrawerSlideOffset(drawer, slideOffset)
            }
            override fun onDrawerOpened(drawer: View) {
                presenter.adapter?.updateOffset(1f)
                SeslSurfaceHighlight.setDrawerSlideOffset(drawer, 1f)
            }
            override fun onDrawerClosed(drawer: View) {
                presenter.adapter?.updateOffset(0f)
                SeslSurfaceHighlight.setDrawerSlideOffset(drawer, 0f)
            }
        })
        // A phone drawer translates at full width, so its labels stay fully laid out.
        presenter.adapter?.updateOffset(if (isPhoneDrawer) 1f else 0f)
        setDrawerOpen(false, false)
        activity.findViewById<ToolbarLayout>(drawerLayoutId).setAdaptiveMarginProvider(
            dev.oneuiproject.oneui.widget.AdaptiveCoordinatorLayout.MARGIN_PROVIDER_ZERO)
    }

    internal fun setDrawerOpen(open: Boolean, animate: Boolean = true) {
        phoneDrawer?.let { drawer ->
            if (open && !animate) drawer.seslSetDrawerViewFloatingBlur(activity,
                activity.findViewById(com.google.android.material.R.id.responsive_pane_drawer))
            if (open) drawer.openDrawer(androidx.core.view.GravityCompat.START, animate)
            else drawer.closeDrawer(androidx.core.view.GravityCompat.START, animate)
            return
        }
        if (open) pane.open(animate) else pane.close(animate)
    }

    fun buildDrawer() {
        val drawerNav = menuView
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
        presenter.updateMenuView(false)
        drawerNav.post {
            applyDrawerIconTints(drawerNav)
            attachDrawerAccountLongPresses(drawerNav)
        }
    }

    fun renderHeader() {
        val stats = TwidgetStore.currentStats(activity, selectedAccount())
        activity.findViewById<ToolbarLayout>(drawerLayoutId).apply {
            if (isEditMode()) {
                renderEditModeNavigation(this)
                val header = listOf(activity.getString(R.string.edit_dashboard), "")
                if (lastHeader != header) {
                    setTitle(header[0])
                    setSubtitle(header[1])
                    lastHeader = header
                    lastRenderedTitle = header[0]
                }
            } else {
                renderDrawerNavigation(this)
                val name = stats.fullName.ifBlank { "@${stats.userName}" }
                val expandedSubtitle = TwidgetStore.lastSyncedText(activity, stats)
                val collapsedSubtitle = "@${stats.userName}"
                val header = listOf(name, stats.isVerified, stats.isPrivate, expandedSubtitle, collapsedSubtitle)
                if (lastHeader != header || collapsedTitle?.toString() != lastRenderedTitle) {
                    if (lastHeader?.take(3) != header.take(3) || collapsedTitle?.toString() != lastRenderedTitle) {
                        val title = VerifiedBadge.decorate(activity, name, stats.isVerified, stats.isPrivate, dp(26))
                        setTitle(title)
                        lastRenderedTitle = title.toString()
                    }
                    this.expandedSubtitle = expandedSubtitle
                    this.collapsedSubtitle = collapsedSubtitle
                    lastHeader = header
                }
            }
        }
    }

    fun updateNavigationBadge(drawerClosed: Boolean? = null) {
        if (!isEditMode()) {
            val layout = activity.findViewById<ToolbarLayout>(drawerLayoutId)
            renderDrawerNavigation(layout)
            applyNavigationBadge(layout, drawerClosed ?: !isDrawerOpen)
        }
    }

    private fun applyNavigationBadge(layout: ToolbarLayout, drawerClosed: Boolean) {
        lastDrawerClosedState = drawerClosed
        layout.setNavigationButtonBadge(if (TwidgetStore.updateAvailable(activity)) Badge.DOT else Badge.NONE)
        layout.post { applyDrawerButtonAppearance(layout) }
    }

    private fun renderEditModeNavigation(layout: ToolbarLayout) {
        if (!showingEditNavigation) {
            expandedBeforeEditing = layout.isExpanded
            hiddenBeforeEditing = layout.appBarLayout.seslIsHided()
            // Change the title and app-bar geometry together. Animating the old
            // collapsing title while replacing it with the edit title makes both
            // headers briefly appear and moves the dashboard underneath the hold.
            layout.setExpanded(expanded = false, animate = false)
            // Keep the app bar's measured height and scroll ranges intact.
            // Removing expansion rebuilds them and loses the dashboard offset.
            layout.appBarLayout.seslAllowStartNestedScroll(false)
            showingEditNavigation = true
        }
        layout.showNavigationButton = true
        val closeIcon = AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_close)
        layout.setNavigationButtonIcon(closeIcon)
        layout.setNavigationButtonTooltip(activity.getString(R.string.done))
        layout.findViewById<Toolbar>(OneUiDesignR.id.toolbarlayout_main_toolbar).apply {
            navigationIcon = closeIcon
            setNavigationOnClickListener { exitEditMode() }
        }
    }

    private fun renderDrawerNavigation(layout: ToolbarLayout) {
        if (showingEditNavigation) {
            layout.appBarLayout.seslAllowStartNestedScroll(true)
            val scrollY = activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.dashboard_scroll)?.scrollY ?: 0
            layout.setExpanded(expanded = expandedBeforeEditing && scrollY == 0, animate = false)
            if (hiddenBeforeEditing && scrollY > 0) layout.appBarLayout.seslSetHide(true, false)
            showingEditNavigation = false
        }
        layout.showNavigationButton = activity.resources.configuration.screenWidthDp < 600
        layout.setNavigationButtonIcon(drawerIcon())
        layout.setNavigationButtonTooltip(
            activity.getString(OneUiDesignR.string.oui_des_navigation_drawer),
        )
        applyDrawerButtonAppearance(layout)
        layout.findViewById<Toolbar>(OneUiDesignR.id.toolbarlayout_main_toolbar).apply {
            navigationIcon = if (activity.resources.configuration.screenWidthDp < 600) drawerIcon() else null
            setNavigationOnClickListener { setDrawerOpen(true) }
        }
    }

    private fun drawerIcon(): Drawable? {
        return AppCompatResources.getDrawable(
            activity,
            OneUiDesignR.drawable.oui_des_ic_ab_drawer,
        )
    }

    private fun applyDrawerButtonAppearance(layout: ToolbarLayout) {
        val primary = activity.getColor(R.color.oneui_text_primary)
        layout.findViewById<ImageView>(OneUiDesignR.id.navRailDrawerButton)?.apply {
            alpha = 1f
            imageTintList = ColorStateList.valueOf(primary)
        }
        layout.findViewById<Toolbar>(OneUiDesignR.id.toolbarlayout_main_toolbar)
            ?.navigationIcon
            ?.mutate()
            ?.setTint(primary)
    }

    private fun attachDrawerAccountLongPresses(drawerNav: DrawerMenuView) {
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
        ProfileImageLoader.memoryCircularBitmap(stats.profileImage, iconSize)?.let { bitmap ->
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
        AppExecutors.executeImage(onRejected = {
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

    private fun applyDrawerIconTints(drawerNav: DrawerMenuView) {
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

        return false
    }

    private fun closeDrawerOnCompactScreens() {
        if (isPhoneDrawer) setDrawerOpen(false)
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        private const val DRAWER_GROUP_ACCOUNTS = 1
        private const val DRAWER_GROUP_ACTIONS = 2
        private const val DRAWER_ACCOUNT_ITEM_BASE = 10_000
        private const val DRAWER_ITEM_ADD_ACCOUNT = 20_000
        private const val DRAWER_STANDARD_ICON_ALPHA = 0.7f
    }
}
