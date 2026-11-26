package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** A quieter version of the Brief material keeps the review's tweet and evidence in focus. */
internal object BriefReviewPageBackground {
    fun install(owner: LifecycleOwner, root: View) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var key: Triple<BriefBlueTheme, Boolean, Float>? = null
            private var material: BriefBlueBackground? = null
            private var animator: ValueAnimator? = null
            private val handler = Handler(Looper.getMainLooper())
            private val tick = object : Runnable {
                override fun run() { apply(); handler.postDelayed(this, 60_000L) }
            }
            private fun apply() {
                val next = Triple(BriefBlueTheme.current(), root.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES, root.resources.displayMetrics.density)
                if (next != key) {
                    key = next
                    material = BriefBlueBackground(next.first, next.second, subdued = true, density = root.resources.displayMetrics.density)
                    root.background = material
                }
                material?.setMotionProgress((BriefBlueBackground.motionPhase() + .17f) % 1f)
            }
            override fun onResume(owner: LifecycleOwner) {
                apply()
                handler.postDelayed(tick, 60_000L)
                if (ValueAnimator.areAnimatorsEnabled()) animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = BriefBlueBackground.MOTION_PERIOD_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { material?.setMotionProgress((BriefBlueBackground.motionPhase() + .17f) % 1f) }
                    start()
                }
            }
            override fun onPause(owner: LifecycleOwner) {
                animator?.cancel(); animator = null
                handler.removeCallbacks(tick)
            }
        })
    }
}
