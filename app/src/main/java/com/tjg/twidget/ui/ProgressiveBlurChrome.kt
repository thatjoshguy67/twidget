package com.tjg.twidget.ui

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.data.TwidgetStore
import dev.oneuiproject.oneui.widget.SemProgressiveBlurOverlay
import kotlin.math.roundToInt

/**
 * Scroll-edge blur hosted on the scroll anchor (so Samsung blur can sample content behind it),
 * full-bleed across the device width, with theme colour applied only via the gradient fade.
 */
object ProgressiveBlurChrome {
    private const val TOP_TAG = "twidget_progressive_blur_top"
    private const val BOTTOM_TAG = "twidget_progressive_blur_bottom"
    private const val TOP_FADE_SCROLL_PX = 16f
    private const val BOTTOM_FADE_SCROLL_PX = 48f

    private data class OverlayHost(
        val anchor: ViewGroup,
        val scrollView: View,
        val topOverlay: SemProgressiveBlurOverlay,
        val bottomOverlay: SemProgressiveBlurOverlay,
        val layoutListener: View.OnLayoutChangeListener,
    )

    private val hosts = mutableMapOf<Activity, OverlayHost>()

    fun attach(activity: Activity, anchor: ViewGroup, scrollView: View) {
        detach(activity)

        val overlayHeight = dp(
            activity,
            if (activity.resources.configuration.smallestScreenWidthDp >= 600) 120 else 68,
        )
        val fadeColor = TwidgetTheme.background(activity)

        anchor.clipChildren = false
        anchor.clipToPadding = false

        val topOverlay = SemProgressiveBlurOverlay(activity).apply {
            tag = TOP_TAG
            edge = SemProgressiveBlurOverlay.Edge.TOP
            this.fadeColor = fadeColor
        }
        val bottomOverlay = SemProgressiveBlurOverlay(activity).apply {
            tag = BOTTOM_TAG
            edge = SemProgressiveBlurOverlay.Edge.BOTTOM
            this.fadeColor = fadeColor
        }

        anchor.addView(
            topOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, overlayHeight, Gravity.TOP),
        )
        anchor.addView(
            bottomOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, overlayHeight, Gravity.BOTTOM),
        )
        topOverlay.bringToFront()
        bottomOverlay.bringToFront()

        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyFullBleedWidth(anchor, topOverlay)
            applyFullBleedWidth(anchor, bottomOverlay)
        }
        anchor.addOnLayoutChangeListener(layoutListener)
        topOverlay.post {
            applyFullBleedWidth(anchor, topOverlay)
            applyFullBleedWidth(anchor, bottomOverlay)
            topOverlay.applyBlurLayers()
            bottomOverlay.applyBlurLayers()
        }

        hosts[activity] = OverlayHost(anchor, scrollView, topOverlay, bottomOverlay, layoutListener)
        bindScrollBehavior(activity, scrollView, topOverlay, bottomOverlay)
        refresh(activity)
    }

    fun detach(activity: Activity) {
        val host = hosts.remove(activity) ?: return
        host.anchor.removeOnLayoutChangeListener(host.layoutListener)
        host.anchor.removeView(host.topOverlay)
        host.anchor.removeView(host.bottomOverlay)
    }

    fun refresh(context: Context, root: View) {
        val activity = context as? Activity ?: return
        refresh(activity)
    }

    fun refresh(activity: Activity) {
        val host = hosts[activity] ?: return
        val enabled = TwidgetStore.appBlurEnabled(activity) && !isReduceTransparencyOn(activity)
        val fadeColor = TwidgetTheme.background(activity)

        host.topOverlay.fadeColor = fadeColor
        host.bottomOverlay.fadeColor = fadeColor
        applyEffect(host.topOverlay, enabled)
        applyEffect(host.bottomOverlay, enabled)
    }

    fun refreshFromRoot(activity: Activity) {
        refresh(activity)
    }

    private fun applyEffect(overlay: SemProgressiveBlurOverlay, enabled: Boolean) {
        overlay.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            overlay.post { overlay.applyBlurLayers() }
        } else {
            overlay.clearBlurLayers()
        }
    }

    /** Extend overlays to the physical screen width (website `100vw`). */
    private fun applyFullBleedWidth(anchor: View, overlay: View) {
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val params = overlay.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.width = anchor.resources.displayMetrics.widthPixels
        params.marginStart = -anchorLoc[0]
        overlay.layoutParams = params
    }

    private fun bindScrollBehavior(
        activity: Activity,
        scrollView: View,
        topOverlay: SemProgressiveBlurOverlay,
        bottomOverlay: SemProgressiveBlurOverlay,
    ) {
        val update = Runnable {
            if (!TwidgetStore.appBlurEnabled(activity) || isReduceTransparencyOn(activity)) {
                topOverlay.alpha = 0f
                bottomOverlay.alpha = 0f
            } else if (isAtBottom(scrollView)) {
                topOverlay.alpha = 0f
                bottomOverlay.alpha = 0f
            } else {
                val offset = scrollOffset(scrollView).toFloat()
                topOverlay.alpha = (offset / TOP_FADE_SCROLL_PX).coerceIn(0f, 1f)
                val distanceFromBottom = distanceFromBottom(scrollView)
                bottomOverlay.alpha = (distanceFromBottom / BOTTOM_FADE_SCROLL_PX).coerceIn(0f, 1f)
            }
        }

        when (scrollView) {
            is NestedScrollView ->
                scrollView.setOnScrollChangeListener { _, _, _, _, _ -> update.run() }
            is RecyclerView ->
                scrollView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = update.run()
                })
        }
        ViewCompat.postOnAnimation(scrollView, update)
        scrollView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update.run() }
    }

    private fun isReduceTransparencyOn(context: Context): Boolean =
        Settings.System.getInt(
            context.contentResolver,
            "accessibility_reduce_transparency",
            0,
        ) != 0

    private fun isAtBottom(scrollView: View): Boolean = !scrollView.canScrollVertically(1)

    private fun scrollOffset(scrollView: View): Int = when (scrollView) {
        is NestedScrollView -> scrollView.scrollY
        is RecyclerView -> scrollView.computeVerticalScrollOffset()
        else -> 0
    }

    private fun distanceFromBottom(scrollView: View): Int = when (scrollView) {
        is NestedScrollView -> {
            val child = scrollView.getChildAt(0) ?: return 0
            (child.height - scrollView.height - scrollView.scrollY).coerceAtLeast(0)
        }
        is RecyclerView -> {
            val range = scrollView.computeVerticalScrollRange()
            val extent = scrollView.computeVerticalScrollExtent()
            val offset = scrollView.computeVerticalScrollOffset()
            (range - extent - offset).coerceAtLeast(0)
        }
        else -> 0
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
