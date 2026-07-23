package com.tjg.twidget.ui

import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tjg.twidget.R

// One UI 8 four-dot loading spinner (res/drawable/oneui_spinner.xml). The AVD
// plays a single 2 s cycle, so it is looped from an animation callback the
// same way SeslProgressBar's CircleAnimationCallback does.
object OneUiSpinner {
    fun loop(view: ImageView) {
        val avd = view.drawable as? AnimatedVectorDrawable ?: return
        avd.registerAnimationCallback(object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                view.post { if (view.isAttachedToWindow) avd.start() }
            }
        })
        avd.start()
    }

    // Swaps the animation inside a SwipeRefreshLayout's stock circle for the
    // One UI 8 spinner. The circle view itself (white disc + shadow) stays.
    fun attachToSwipeRefresh(layout: SwipeRefreshLayout) {
        val circle = (0 until layout.childCount)
            .map(layout::getChildAt)
            .filterIsInstance<ImageView>()
            .firstOrNull() ?: return
        val pad = (4 * layout.resources.displayMetrics.density).toInt()
        circle.setPadding(pad, pad, pad, pad)
        circle.setImageResource(R.drawable.oneui_spinner)
        loop(circle)
    }
}
