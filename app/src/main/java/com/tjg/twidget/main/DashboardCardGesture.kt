package com.tjg.twidget.main

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup

/** A stationary hold opens actions; moving after the hold picks up the same live card. */
internal class DashboardCardGesture(
    private val owner: ViewGroup,
    private val canStart: (MotionEvent) -> Boolean,
    private val onHold: () -> Unit,
    private val onDrag: () -> Boolean,
    private val onRelease: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val slop = ViewConfiguration.get(owner.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pending = false
    private var held = false
    private var consume = false
    private val hold = Runnable {
        if (!pending || !owner.isAttachedToWindow) return@Runnable
        pending = false
        held = true
        consume = true
        cancelPress(owner)
        owner.parent?.requestDisallowInterceptTouchEvent(true)
        onHold()
    }
    private fun cancelPress(view: View) {
        view.cancelLongPress()
        view.isPressed = false
        if (view is ViewGroup) for (index in 0 until view.childCount) cancelPress(view.getChildAt(index))
    }
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                consume = false
                downX = event.rawX; downY = event.rawY
                pending = canStart(event)
                if (pending) owner.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (dx * dx + dy * dy > slop * slop) {
                    owner.removeCallbacks(hold)
                    pending = false
                    if (held) {
                        held = false
                        onCancel() // The menu never follows a moving card.
                        owner.parent?.requestDisallowInterceptTouchEvent(false)
                        onDrag()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                owner.removeCallbacks(hold)
                pending = false
                if (held) {
                    held = false
                    owner.parent?.requestDisallowInterceptTouchEvent(false)
                    onRelease()
                }
                val result = consume
                consume = false
                return result
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                val result = consume
                cancel()
                return result
            }
        }
        return consume
    }
    fun cancel() {
        owner.removeCallbacks(hold)
        pending = false
        if (held) {
            held = false
            owner.parent?.requestDisallowInterceptTouchEvent(false)
            onCancel()
        }
        consume = false
    }
}
