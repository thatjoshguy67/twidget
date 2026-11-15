package com.tjg.twidget.main

import android.content.Context
import android.view.View
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.ui.FollowerCountCardView

/** Both spans share the widget's fitted word composition, without its account handle. */
internal object FollowerCountCard {
    fun create(context: Context, stats: ProfileStats, delta: Long, span: Int, existing: View? = null, catalogue: Boolean = false): View =
        ((existing as? FollowerCountCardView) ?: FollowerCountCardView(context).apply {
            id = R.id.followers_value
            setBackgroundResource(R.drawable.dashboard_card_bg)
            val inset = (12 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }).apply { bind(stats, delta) }
}
