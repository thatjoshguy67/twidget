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

    const val DEBUG_FORCE_KEY = "debug_force_celebrations"
    private const val DEBUG_RUN_KEY = "debug_celebration_run"
    private const val PREFS = "twidget_milestone_celebrations"

    fun forceEnabled(context: Context): Boolean = com.tjg.twidget.data.TwidgetStore.debugMenuUnlocked(context) &&
        context.getSharedPreferences(com.tjg.twidget.data.TwidgetStore.PREFS, Context.MODE_PRIVATE).getBoolean(DEBUG_FORCE_KEY, false)

    fun setForceEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(com.tjg.twidget.data.TwidgetStore.PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(DEBUG_FORCE_KEY, enabled)
            if (enabled) putLong(DEBUG_RUN_KEY, prefs.getLong(DEBUG_RUN_KEY, 0L) + 1L)
        }.apply()
        com.tjg.twidget.core.AppExecutors.execute { com.tjg.twidget.widget.TwidgetBriefWidget.updateAll(context.applicationContext) }
    }

    data class WidgetTrigger(val account: String, val achievements: Set<String>, val forced: Boolean,
        val widgetId: Int = 0, val debugRun: Long = 0L) {
        val show get() = forced || achievements.isNotEmpty()
    }

    fun widgetTrigger(context: Context, account: String, widgetId: Int): WidgetTrigger {
        if (forceEnabled(context)) {
            val prefs = context.getSharedPreferences(com.tjg.twidget.data.TwidgetStore.PREFS, Context.MODE_PRIVATE)
            val run = prefs.getLong(DEBUG_RUN_KEY, 1L)
            return WidgetTrigger(account, emptySet(), prefs.getLong("debug_celebration_widget_$widgetId", 0L) != run, widgetId, run)
        }
        if (account.isBlank()) return WidgetTrigger(account, emptySet(), false)
        val goals = MilestoneGoalStore.readAll(context, account)
        if (goals.isEmpty()) return WidgetTrigger(account, emptySet(), false)
        val seen = AccountPreferences.open(context, PREFS).getStringSet(accountKey(account), emptySet()).orEmpty()
        val stats = com.tjg.twidget.data.TwidgetStore.currentStats(context, account)
        val history = com.tjg.twidget.data.TwidgetStore.fullHistory(context, account)
        val analytics = com.tjg.twidget.analytics.AnalyticsClient.cached(context, account)
        val imported = com.tjg.twidget.analytics.ImportedAnalyticsStore.all(context, account)
        return WidgetTrigger(account, goals.mapNotNull { goal ->
            val key = "widget:$widgetId:${goal.metric.storageId}:${goal.target}"
            val value = MilestoneMetricResolver.resolve(context, account, goal.metric, stats, history, analytics, imported).value
            key.takeIf { eligible(value, goal, key in seen) }
        }.toSet(), false)
    }

    fun recordWidgetTrigger(context: Context, trigger: WidgetTrigger) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (trigger.forced) {
            context.getSharedPreferences(com.tjg.twidget.data.TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
                .putLong("debug_celebration_widget_${trigger.widgetId}", trigger.debugRun).apply()
            return
        }
        if (trigger.achievements.isEmpty()) return
        synchronized(com.tjg.twidget.data.TwidgetStore) {
            val prefs = AccountPreferences.open(context, PREFS)
            val account = accountKey(trigger.account)
            prefs.edit().putStringSet(account, (prefs.getStringSet(account, emptySet()).orEmpty() + trigger.achievements).toMutableSet()).apply()
        }
    }

    private fun accountKey(account: String): String = account.trim().trimStart('@').lowercase(Locale.ROOT)

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
                if (forceEnabled(anchor.context)) return // Debug playback never consumes a real achievement.
                val activity = anchor.context as? Activity ?: return
                if (!anchor.isAttachedToWindow || !anchor.isShown || !activity.hasWindowFocus()) return
                val visible = android.graphics.Rect()
                if (!anchor.getGlobalVisibleRect(visible) || visible.height() < anchor.height / 2) return
                val key = accountKey(account)
                val achievement = "${goal.metric.storageId}:${goal.target}"
                val prefs = AccountPreferences.open(anchor.context, PREFS)
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
