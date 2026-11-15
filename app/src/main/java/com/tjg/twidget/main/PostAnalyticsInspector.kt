package com.tjg.twidget.main

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.oneui.floatingdock.FloatingPane
import com.google.android.material.oneui.floatingdock.FloatingPaneLayout
import com.google.android.material.oneui.floatingdock.IFloatingPaneCallback
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.brief.BriefReviewTarget
import com.tjg.twidget.brief.BriefTweetReviewActivity
import com.tjg.twidget.ui.KeyboardNavigation
import com.tjg.twidget.ui.SeslKeyboardFocus
import com.tjg.twidget.ui.SeslSurfaceHighlight
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.startRightSidePopOverActivity
import java.text.NumberFormat

/** One native SESL pane per dashboard. The dashboard remains touchable outside it. */
internal class PostAnalyticsInspector private constructor(private val activity: MainActivity) : DefaultLifecycleObserver {
    private val root = activity.findViewById<FrameLayout>(android.R.id.content)
    private val pane = FloatingPaneLayout(activity).apply { id = R.id.post_inspector_pane }
    private val mode = FloatingPane.FloatingPaneMode.MODE_FLOATING
    private val content = LinearLayout(activity).apply {
        id = com.google.android.material.R.id.result_layout_content
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(12))
    }
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val title = TextView(activity).apply {
        textSize = 16f; maxLines = 2; setTextColor(activity.getColor(R.color.oneui_text_primary))
        typeface = TwidgetFonts.forApp(activity, 700)
    }
    private val minimized = LinearLayout(activity).apply {
        id = com.google.android.material.R.id.result_layout_minimize
        gravity = Gravity.CENTER_VERTICAL
    }
    private val restore = button(R.string.floating_restore, R.id.post_inspector_restore) { minimize(false) }
    private var post: PostSummary? = null
    private var account = ""
    private var returnFocus: View? = null
    private var restoring = activity.savedStateRegistry.consumeRestoredStateForKey(STATE)
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { if (!pane.isMinimizeView()) minimize(true) else close() }
    }
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun button(text: Int, id: Int = View.NO_ID, action: () -> Unit) = AppCompatButton(activity).apply {
        this.id = id; setText(text); isAllCaps = false; minimumHeight = dp(48)
        setOnClickListener { action() }
    }
    private var lastBounds = ""
    private val accountListener = android.view.ViewTreeObserver.OnPreDrawListener {
        if (post != null && !activity.selectedAccount.equals(account, true)) close()
        true
    }
    init {
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(R.string.floating_minimize, R.id.post_inspector_minimize) { minimize(true) }, LinearLayout.LayoutParams(-2, -2))
        content.addView(header)
        content.addView(NestedScrollView(activity).apply { addView(body); isFillViewport = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(activity).apply { gravity = Gravity.END }
        footer.addView(button(R.string.floating_close, R.id.post_inspector_close) { close() })
        content.addView(footer)
        pane.addView(content, FrameLayout.LayoutParams(dp(380), dp(440)))
        minimized.addView(restore, LinearLayout.LayoutParams(0, -2, 1f))
        minimized.addView(androidx.appcompat.widget.AppCompatImageButton(activity).apply {
            setImageResource(dev.oneuiproject.oneui.R.drawable.ic_oui_close)
            imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.oneui_text_primary))
            contentDescription = activity.getString(R.string.floating_close)
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        pane.setMinimizeView(minimized)
        pane.setBlurDisable(true)
        pane.setAllowModes(mode)
        pane.setPaneMode(mode)
        pane.setResizeByContentScrollEnabled(false)
        pane.visibility = View.GONE
        root.addView(pane, FrameLayout.LayoutParams(-1, -1))
        pane.addCallback(object : IFloatingPaneCallback {
            override fun onMinimizedChanged(mode: FloatingPane.FloatingPaneMode, minimized: Boolean) {
                if (minimized) restore.requestFocus()
            }
        })
        pane.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        root.viewTreeObserver.addOnPreDrawListener(accountListener)
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.lifecycle.addObserver(this)
        activity.savedStateRegistry.registerSavedStateProvider(STATE) { Bundle().apply {
            putString("url", post?.url); putString("account", account); putBoolean("minimized", pane.isMinimizeView())
        } }
        KeyboardNavigation.register(activity, "inspector", R.string.floating_shortcut_inspector, KeyEvent.KEYCODE_M,
            enabled = { post != null }) { minimize(!pane.isMinimizeView()) }
        if (activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) restoreSaved()
    }
    private fun updateBounds() {
        if (root.width == 0 || root.height == 0) return
        val insets = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val left = (insets?.left ?: 0) + dp(12)
        val right = (insets?.right ?: 0) + dp(12)
        val top = (insets?.top ?: 0) + dp(12)
        val bottom = maxOf(insets?.bottom ?: 0, ime) + dp(12)
        val width = minOf(dp(420), root.width - left - right).coerceAtLeast(1)
        val height = minOf(dp(500), ((root.height - top - bottom) * .68f).toInt()).coerceAtLeast(1)
        val signature = "$width/$height/$left/$right/$top/$bottom"
        if (signature == lastBounds) return
        lastBounds = signature
        pane.setResultWidth(mode, width); pane.setResultHeight(mode, height)
        pane.setMinWidth(mode, width); pane.setMaxWidth(mode, width)
        pane.setMinHeight(mode, height); pane.setMaxHeight(mode, height)
        pane.setMinimizeWidth(mode, minOf(dp(300), width))
        pane.setMinimizeBottomInset(bottom)
        pane.addUntouchableAreaInset(left, top, right, bottom)
    }
    fun show(value: PostSummary, username: String, origin: View? = null) {
        account = username; post = value; returnFocus = origin
        title.text = activity.getString(R.string.floating_inspector)
        restore.text = activity.getString(R.string.floating_inspector)
        restore.contentDescription = "${restore.text}: ${value.text.take(80)}"
        body.removeAllViews()
        fun line(text: CharSequence, size: Float = 15f) {
            body.addView(TextView(activity).apply {
                this.text = text; textSize = size; setTextColor(activity.getColor(R.color.oneui_text_primary))
                setPadding(0, dp(6), 0, dp(6))
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        line("@${value.authorUserName.ifBlank { username }}", 13f)
        line(value.fullText?.takeIf { it.isNotBlank() } ?: value.text.ifBlank { value.url })
        val formatter = NumberFormat.getIntegerInstance(com.tjg.twidget.core.AppLocales.applicationLocale())
        line(if (value.viewsKnown) activity.getString(R.string.post_metric_views, formatter.format(value.views))
            else activity.getString(R.string.floating_views_unknown))
        line(activity.getString(R.string.post_metric_likes, formatter.format(value.likes)))
        line(activity.getString(R.string.post_metric_replies, formatter.format(value.replies)))
        line(activity.getString(R.string.post_metric_reposts, formatter.format(value.reposts)))
        body.addView(button(R.string.open_post) { activity.dashboardTweetLauncher.open(value.url) })
        body.addView(button(R.string.floating_report) {
            activity.startRightSidePopOverActivity(BriefTweetReviewActivity.intent(activity,
                BriefReviewTarget(username, postUrl = value.url, fromDashboard = true)))
        })
        pane.visibility = View.VISIBLE
        updateBounds()
        pane.show(ValueAnimator.areAnimatorsEnabled())
        pane.enterMinimizeView(false)
        back.isEnabled = true
        TwidgetFonts.applyTo(pane); SeslSurfaceHighlight.applyTo(pane); SeslKeyboardFocus.observeWindow(pane)
        pane.post { content.findViewById<View>(R.id.post_inspector_minimize)?.requestFocus() }
    }
    internal fun minimize(value: Boolean) {
        if (post == null) return
        pane.enterMinimizeView(value)
        if (value) returnFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        else content.findViewById<View>(R.id.post_inspector_minimize)?.requestFocus()
    }
    internal fun close() {
        pane.hide(false); pane.visibility = View.GONE
        post = null; account = ""; back.isEnabled = false
        returnFocus?.takeIf { it.isAttachedToWindow }?.requestFocus(); returnFocus = null
    }
    override fun onResume(owner: LifecycleOwner) { restoreSaved(); updateBounds() }
    private fun restoreSaved() {
        val state = restoring ?: return
        restoring = null
        val username = state.getString("account").orEmpty()
        if (!username.equals(activity.selectedAccount, true)) return
        val url = state.getString("url") ?: return
        val data = activity.analytics ?: AnalyticsClient.cached(activity, username) ?: return
        val value = (data.recentPosts + data.recentReplies + listOfNotNull(data.best, data.worst, data.banger)).firstOrNull { it.url == url } ?: return
        show(value, username)
        pane.post { minimize(state.getBoolean("minimized")) }
    }
    override fun onDestroy(owner: LifecycleOwner) {
        pane.hide(false); pane.removeAllCallback(); back.remove()
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(accountListener)
        root.removeView(pane)
        if (root.getTag(R.id.post_inspector_controller) === this) root.setTag(R.id.post_inspector_controller, null)
        activity.savedStateRegistry.unregisterSavedStateProvider(STATE)
    }
    companion object {
        private const val STATE = "floating_post_inspector"
        fun attach(activity: MainActivity): PostAnalyticsInspector {
            val root = activity.findViewById<View>(android.R.id.content)
            return (root.getTag(R.id.post_inspector_controller) as? PostAnalyticsInspector)?.takeIf { it.activity === activity } ?: PostAnalyticsInspector(activity).also {
                root.setTag(R.id.post_inspector_controller, it)
            }
        }
    }
}
