package com.tjg.twidget.widget

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import androidx.appcompat.widget.AppCompatButton
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.settings.SettingsCategoryActivity
import com.tjg.twidget.settings.SettingsPage
import com.tjg.twidget.settings.WidgetSettingsCard
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetDefaultsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var preferences: SharedPreferences
    private lateinit var original: Map<String, *>

    @Before fun preserve() {
        preferences = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        original = preferences.all
    }

    @After fun restore() {
        preferences.edit().clear().apply {
            original.forEach { (key, value) -> when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
        }.commit()
        TwidgetWidget.updateAll(context)
        TwidgetBriefWidget.updateAll(context)
        LockScreenFollowerViews.updateAll(context)
    }

    @Test fun defaultsAreIndependentAndExplicitInstanceSettingsSurvive() {
        preferences.edit().remove("widget_style_brief").remove("widget_style_lock_screen").commit()
        val followers = TwidgetStore.widgetSettings(context)
        val brief = TwidgetStore.widgetSettings(context, kind = WidgetKind.BRIEF)
        val lock = TwidgetStore.widgetSettings(context, kind = WidgetKind.LOCK_SCREEN)
        val instance = followers.copy(fontFamily = TwidgetStore.FONT_SYSTEM, tintAlpha = 80)
        TwidgetStore.saveWidgetSettings(context, 987654, instance)
        TwidgetStore.saveWidgetSettings(context, 0, followers.copy(style = WidgetStyle.MATERIAL, tintAlpha = 240))
        assertEquals(brief, TwidgetStore.widgetSettings(context, kind = WidgetKind.BRIEF))
        assertEquals(lock, TwidgetStore.widgetSettings(context, kind = WidgetKind.LOCK_SCREEN))
        assertEquals(instance, TwidgetStore.widgetSettings(context, 987654))
        val changed = brief.copy(fontFamily = TwidgetStore.FONT_GOOGLE_SANS_FLEX, colorMode = TwidgetStore.COLOR_MODE_DARK)
        TwidgetStore.saveWidgetSettings(context, 0, changed, WidgetKind.BRIEF)
        assertEquals(changed, TwidgetStore.widgetSettings(context, kind = WidgetKind.BRIEF))
        assertEquals(240, TwidgetStore.widgetSettings(context).tintAlpha)
    }

    @Test fun newDefaultsOpenTwidgetAndKeepExplicitTapChoices() {
        preferences.edit().remove("widget_tap_action").remove("widget_tap_action_brief")
            .remove("widget_tap_action_lock_screen").remove("widget_tap_action_987654")
            .commit()
        // The legacy settings key is part of the same preference contract.
        preferences.edit().remove("tap_action").commit()
        assertEquals(TwidgetStore.TAP_APP, TwidgetStore.widgetSettings(context).tapAction)
        TwidgetStore.saveWidgetSettings(context, 987654,
            TwidgetStore.widgetSettings(context).copy(tapAction = TwidgetStore.TAP_REFRESH))
        assertEquals(TwidgetStore.TAP_REFRESH, TwidgetStore.widgetSettings(context, 987654).tapAction)
    }

    @Test fun horizontalSwipeChangesControlsAndCancelDoesNotSave() {
        val initial = TwidgetStore.widgetSettings(context).copy(style = WidgetStyle.ONE_UI, fontFamily = TwidgetStore.FONT_ONE_UI_SANS)
        TwidgetStore.saveWidgetSettings(context, 0, initial)
        ActivityScenario.launch<WidgetConfigActivity>(Intent(context, WidgetConfigActivity::class.java)
            .putExtra(WidgetConfigActivity.EXTRA_DEFAULT_KIND, WidgetKind.FOLLOWERS.name)).use { scenario ->
            instrumentation.waitForIdleSync()
            val down = SystemClock.uptimeMillis()
            for (step in 0..12) {
                scenario.onActivity { activity ->
                    val pager = activity.findViewById<ViewPager>(R.id.widget_style_pager)
                    val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val event = MotionEvent.obtain(down, down + step * 20, action,
                        pager.width * (.85f - .7f * step / 12), pager.height * .5f, 0)
                    pager.dispatchTouchEvent(event)
                    event.recycle()
                }
                SystemClock.sleep(20)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            scenario.onActivity { activity ->
                assertEquals(1, activity.findViewById<ViewPager>(R.id.widget_style_pager).currentItem)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.opacity_block).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.contained_footer_row).visibility)
                assertEquals(initial, TwidgetStore.widgetSettings(context))
                capture("editor-material")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(1, activity.findViewById<ViewPager>(R.id.widget_style_pager).currentItem)
                activity.findViewById<View>(R.id.btn_cancel).performClick()
            }
        }
        assertEquals(initial, TwidgetStore.widgetSettings(context))
    }

    @Test fun editorReturnsToCardsAfterRepeatedOpenAndCancel() {
        val before = TwidgetStore.widgetSettings(context)
        ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(context, SettingsPage.WIDGETS)).use { scenario ->
            repeat(2) { attempt ->
                val monitor = instrumentation.addMonitor(WidgetConfigActivity::class.java.name, null, false)
                try {
                    scenario.onActivity { activity ->
                        val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                            as androidx.preference.PreferenceFragmentCompat
                        val card = fragment.findPreference<dev.oneuiproject.oneui.preference.LayoutPreference>("widget_card_followers")!!.getView() as ViewGroup
                        (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<ViewGroup>()
                            .flatMap { group -> (0 until group.childCount).map { group.getChildAt(it) } }
                            .filterIsInstance<AppCompatButton>().first().performClick()
                    }
                    val editor = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as WidgetConfigActivity?
                    assertNotNull("Edit must open the widget editor", editor)
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(400)
                    instrumentation.runOnMainSync {
                        assertTrue(editor!!.findViewById<View>(R.id.preview_container).isShown)
                        if (attempt == 0) editor.findViewById<View>(R.id.btn_cancel).performClick()
                    }
                    if (attempt == 1) {
                        // Inject at the system input boundary so this exercises
                        // Android's edge-back gesture, not an app back callback.
                        val location = IntArray(2)
                        var width = 0
                        var height = 0
                        instrumentation.runOnMainSync {
                            editor!!.window.decorView.getLocationOnScreen(location)
                            width = editor.window.decorView.width
                            height = editor.window.decorView.height
                        }
                        val down = SystemClock.uptimeMillis()
                        for (step in 0..20) {
                            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                                location[0] + 1f + width * .4f * step / 20,
                                location[1] + height * .5f, 0).apply {
                                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            }
                            instrumentation.uiAutomation.injectInputEvent(event, true)
                            event.recycle()
                            SystemClock.sleep(20)
                        }
                    }
                    val returnDeadline = SystemClock.uptimeMillis() + 5000
                    var cardsVisible = false
                    while (!cardsVisible && SystemClock.uptimeMillis() < returnDeadline) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            cardsVisible = activity.findViewById<View>(R.id.preference_fragment_container).isShown
                        }
                        if (!cardsVisible) SystemClock.sleep(50)
                    }
                    assertTrue("Widget settings must return after cancelling the editor", cardsVisible)
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            }
        }
        assertEquals(before, TwidgetStore.widgetSettings(context))
    }

    @Test fun predictivePreviewTracksProgressAndRestoresAfterCancellation() {
        ActivityScenario.launch<WidgetConfigActivity>(Intent(context, WidgetConfigActivity::class.java)
            .putExtra(WidgetConfigActivity.EXTRA_DEFAULT_KIND, WidgetKind.FOLLOWERS.name)).use { scenario ->
            scenario.onActivity { it.onEnterAnimationComplete() }
            SystemClock.sleep(300)
            scenario.onActivity { activity ->
                val preview = activity.findViewById<WallpaperPreviewLayout>(R.id.preview_container)
                val dispatcher = activity.onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 0f, 0f, 0))
                dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(100f, 0f, .65f, 0))
                assertEquals(.35f, preview.wallpaperRevealFraction, .01f)
                dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(50f, 0f, .25f, 0))
                assertEquals(.75f, preview.wallpaperRevealFraction, .01f)
                dispatcher.dispatchOnBackCancelled()
            }
            SystemClock.sleep(300)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals(1f, activity.findViewById<WallpaperPreviewLayout>(R.id.preview_container).wallpaperRevealFraction, .01f)
            }
        }
    }

    @Test fun saveBriefDefaultsAndLockEditorKeepOnlyRelevantControls() {
        val followers = TwidgetStore.widgetSettings(context)
        ActivityScenario.launch<WidgetConfigActivity>(Intent(context, WidgetConfigActivity::class.java)
            .putExtra(WidgetConfigActivity.EXTRA_DEFAULT_KIND, WidgetKind.BRIEF.name)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.account_group).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.tap_action_card).visibility)
                activity.findViewById<ViewPager>(R.id.widget_style_pager).setCurrentItem(1, false)
                activity.findViewById<View>(R.id.btn_save).performClick()
            }
        }
        assertEquals(WidgetStyle.MATERIAL, TwidgetStore.widgetSettings(context, kind = WidgetKind.BRIEF).style)
        assertEquals(followers, TwidgetStore.widgetSettings(context))
        ActivityScenario.launch<WidgetConfigActivity>(Intent(context, WidgetConfigActivity::class.java)
            .putExtra(WidgetConfigActivity.EXTRA_DEFAULT_KIND, WidgetKind.LOCK_SCREEN.name)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(1, activity.findViewById<ViewPager>(R.id.widget_style_pager).adapter!!.count)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.opacity_block).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.tap_action_card).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.logo_row).visibility)
                assertEquals((220 * activity.resources.displayMetrics.density).toInt(), activity.findViewById<View>(R.id.preview_container).layoutParams.height)
                assertFalse(activity.findViewById<dev.oneuiproject.oneui.widget.CardItemView>(R.id.language_row).showTopDivider)
                assertTrue(activity.findViewById<dev.oneuiproject.oneui.widget.CardItemView>(R.id.logo_row).showTopDivider)
                activity.findViewById<View>(R.id.btn_cancel).performClick()
            }
        }
    }

    @Test fun cardsRenderAndUnsupportedLockScreenHasNoActions() {
        ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(context, SettingsPage.WIDGETS)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                    as androidx.preference.PreferenceFragmentCompat
                val cards = WidgetKind.entries.map { kind ->
                    fragment.findPreference<dev.oneuiproject.oneui.preference.LayoutPreference>("widget_card_${kind.name.lowercase()}")!!.getView()!!
                }
                fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                    (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                assertEquals(2, descendants(cards[0]).filterIsInstance<AppCompatButton>().size)
                assertEquals(2, descendants(cards[1]).filterIsInstance<AppCompatButton>().size)
                val disabled = WidgetSettingsCard(activity, WidgetKind.LOCK_SCREEN, false, { fail("Disabled editor") }, { fail("Disabled add") }, oneUi = false)
                assertEquals(R.drawable.widget_device_one_ui, WidgetSettingsCard.deviceAsset(true))
                assertEquals(R.drawable.widget_device_material, WidgetSettingsCard.deviceAsset(false))
                assertTrue(descendants(disabled).filterIsInstance<AppCompatButton>().isEmpty())
                assertTrue(descendants(disabled).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.widget_one_ui_only) })
            }
            capture("widgets-one-ui")
        }
        ActivityScenario.launch<WidgetConfigActivity>(Intent(context, WidgetConfigActivity::class.java)
            .putExtra(WidgetConfigActivity.EXTRA_DEFAULT_KIND, WidgetKind.FOLLOWERS.name)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.findViewById<ViewPager>(R.id.widget_style_pager).setCurrentItem(0, false) }
            capture("editor-one-ui")
        }
    }

    @Test fun samsungAndPixelMockupsUseLightAndDarkFigmaAssets() {
        ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(context, SettingsPage.WIDGETS)).use { scenario ->
            scenario.onActivity { activity ->
                val lightConfig = android.content.res.Configuration(activity.resources.configuration).apply {
                    uiMode = uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv() or android.content.res.Configuration.UI_MODE_NIGHT_NO
                }
                val darkConfig = android.content.res.Configuration(lightConfig).apply {
                    uiMode = uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv() or android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                val light = androidx.appcompat.view.ContextThemeWrapper(activity.createConfigurationContext(lightConfig), R.style.Theme_Twidget)
                val dark = androidx.appcompat.view.ContextThemeWrapper(activity.createConfigurationContext(darkConfig), R.style.Theme_Twidget)
                for (oneUi in listOf(true, false)) {
                    val asset = WidgetSettingsCard.deviceAsset(oneUi)
                    val lightBitmap = (light.getDrawable(asset) as android.graphics.drawable.BitmapDrawable).bitmap
                    val darkBitmap = (dark.getDrawable(asset) as android.graphics.drawable.BitmapDrawable).bitmap
                    assertEquals(918, lightBitmap.width)
                    assertEquals(1950, lightBitmap.height)
                    assertEquals(lightBitmap.width, darkBitmap.width)
                    assertFalse("Dark mockups must use the original dark wallpaper", lightBitmap.sameAs(darkBitmap))
                    for ((theme, themedContext) in listOf("light" to light, "dark" to dark)) {
                        val defaults = TwidgetStore.widgetSettings(context)
                        TwidgetStore.saveWidgetSettings(context, 0, defaults.copy(colorMode = if (theme == "dark")
                            TwidgetStore.COLOR_MODE_DARK else TwidgetStore.COLOR_MODE_LIGHT))
                        val card = WidgetSettingsCard(themedContext, WidgetKind.FOLLOWERS, true, {}, {}, oneUi)
                        val width = (392 * context.resources.displayMetrics.density).toInt()
                        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        card.layout(0, 0, width, card.measuredHeight)
                        val bitmap = Bitmap.createBitmap(width, card.height, Bitmap.Config.ARGB_8888)
                        card.draw(android.graphics.Canvas(bitmap))
                        java.io.File(context.getExternalFilesDir(null), "widget-${if (oneUi) "samsung" else "pixel"}-$theme.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test fun lockScreenPreviewsKeepNaturalArtworkProportions() {
        ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(context, SettingsPage.WIDGETS)).use { scenario ->
            scenario.onActivity { activity ->
                val card = WidgetSettingsCard(activity, WidgetKind.LOCK_SCREEN, true, {}, {}, oneUi = true)
                val width = (392 * activity.resources.displayMetrics.density).toInt()
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                val previews = (0 until card.childCount).map { card.getChildAt(it) }
                    .filterIsInstance<android.widget.ImageView>()
                    .filter { it.scaleType == android.widget.ImageView.ScaleType.FIT_CENTER }
                assertEquals(2, previews.size)
                previews.forEach { image ->
                    val matrix = FloatArray(9).also { image.imageMatrix.getValues(it) }
                    assertEquals("Lock artwork must scale uniformly", matrix[android.graphics.Matrix.MSCALE_X],
                        matrix[android.graphics.Matrix.MSCALE_Y], .001f)
                }
                val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                card.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.getExternalFilesDir(null), "widget-lock-screen.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun addOpensNativeLauncherDialog() {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        org.junit.Assume.assumeTrue(manager.isRequestPinAppWidgetSupported)
        ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(context, SettingsPage.WIDGETS)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                    as androidx.preference.PreferenceFragmentCompat
                val card = fragment.findPreference<dev.oneuiproject.oneui.preference.LayoutPreference>("widget_card_followers")!!.getView()!!
                fun findAdd(view: View): View? {
                    if (view is AppCompatButton && view.text == activity.getString(R.string.schedule_add)) return view
                    if (view is ViewGroup) for (index in 0 until view.childCount) findAdd(view.getChildAt(index))?.let { return it }
                    return null
                }
                assertTrue(findAdd(card)!!.performClick())
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertNotNull("The launcher must show the pinning dialog", root)
            assertNotEquals(context.packageName, root!!.packageName.toString())
            capture("widget-add-dialog")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        }
    }

    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
