package com.tjg.twidget.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.ListPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.BuildConfig
import com.tjg.twidget.ui.AppAppearance
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleSettingsStore
import com.tjg.twidget.widget.WidgetStyle
import com.tjg.twidget.widget.RefreshWorker
import dev.oneuiproject.oneui.preference.LayoutPreference
import dev.oneuiproject.oneui.widget.RadioItemViewGroup
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against real SESL views and the app's real stores. Restores existing emulator settings. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val activities = mutableListOf<Activity>()
    private lateinit var preferences: SharedPreferences
    private lateinit var saved: Map<String, *>
    private lateinit var briefPreferences: SharedPreferences
    private lateinit var savedBrief: Map<String, *>
    private lateinit var savedProvider: ScheduleProvider

    @Before fun preserveSettings() {
        preferences = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        saved = preferences.all
        briefPreferences = context.getSharedPreferences("twidget_brief_settings", Context.MODE_PRIVATE)
        savedBrief = briefPreferences.all
        savedProvider = ScheduleSettingsStore.defaultProvider(context)
    }

    @After fun restoreSettings() {
        instrumentation.runOnMainSync { activities.reversed().forEach { it.finish() } }
        instrumentation.waitForIdleSync()
        listOf(preferences to saved, briefPreferences to savedBrief).forEach { (store, snapshot) ->
            store.edit().clear().apply {
                snapshot.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
        }
        ScheduleSettingsStore.setDefaultProvider(context, savedProvider)
        RefreshWorker.schedule(context)
    }

    @Test fun briefBadgeClearsOnlyAfterSetupAndViewingSettings() {
        briefPreferences.edit().remove("settings_viewed").apply()
        BriefSettingsStore.setOnboardingComplete(context, false)
        assertTrue(BriefSettingsStore.showSettingsBadge(context))
        BriefSettingsStore.setOnboardingComplete(context, true)
        assertTrue(BriefSettingsStore.showSettingsBadge(context))
        BriefSettingsStore.setOnboardingComplete(context, false)
        launch(Intent(context, BriefSettingsActivity::class.java))
        assertTrue(BriefSettingsStore.settingsViewed(context))
        assertTrue(BriefSettingsStore.showSettingsBadge(context))
        BriefSettingsStore.setOnboardingComplete(context, true)
        assertFalse(BriefSettingsStore.showSettingsBadge(context))
        BriefSettingsStore.setEnabled(context, false)
        assertFalse(BriefSettingsStore.showSettingsBadge(context))
    }

    @Test fun briefContentIconsShareOneColumnAndSwitchActionsRemainSeparate() {
        val activity = launch(Intent(context, BriefContentSettingsActivity::class.java))
        onMain {
            val content = fragment(activity)
            val list = content.listView
            val columns = (0 until list.childCount).mapNotNull { index ->
                list.getChildAt(index).findViewById<android.view.View>(android.R.id.icon)
                    ?.takeIf { it.visibility == android.view.View.VISIBLE }
                    ?.let { icon -> IntArray(2).also { icon.getLocationOnScreen(it) }[0] }
            }
            assertTrue(columns.size >= 4)
            assertEquals(1, columns.distinct().size)
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index)
                val icon = row.findViewById<android.view.View>(android.R.id.icon) ?: continue
                if (icon.visibility != android.view.View.VISIBLE) continue
                val title = row.findViewById<android.view.View>(android.R.id.title)
                val rowPosition = IntArray(2).also { row.getLocationOnScreen(it) }
                val titlePosition = IntArray(2).also { title.getLocationOnScreen(it) }
                val iconPosition = IntArray(2).also { icon.getLocationOnScreen(it) }
                assertEquals((rowPosition[0] + titlePosition[0]) / 2.0,
                    iconPosition[0] + icon.width / 2.0, 1.0)
            }
            val plain = content.findPreference<androidx.preference.SwitchPreferenceCompat>("brief_content_top_tweet")!!
            val before = plain.isChecked
            plain.performClick()
            assertEquals(!before, plain.isChecked)
            val navigable = content.findPreference<androidx.preference.SeslSwitchPreferenceScreen>("brief_content_post_follow_through")!!
            val selected = navigable.isChecked
            navigable.performClick()
            assertEquals(selected, navigable.isChecked)
            navigable.callChangeListener(!selected)
            assertEquals(!selected, BriefSettingsStore.contentEnabled(context, com.tjg.twidget.brief.BriefContentCategory.POST_FOLLOW_THROUGH))
        }
    }

    @Test fun aboutKeepsHeaderActionsCreditsAndDebugUnlock() {
        val activity = launch(Intent(context, com.tjg.twidget.main.AboutActivity::class.java))
        onMain {
            val header = activity.findViewById<dev.oneuiproject.oneui.widget.CardItemView>(R.id.about_compact_header)
            assertTrue(header.summary.toString().contains(context.packageManager.getPackageInfo(context.packageName, 0).versionName!!))
            assertTrue(header.getEndImageView().hasOnClickListeners())
            assertTrue(activity.findViewById<android.view.View>(R.id.about_tjg_credit).findViewById<android.view.View>(dev.oneuiproject.oneui.design.R.id.cardview_container).hasOnClickListeners())
            assertNull(activity.findViewById<dev.oneuiproject.oneui.widget.CardItemView>(R.id.about_fxtwitter_credit).summary)
            val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.about_toolbar)
            assertEquals(if (BuildConfig.IN_APP_UPDATES) 2 else 1, toolbar.menu.size())
            if (BuildConfig.IN_APP_UPDATES) assertTrue(toolbar.menu.getItem(1).hasSubMenu())
            assertEquals("https://github.com/tribalfs/oneui-design", context.getString(R.string.link_oneui_project))
            TwidgetStore.setDebugMenuUnlocked(context, false)
            repeat(7) { header.getSummaryView().performClick() }
            assertTrue(TwidgetStore.debugMenuUnlocked(context))
            activity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.about_app_bar).setExpanded(false, false)
        }
        instrumentation.waitForIdleSync()
        capture("AboutCollapsed")
    }

    @Test fun sharedWidgetOpacityRetainsCancelAndSaveBehaviour() {
        val original = TwidgetStore.widgetSettings(context)
        val cancelled = launch(Intent(context, com.tjg.twidget.widget.WidgetConfigActivity::class.java))
        onMain {
            val slider = cancelled.findViewById<androidx.appcompat.widget.SeslSeekBar>(R.id.opacity_slider)
            assertEquals(3, slider.max)
            slider.progress = 0
            cancelled.findViewById<android.view.View>(R.id.btn_cancel).performClick()
            assertEquals(original, TwidgetStore.widgetSettings(context))
        }
        val saved = launch(Intent(context, com.tjg.twidget.widget.WidgetConfigActivity::class.java))
        onMain {
            saved.findViewById<androidx.appcompat.widget.SeslSeekBar>(R.id.opacity_slider).progress = 3
            saved.findViewById<android.view.View>(R.id.btn_save).performClick()
            assertEquals(240, TwidgetStore.widgetSettings(context).tintAlpha)
        }
    }

    @Test fun mainPageOpensEveryCategoryAndKeepsAccountActions() {
        val activity = launch(Intent(context, SettingsActivity::class.java))
        onMain {
            val main = fragment(activity)
            val keys = (0 until main.preferenceScreen.preferenceCount).mapNotNull {
                main.preferenceScreen.getPreference(it).key
            }
            assertEquals(listOf("main_account", "accounts", "appearance", "data_sources", "brief_settings_pref",
                "notifications", SettingsActivity.PREFERENCE_SCHEDULING, "app_language_pref", "about_twidget"),
                keys.filter { it != "bottom_inset" && it != "debug_menu" && it != "update_suggestion" })
            assertNull(main.findPreference<Preference>("refresh_interval_pref"))
            assertNull(main.findPreference<Preference>("data_source_pref"))
        }
        SettingsPage.entries.forEach { page ->
            val category = launch(SettingsCategoryActivity.intent(context, page))
            onMain {
                assertTrue(fragment(category).preferenceScreen.preferenceCount > 1)
                if (page == SettingsPage.ACCOUNTS) {
                    assertNotNull(fragment(category).findPreference<Preference>("analytics_import"))
                    assertNotNull(fragment(category).findPreference<Preference>("buffer_settings"))
                    assertNotNull(fragment(category).findPreference<Preference>("add_account"))
                    assertTrue(AccountPopupAction.DELETE in accountPopupActions(true))
                }
            }
        }
    }

    @Test fun movedDataControlsStillPersistAndClampRefreshInterval() {
        val activity = launch(SettingsCategoryActivity.intent(context, SettingsPage.DATA))
        onMain {
            val screen = fragment(activity)
            assertTrue(screen.findPreference<Preference>("refresh_interval_pref")!!.callChangeListener("1"))
            assertEquals(15, TwidgetStore.settings(context).refreshIntervalMinutes)
            assertTrue(screen.findPreference<Preference>("refresh_interval_pref")!!.callChangeListener("999"))
            assertEquals(240, TwidgetStore.settings(context).refreshIntervalMinutes)
            val old = TwidgetStore.settings(context).refreshOnLaunch
            screen.findPreference<Preference>("refresh_on_launch_pref")!!.callChangeListener(!old)
            assertEquals(!old, TwidgetStore.settings(context).refreshOnLaunch)
            screen.findPreference<Preference>("data_source_pref")!!.callChangeListener(TwidgetStore.DATA_SOURCE_SELF_HOSTED)
            assertEquals(TwidgetStore.DATA_SOURCE_SELF_HOSTED, TwidgetStore.settings(context).dataSource)
        }
    }

    @Test fun sourcePagesKeepOnlyTheirOwnCredentialControls() {
        listOf(TwidgetStore.DATA_SOURCE_SELF_HOSTED, TwidgetStore.DATA_SOURCE_TWITTERAPIS, TwidgetStore.DATA_SOURCE_X_API).forEach { source ->
            val activity = launch(Intent(context, SettingsAdvancedActivity::class.java).putExtra(SettingsAdvancedActivity.EXTRA_SOURCE, source))
            onMain {
                val screen = fragment(activity)
                assertEquals(source == TwidgetStore.DATA_SOURCE_SELF_HOSTED, screen.findPreference<Preference>("self_hosted_token_pref") != null)
                assertEquals(source == TwidgetStore.DATA_SOURCE_TWITTERAPIS, screen.findPreference<Preference>("twitterapis_api_key_pref") != null)
                assertEquals(source == TwidgetStore.DATA_SOURCE_X_API, screen.findPreference<Preference>("x_api_token_pref") != null)
            }
        }
    }

    @Test fun appearanceDefaultsPersistWithoutOverwritingWidgetOverrides() {
        val widgetId = 987654
        val original = TwidgetStore.widgetSettings(context)
        TwidgetStore.saveWidgetSettings(context, 0, original.copy(style = WidgetStyle.ONE_UI, fontFamily = TwidgetStore.FONT_ONE_UI_SANS))
        val specific = original.copy(fontFamily = TwidgetStore.FONT_ONE_UI_SANS, colorMode = TwidgetStore.COLOR_MODE_LIGHT, tintAlpha = 180)
        TwidgetStore.saveWidgetSettings(context, widgetId, specific)
        val activity = launch(SettingsCategoryActivity.intent(context, SettingsPage.APPEARANCE))
        onMain {
            val screen = fragment(activity)
            val ordered = (0 until screen.preferenceScreen.preferenceCount).map {
                screen.preferenceScreen.getPreference(it)
            }
            assertEquals("settings_theme", ordered[0].key)
            assertEquals("settings_theme_system", ordered[1].key)
            assertTrue(ordered[2] is dev.oneuiproject.oneui.preference.InsetPreferenceCategory)
            assertEquals("settings_app_font", ordered[3].key)
            val defaultsIndex = ordered.indexOfFirst { it.title == context.getString(R.string.settings_widget_defaults) }
            assertTrue(defaultsIndex > 3)
            assertEquals("settings_widget_style", ordered[defaultsIndex + 1].key)
            assertTrue(ordered[defaultsIndex + 2] is dev.oneuiproject.oneui.preference.InsetPreferenceCategory)
            assertEquals(listOf("settings_widget_opacity", "settings_widget_font", "settings_widget_colours",
                "settings_widget_logo", "settings_widget_contained_footer", "settings_widget_contained_footer_description"),
                ordered.drop(defaultsIndex + 3).take(6).map { it.key })
            val showsFontTip = BuildConfig.FLAVOR == "github" && TwidgetFonts.hasSystemOneUiSans
            assertEquals(showsFontTip, ordered[3].widgetLayoutResource == R.layout.preference_font_tip)
            assertNull(screen.findPreference<Preference>("settings_app_font_tip"))
            assertNull(screen.findPreference<Preference>("settings_app_font_inset"))
            val style = screen.findPreference<ListPreference>("settings_widget_style")!!
            val opacity = screen.findPreference<LayoutPreference>("settings_widget_opacity")!!
            val font = screen.findPreference<ListPreference>("settings_widget_font")!!
            val footer = screen.findPreference<Preference>("settings_widget_contained_footer")!!
            val footerDescription = screen.findPreference<Preference>("settings_widget_contained_footer_description")!!
            assertTrue(opacity.isVisible)
            assertFalse(footer.isVisible)
            assertFalse(footerDescription.isVisible)
            style.callChangeListener(WidgetStyle.MATERIAL.storedValue)
            assertFalse(opacity.isVisible)
            assertTrue(footer.isVisible)
            assertTrue(footerDescription.isVisible)
            assertEquals(TwidgetStore.FONT_GOOGLE_SANS_FLEX, font.value)
            assertEquals(WidgetStyle.MATERIAL, TwidgetStore.widgetSettings(context, widgetId + 1).style)
            assertEquals(specific, TwidgetStore.widgetSettings(context, widgetId))
            font.callChangeListener(TwidgetStore.FONT_SYSTEM)
            style.callChangeListener(WidgetStyle.ONE_UI.storedValue)
            assertEquals(TwidgetStore.FONT_SYSTEM, TwidgetStore.widgetSettings(context).fontFamily)
            assertTrue(opacity.isVisible)
            assertFalse(footer.isVisible)
            assertFalse(footerDescription.isVisible)
            screen.findPreference<Preference>("settings_widget_font")!!.callChangeListener(TwidgetStore.FONT_GOOGLE_SANS_FLEX)
            screen.findPreference<Preference>("settings_widget_colours")!!.callChangeListener(TwidgetStore.COLOR_MODE_DARK)
            opacity.findViewById<androidx.appcompat.widget.SeslSeekBar>(R.id.opacity_slider).progress = 1
            assertEquals(TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.widgetSettings(context).fontFamily)
            assertEquals(TwidgetStore.COLOR_MODE_DARK, TwidgetStore.widgetSettings(context).colorMode)
            assertEquals(102, TwidgetStore.widgetSettings(context).tintAlpha)
            screen.findPreference<Preference>("settings_widget_logo")!!.callChangeListener(TwidgetStore.LOGO_TWITTER)
            assertEquals(TwidgetStore.LOGO_TWITTER, TwidgetStore.widgetSettings(context).logo)
            assertEquals(specific, TwidgetStore.widgetSettings(context, widgetId))
            assertEquals(TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.widgetSettings(context, widgetId + 1).fontFamily)
        }
    }

    @Test fun googleTypographyMatchesReferenceAndRestoresOtherFonts() {
        AppAppearance.setFont(context, AppAppearance.Font.GOOGLE_SANS_FLEX)
        TwidgetStore.saveWidgetSettings(context, 0, TwidgetStore.widgetSettings(context).copy(style = WidgetStyle.ONE_UI))
        val activity = launch(SettingsCategoryActivity.intent(context, SettingsPage.APPEARANCE))
        onMain {
            fun labels(view: android.view.View): List<android.widget.TextView> = when (view) {
                is android.widget.TextView -> listOf(view)
                is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                else -> emptyList()
            }
            TwidgetFonts.applyTo(activity.window.decorView)
            val visible = labels(activity.window.decorView)
            val title = visible.first { it.text == context.getString(R.string.settings_app_font) }
            val section = visible.first { it.text == context.getString(R.string.settings_widget_defaults) }
            assertEquals(500, title.typeface.weight)
            assertEquals(700, section.typeface.weight)
            val normalWidth = android.graphics.Paint(section.paint).apply { typeface = TwidgetFonts.forApp(context, 700) }
            assertTrue(section.paint.measureText(section.text.toString()) < normalWidth.measureText(section.text.toString()))
            val first = title.typeface
            repeat(3) { TwidgetFonts.applyTo(activity.window.decorView) }
            assertSame(first, title.typeface)
            val label = android.widget.TextView(activity).apply {
                id = android.R.id.title
                textSize = 17f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400, false)
            }
            val originalSize = label.textSize
            TwidgetFonts.applyTo(label)
            assertEquals(500, label.typeface.weight)
            assertEquals(originalSize, label.textSize, .1f)
            AppAppearance.setFont(context, AppAppearance.Font.SYSTEM)
            TwidgetFonts.applyTo(label)
            assertEquals(400, label.typeface.weight)
            assertEquals(originalSize, label.textSize, .1f)
            AppAppearance.setFont(context, AppAppearance.Font.GOOGLE_SANS_FLEX)
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(context.cacheDir, "appearance-google.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun changingAppFontPreservesSystemScaledTextSizes() {
        onMain {
            for (scale in listOf(.85f, 1f, 1.5f, 2f)) {
                val configuration = android.content.res.Configuration(context.resources.configuration).apply {
                    fontScale = scale
                }
                val scaled = context.createConfigurationContext(configuration)
                for (size in listOf(11f, 14f, 17f, 32f)) {
                    for (id in listOf(android.R.id.title, android.R.id.summary, dev.oneuiproject.oneui.design.R.id.switch_card_title, android.view.View.NO_ID)) {
                        val label = android.widget.TextView(scaled).apply {
                            this.id = id
                            textSize = size
                        }
                        val expectedSize = label.textSize
                        for (font in listOf(AppAppearance.Font.DEFAULT, AppAppearance.Font.GOOGLE_SANS_FLEX,
                            AppAppearance.Font.SYSTEM, AppAppearance.Font.GOOGLE_SANS_FLEX)) {
                            AppAppearance.setFont(context, font)
                            repeat(3) { TwidgetFonts.applyTo(label) }
                            assertEquals("$font must preserve $size sp at system scale $scale", expectedSize, label.textSize, .01f)
                        }
                    }
                }
            }
        }
    }

    @Test fun googleAppWeightsMatchVariableOutlinesWithoutSyntheticBold() {
        AppAppearance.setFont(context, AppAppearance.Font.GOOGLE_SANS_FLEX)
        onMain {
            for (weight in listOf(400, 500, 700)) {
                val reference = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = TwidgetFonts.googleSansFlex(context)
                    textSize = 48f
                    fontVariationSettings = "'wght' $weight, 'wdth' 100, 'ROND' ${if (weight == 700) 100 else 0}, 'GRAD' 0, 'slnt' 0, 'opsz' 18"
                }
                val actual = android.graphics.Paint(reference).apply { typeface = TwidgetFonts.forApp(context, weight) }
                fun render(paint: android.graphics.Paint) = android.graphics.Bitmap.createBitmap(600, 90, android.graphics.Bitmap.Config.ARGB_8888).apply {
                    android.graphics.Canvas(this).drawText("Appearance 123", 10f, 65f, paint)
                }
                val expected = render(reference)
                val result = render(actual)
                assertTrue("Weight $weight outlines must match the original variable axes", expected.sameAs(result))
                expected.recycle()
                result.recycle()
            }
        }
    }

    @Test fun appFontSelectionPersistsAndKeepsWidgetFontsIndependent() {
        val widgets = TwidgetStore.widgetSettings(context)
        AppAppearance.setFont(context, AppAppearance.Font.DEFAULT)
        ActivityScenario.launch<SettingsCategoryActivity>(
            SettingsCategoryActivity.intent(context, SettingsPage.APPEARANCE)
        ).use { scenario ->
            listOf(AppAppearance.Font.GOOGLE_SANS_FLEX, AppAppearance.Font.SYSTEM,
                AppAppearance.Font.DEFAULT).forEach { font ->
                scenario.onActivity { activity ->
                    val preference = fragment(activity).findPreference<ListPreference>("settings_app_font")!!
                    assertEquals(listOf(context.getString(R.string.settings_app_font_default),
                        context.getString(R.string.widget_font_google), context.getString(R.string.widget_font_system)),
                        preference.entries.map { it.toString() })
                    assertTrue(preference.callChangeListener(font.value))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(font, AppAppearance.font(activity))
                    assertEquals(font.value, fragment(activity).findPreference<ListPreference>("settings_app_font")!!.value)
                    assertEquals(widgets, TwidgetStore.widgetSettings(activity))
                    val title = activity.findViewById<android.widget.TextView>(
                        com.google.android.material.R.id.collapsing_appbar_extended_title)
                    assertEquals(TwidgetFonts.forApp(activity, 700), title.typeface)
                }
                scenario.recreate()
                scenario.onActivity { assertEquals(font, AppAppearance.font(it)) }
            }
        }
    }

    @Test fun appFontsPreserveWeightsAndStyleInViewsAndDialogWindows() {
        ActivityScenario.launch<SettingsCategoryActivity>(
            SettingsCategoryActivity.intent(context, SettingsPage.APPEARANCE)
        ).use { scenario ->
            AppAppearance.Font.entries.forEach { font ->
                AppAppearance.setFont(context, font)
                scenario.recreate()
                lateinit var dialog: androidx.appcompat.app.AlertDialog
                scenario.onActivity { activity ->
                    val column = android.widget.LinearLayout(activity)
                    listOf(200, 400, 600, 700).forEach { weight ->
                        val label = android.widget.TextView(activity).apply {
                            text = "Weight $weight"
                            typeface = if (android.os.Build.VERSION.SDK_INT >= 28)
                                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, weight, true)
                            else android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                                if (weight >= 600) android.graphics.Typeface.BOLD_ITALIC else android.graphics.Typeface.ITALIC)
                        }
                        column.addView(label)
                        TwidgetFonts.applyTo(label)
                        if (android.os.Build.VERSION.SDK_INT >= 28) assertEquals(weight, label.typeface.weight)
                        assertTrue(label.typeface.isItalic)
                        val first = label.typeface
                        TwidgetFonts.applyTo(label)
                        assertEquals(first, label.typeface)
                    }
                    dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("Font preview")
                        .setMessage("Dialog body")
                        .setView(column)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val body = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!
                    val weight = if (android.os.Build.VERSION.SDK_INT >= 28) body.typeface.weight else 400
                    assertEquals(TwidgetFonts.forApp(activity, weight), body.typeface)
                    dialog.dismiss()
                }
            }
        }
    }

    @Test fun schedulingRadioRowsPersistBothMethods() {
        val activity = launch(SettingsCategoryActivity.intent(context, SettingsPage.SCHEDULING))
        onMain {
            val group = fragment(activity).findPreference<LayoutPreference>("schedule_default_method_pref")!!.getView() as RadioItemViewGroup
            group.check(group.getChildAt(0).id)
            assertEquals(ScheduleProvider.BUFFER, ScheduleSettingsStore.defaultProvider(context))
            group.check(group.getChildAt(1).id)
            assertEquals(ScheduleProvider.LOCAL_REMINDER, ScheduleSettingsStore.defaultProvider(context))
        }
    }

    @Test fun settingsCopyHasGermanResources() {
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.GERMAN) }
        val german = context.createConfigurationContext(configuration)
        assertEquals("Daten und Quellen", german.getString(R.string.settings_data_sources))
        assertEquals("Hauptkonto", german.getString(R.string.settings_main_account))
        assertEquals("Widget-Standardeinstellungen", german.getString(R.string.settings_widget_defaults))
    }

    @Test fun existingSubpagesRenderAndRetainTheirControls() {
        listOf(
            BriefSettingsActivity::class.java to "brief",
            BriefContentSettingsActivity::class.java to "brief-content",
            SettingsDebugActivity::class.java to "debug",
            SettingsScheduleActivity::class.java to "buffer",
        ).forEach { (type, name) ->
            val activity = launch(Intent(context, type))
            onMain { assertTrue(fragment(activity).preferenceScreen.preferenceCount > 0) }
            capture(name)
        }
    }

    @Test fun languageOpensSystemPageAndLegacyDialogUsesSingleChoiceRows() {
        val activity = launch(Intent(context, SettingsActivity::class.java))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val filter = android.content.IntentFilter(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS).apply { addDataScheme("package") }
            val monitor = instrumentation.addMonitor(filter, null, true)
            try {
                onMain { SettingsLanguage.open(activity) }
                assertEquals(1, monitor.hits)
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
        onMain {
            val dialog = SettingsLanguage.showLegacyDialog(activity)
            assertEquals(android.widget.ListView.CHOICE_MODE_SINGLE, dialog.listView.choiceMode)
            assertEquals(3, dialog.listView.adapter.count)
            dialog.dismiss()
        }
    }

    private fun capture(name: String) {
        val directory = java.io.File(context.getExternalFilesDir(null), "settings-review").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(directory, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun launch(intent: Intent): AppCompatActivity {
        val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as AppCompatActivity
        activities.add(activity)
        instrumentation.waitForIdleSync()
        val name = intent.getStringExtra(SettingsCategoryActivity.EXTRA_PAGE)
            ?: intent.getStringExtra(SettingsAdvancedActivity.EXTRA_SOURCE)
            ?: activity.javaClass.simpleName
        capture(name)
        return activity
    }

    private fun fragment(activity: AppCompatActivity) = activity.supportFragmentManager
        .findFragmentById(R.id.preference_fragment_container) as PreferenceFragmentCompat

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
