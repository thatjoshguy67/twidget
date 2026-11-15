package com.tjg.twidget.main

import kotlin.math.hypot

/** Row-major slots match the dashboard's one/two-column GridLayout. */
internal object DashboardDragPolicy {
    data class Tile(val id: String, val span: Int, val height: Float = 1f)
    data class Slot(val row: Int, val column: Int, val span: Int)
    data class Target(val index: Int, val slot: Slot, val skippedCells: Int, val centerY: Float)

    fun pack(tiles: List<Tile>, columns: Int): Map<String, Slot> {
        require(columns > 0)
        var row = 0
        var column = 0
        return buildMap {
            tiles.forEach { tile ->
                require(tile.span in 1..columns)
                if (column + tile.span > columns) { row++; column = 0 }
                put(tile.id, Slot(row, column, tile.span))
                column += tile.span
                if (column == columns) { row++; column = 0 }
            }
        }
    }

    // The peers keep their original relative order throughout a drag. Targets
    // therefore stay put while their *views* animate into the new arrangement.
    fun targets(peers: List<Tile>, dragged: Tile, columns: Int): List<Target> =
        (0..peers.size).map { index ->
            val order = peers.toMutableList().apply { add(index, dragged) }
            val slots = pack(order, columns)
            val slot = slots.getValue(dragged.id)
            val heights = FloatArray(slots.values.maxOf { it.row } + 1)
            order.forEach { tile ->
                val row = slots.getValue(tile.id).row
                heights[row] = maxOf(heights[row], tile.height)
            }
            val centerY = heights.take(slot.row).sum() + heights[slot.row] / 2f
            Target(index, slot, slot.row * columns + slot.column - peers.take(index).sumOf { it.span }, centerY)
        }

    fun nearest(targets: List<Target>, x: Float, y: Float, activeIndex: Int, stickiness: Float = .18f): Target? {
        fun distance(target: Target) = hypot(x - target.slot.column - target.slot.span / 2f,
            y - target.centerY)
        val active = targets.firstOrNull { it.index == activeIndex }
        val nearest = targets.minWithOrNull(compareBy<Target> { distance(it) }
            .thenBy { it.skippedCells }.thenBy { kotlin.math.abs(it.index - activeIndex) }) ?: return null
        // Prefer the preview when destinations coincide or the pointer merely
        // strays over a cell boundary. Coordinates are in logical grid units.
        return if (active != null && distance(active) <= distance(nearest) + stickiness) active else nearest
    }
}

/** A stationary hover also matures; the UI schedules [remaining] without requiring more motion events. */
internal class DashboardDropHover(private val dwell: Long = 160L) {
    private var candidate: Int? = null
    private var since = 0L

    fun update(index: Int?, active: Int, now: Long): Int? {
        if (index == null || index == active) { clear(); return null }
        if (candidate != index) { candidate = index; since = now }
        return index.takeIf { now - since >= dwell }
    }
    fun remaining(now: Long): Long? = candidate?.let { (dwell - (now - since)).coerceAtLeast(1L) }
    fun clear() { candidate = null }
}
