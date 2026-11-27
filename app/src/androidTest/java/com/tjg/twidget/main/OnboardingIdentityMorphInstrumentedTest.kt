package com.tjg.twidget.main

import android.animation.ValueAnimator
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.SharedProfileDetails
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class OnboardingIdentityMorphInstrumentedTest {
    @Test fun repeatedLayoutsDoNotSnapTheMorphAndTheNameRetainsWeight700() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        assumeTrue(kotlin.math.abs(Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) - 1f) < .01f)
        ActivityScenario.launch<OnboardingActivity>(Intent(context, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_ADD_ACCOUNT, true)).use { scenario ->
            fun goTo(step: Int) = scenario.onActivity { activity ->
                activity.javaClass.getDeclaredMethod("goToStep", Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }.invoke(activity, step)
            }
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[OnboardingAccountSession::class.java].candidate = ProfileStats(
                    "A confirmed display name", "morph_fixture", 10, 1, 2, 0,
                    profileDetails = SharedProfileDetails(bio = "A profile biography that wraps over several lines to exercise the expanding card."))
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(750)
            repeat(3) {
                goTo(3) // Account confirmation: no lookup, account acceptance, or storage writes.
                instrumentation.waitForIdleSync()
                Thread.sleep(60)
                scenario.onActivity { it.findViewById<View>(R.id.profile_identity_host).requestLayout() }
                Thread.sleep(80)
                scenario.onActivity { activity ->
                    val host = activity.findViewById<View>(R.id.profile_identity_host)
                    val content = activity.findViewById<View>(R.id.detected_profile)
                    val collapsed = (60 * activity.resources.displayMetrics.density).toInt()
                    assertTrue("The card must be between its endpoints after another layout", host.height > collapsed)
                    assertTrue("A deferred layout must not replace the morph with its final frame", host.height < content.layoutParams.height)
                    assertTrue(activity.findViewById<TextView>(R.id.detected_name).typeface.isBold)
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        assertEquals(700, activity.findViewById<TextView>(R.id.detected_name).typeface.weight)
                    }
                }
                Thread.sleep(650)
                scenario.onActivity { activity ->
                    assertEquals(activity.findViewById<View>(R.id.detected_profile).layoutParams.height,
                        activity.findViewById<View>(R.id.profile_identity_host).height)
                }
                goTo(2)
                Thread.sleep(750)
            }
        }
    }
}
