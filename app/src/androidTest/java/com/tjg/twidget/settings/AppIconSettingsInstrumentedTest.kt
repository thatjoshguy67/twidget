package com.tjg.twidget.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppIconSettingsInstrumentedTest {
    @Test fun switchingKeepsOneLauncherAndSharedAdaptiveGeometry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AppIconSettings.selected(context)
        val pid = android.os.Process.myPid()
        try {
            for (style in AppIconStyle.entries) {
                AppIconSettings.select(context, style)
                assertEquals(style, AppIconSettings.selected(context))
                val launchers = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
                assertEquals("Only one launcher entry", 1, launchers.size)
                assertEquals(style.componentName(context).className, launchers.single().activityInfo.name)
                assertEquals(style.icon, launchers.single().activityInfo.icon)
                assertEquals("Switch does not restart the app", pid, android.os.Process.myPid())
                val icon = context.getDrawable(style.icon) as AdaptiveIconDrawable
                assertNotNull(icon.monochrome)
                val bitmap = Bitmap.createBitmap(324, 324, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, 324, 324)
                icon.draw(Canvas(bitmap))
                File(context.filesDir, "launcher-icon-${style.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(context, SettingsPage.APPEARANCE)).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(AppIconStyle.DARK, AppIconSettings.selected(activity))
                    val fragment = activity.supportFragmentManager.findFragmentById(com.tjg.twidget.R.id.preference_fragment_container) as SettingsCategoryPreferenceFragment
                    assertNotNull(fragment.findPreference<androidx.preference.Preference>("settings_app_icon"))
                    val view = AppIconSettings.pickerView(activity)
                    val width = (392 * activity.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,width,view.measuredHeight)
                    val bitmap = Bitmap.createBitmap(width,view.height,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    File(context.filesDir,"app-icon-picker.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
            }
        } finally { AppIconSettings.select(context, original) }
    }
}
