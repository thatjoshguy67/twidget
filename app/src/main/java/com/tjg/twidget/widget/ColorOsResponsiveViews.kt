package com.tjg.twidget.widget

import android.content.Context
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.floor

/** Shared live-viewport handling for ColorOS surfaces only. */
internal object ColorOsResponsiveViews {
    fun wrap(context: Context, width: Int, height: Int, base: RemoteViews,
        compact: (Int, Int) -> RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT < 31) return base
        val variants = linkedMapOf<SizeF, RemoteViews>()
        val density = context.resources.displayMetrics.density
        fun add(key: SizeF, source: RemoteViews) {
            val view = RemoteViews(source)
            // Framework size matching allows rounding slack of almost 2dp. Leave
            // that slack outside the card, and round down to keep it inside pixels.
            val w = floor((key.width - 2f).coerceAtLeast(0f) * density)
            val h = floor((key.height - 2f).coerceAtLeast(0f) * density)
            view.setViewLayoutWidth(android.R.id.background, w, TypedValue.COMPLEX_UNIT_PX)
            view.setViewLayoutHeight(android.R.id.background, h, TypedValue.COMPLEX_UNIT_PX)
            variants[key] = view
        }
        // ColorOS also inspects/inflates the default child during asynchronous
        // refreshes. An invisible sentinel can leave a blank card until a size
        // change triggers selection again. Keep that default small AND populated,
        // sharing its artwork with the minimum supported one-row variant.
        val narrow = width.coerceAtMost(110).coerceAtLeast(3)
        val minimum = compact(narrow, 40)
        variants[SizeF(1f, 1f)] = RemoteViews(minimum).apply {
            setViewLayoutWidth(android.R.id.background, floor((narrow - 2f) * density), TypedValue.COMPLEX_UNIT_PX)
            setViewLayoutHeight(android.R.id.background, floor(38f * density), TypedValue.COMPLEX_UNIT_PX)
        }
        for (scale in listOf(.25f, .4f, .55f, .7f, .8f, .825f, .85f, .875f, .9f, .925f, .95f, 1f)) {
            add(SizeF((width * scale).coerceAtLeast(3f), (height * scale).coerceAtLeast(3f)), base)
        }
        // One-row variants also work before a tall card's provider callback arrives.
        add(SizeF(narrow.toFloat(), 40f), minimum)
        for (w in setOf(width.coerceAtMost(148).coerceAtLeast(3), width.coerceAtLeast(3))) {
            add(SizeF(w.toFloat(), 76f), compact(w, 76))
        }
        return RemoteViews(variants)
    }
}
