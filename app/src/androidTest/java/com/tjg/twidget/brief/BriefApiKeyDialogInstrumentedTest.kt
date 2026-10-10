package com.tjg.twidget.brief

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.settings.BriefSettingsActivity
import com.tjg.twidget.ui.AppAppearance
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefApiKeyDialogInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun figmaDialogKeepsActionsVisibleAtPhoneWidthAndWithLargeText() {
        val originalMode = AppAppearance.mode(context)
        val originalFont = AppAppearance.font(context)
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        try {
            for (language in listOf("en", "de")) for (dark in listOf(false, true)) for (font in listOf(AppAppearance.Font.DEFAULT, AppAppearance.Font.GOOGLE_SANS_FLEX)) {
                instrumentation.runOnMainSync {
                    AppAppearance.setMode(context, if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
                    AppAppearance.setFont(context, font)
                }
                ActivityScenario.launch(BriefSettingsActivity::class.java).use { scenario ->
                    scenario.onActivity {
                        AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(language))
                    }
                    instrumentation.waitForIdleSync()
                    val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                    var localeApplied = false
                    while (!localeApplied && android.os.SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { localeApplied = it.resources.configuration.locales[0].language == language }
                        if (!localeApplied) android.os.SystemClock.sleep(50)
                    }
                    assertTrue("Activity uses requested language: $language", localeApplied)
                    instrumentation.waitForIdleSync()
                    lateinit var dialog: AlertDialog
                    scenario.onActivity { activity ->
                        dialog = BriefApiKeyDialog.show(activity, required = false) { fail("Layout check must not save") }
                        dialog.findViewById<EditText>(R.id.brief_api_key_input)!!.setText("example-api-key-for-layout-only")
                    }
                    instrumentation.waitForIdleSync()
                    saveImage(dialog, "api-key-$language-${font.value}-${if (dark) "dark" else "light"}.png")
                    instrumentation.runOnMainSync {
                        assertActionsVisible(dialog)
                        assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility)
                        assertTrue("Compact default height", dialog.window!!.decorView.height / context.resources.displayMetrics.density < 380f)
                        val message = dialog.findViewById<TextView>(R.id.brief_api_key_message)!!.text as android.text.Spanned
                        val links = message.getSpans(0, message.length, android.text.style.ClickableSpan::class.java)
                        assertEquals("Inline privacy policy remains clickable", 1, links.size)
                        assertEquals(dialog.context.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                        assertEquals(dialog.context.getColor(R.color.oneui_text_primary), dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
                        if (language == "de") {
                            assertTrue(message.toString().contains("Datenschutzerklärung"))
                            assertTrue(message.toString().contains("dein Briefing"))
                            assertEquals("Speichern", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                            assertEquals("Abbrechen", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                            assertTrue(dialog.findViewById<TextView>(R.id.brief_api_key_link)!!.text.toString().startsWith("Hier tippen"))
                        }
                        val input = dialog.findViewById<EditText>(R.id.brief_api_key_input)!!
                        assertNotNull(input.transformationMethod)
                        assertEquals(1, input.maxLines)
                        val density = context.resources.displayMetrics.density
                        // Constrain only the dialog to simulate a narrow window with the IME open.
                        dialog.window!!.setLayout((360 * density).toInt(), (340 * density).toInt())
                        fun enlarge(view: View) {
                            if (view is TextView) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * 1.5f)
                            if (view is ViewGroup) for (i in 0 until view.childCount) enlarge(view.getChildAt(i))
                        }
                        enlarge(dialog.window!!.decorView)
                    }
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertActionsVisible(dialog)
                        val scroll = dialog.findViewById<NestedScrollView>(R.id.brief_api_key_scroll)!!
                        assertTrue("Large text scrolls inside the dialog", scroll.canScrollVertically(1))
                        scroll.fullScroll(View.FOCUS_DOWN)
                    }
                    instrumentation.waitForIdleSync()
                    saveImage(dialog, "api-key-large-text-$language-${font.value}-${if (dark) "dark" else "light"}.png")
                    instrumentation.runOnMainSync {
                        assertActionsVisible(dialog)
                        dialog.dismiss()
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(originalLocales)
                AppAppearance.setFont(context, originalFont)
                AppAppearance.setMode(context, originalMode)
            }
        }
    }

    @Test fun keyboardDoneValidatesRequiredKeyAndCancelDoesNotChangeCredentials() {
        val original = BriefSettingsStore.cloudApiKey(context)
        ActivityScenario.launch(BriefSettingsActivity::class.java).use { scenario ->
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                dialog = BriefApiKeyDialog.show(activity, required = true) { fail("Empty key must not save") }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val input = dialog.findViewById<EditText>(R.id.brief_api_key_input)!!
                input.setText("   ")
                input.onEditorAction(EditorInfo.IME_ACTION_DONE)
                assertTrue(dialog.isShowing)
                assertNotNull(input.error)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals(original, BriefSettingsStore.cloudApiKey(context))
            }
        }
    }

    private fun assertActionsVisible(dialog: AlertDialog) {
        for (id in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            val button = dialog.getButton(id)
            val visible = Rect()
            assertTrue(button.getGlobalVisibleRect(visible))
            assertEquals("Action is not clipped", button.height, visible.height())
            assertEquals("Action label stays on one line", 1, button.lineCount)
            assertEquals(0, button.layout.getEllipsisCount(0))
        }
    }

    private fun saveImage(dialog: AlertDialog, name: String) {
        val bounds = Rect()
        instrumentation.runOnMainSync {
            val view = dialog.window!!.decorView
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            bounds.set(position[0], position[1], position[0] + view.width, position[1] + view.height)
        }
        // Capture the composed window: background blur cannot draw into a software Canvas.
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val bitmap = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
        screenshot.recycle()
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
