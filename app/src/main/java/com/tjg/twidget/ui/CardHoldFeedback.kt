package com.tjg.twidget.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.abs

/** Observe a hold without consuming ordinary taps or scroll gestures. */
internal class CardHoldFeedback(
    private val owner: ViewGroup,
    private val target: View = owner,
    private val enabled: () -> Boolean = { true },
) {
    private val slop = ViewConfiguration.get(owner.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pending = false
    private var consumed = false
    private var cancellingNativePress = false
    private var cancelHapticRamp: (() -> Unit)? = null
    private fun stopHapticRamp() {
        cancelHapticRamp?.invoke()
        cancelHapticRamp = null
    }
    private val completeHold = Runnable {
        if (!pending || !owner.isAttachedToWindow || !enabled()) return@Runnable
        pending = false
        stopHapticRamp()
        cancellingNativePress = true
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        owner.dispatchTouchEvent(cancel)
        cancel.recycle()
        cancellingNativePress = false
        consumed = true
        owner.performLongClick()
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (cancellingNativePress) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            consumed = false
            if (enabled()) {
                downX = event.x
                downY = event.y
                pending = true
                stopHapticRamp()
                cancelHapticRamp = TwidgetHaptics.startHoldRamp(owner, HOLD_MS)
                owner.postDelayed(completeHold, HOLD_MS)
                if (ValueAnimator.areAnimatorsEnabled()) {
                    target.animate().cancel()
                    target.animate().scaleX(0.975f).scaleY(0.975f).setDuration(HOLD_MS)
                        .setInterpolator(PathInterpolator(0.2f, 0f, 0.2f, 1f)).start()
                }
            }
        } else if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN) ||
            event.actionMasked == MotionEvent.ACTION_MOVE && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
            owner.removeCallbacks(completeHold)
            stopHapticRamp()
            if (pending || consumed) {
                pending = false
                target.animate().cancel()
                target.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
            }
        }
        return consumed
    }

    fun detach() {
        owner.removeCallbacks(completeHold)
        stopHapticRamp()
        pending = false
        target.animate().cancel()
    }

    companion object { const val HOLD_MS = 350L }
}
