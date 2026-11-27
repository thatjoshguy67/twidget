package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.AnimatedStateListDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MilestoneCelebration
import com.tjg.twidget.widget.TwidgetBriefWidget
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MilestoneForceCelebrationInstrumentedTest {
    @Test fun nativeBurstMovesAndClearsWithoutLooping() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val hostClass = Class.forName("com.tjg.twidget.brief.BriefCoachTestActivity").asSubclass(android.app.Activity::class.java)
        androidx.test.core.app.ActivityScenario.launch(hostClass).use { scenario ->
            var widget: android.view.View? = null
            val background = android.graphics.Color.rgb(16, 48, 98)
            fun capture(): IntArray {
                val ready = java.util.concurrent.CountDownLatch(1)
                var result = -1
                lateinit var bitmap: android.graphics.Bitmap
                scenario.onActivity { host ->
                    val target = requireNotNull(widget)
                    val position = IntArray(2)
                    target.getLocationInWindow(position)
                    bitmap = android.graphics.Bitmap.createBitmap(target.width, target.height, android.graphics.Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(host.window,
                        android.graphics.Rect(position[0], position[1], position[0] + target.width, position[1] + target.height),
                        bitmap, { result = it; ready.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
                assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(android.view.PixelCopy.SUCCESS, result)
                return IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    bitmap.recycle()
                }
            }
            run {
                scenario.onActivity { host ->
                    val container = FrameLayout(host).apply { setBackgroundColor(background) }
                    val views = android.widget.RemoteViews(context.packageName, R.layout.widget_celebration).apply {
                        setBoolean(R.id.widget_celebration_effect, "setEnabled", true)
                    }
                    widget = views.apply(host, container)
                    val density = host.resources.displayMetrics.density
                    container.addView(widget, FrameLayout.LayoutParams((352 * density).toInt(), (154 * density).toInt(), android.view.Gravity.CENTER))
                    host.setContentView(container)
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(650)
                val first = capture()
                assertTrue("A burst must contain visible paper pieces", first.count { it != background } > 100)
                Thread.sleep(600)
                val second = capture()
                assertFalse("Particles must move, rather than remaining a static overlay", first.contentEquals(second))
                Thread.sleep(4750)
                val after = capture()
                assertTrue("Celebration must finish without restarting or leaving flecks", after.all { it == background })
            }
        }
    }

    @Test fun oneDebugFlagDrivesWidgetPlaybackWithoutConsumingRealAchievements() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val force = prefs.getBoolean(MilestoneCelebration.DEBUG_FORCE_KEY, false)
        val unlocked = prefs.getBoolean("debug_menu_unlocked", false)
        val debugSeenKey = "debug_celebration_widget_987654321"
        val debugSeen = prefs.all[debugSeenKey] as? Long
        val history = context.getSharedPreferences("twidget_milestone_celebrations", Context.MODE_PRIVATE)
        val before = history.all.toMap()
        try {
            prefs.edit().remove(debugSeenKey).putBoolean("debug_menu_unlocked", true).putBoolean(MilestoneCelebration.DEBUG_FORCE_KEY, true).commit()
            val trigger = MilestoneCelebration.widgetTrigger(context, "", 987654321)
            assertTrue(trigger.forced)
            assertTrue(trigger.show)
            assertTrue(trigger.achievements.isEmpty())
            MilestoneCelebration.recordWidgetTrigger(context, trigger)
            assertEquals(before, history.all)
            assertFalse("Ordinary widget updates must not replay the forced celebration",
                MilestoneCelebration.widgetTrigger(context, "", 987654321).show)
            val forcedViews = TwidgetBriefWidget.createViews(context, 987654321, 352, 176, "", null)
            instrumentation.runOnMainSync {
                val root = forcedViews.apply(context, FrameLayout(context))
                val effect = root.findViewById<ImageView>(R.id.widget_celebration_effect)
                assertTrue(effect.isEnabled)
                assertTrue(effect.drawable is AnimatedStateListDrawable)
                assertTrue("The forced effect starts native particle motion", effect.drawable.current is AnimatedVectorDrawable)
                assertTrue((effect.drawable.current as AnimatedVectorDrawable).isRunning)
            }
            prefs.edit().putBoolean(MilestoneCelebration.DEBUG_FORCE_KEY, false).commit()
            // The ordinary completed-goal path is independently available with debug force disabled.
            val normalViews = TwidgetBriefWidget.createViews(context, 987654321, 352, 176, "", null, celebrate = true)
            instrumentation.runOnMainSync {
                val root = normalViews.apply(context, FrameLayout(context))
                val effect = root.findViewById<ImageView>(R.id.widget_celebration_effect)
                assertTrue(effect.isEnabled)
                assertTrue(effect.drawable is AnimatedStateListDrawable)
                assertTrue("Real completion starts native particle motion", effect.drawable.current is AnimatedVectorDrawable)
                assertTrue((effect.drawable.current as AnimatedVectorDrawable).isRunning)
            }
            prefs.edit().putBoolean("debug_menu_unlocked", false).putBoolean(MilestoneCelebration.DEBUG_FORCE_KEY, true).commit()
            assertFalse("Locking debug disables the force flag", MilestoneCelebration.forceEnabled(context))
        } finally {
            prefs.edit().apply {
                putBoolean("debug_menu_unlocked", unlocked).putBoolean(MilestoneCelebration.DEBUG_FORCE_KEY, force)
                if (debugSeen == null) remove(debugSeenKey) else putLong(debugSeenKey, debugSeen)
            }.commit()
        }
    }
}
