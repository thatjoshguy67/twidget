package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostMedia
import kotlin.math.roundToInt

/** Shared tweet media presentation for dashboard and Brief cards. */
internal object TweetMediaView {
    const val MAX_HEIGHT_DP = 280
    private const val STRIP_HEIGHT_DP = 218

    fun create(context: Context, media: List<PostMedia>): View {
        require(media.isNotEmpty())
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        fun image(item: PostMedia) = MediaAspectImageView(context).apply {
            maximumMediaHeightPx = dp(MAX_HEIGHT_DP)
            contentDescription = item.alt.ifBlank { context.getString(R.string.post_media) }
            ProfileImageLoader.loadMediaInto(context, this, item.url, dp(14))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        if (media.size == 1) return image(media.first())
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            clipToOutline = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                media.forEachIndexed { index, item ->
                    addView(image(item), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(STRIP_HEIGHT_DP),
                    ).apply { if (index > 0) marginStart = dp(8) })
                }
            })
        }
    }
}
