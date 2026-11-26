package com.tjg.twidget.brief

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.settings.BriefSettingsActivity
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefProviderSetupInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun chooserPrioritisesChatGptWithoutChangingProviderWhenCancelled() {
        val before = BriefOpenAi.method(context)
        ActivityScenario.launch(BriefSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = BriefProviderSetupDialog.show(activity, { fail("No sign-in requested") }, { fail("Nothing saved") })
                assertEquals(3, dialog.listView.count)
                assertEquals(0, dialog.listView.checkedItemPosition)
                assertEquals(activity.getString(R.string.brief_setup_chatgpt_continue), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                dialog.listView.performItemClick(dialog.listView.getChildAt(2), 2, 2)
                assertEquals(activity.getString(R.string.brief_setup_enter_key), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                assertEquals(before, BriefOpenAi.method(context))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals(before, BriefOpenAi.method(context))
            }
        }
    }

    @Test fun signedOutAccountCanStartSignInFromTheDefaultChoice() {
        assumeFalse(BriefOpenAi.signedIn(context))
        val before = BriefOpenAi.method(context)
        ActivityScenario.launch(BriefSettingsActivity::class.java).use { scenario ->
            var launched = false
            scenario.onActivity { activity ->
                val dialog = BriefProviderSetupDialog.show(activity, { launched = true }, { fail("No connection yet") })
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(launched)
                assertEquals(before, BriefOpenAi.method(context))
            }
        }
    }

    @Test fun blueLightPoolsHaveVisibleVariationAndMoveInBothAppearances() {
        for (dark in listOf(false, true)) {
            val drawable = BriefBlueBackground(BriefBlueTheme.MORNING, dark)
            drawable.setBounds(0, 0, 360, 800)
            val first = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
            val second = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
            drawable.setMotionProgress(0f); drawable.draw(Canvas(first))
            drawable.setMotionProgress(.25f); drawable.draw(Canvas(second))
            val a = IntArray(360 * 800); val b = IntArray(a.size)
            first.getPixels(a, 0, 360, 0, 0, 360, 800)
            second.getPixels(b, 0, 360, 0, 0, 360, 800)
            assertTrue("The background contains distinct light pools", a.toSet().size > 1_000)
            assertTrue("The pools visibly move", a.indices.count { a[it] != b[it] } > a.size / 4)
            first.recycle(); second.recycle()
        }
    }
}
