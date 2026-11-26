package com.tjg.twidget.main

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import com.tjg.twidget.R
import com.tjg.twidget.data.AccountPreferences
import java.util.Locale
import kotlin.math.sin

internal object MilestoneCelebration {
    private const val TAG = "milestone_celebration_overlay"

    fun eligible(current: Double?, goal: AccountGoalSettings, seen: Boolean): Boolean =
        !seen && goal.configured && goal.target.isFinite() && goal.target > 0 &&
            current != null && current.isFinite() && current >= goal.target

    /** Per account/metric/target, shared by Brief and account goals. No fake progress is persisted. */
    fun attach(anchor: View, account: String, goal: AccountGoalSettings, current: Double?) {
        if (!eligible(current, goal, false)) return
        val observer = object : View.OnAttachStateChangeListener, android.view.ViewTreeObserver.OnScrollChangedListener, Runnable {
            private var queued = false
            private var earliest = 0L
            private fun schedule() {
                if (queued || !anchor.isAttachedToWindow) return
                queued = true
                anchor.postDelayed(this, (earliest - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
            }
            override fun onViewAttachedToWindow(v: View) {
                earliest = android.os.SystemClock.uptimeMillis() + 1800L
                v.viewTreeObserver.addOnScrollChangedListener(this)
                schedule()
            }
            override fun onViewDetachedFromWindow(v: View) {
                v.removeCallbacks(this); queued = false
                if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnScrollChangedListener(this)
            }
            override fun onScrollChanged() = schedule()
            override fun run() {
                queued = false
                val activity = anchor.context as? Activity ?: return
                if (!anchor.isAttachedToWindow || !anchor.isShown || !activity.hasWindowFocus()) return
                val visible = android.graphics.Rect()
                if (!anchor.getGlobalVisibleRect(visible) || visible.height() < anchor.height / 2) return
                val key = account.trim().trimStart('@').lowercase(Locale.ROOT)
                val achievement = "${goal.metric.storageId}:${goal.target}"
                val prefs = AccountPreferences.open(anchor.context, "twidget_milestone_celebrations")
                val celebrated = prefs.getStringSet(key, emptySet()).orEmpty()
                if (!eligible(current, goal, achievement in celebrated)) return
                if (show(activity)) {
                    prefs.edit().putStringSet(key, (celebrated + achievement).toMutableSet()).apply()
                    if (anchor.viewTreeObserver.isAlive) anchor.viewTreeObserver.removeOnScrollChangedListener(this)
                }
            }
        }
        anchor.addOnAttachStateChangeListener(observer)
        if (anchor.isAttachedToWindow) observer.onViewAttachedToWindow(anchor)
    }

    fun preview(activity: Activity) { show(activity) }

    private fun show(activity: Activity): Boolean {
        val parent = activity.window.decorView as? ViewGroup ?: return false
        if (parent.findViewWithTag<View>(TAG) != null || activity.isFinishing || activity.isDestroyed) return false
        val overlay = ConfettiView(activity).apply { tag = TAG }
        parent.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        @Suppress("DEPRECATION")
        overlay.announceForAccessibility(activity.getString(R.string.milestone_celebration_message))
        overlay.start()
        return true
    }
}

/** Short, touch-through overlay with deterministic blue/silver particles and no bitmap allocations per frame. */
private class ConfettiView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val colors = intArrayOf(0xFF1881FF.toInt(), 0xFF75BAFF.toInt(), 0xFFC6E8FF.toInt(), 0xFFE9F2FF.toInt())
    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun start() {
        if (!ValueAnimator.areAnimatorsEnabled()) { post { remove() }; return }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
                if (progress >= 1f) remove()
            }
            start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        repeat(64) { index ->
            val seed = (index * 37 % 101) / 100f
            val x = width * seed + sin((progress * 7 + index).toDouble()).toFloat() * 28 * density
            val y = -height * .2f + progress * height * (1.1f + (index % 5) * .08f) - (index % 7) * 18 * density
            paint.color = colors[index % colors.size]
            paint.alpha = (255 * ((1f - progress) * 3).coerceIn(0f, 1f)).toInt()
            val save = canvas.save()
            canvas.rotate(index * 29f + progress * 300f, x, y)
            canvas.drawRoundRect(x, y, x + 3 * density, y + (6 + index % 4) * density, density, density, paint)
            canvas.restoreToCount(save)
        }
    }
    private fun remove() { (parent as? ViewGroup)?.removeView(this) }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
}
