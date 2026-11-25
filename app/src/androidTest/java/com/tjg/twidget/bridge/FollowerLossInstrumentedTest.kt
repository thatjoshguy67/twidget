package com.tjg.twidget.bridge

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowerLossInstrumentedTest {
    @Test fun observedLossesPersistByIdAndFitExpandedCardsInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("twidget_follower_losses", Context.MODE_PRIVATE)
        val id = "999999999999999999"
        val previous = prefs.getString(id, null)
        val now = System.currentTimeMillis()
        try {
            val row = FollowerLossObservation(now - 7 * 86_400_000L, now - 1000, 3)
            FollowerLossStore.save(context, id, listOf(row))
            assertEquals(row, FollowerLossStore.latest(context, id, now))
            assertNull(FollowerLossStore.latest(context, id, now + 8 * 86_400_000L))
            assertNull(FollowerLossStore.latest(context, "999999999999999998", now))
            instrumentation.runOnMainSync {
                for (night in listOf(false, true)) {
                    val config = android.content.res.Configuration(context.resources.configuration).apply {
                        uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                    }
                    val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Twidget)
                    val root = LayoutInflater.from(themed).inflate(R.layout.metric_card_small_stat, null, false)
                    root.setTag(R.id.dashboard_stat_expanded, true)
                    root.setTag(R.id.dashboard_stat_detail_compact, "-3")
                    root.setTag(R.id.dashboard_stat_detail_expanded, "-3\n3 lost · Nov 19–25")
                    root.findViewById<TextView>(R.id.metric_label).text = "Followers"
                    root.findViewById<TextView>(R.id.followers_value).text = "8,079"
                    val density = context.resources.displayMetrics.density
                    for (width in listOf(320, 400, 640, 180, 400)) {
                        root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec((144 * density).toInt(), View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                        val detail = root.findViewById<TextView>(R.id.stat_detail)
                        assertEquals(if (width == 180) "-3" else "-3\n3 lost · Nov 19–25", detail.text.toString())
                        assertTrue(detail.layout.height <= detail.height + 1)
                        for (line in 0 until detail.layout.lineCount) assertEquals(0, detail.layout.getEllipsisCount(line))
                    }
                }
            }
        } finally { prefs.edit().putString(id, previous).commit() }
    }
}
