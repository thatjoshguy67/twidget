package com.tjg.twidget.main

import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.view.WindowCallbackWrapper
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tjg.twidget.R
import com.tjg.twidget.ui.DashboardRecyclerView
import com.tjg.twidget.ui.TwidgetHaptics

/** Keep the catalogue's touch window alive while its card joins the native grid. */
internal class DashboardCatalogueTouchBridge(
    private val activity: MainActivity,
    private val dialog: BottomSheetDialog,
    private val controller: MainEditModeController,
) {
    private var originalCallback: Window.Callback? = null
    private var installedCallback: Window.Callback? = null
    private var latest: MotionEvent? = null
    private var pickup: MotionEvent? = null
    private var active = false
    private var ready = false
    private var completed = false
    private val pending = mutableListOf<MotionEvent>()
    private val grid get() = activity.findViewById<DashboardRecyclerView>(R.id.dashboard_content)

    fun install() {
        val window = dialog.window ?: return
        val original = window.callback
        originalCallback = original
        val callback = object : WindowCallbackWrapper(original) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (!active) {
                    latest?.recycle()
                    latest = MotionEvent.obtain(event)
                    return super.dispatchTouchEvent(event)
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    controller.finishDashboardDrag(false)
                    completed = true
                    dialog.dismiss()
                    return true
                }
                if (ready) forward(event) else pending += MotionEvent.obtain(event)
                return true
            }
        }
        installedCallback = callback
        window.callback = callback
    }

    fun start(card: DashboardCardType, span: Int): Boolean {
        val initial = latest?.let(MotionEvent::obtain) ?: return false
        if (initial.pointerCount != 1 || initial.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            initial.recycle()
            return false
        }
        active = true
        pickup = initial
        val started = controller.startDrawerCardDrag(card, span, initial.rawY) { source ->
            if (!active || !dialog.isShowing) return@startDrawerCardDrag
            if (source == null) { dialog.dismiss(); return@startDrawerCardDrag }
            val view = grid
            val down = MotionEvent.obtain(initial)
            localise(down)
            down.action = MotionEvent.ACTION_DOWN
            down.offsetLocation(source.left + source.width / 2f - down.x, source.top + source.height / 2f - down.y)
            view.dispatchTouchEvent(down)
            down.recycle()
            if (!activity.dashboardBinder.startPreparedCardDrag(card, source)) {
                controller.finishDashboardDrag(false)
                dialog.dismiss()
                return@startDrawerCardDrag
            }
            ready = true
            val move = MotionEvent.obtain(initial).apply { action = MotionEvent.ACTION_MOVE }
            forward(move)
            move.recycle()
            val queued = pending.toList()
            pending.clear()
            queued.forEach { event ->
                if (active) forward(event)
                event.recycle()
            }
        }
        if (!started) {
            active = false
            initial.recycle()
            pickup = null
            return false
        }
        // The real card is visible in the activity. Only this transparent window
        // retains the original stream, including additional fingers and history.
        dialog.setDismissWithAnimation(false)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.visibility = View.INVISIBLE
        dialog.window?.apply {
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            if (Build.VERSION.SDK_INT >= 31) setBackgroundBlurRadius(0)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        TwidgetHaptics.dragPickup(grid)
        return true
    }

    private fun localise(event: MotionEvent) {
        val origin = IntArray(2).also(grid::getLocationOnScreen)
        event.offsetLocation(event.rawX - event.x - origin[0], event.rawY - event.y - origin[1])
    }

    private fun forward(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        localise(copy)
        grid.dispatchTouchEvent(copy)
        copy.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            completed = true
            active = false
            dialog.dismiss()
        }
    }

    fun dispose() {
        dialog.window?.let { window ->
            if (window.callback === installedCallback) originalCallback?.let { window.callback = it }
        }
        originalCallback = null
        installedCallback = null
        if (active && !completed) controller.finishDashboardDrag(false)
        active = false
        latest?.recycle()
        latest = null
        pickup?.recycle()
        pickup = null
        pending.forEach(MotionEvent::recycle)
        pending.clear()
    }
}
