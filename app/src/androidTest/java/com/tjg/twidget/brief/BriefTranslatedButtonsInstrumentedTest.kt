package com.tjg.twidget.brief

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class BriefTranslatedButtonsInstrumentedTest {
    @Test fun translatedActionsWrapAtNarrowWidthsAndLargeFontSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (tag in listOf("ca-ES", "es-ES", "de-DE", "fr-FR")) for (scale in listOf(1f, 1.5f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag)); fontScale = scale
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                val buttons = listOf(
                    BriefReviewViews.button(context, R.string.review_add_key) {},
                    LayoutInflater.from(context).inflate(R.layout.activity_twidget_brief, null)
                        .findViewById<TextView>(R.id.brief_provider_required_action),
                    LayoutInflater.from(context).inflate(R.layout.view_brief_refresh_state, null)
                        .findViewById<TextView>(R.id.brief_refresh_error_action).apply { setText(R.string.review_add_key) },
                    LayoutInflater.from(context).inflate(R.layout.activity_onboarding, null)
                        .findViewById<TextView>(R.id.primary_button).apply { setText(R.string.onboarding_run_locally) },
                )
                for (button in buttons) {
                    val width = (140 * context.resources.displayMetrics.density).toInt()
                    button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    button.layout(0, 0, width, button.measuredHeight)
                    assertTrue("$tag/$scale/${button.text} wraps", button.lineCount > 1)
                    val layout = requireNotNull(button.layout)
                    assertTrue("$tag/$scale/${button.text} has room for every line",
                        button.height - button.compoundPaddingTop - button.compoundPaddingBottom >= layout.height)
                    for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                }
            }
        }
    }
    @Test fun compactReviewButtonsShareARowWhenTheyFit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget)
            val actions = BriefReviewViews.actions(context, listOf(
                BriefReviewAction(R.string.brief_action_open_tweet) {},
                BriefReviewAction(R.string.review_view) {}), compact = true)
            val width = BriefReviewViews.dp(context, 430)
            actions.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            actions.layout(0, 0, width, actions.measuredHeight)
            assertEquals(android.widget.LinearLayout.HORIZONTAL, actions.orientation)
            assertEquals(actions.getChildAt(0).top, actions.getChildAt(1).top)
            assertTrue(actions.getChildAt(1).left >= actions.getChildAt(0).right)
        }
    }

}
