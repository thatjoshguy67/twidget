package com.tjg.twidget.followers

internal data class FollowerListSurfaceBounds(val top: Int, val bottom: Int)

/** Keep rounded ends attached to content, never to the middle of a scrolling viewport. */
internal fun followerListSurfaceBounds(
    viewportHeight: Int,
    cornerRadius: Int,
    itemCount: Int,
    firstRowTop: Int?,
    lastRowBottom: Int?,
    endGap: Int,
): FollowerListSurfaceBounds {
    if (itemCount == 0 || viewportHeight <= 0) return FollowerListSurfaceBounds(0, 0)
    val top = (firstRowTop ?: -cornerRadius).coerceIn(-cornerRadius, viewportHeight)
    val bottom = (lastRowBottom?.plus(endGap) ?: (viewportHeight + cornerRadius))
        .coerceIn(top, viewportHeight + cornerRadius)
    return FollowerListSurfaceBounds(top, bottom)
}
