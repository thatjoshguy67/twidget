package com.tjg.twidget.main

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.StreakSnapshot
import com.tjg.twidget.ui.TwidgetFonts
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DashboardCardRenderingInstrumentedTest {
    @Test fun compactCardsFitBothThemesAndLongValues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                val density = context.resources.displayMetrics.density
                val width = (189 * density).toInt()
                val height = (160 * density).toInt()
                for (value in listOf("5.7x", "18.8", "2.9", "1.02%", "12.02%", "123,456,789")) {
                    val root = LayoutInflater.from(context).inflate(R.layout.metric_card_small_stat, null, false)
                    root.findViewById<ImageView>(R.id.metric_platform_icon).apply {
                        setImageResource(R.drawable.ic_logo_twitter)
                        imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.oneui_text_secondary))
                    }
                    root.findViewById<TextView>(R.id.metric_label).text = "Follower ratio"
                    root.findViewById<TextView>(R.id.stat_detail).text = "6,406 more followers"
                    TwidgetFonts.applyTo(root)
                    val number = root.findViewById<TextView>(R.id.followers_value)
                    number.text = value
                    TwidgetFonts.setRole(number, TwidgetFonts.Role.DASHBOARD_VALUE)
                    render(root, width, height, File(context.filesDir, "stat-$night-$value.png"))
                    assertTrue("Number fits card", number.paint.measureText(value) <= number.width - number.compoundPaddingLeft - number.compoundPaddingRight + 1f)
                    if (value in listOf("5.7x", "18.8", "2.9")) {
                        assertTrue("Ordinary stat values retain their original width: $value",
                            (number as com.tjg.twidget.ui.CardValueTextView).fittedWidth == 124)
                    }
                    if (value == "12.02%") {
                        assertTrue("Width narrows before reducing text size",
                            (number as com.tjg.twidget.ui.CardValueTextView).fittedWidth < 124)
                        assertTrue("The 60sp numeral height is retained", kotlin.math.abs(number.textSize - 60 * context.resources.displayMetrics.scaledDensity) < 1f)
                        val compressedWidth = number.fittedWidth
                        number.text = "5.7x"
                        render(root, width, height, File(context.filesDir, "stat-restored-$night.png"))
                        assertTrue("Short values restore the original width", number.fittedWidth > compressedWidth)
                    }
                    assertTrue("Detail stays inside card", root.findViewById<View>(R.id.stat_detail).bottom <= height)
                }
                val graph = LayoutInflater.from(context).inflate(R.layout.metric_card_followers, null, false)
                graph.findViewById<TextView>(R.id.followers_value).text = "7,671"
                graph.findViewById<TextView>(R.id.followers_delta).apply {
                    text = "+21"
                    TwidgetFonts.setRole(this, TwidgetFonts.Role.CHART_DELTA)
                }
                graph.findViewById<com.tjg.twidget.ui.MetricChartView>(R.id.followers_chart).apply {
                    setSeries(listOf(38L, 54L, 44L, 56L, 34L, 68L, 100L).mapIndexed { index, value ->
                        com.tjg.twidget.analytics.ImportedChartPoint("Jun ${24 + index}", value)
                    })
                    setAverageSeries(listOf(50L, 40L, 48L, 52L, 60L, 58L, 72L))
                }
                TwidgetFonts.applyTo(graph)
                render(graph, (392 * density).toInt(), (263 * density).toInt(),
                    File(context.filesDir, "graph-$night.png"))
                val states = listOf(
                    "steady" to StreakSnapshot(12, true, null, longestStreak = 120),
                    "record" to StreakSnapshot(120, true, null, longestStreak = 120),
                    "save" to StreakSnapshot(12, false, null),
                    "restart" to StreakSnapshot(0, false, null),
                )
                for ((state, snapshot) in states) {
                    val streak = StreakCardFactory.create(context, snapshot)
                    TwidgetFonts.applyTo(streak)
                    render(streak, width, height, File(context.filesDir, "streak-$state-$night.png"))
                    val number = streak.findViewById<TextView>(R.id.followers_value)
                    assertTrue("Streak count fits", number.paint.measureText(number.text.toString()) <= number.width - number.compoundPaddingLeft - number.compoundPaddingRight + 1f)
                    val title = streak.findViewById<TextView>(R.id.metric_label)
                    assertTrue("The complete state title fits", title.paint.measureText(title.text.toString()) <= title.width + 1f)
                }
            }
        }
    }

    private fun render(view: View, width: Int, height: Int, file: File) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
