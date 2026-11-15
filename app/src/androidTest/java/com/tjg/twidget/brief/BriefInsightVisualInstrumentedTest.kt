package com.tjg.twidget.brief

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefInsightVisualInstrumentedTest {
    @Test fun everyInsightMeasuresAndRendersAtCoverAndInnerColumnWidths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = BriefSnapshot(username = "preview", generatedAt = 1_800_000_000_000L,
            sourceSyncedAt = 0, analyticsCachedAt = 0, followerScanCompletedAt = 0, followers = 0,
            following = 0, posts = 0, followersToday = 0, followersWeek = 0, topFollowerRanks = emptyMap(), cards = emptyList())
        val cards = BriefInsightPreview.snapshot(BriefStrings.from(context), source).cards
        instrumentation.runOnMainSync {
            for (widthDp in listOf(300, 392, 520)) for (card in cards) {
                val view = BriefInsightViews.create(context, card) { fail("Preview must not invoke actions") }
                val width = BriefReviewViews.dp(context, widthDp)
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                assertTrue("${card.type} must have content", view.height > 0)
                if (widthDp == 392) assertTrue("${card.type} should remain compact", view.height <= BriefReviewViews.dp(context, 260))
                fun checkText(item: View) {
                    if (item is TextView && item.text.isNotBlank()) {
                        assertTrue("${card.type}: ${item.text} must have visible width", item.width > item.paddingLeft + item.paddingRight)
                        assertEquals("${card.type}: ${item.text} must not be ellipsized", 0,
                            (0 until (item.layout?.lineCount ?: 0)).sumOf { item.layout.getEllipsisCount(it) })
                    }
                    if (item is ViewGroup) for (i in 0 until item.childCount) checkText(item.getChildAt(i))
                }
                checkText(view)
                val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                if (widthDp == 392) {
                    val directory = java.io.File(context.cacheDir, "brief-insight-previews").apply { mkdirs() }
                    java.io.File(directory, "${card.type.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                bitmap.recycle()
            }
        }
    }
}
