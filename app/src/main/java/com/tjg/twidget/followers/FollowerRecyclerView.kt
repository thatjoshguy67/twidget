package com.tjg.twidget.followers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.R

/** Draw the card and its clipping from the same row positions in the same frame. */
class FollowerRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {
    private val surfacePath = Path()
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_card_bg)
    }
    private val radius = 28f * resources.displayMetrics.density
    private val endGap = (4f * resources.displayMetrics.density).toInt()

    override fun draw(canvas: Canvas) {
        // SESL's fill/corners belong to preference lists. This list owns its surface.
        seslSetLastRoundedCorner(false)
        val count = adapter?.itemCount ?: 0
        val bounds = followerListSurfaceBounds(
            height, radius.toInt(), count,
            findViewHolderForAdapterPosition(0)?.itemView?.y?.toInt(),
            findViewHolderForAdapterPosition(count - 1)?.itemView?.let { (it.y + it.height).toInt() },
            endGap,
        )
        surfacePath.rewind()
        surfacePath.addRoundRect(0f, bounds.top.toFloat(), width.toFloat(), bounds.bottom.toFloat(),
            radius, radius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(surfacePath)
        canvas.drawPaint(surfacePaint)
        super.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
