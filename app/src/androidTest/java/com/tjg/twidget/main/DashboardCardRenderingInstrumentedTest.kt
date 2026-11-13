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
    @Test fun tweetFadeRevealsNormalAndPressedCardSurfaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val width = (200 * density).toInt()
            val height = (68 * density).toInt()
            fun draw(surface: Int, faded: Boolean): Bitmap {
                val root = android.widget.FrameLayout(context).apply { setBackgroundColor(surface) }
                val body = if (faded) com.tjg.twidget.ui.DashboardFadingTextView(context) else TextView(context)
                body.apply {
                    text = List(20) { "Tweet body" }.joinToString("\n")
                    textSize = 13f
                    typeface = TwidgetFonts.forApp(context, 400)
                    setTextColor(android.graphics.Color.WHITE)
                    includeFontPadding = false
                    setPadding(0, 0, 0, 0)
                    androidx.core.widget.TextViewCompat.setLineHeight(this, (18 * density).toInt())
                }
                root.addView(body, android.widget.FrameLayout.LayoutParams(-1, -1))
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
            for (surface in listOf(0xff17171a.toInt(), 0xff47474a.toInt())) {
                val plain = draw(surface, false)
                val faded = draw(surface, true)
                try {
                    assertTrue("The fade does not paint over the active card surface", faded.getPixel(width - 2, height - 2) == surface)
                    assertTrue("The visible cutoff is fully transparent", (0 until width).all { x -> faded.getPixel(x, height - 1) == surface })
                    val textStillFades = (height * 2 / 3 until height).any { y ->
                        (0 until width / 2).any { x ->
                            plain.getPixel(x, y) == android.graphics.Color.WHITE &&
                                android.graphics.Color.red(faded.getPixel(x, y)) < 245
                        }
                    }
                    assertTrue("Overflowing body text retains its fade", textStillFades)
                } finally { plain.recycle(); faded.recycle() }
            }
        }
    }

    @Test fun dashboardTypographyFollowsEachAppFontSetting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (font in com.tjg.twidget.ui.AppAppearance.Font.entries) {
                val context = object : ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget) {
                    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                        val preferences = super.getSharedPreferences(name, mode)
                        return object : android.content.SharedPreferences by preferences {
                            override fun getString(key: String?, defValue: String?): String? =
                                if (key == "app_font_family") font.value else preferences.getString(key, defValue)
                        }
                    }
                }
                val density = context.resources.displayMetrics.density
                val root = LayoutInflater.from(context).inflate(R.layout.metric_card_small_stat, null, false)
                val heading = root.findViewById<TextView>(R.id.metric_label).apply { text = "Avg views" }
                val value = root.findViewById<com.tjg.twidget.ui.CardValueTextView>(R.id.followers_value).apply { text = "9.22k" }
                render(root, (189 * density).toInt(), (160 * density).toInt(), File(context.filesDir, "font-${font.value}.png"))
                TwidgetFonts.applyTo(root)
                assertTypefaceMatches(heading, TwidgetFonts.forApp(context,
                    if (font == com.tjg.twidget.ui.AppAppearance.Font.GOOGLE_SANS_FLEX) 500 else 700))
                val actionHeading = TextView(context).apply { text = heading.text }
                TwidgetFonts.dashboardHeading(actionHeading)
                assertTypefaceMatches(heading, actionHeading.paint.typeface)
                assertTypefaceMatches(value, TwidgetFonts.cardValueTypeface(context, value.fittedWidth))
                val brief = TextView(context)
                TwidgetFonts.setRole(brief, TwidgetFonts.Role.DASHBOARD_EDITORIAL)
                assertTypefaceMatches(brief, TwidgetFonts.forApp(context, 400))
            }
        }
    }

    @Test fun narrowCardsKeepReadableHeadingsAndDropSecondaryClutter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.4f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply { this.fontScale = fontScale }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                val density = context.resources.displayMetrics.density
                val root = LayoutInflater.from(context).inflate(R.layout.metric_card_small_stat, null, false)
                val label = root.findViewById<TextView>(R.id.metric_label).apply { text = "Engagement rate" }
                val number = root.findViewById<TextView>(R.id.followers_value).apply { text = "12.02%" }
                root.findViewById<TextView>(R.id.stat_detail).text = "6,406 more followers"
                TwidgetFonts.applyTo(root)
                for (widthDp in listOf(140, 189, 392, 140)) {
                    render(root, (widthDp * density).toInt(), (160 * density).toInt(),
                        File(context.filesDir, "density-$fontScale-$widthDp.png"))
                    val nativeLabel = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)
                    assertTrue("Headings stay readable at every width and system font size", kotlin.math.abs(label.textSize - nativeLabel) < 1f)
                    TwidgetFonts.applyTo(root)
                    assertTypefaceMatches(label, TwidgetFonts.forApp(context,
                        if (com.tjg.twidget.ui.AppAppearance.font(context) == com.tjg.twidget.ui.AppAppearance.Font.GOOGLE_SANS_FLEX) 500 else 700))
                    assertTrue("Headings keep their complete line height", label.layout.height <= label.height + 1)
                    assertTrue("Primary values remain complete", number.paint.measureText(number.text.toString()) <=
                        number.width - number.compoundPaddingLeft - number.compoundPaddingRight + 1f)
                    val detail = root.findViewById<TextView>(R.id.stat_detail)
                    if (detail.visibility == View.VISIBLE) assertTrue("Secondary copy is shown only when readable in full",
                        detail.paint.measureText(detail.text.toString()) <= detail.width + 1f)
                    assertTrue("Stats no longer carry a redundant platform mark", root.findViewById<View>(R.id.metric_platform_icon).visibility == View.GONE)
                }
                val identity = com.tjg.twidget.ui.DashboardFollowerIdentityLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    addView(TextView(context).apply { text = "Ramin Nasibov"; textSize = 16f; typeface = TwidgetFonts.forApp(context, 700) },
                        android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * density).toInt() })
                    addView(TextView(context).apply { text = "@RaminNasibov"; textSize = 12f },
                        android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                }
                for (widthDp in listOf(140, 340, 140)) {
                    render(identity, (widthDp * density).toInt(), (40 * density).toInt(),
                        File(context.filesDir, "identity-$fontScale-$widthDp.png"))
                    assertTrue("Handles disappear when they would be unreadable and return when space opens up",
                        identity.getChildAt(1).visibility == if (widthDp == 140) View.GONE else View.VISIBLE)
                }
            }
        }
    }

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
                // Match the new icon-above-copy Brief design at phone/fold widths.
                for (cardWidth in listOf(284, 340, 392)) {
                    val brief = LayoutInflater.from(context).inflate(R.layout.brief_dashboard_card, null, false)
                    val title = brief.findViewById<TextView>(R.id.brief_dashboard_title).apply {
                        text = "Your posts get 34.1k views."
                    }
                    val message = brief.findViewById<TextView>(R.id.brief_dashboard_message).apply {
                        setTag(R.id.dashboard_design_proportional_text, true)
                        text = "Your posting rhythm is active with 8052 followers."
                    }
                    val icon = brief.findViewById<ImageView>(R.id.brief_dashboard_icon).apply {
                        setImageBitmap(com.tjg.twidget.widget.BriefWidgetArtworkRenderer.stateIcon(
                            context, com.tjg.twidget.brief.BriefCardType.SLOWDOWN, "", (60 * density).toInt(),
                            context.getColor(R.color.oneui_text_primary)))
                    }
                    brief.background = MilestoneCardBackgroundDrawable(
                        context.getColor(R.color.dashboard_brief_start), context.getColor(R.color.dashboard_brief_end),
                        28f * density)
                    TwidgetFonts.applyTo(brief)
                    val cardHeight = (160 * density).toInt()
                    render(brief, (cardWidth * density).toInt(), cardHeight, File(context.filesDir, "brief-$night-$cardWidth.png"))
                    assertTrue("Shared widget state artwork remains available", icon.drawable != null)
                    val copy = brief.findViewById<View>(R.id.brief_dashboard_copy)
                    if (cardWidth == 284) assertTrue("Long portrait copy gives its space priority over the icon", icon.visibility == View.GONE)
                    if (icon.visibility == View.VISIBLE) {
                        assertTrue("Brief artwork sits above the copy without overlap", icon.bottom < copy.top)
                        assertTrue("The complete icon stays inside the card", icon.top >= 0)
                    }
                    assertTrue("Brief text stays inside the row", copy.bottom <= cardHeight - brief.paddingBottom + 1)
                    for ((text, size) in listOf(title to 30f, message to 20f)) {
                        val nativeSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                            size * cardHeight / (214f * density), context.resources.displayMetrics)
                        assertTrue("Brief typography follows the mockup's row proportions", kotlin.math.abs(text.textSize - nativeSize) < 1f)
                        assertTrue("Brief lines retain their full height", text.layout.height <= text.height + 1)
                    }
                    title.text = "Getting attention"
                    message.text = "You're on a roll."
                    render(brief, (cardWidth * density).toInt(), cardHeight, File(context.filesDir, "brief-short-$night-$cardWidth.png"))
                    assertTrue("The icon reappears when the real copy leaves enough room", icon.visibility == View.VISIBLE)
                }
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
                    (number as com.tjg.twidget.ui.CardValueTextView).configure(80f, 79)
                    TwidgetFonts.setRole(number, TwidgetFonts.Role.DASHBOARD_VALUE)
                    render(root, width, height, File(context.filesDir, "stat-$night-$value.png"))
                    assertTrue("Number fits card", number.paint.measureText(value) <= number.width - number.compoundPaddingLeft - number.compoundPaddingRight + 1f)
                    if (value in listOf("5.7x", "18.8", "2.9")) {
                        assertTrue("Ordinary stat values retain their original width: $value",
                            (number as com.tjg.twidget.ui.CardValueTextView).fittedWidth in 85..110)
                    }
                    if (value == "12.02%") {
                        val preferred = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                            55.5f, context.resources.displayMetrics)
                        assertTrue("Shared stat typography is retained", kotlin.math.abs(number.textSize - preferred) < 2f)
                    }
                    if (value == "123,456,789") {
                        val compressedWidth = number.fittedWidth
                        number.text = "5.7x"
                        render(root, width, height, File(context.filesDir, "stat-restored-$night.png"))
                        assertTrue("Short values restore the preferred width", number.fittedWidth >= compressedWidth)
                    }
                    assertTrue("Detail stays inside card", root.findViewById<View>(R.id.stat_detail).bottom <= height)
                    assertTrue("Stat corners remain native across every rendered card",
                        kotlin.math.abs((root.background as android.graphics.drawable.GradientDrawable).cornerRadius - 28f * density) < 1f)
                    val label = root.findViewById<TextView>(R.id.metric_label)
                    val nativeLabel = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)
                    assertTrue("Labels retain their readable native size", kotlin.math.abs(label.textSize - nativeLabel) < 1f)
                }
                val graph = LayoutInflater.from(context).inflate(R.layout.metric_card_followers, null, false)
                graph.findViewById<TextView>(R.id.followers_value).apply {
                    text = "7,671"
                    typeface = TwidgetFonts.forApp(context, 700)
                }
                graph.findViewById<com.tjg.twidget.ui.CardValueTextView>(R.id.followers_delta).apply {
                    text = "+21"; configure(24f, 60)
                }
                graph.findViewById<com.tjg.twidget.ui.MetricChartView>(R.id.followers_chart).apply {
                    compactCardInsets = true
                    setSeries(listOf(38L, 54L, 44L, 56L, 34L, 68L, 100L).mapIndexed { index, value ->
                        com.tjg.twidget.analytics.ImportedChartPoint("Jun ${24 + index}", value)
                    })
                    setAverageSeries(listOf(50L, 40L, 48L, 52L, 60L, 58L, 72L))
                }
                TwidgetFonts.applyTo(graph)
                render(graph, (392 * density).toInt(), (156 * density).toInt(),
                    File(context.filesDir, "graph-$night.png"))
                for (id in listOf(R.id.metric_label, R.id.followers_value, R.id.followers_delta)) {
                    assertTrue("The landscape summary stays left aligned",
                        graph.findViewById<TextView>(id).gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK == android.view.Gravity.START)
                }
                assertTrue("Landscape count uses exactly 700 weight without synthetic bold",
                    graph.findViewById<TextView>(R.id.followers_value).let { it.typeface.weight == 700 && !it.paint.isFakeBoldText })
                val change = graph.findViewById<TextView>(R.id.followers_delta)
                val total = graph.findViewById<TextView>(R.id.followers_value)
                val metric = graph.findViewById<TextView>(R.id.metric_label)
                val wideSummary = graph.findViewById<View>(R.id.dashboard_graph_summary)
                assertTrue("Wide graphs read change, total, metric", change.bottom <= total.top && total.bottom <= metric.top)
                assertTrue("The left-aligned summary is centred vertically as one group",
                    kotlin.math.abs(change.top + metric.bottom - wideSummary.height) < 3 * density)
                render(graph, (284 * density).toInt(), height, File(context.filesDir, "graph-portrait-$night.png"))
                val summary = graph.findViewById<View>(R.id.dashboard_graph_summary)
                assertTrue("Portrait graph gives the stats column's space to the plot", summary.visibility == View.GONE)
                val graphHeading = graph.findViewById<TextView>(R.id.dashboard_graph_heading)
                assertTrue("The compact graph keeps its metric heading", graphHeading.visibility == View.VISIBLE && graphHeading.text.isNotBlank())
                assertTrue("Graph headings are centred when no subpage action is available",
                    graphHeading.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK == android.view.Gravity.CENTER_HORIZONTAL)
                TwidgetFonts.applyTo(graph)
                assertTypefaceMatches(graphHeading, TwidgetFonts.forApp(context,
                    if (com.tjg.twidget.ui.AppAppearance.font(context) == com.tjg.twidget.ui.AppAppearance.Font.GOOGLE_SANS_FLEX) 500 else 700))
                assertTrue("The compact chart uses the available width", graph.findViewById<View>(R.id.followers_chart).width > 250 * density)
                for (span in 1..2) {
                    val counter = FollowerCountCard.create(context,
                        com.tjg.twidget.data.ProfileStats("That Josh Guy", "mock", 7671, 1265, 12, 500), 21, span)
                    render(counter, ((if (span == 1) 189 else 392) * density).toInt(), height,
                        File(context.filesDir, "count-$night-$span.png"))
                    val text = counter.findViewById<TextView>(R.id.followers_value)
                    assertTrue("Both count sizes share the native card radius",
                        kotlin.math.abs((counter.background as android.graphics.drawable.GradientDrawable).cornerRadius - 28f * density) < 1f)
                    assertTrue("Both count sizes use complete lettered text", text.text.toString().contains("Seven"))
                    assertTrue("All words fit within the count card", text.layout.getLineEnd(text.lineCount - 1) == text.text.length)
                    assertTrue("No count words are ellipsized", (0 until text.lineCount).all { text.layout.getEllipsisCount(it) == 0 })
                }
                for (count in listOf(7671L, 8051L)) {
                    val compact = FollowerCountCard.create(context,
                        com.tjg.twidget.data.ProfileStats("That Josh Guy", "mock", count, 1265, 12, 500), 21, 1) as TextView
                    render(compact, width, height, File(context.filesDir, "compact-count-$count-$night.png"))
                    val available = compact.height - compact.paddingTop - compact.paddingBottom
                    assertTrue("Shorter written counts fill the tile rather than leaving empty top/bottom bands",
                        compact.layout.height >= available * .8f)
                    assertTrue("Compact count still fits without clipping", compact.layout.height <= available + 1)
                    assertTrue("Every count word remains visible", compact.layout.getLineEnd(compact.lineCount - 1) == compact.text.length &&
                        (0 until compact.lineCount).all { compact.layout.getEllipsisCount(it) == 0 })
                }
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
                    val nativeSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)
                    assertTrue("Streak labels do not shrink to fit a clipped line", kotlin.math.abs(title.textSize - nativeSize) < 1f)
                    assertTrue("Streak label has full line height", title.layout.height <= title.height - title.compoundPaddingTop - title.compoundPaddingBottom + 1)
                }
            }
        }
    }

    private fun assertTypefaceMatches(view: TextView, expected: android.graphics.Typeface) {
        val probe = "Followers Posts / day 9.22k"
        val reference = android.graphics.Paint(view.paint).apply { typeface = expected }
        assertTrue("Rendered font weight matches: actual=${view.paint.typeface.weight}, expected=${expected.weight}", view.paint.typeface.weight == expected.weight)
        assertTrue("Font width matches the selected style", kotlin.math.abs(view.paint.measureText(probe) - reference.measureText(probe)) < 0.5f)
    }

    private fun render(view: View, width: Int, height: Int, file: File) {
        repeat(2) {
            com.tjg.twidget.ui.DashboardCardDesign.apply(view, height)
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
