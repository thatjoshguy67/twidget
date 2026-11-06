package com.tjg.twidget.main

import com.tjg.twidget.schedule.ScheduleStore

import android.app.Activity
import androidx.core.widget.NestedScrollView
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tjg.twidget.notices.*
import com.tjg.twidget.update.ReleaseNotice
import org.junit.Assume.assumeTrue
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.io.File
import android.graphics.Bitmap
import android.content.SharedPreferences
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ImageButton
import androidx.core.view.children
import android.widget.LinearLayout
import androidx.picker.widget.SeslNumberPicker
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SeslCompatibilityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val account = "sesl_compat_test"
    private lateinit var saved: List<Pair<SharedPreferences, Map<String, *>>>

    @Before fun preserveSettings() {
        saved = listOf(TwidgetStore.PREFS, "twidget_account_goals", ScheduleStore.PREFS_NAME, "twidget_release_notices").map { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs to prefs.all
        }
    }

    @After fun restoreSettings() {
        saved.forEach { (prefs, values) ->
            prefs.edit().clear().apply {
                values.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
        }
    }

    @Test fun aboutStartsExpandedOnEveryFreshVisit() {
        repeat(2) { visit ->
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                settle()
                scenario.onActivity { activity ->
                    val appBar = activity.findViewById<AppBarLayout>(R.id.about_app_bar)
                    assertEquals("About must open expanded", 0, appBar.top)
                    val hero = activity.findViewById<View>(R.id.about_header_icon)
                    assertTrue("About hero is visible", hero.alpha > .99f)
                    assertFullyVisible(hero)
                    val scroll = activity.findViewById<View>(R.id.about_scroll)
                    assertEquals(0, scroll.scrollY)
                }
                capture("AboutExpanded-$visit")
                swipeUp(scenario)
                swipeUp(scenario)
                settle()
                scenario.onActivity { activity ->
                    assertFullyVisible(activity.findViewById<Toolbar>(R.id.about_toolbar).children.filterIsInstance<ImageButton>().firstOrNull())
                }
                capture("AboutScrolled-$visit")
            }
        }
    }

    @Test fun aboutCollapsedHeaderClearsToolbarBeforeFloating() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                activity.findViewById<AppBarLayout>(R.id.about_app_bar).setExpanded(false, false)
            }
            settle()
            capture("AboutFirstCollapse")
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.about_toolbar)
                val header = activity.findViewById<View>(R.id.about_compact_header)
                val toolbarPosition = IntArray(2).also(toolbar::getLocationOnScreen)
                val headerPosition = IntArray(2).also(header::getLocationOnScreen)
                assertTrue("Collapsed About content overlaps plain toolbar: header=${headerPosition[1]}, toolbarBottom=${toolbarPosition[1] + toolbar.height}",
                    // SESL converts its fractional scroll range back to integer pixels.
                    headerPosition[1] >= toolbarPosition[1] + toolbar.height - 1)
            }
        }
    }

    @Test fun estimateTipAppearsOnlyForEstimatesAndStaysDismissedAfterRecreation() {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .putString("username", account)
            .putString("bridge_url", "http://127.0.0.1:1")
            .putBoolean("onboarded", true)
            .putBoolean("refresh_on_launch", false)
            .remove("history_$account")
            .remove("estimate_tip_dismissed")
            .commit()
        val now = System.currentTimeMillis()
        val stats = ProfileStats("SESL Test", account, 500, 100, 200, 300, syncedAt = now)
        TwidgetStore.saveStats(context, stats)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.history_notice).visibility)
                TwidgetStore.saveStats(context, stats.copy(history = listOf(
                    HistorySample("Earlier", 400, 100, 200, 300, now - 4 * 86_400_000L),
                )))
                repeat(2) { activity.dashboardBinder.bindContent() }
                val tip = activity.findViewById<dev.oneuiproject.oneui.widget.TipsCard>(R.id.history_notice)
                assertEquals(View.VISIBLE, tip.visibility)
                val actions = tip.findViewById<LinearLayout>(dev.oneuiproject.oneui.design.R.id.tips_bottom_bar)
                assertEquals("Refreshing must not duplicate the dismissal action", 1, actions.childCount)
                assertTrue(tip.findViewById<View>(R.id.history_notice_dismiss).performClick())
                assertEquals(View.GONE, tip.visibility)
                assertTrue(TwidgetStore.isEstimateTipDismissed(activity))
                activity.dashboardBinder.bindContent()
                assertEquals(View.GONE, tip.visibility)
            }
            scenario.recreate()
            settle()
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.history_notice).visibility)
                assertTrue(TwidgetStore.isEstimateTipDismissed(activity))
            }
        }
    }

    @Test fun dashboardActionsAndFabRemainOnScreenThroughoutScroll() {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .remove("accounts").putString("username", account).putBoolean("refresh_on_launch", false).commit()
        TwidgetStore.saveStats(context, ProfileStats("SESL Test", account, 7757, 200, 400, 900))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val layout = activity.findViewById<ToolbarLayout>(R.id.main_toolbar_layout)
                val toolbarPosition = IntArray(2).also(layout.toolbar::getLocationOnScreen)
                val appbarPosition = IntArray(2).also(layout.appBarLayout::getLocationOnScreen)
                assertTrue("Expanded toolbar must stay inside its app bar", toolbarPosition[1] + layout.toolbar.height <= appbarPosition[1] + layout.appBarLayout.height)
            }
            capture("DashboardExpanded")
            repeat(4) { index ->
                swipeUp(scenario)
                settle()
                scenario.onActivity { activity ->
                    val layout = activity.findViewById<ToolbarLayout>(R.id.main_toolbar_layout)
                    assertFullyVisible(layout.toolbar.children.filterIsInstance<ImageButton>().firstOrNull())
                    assertFullyVisible(layout.toolbar.seslGetMenuView())
                    val floating = layout.toolbar.parent as View
                    assertEquals(false, floating.javaClass.getMethod("getEnableScrollTransition").invoke(floating))
                    assertEquals("Toolbar must remain opaque while scrolling", 1f, floating.alpha, .01f)
                }
                capture("DashboardScrolled-$index")
            }
        }
    }

    @Test fun dashboardFirstFrameShowsCachedStatsAndSavedCards() {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("onboarded", true).remove("accounts").putString("username", account)
            .putBoolean("refresh_on_launch", false).commit()
        TwidgetStore.saveStats(context, ProfileStats("Cached heading", account, 7757, 200, 400, 900))
        val cards = listOf("milestone", "followers", "daily_streak", "follower_ratio")
        TwidgetStore.saveDashboardCards(context, cards)
        val observed = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.CREATED) {
                val layout = activity.findViewById<ToolbarLayout>(R.id.main_toolbar_layout)
                assertEquals("Cached heading", layout.collapsedTitle.toString())
                assertEquals("@$account", layout.collapsedSubtitle.toString())
                assertEquals(TwidgetStore.lastSyncedText(activity, TwidgetStore.currentStats(activity, account)), layout.expandedSubtitle.toString())
                val grid = activity.findViewById<android.widget.GridLayout>(R.id.dashboard_content)
                assertEquals(cards, (0 until grid.childCount).map { grid.getChildAt(it).tag })
                assertEquals(activity.dp(DashboardCardSize.MILESTONE.heightDp), grid.getChildAt(0).layoutParams.height)
                assertEquals(activity.dp(DashboardCardSize.CHART.heightDp), grid.getChildAt(1).layoutParams.height)
                assertEquals("7,757", activity.findViewById<android.widget.TextView>(R.id.followers_value).text.toString())
                assertFalse(activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.main_refresh).isRefreshing)
                observed.set(true)
            }
        }
        val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        lifecycle.addLifecycleCallback(callback)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { settle() }
            assertTrue("Cached stats and saved cards must be ready before the first frame", observed.get())
        } finally {
            lifecycle.removeLifecycleCallback(callback)
        }
    }

    @Test fun dashboardHeadingAndViewportSurviveEditingAndNestedPages() {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("onboarded", true).remove("accounts").putString("username", account)
            .putBoolean("refresh_on_launch", false).commit()
        TwidgetStore.saveStats(context, ProfileStats("Stable heading", account, 7757, 200, 400, 900))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            var height = 0
            var scrollY = 0
            scenario.onActivity { activity ->
                val layout = activity.findViewById<ToolbarLayout>(R.id.main_toolbar_layout)
                layout.setExpanded(false, false)
            }
            settle()
            scenario.onActivity { activity ->
                val layout = activity.findViewById<ToolbarLayout>(R.id.main_toolbar_layout)
                val scroll = activity.findViewById<NestedScrollView>(R.id.dashboard_scroll)
                scroll.scrollTo(0, activity.dp(180))
                scrollY = scroll.scrollY
                height = layout.appBarLayout.height
                val title = layout.collapsedTitle
                activity.render()
                assertSame("Unchanged account titles must retain their native spans", title, layout.collapsedTitle)
                activity.editModeController.setEditMode(true)
            }
            settle()
            scenario.onActivity { activity ->
                val layout = activity.findViewById<ToolbarLayout>(R.id.main_toolbar_layout)
                assertTrue("Edit mode keeps app bar geometry", layout.isExpandable)
                assertEquals(height, layout.appBarLayout.height)
                assertEquals(scrollY, activity.findViewById<NestedScrollView>(R.id.dashboard_scroll).scrollY)
                assertEquals(activity.getString(R.string.edit_dashboard), layout.collapsedTitle.toString())
                activity.editModeController.setEditMode(false)
            }
            settle()
            var offset = 0
            lateinit var title: CharSequence
            scenario.onActivity { activity ->
                val layout = activity.findViewById<ToolbarLayout>(R.id.main_toolbar_layout)
                assertFalse("Leaving a scrolled edit session must not expand the heading", layout.isExpanded)
                assertEquals(scrollY, activity.findViewById<NestedScrollView>(R.id.dashboard_scroll).scrollY)
                offset = layout.appBarLayout.top
                title = requireNotNull(layout.collapsedTitle)
                activity.startActivity(Intent(activity, com.tjg.twidget.settings.SettingsActivity::class.java))
            }
            settle()
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<com.tjg.twidget.settings.SettingsActivity>().forEach { it.finish() }
            }
            settle()
            scenario.onActivity { activity ->
                val layout = activity.findViewById<ToolbarLayout>(R.id.main_toolbar_layout)
                assertSame(title, layout.collapsedTitle)
                assertEquals(offset, layout.appBarLayout.top)
                assertEquals(scrollY, activity.findViewById<NestedScrollView>(R.id.dashboard_scroll).scrollY)
            }
        }
    }




    @Test fun widgetSettingsUsesNativeFloatingActions() {
        ActivityScenario.launch(com.tjg.twidget.widget.WidgetConfigActivity::class.java).use { scenario ->
            settle()
            repeat(3) { step ->
                scenario.onActivity { activity ->
                    if (step == 0) {
                        val toolbar = activity.findViewById<ToolbarLayout>(R.id.widget_config_root).toolbar
                        val back = toolbar.children.filterIsInstance<ImageButton>().first()
                        assertFullyVisible(back)
                        assertEquals("Native back button must remain opaque", 1f, back.alpha, .01f)
                        assertEquals("Native back icon must remain opaque", 255, back.drawable.alpha)
                        val title = toolbar.children.filterIsInstance<android.widget.TextView>()
                            .first { it.text == activity.getString(R.string.widget_settings) }
                        assertEquals("Widget settings title must be visible on entry", 1f, title.alpha, .01f)
                        assertFullyVisible(title)
                        val preview = activity.findViewById<View>(R.id.preview_container)
                        assertTrue("Preview must start below the initial toolbar",
                            IntArray(2).also(preview::getLocationOnScreen)[1] >=
                                IntArray(2).also(toolbar::getLocationOnScreen)[1] + toolbar.height)
                    }
                    val bar = activity.findViewById<ViewGroup>(R.id.config_button_bar)
                    assertEquals("FloatingBottomLayout", bar.javaClass.simpleName)
                    assertEquals(false, bar.javaClass.getMethod("getEnableScrollTransition").invoke(bar))
                    val cancel = activity.findViewById<View>(R.id.btn_cancel)
                    val save = activity.findViewById<View>(R.id.btn_save)
                    assertEquals("DividerButton", cancel.javaClass.simpleName)
                    assertEquals("DividerButtonLayout", cancel.parent.javaClass.simpleName)
                    assertSame(cancel.parent, save.parent)
                    assertTrue(cancel.hasOnClickListeners())
                    assertTrue(save.hasOnClickListeners())
                    assertFullyVisible(cancel)
                    assertFullyVisible(save)
                    val buttons = cancel.parent as ViewGroup
                    assertTrue("Native action bar should wrap its buttons", buttons.width < activity.window.decorView.width * .9f)
                    assertTrue(descendants(buttons).any { it.javaClass.simpleName == "Divider" })
                }
                capture("WidgetNativeActions-$step")
                swipeUp(scenario)
                settle()
            }
        }
    }

    @Test fun widgetSettingsNativeActionsInDarkMode() {
        instrumentation.runOnMainSync {
            com.tjg.twidget.ui.AppAppearance.setMode(context, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        }
        try {
            widgetSettingsUsesNativeFloatingActions()
        } finally {
            instrumentation.runOnMainSync {
                com.tjg.twidget.ui.AppAppearance.setMode(context, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    @Test fun drawerHeaderClearsStatusBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            // Test the permanent tablet rail as well as the open drawer. A margin
            // on drawer_panel alone works on phones but is ignored by SlidingPaneLayout.
            listOf(false, true, false).forEach { open ->
                scenario.onActivity { activity ->
                    activity.findViewById<dev.oneuiproject.oneui.layout.NavDrawerLayout>(R.id.main_toolbar_layout)
                        .setDrawerOpen(open, false)
                }
                settle()
                capture("DrawerSafeHeader-${if (open) "Expanded" else "Collapsed"}")
                scenario.onActivity { activity ->
                    val drawer = activity.findViewById<dev.oneuiproject.oneui.layout.NavDrawerLayout>(R.id.main_toolbar_layout)
                    if (open || drawer.isLargeScreenMode) {
                        val panel = activity.findViewById<View>(dev.oneuiproject.oneui.design.R.id.drawer_panel)
                        val safe = ViewCompat.getRootWindowInsets(panel)!!.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
                        val y = IntArray(2).also(panel::getLocationOnScreen)[1]
                        assertTrue("Drawer surface must begin below the status bar (open=$open, tablet=${drawer.isLargeScreenMode}, y=$y, inset=$safe)", y >= safe)
                        val bottomInset = ViewCompat.getRootWindowInsets(panel)!!
                            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).bottom
                        val window = panel.rootView
                        val windowY = IntArray(2).also(window::getLocationOnScreen)[1]
                        val safeBottom = windowY + window.height - bottomInset
                        assertTrue(
                            "Drawer surface must end above navigation (open=$open, tablet=${drawer.isLargeScreenMode}, bottom=${y + panel.height}, safeBottom=$safeBottom)",
                            y + panel.height <= safeBottom,
                        )
                        val button = if (open) dev.oneuiproject.oneui.design.R.id.oui_des_drawer_header_button
                            else dev.oneuiproject.oneui.design.R.id.navRailDrawerButton
                        assertFullyVisible(activity.findViewById(button))
                    }
                }
                capture("DrawerSafeHeader-${if (open) "Expanded" else "Collapsed"}")
            }
        }
    }

    @Test fun drawerClearsStatusBarInDarkMode() {
        val originalMode = com.tjg.twidget.ui.AppAppearance.mode(context)
        instrumentation.runOnMainSync {
            com.tjg.twidget.ui.AppAppearance.setMode(context, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        }
        try { drawerHeaderClearsStatusBar() } finally {
            instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, originalMode) }
        }
    }

    @Test fun preferencePageBindsNativeFadeBehindStatusBar() {
        ActivityScenario.launch(com.tjg.twidget.settings.SettingsActivity::class.java).use { scenario ->
            settle()
            // Keep this scroll test independent of how many real settings fit
            // at the current native row size and display configuration.
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                    as androidx.preference.PreferenceFragmentCompat
                repeat(20) { index ->
                    fragment.preferenceScreen.addPreference(androidx.preference.Preference(activity).apply {
                        key = "scroll_fixture_$index"
                        title = "Scroll fixture $index"
                    })
                }
            }
            settle()
            repeat(3) { swipeUp(scenario); settle() }
            capture("PreferencesNativeTopFade")
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val list = descendants(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.isShown }
                assertEquals(true, list.javaClass.getMethod("seslIsFadingEdgeEnabled").invoke(list))
                val helper = androidx.recyclerview.widget.RecyclerView::class.java.getDeclaredField("mFadingEdgeHelper").apply { isAccessible = true }.get(list)
                val statusHeight = helper.javaClass.getDeclaredField("mStatusBarHeight").apply { isAccessible = true }.getInt(helper)
                assertTrue("Native fade must receive status-bar insets", statusHeight > 0)
                val floating = descendants(root).first { it.javaClass.simpleName == "FloatingToolbarLayout" }
                assertSame(list, floating.javaClass.getMethod("getRecyclerView").invoke(floating))
                val status = ViewCompat.getRootWindowInsets(list)!!.getInsets(WindowInsetsCompat.Type.statusBars()).top
                assertTrue("Scrolling list must reach behind the status bar: listY=${IntArray(2).also(list::getLocationOnScreen)[1]}, status=$status, canScrollUp=${list.canScrollVertically(-1)}", IntArray(2).also(list::getLocationOnScreen)[1] < status)
                assertFullyVisible(activity.findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).toolbar.children.filterIsInstance<ImageButton>().first())
            }
            capture("PreferencesNativeTopFade")
        }
    }

    @Test fun settingsExplanationsUseNativeDescriptionsInBothThemes() {
        val originalMode = com.tjg.twidget.ui.AppAppearance.mode(context)
        val pages = listOf(
            "share_history_pref" to com.tjg.twidget.settings.SettingsCategoryActivity.intent(context, com.tjg.twidget.settings.SettingsPage.DATA),
            "twitterapis_configure_pref" to Intent(context, com.tjg.twidget.settings.SettingsAdvancedActivity::class.java)
                .putExtra(com.tjg.twidget.settings.SettingsAdvancedActivity.EXTRA_SOURCE, TwidgetStore.DATA_SOURCE_TWITTERAPIS),
        )
        try {
            listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES).forEach { mode ->
                instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, mode) }
                pages.forEach { (key, intent) ->
                    ActivityScenario.launch<androidx.fragment.app.FragmentActivity>(intent).use { scenario ->
                        settle()
                        scenario.onActivity { activity ->
                            val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                                as androidx.preference.PreferenceFragmentCompat
                            val row = fragment.findPreference<androidx.preference.Preference>(key)!!
                            assertNull("Explanatory copy must sit outside the action card", row.summary)
                            val description = fragment.findPreference<androidx.preference.SeslPreferenceCaption>("${key}_description")!!
                            assertFalse(description.isSelectable)
                            assertFalse(description.title.isNullOrBlank())
                            assertEquals(androidx.preference.R.layout.sesl_preference_caption, description.layoutResource)
                            val screen = fragment.preferenceScreen
                            val rowIndex = (0 until screen.preferenceCount).first { screen.getPreference(it) === row }
                            assertSame(description, screen.getPreference(rowIndex + 1))
                            fragment.scrollToPreference(description)
                        }
                        settle()
                        capture("NativeDescription-$key")
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, originalMode) }
        }
    }

    @Test fun settingsCardsUseNativeSpacingInBothThemes() {
        val originalMode = com.tjg.twidget.ui.AppAppearance.mode(context)
        try {
            listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES).forEach { mode ->
                instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, mode) }
                ActivityScenario.launch(com.tjg.twidget.settings.SettingsActivity::class.java).use { scenario ->
                    settle()
                    scenario.onActivity { activity ->
                        val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                            as androidx.preference.PreferenceFragmentCompat
                        val nativeHeight = activity.resources.getDimensionPixelSize(androidx.appcompat.R.dimen.sesl_list_preferred_item_height_small)
                        val nativeInset = activity.resources.getDimensionPixelSize(androidx.appcompat.R.dimen.sesl_list_item_padding_horizontal)
                        listOf("accounts", "appearance", "data_sources").forEach { key ->
                            val card = fragment.findPreference<dev.oneuiproject.oneui.preference.LayoutPreference>(key)!!.getView()!!
                            val content = card.findViewById<View>(dev.oneuiproject.oneui.design.R.id.main_content)
                            assertEquals("$key row uses native minimum height", nativeHeight, content.minimumHeight)
                            assertEquals("$key start padding", nativeInset, content.paddingStart)
                            assertEquals("$key end padding", nativeInset, content.paddingEnd)
                            assertEquals("Single-line card should use native row height", nativeHeight, content.height)
                            assertFullyVisible(card.findViewById(dev.oneuiproject.oneui.design.R.id.cardview_title))
                        }
                    }
                    capture("SettingsNativeCardSpacing")
                }
            }
        } finally {
            instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, originalMode) }
        }
    }

    /** Captures real pages in both themes, before and after nested scrolling. */
    @Test fun visualAuditPagesInBothThemes() {
        val originalMode = com.tjg.twidget.ui.AppAppearance.mode(context)
        val pages = listOf(
            "Dashboard" to Intent(context, MainActivity::class.java),
            "About" to Intent(context, AboutActivity::class.java),
            "Widget" to Intent(context, com.tjg.twidget.widget.WidgetConfigActivity::class.java),
            "Settings" to Intent(context, com.tjg.twidget.settings.SettingsActivity::class.java),
            "Brief" to Intent(context, com.tjg.twidget.settings.BriefSettingsActivity::class.java),
            "BriefContent" to Intent(context, com.tjg.twidget.settings.BriefContentSettingsActivity::class.java),
            "Debug" to Intent(context, com.tjg.twidget.settings.SettingsDebugActivity::class.java),
            "Bridge" to Intent(context, com.tjg.twidget.settings.SettingsAdvancedActivity::class.java),
            "TwitterApis" to Intent(context, com.tjg.twidget.settings.SettingsAdvancedActivity::class.java)
                .putExtra(com.tjg.twidget.settings.SettingsAdvancedActivity.EXTRA_SOURCE, TwidgetStore.DATA_SOURCE_TWITTERAPIS),
            "XApi" to Intent(context, com.tjg.twidget.settings.SettingsAdvancedActivity::class.java)
                .putExtra(com.tjg.twidget.settings.SettingsAdvancedActivity.EXTRA_SOURCE, TwidgetStore.DATA_SOURCE_X_API),
        ) + com.tjg.twidget.settings.SettingsPage.entries.map {
            it.name to com.tjg.twidget.settings.SettingsCategoryActivity.intent(context, it)
        }
        try {
            listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES).forEach { mode ->
                instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, mode) }
                pages.forEach { (name, intent) ->
                    ActivityScenario.launch<Activity>(intent).use { scenario ->
                        settle()
                        scenario.onActivity { activity ->
                            val actual = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                            assertEquals(if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
                                android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO, actual)
                        }
                        scenario.onActivity { assertNativeCardSpacing(it, name) }
                        capture("Audit-$name-Expanded")
                        repeat(3) { swipeUp(scenario) }
                        settle()
                        scenario.onActivity { activity ->
                            val root = activity.findViewById<ViewGroup>(android.R.id.content)
                            descendants(root).filterIsInstance<Toolbar>().filter { it.isShown && it.alpha > .99f }.forEach { toolbar ->
                                toolbar.children.filterIsInstance<ImageButton>().filter { it.isShown }.forEach(::assertFullyVisible)
                            }
                        }
                        scenario.onActivity { assertNativeCardSpacing(it, name) }
                        capture("Audit-$name-Scrolled")
                    }
                }
                ActivityScenario.launch(com.tjg.twidget.settings.SettingsActivity::class.java).use { scenario ->
                    settle()
                    lateinit var dialog: androidx.appcompat.app.AlertDialog
                    scenario.onActivity { dialog = com.tjg.twidget.settings.SettingsLanguage.showLegacyDialog(it) }
                    settle()
                    capture("Audit-LanguageDialog")
                    scenario.onActivity { dialog.dismiss() }
                }
            }
        } finally {
            instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, originalMode) }
        }
    }

    private fun assertNativeCardSpacing(activity: Activity, page: String) {
        val nativeHeight = activity.resources.getDimensionPixelSize(androidx.appcompat.R.dimen.sesl_list_preferred_item_height_small)
        val nativeInset = activity.resources.getDimensionPixelSize(androidx.appcompat.R.dimen.sesl_list_item_padding_horizontal)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        descendants(root).filterIsInstance<dev.oneuiproject.oneui.widget.CardItemView>().forEach { card ->
            val content = card.findViewById<View>(dev.oneuiproject.oneui.design.R.id.main_content)
            assertEquals("$page card minimum height", nativeHeight, content.minimumHeight)
            assertEquals("$page card start padding", nativeInset, content.paddingStart)
            assertEquals("$page card end padding", nativeInset, content.paddingEnd)
        }
        if (activity is androidx.fragment.app.FragmentActivity &&
            activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) != null) {
            val attrs = activity.obtainStyledAttributes(intArrayOf(
                androidx.appcompat.R.attr.listPreferredItemHeightSmall,
                android.R.attr.listPreferredItemPaddingStart, android.R.attr.listPreferredItemPaddingEnd))
            try {
                assertEquals("$page preference minimum height", nativeHeight, attrs.getDimensionPixelSize(0, -1))
                assertEquals("$page preference start padding", nativeInset, attrs.getDimensionPixelSize(1, -1))
                assertEquals("$page preference end padding", nativeInset, attrs.getDimensionPixelSize(2, -1))
            } finally { attrs.recycle() }
        }
    }

    private fun assertFullyVisible(view: View?) {
        assertNotNull("Action must exist", view)
        view!!
        val rect = Rect()
        assertTrue("Action must be visible: ${view.javaClass.simpleName}", view.getGlobalVisibleRect(rect))
        // SESL scales floating controls during its native scroll transition.
        // Compare against transformed bounds rather than unscaled layout size.
        val expected = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val transform = android.graphics.Matrix()
        view.transformMatrixToGlobal(transform)
        transform.mapRect(expected)
        val rounded = Rect().also(expected::roundOut)
        assertEquals("Action is clipped vertically: $rect; expected=$expected", rounded.height().toFloat(), rect.height().toFloat(), 1f)
        assertEquals("Action is clipped horizontally: $rect; expected=$expected", rounded.width().toFloat(), rect.width().toFloat(), 1f)
        val location = IntArray(2).also(view::getLocationOnScreen)
        val rootLocation = IntArray(2).also(view.rootView::getLocationOnScreen)
        val insets = ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.systemBars())
        assertTrue("Action overlaps status bar: $rect", location[1] >= rootLocation[1] + insets.top)
        assertTrue("Action overlaps navigation bar: $rect", location[1] + view.height <= rootLocation[1] + view.rootView.height - insets.bottom)
    }

    private fun <T : Activity> swipeUp(scenario: ActivityScenario<T>) {
        var width = 0f
        var height = 0f
        scenario.onActivity { activity ->
            width = activity.window.decorView.width.toFloat()
            height = activity.window.decorView.height.toFloat()
        }
        val down = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, down + step * 20, action,
                width * .5f, height * (.8f - .55f * step / 20), 0)
            // Dispatch into this activity's window so foldable secondary displays
            // and pop-overs exercise the same view gesture path without targeting
            // the default display or a different system window.
            scenario.onActivity { it.dispatchTouchEvent(event) }
            event.recycle()
            SystemClock.sleep(20)
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(650)
        instrumentation.waitForIdleSync()
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "sesl9-chrome-review").apply { mkdirs() }
        File(directory, "$name${if (com.tjg.twidget.ui.AppAppearance.mode(context) == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) "-dark" else ""}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }







    @Test fun noticesCardsUseDesignSectionsAndRetainChangelogNavigation() {
        val stable = ReleaseNotice("v99.0.0", "Twidget v99.0.0", "A concise release introduction.\n\n## Added\n- A feature",
            "https://example.com/stable", false, "2026-10-10T10:00:00Z")
        val beta = stable.copy(tag = "v99.0.0-beta.4", title = "Twidget v99.0.0 Beta 4", prerelease = true)
        ReleaseNoticesStore.save(context, listOf(beta, stable))
        val originalMode = com.tjg.twidget.ui.AppAppearance.mode(context)
        try {
            for (mode in listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, mode) }
                ReleaseNoticesStore.save(context, listOf(beta, stable))
                ActivityScenario.launch(NoticesActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val content = activity.findViewById<LinearLayout>(R.id.notices_content)
                        val sections = content.children.filterIsInstance<dev.oneuiproject.oneui.widget.Separator>().toList()
                        assertEquals(listOf(activity.getString(R.string.notices_latest_version), activity.getString(R.string.notices_older_releases)), sections.map { it.text.toString() })
                        val cards = content.children.filter { it.isClickable }.toList()
                        val releases = ReleaseNoticesStore.visible(activity).filterNot { it.upcoming }
                        val latest = releases.firstOrNull { !it.prerelease } ?: releases.first()
                        assertEquals(listOf(latest.tag) + releases.filter { it != latest }.map { it.tag }, cards.map { it.tag })
                        val latestTexts = descendants(cards[0] as ViewGroup).filterIsInstance<android.widget.TextView>()
                        assertTrue(latestTexts.any { it.text.toString() == latest.title.removePrefix("Twidget ") && it.getTag(R.id.app_font_role) == com.tjg.twidget.ui.TwidgetFonts.Role.CODE })
                        assertTrue(latestTexts.any { it.text.toString() == ReleaseNoticeText.summary(latest.body) })
                        assertFalse(latestTexts.any { it.text.toString().contains("View changelog") })
                    }
                    settle()
                    capture("NoticesCards")
                    val monitor = instrumentation.addMonitor(NoticeDetailActivity::class.java.name, null, false)
                    var selectedTag: String? = null
                    try {
                        scenario.onActivity { activity ->
                            val card = activity.findViewById<LinearLayout>(R.id.notices_content).children.first { it.isClickable }
                            selectedTag = card.tag as String
                            card.performClick()
                        }
                        val detail = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                        assertNotNull(detail)
                        assertEquals(selectedTag, detail!!.intent.getStringExtra(NoticeDetailActivity.EXTRA_NOTICE_TAG))
                        instrumentation.runOnMainSync { detail.finish() }
                    } finally { instrumentation.removeMonitor(monitor) }
                }
            }
        } finally {
            instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, originalMode) }
        }
    }

    @Test fun noticeReaderUsesNativeFloatingBack() {
        val notice = ReleaseNotice("chrome-test", "Twidget v1.3.0 Beta 4", "Reader text.\n\n".repeat(100),
            "https://github.com", true, "2026-09-18T12:00:00Z")
        ReleaseNoticesStore.save(context, listOf(notice))
        ActivityScenario.launchActivityForResult<NoticeDetailActivity>(Intent(context, NoticeDetailActivity::class.java)
            .putExtra(NoticeDetailActivity.EXTRA_NOTICE_TAG, notice.tag)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.notice_detail_title)
                assertEquals("v1.3.0 Beta 4", title.text.toString())
                assertEquals(com.tjg.twidget.ui.TwidgetFonts.Role.CODE, title.getTag(R.id.app_font_role))
                val badge = activity.findViewById<android.widget.TextView>(R.id.notice_detail_beta)
                assertEquals(View.VISIBLE, badge.visibility)
                assertEquals(activity.getString(R.string.notices_beta), badge.text.toString())
                assertEquals(android.graphics.Color.WHITE, badge.currentTextColor)
                val host = activity.findViewById<FrameLayout>(R.id.notice_detail_back)
                assertNull(host.background)
                assertEquals(activity.window.decorView.width, host.width)
                val location = IntArray(2).also(host::getLocationOnScreen)
                assertEquals(ViewCompat.getRootWindowInsets(host)!!.getInsets(WindowInsetsCompat.Type.systemBars()).top, location[1])
                val toolbar = descendants(host).filterIsInstance<Toolbar>().single()
                assertEquals("FloatingToolbarLayout", toolbar.parent.javaClass.simpleName)
                assertFullyVisible(toolbar.children.filterIsInstance<ImageButton>().first())
            }
            capture("NoticeNativeBackExpanded")
            swipeUp(scenario)
            settle()
            capture("NoticeNativeBackScrolled")
            scenario.onActivity { activity ->
                val toolbar = descendants(activity.findViewById(R.id.notice_detail_back)).filterIsInstance<Toolbar>().single()
                val back = toolbar.children.filterIsInstance<ImageButton>().first()
                assertFullyVisible(back)
                back.performClick()
            }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }




    private fun descendants(root: ViewGroup): Sequence<View> = sequence {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            yield(child)
            if (child is ViewGroup) yieldAll(descendants(child))
        }
    }

    @Test fun milestonePickerPreservesExactTargetsWithWheelIntervals() {
        TwidgetStore.saveStats(context, ProfileStats("Test", account, 100, 10, 20, 30))
        ActivityScenario.launch<MilestoneGoalActivity>(MilestoneGoalActivity.intent(context, account)).use { scenario ->
            scenario.onActivity { activity ->
                MilestoneGoalDialog.show(activity, account, MilestoneMetric.FOLLOWERS) {}
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val root = WindowInspector.getGlobalWindowViews().single {
                    it.findViewById<SeslNumberPicker>(R.id.milestone_number_picker) != null
                }
                val picker = root.findViewById<SeslNumberPicker>(R.id.milestone_number_picker)
                assertEquals(1, picker.minValue)
                assertEquals(999_999_999, picker.maxValue)
                picker.setEditTextMode(true)
                picker.editText.requestFocus()
                picker.editText.setText("123")
                // Save while editing: the dialog must commit an exact value that is
                // not a multiple of its ten-follower wheel interval.
                root.findViewById<Button>(android.R.id.button1).performClick()
                assertEquals(123.0, MilestoneGoalStore.readAll(context, account)
                    .single { it.metric == MilestoneMetric.FOLLOWERS }.target, 0.0)
            }
        }
    }

    @Test fun chartBackDispatcherReturnsSavedRangeChange() {
        val now = System.currentTimeMillis()
        val history = listOf(0L, 10L).map { daysAgo ->
            HistorySample("Day $daysAgo", 100, 10, 20, 30, now - daysAgo * 86_400_000L)
        }
        TwidgetStore.saveStats(context, ProfileStats("Test", account, 100, 10, 20, 30, history = history))
        TwidgetStore.saveChartRange(context, account, "followers", HistoryRange.WEEK)
        ActivityScenario.launchActivityForResult<MetricChartActivity>(
            MetricChartActivity.intent(context, account, "followers"),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val row = activity.findViewById<LinearLayout>(R.id.metric_chart_range_row)
                val allTime = (0 until row.childCount).map { row.getChildAt(it) as Button }
                    .single { it.text == activity.getString(HistoryRange.ALL_TIME.labelRes) }
                allTime.performClick()
                activity.onBackPressedDispatcher.onBackPressed()
            }
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals(HistoryRange.ALL_TIME, TwidgetStore.chartRange(context, account, "followers"))
        }
    }
}
