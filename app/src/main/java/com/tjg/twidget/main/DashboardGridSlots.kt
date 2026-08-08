package com.tjg.twidget.main

import kotlin.math.pow

/**
 * Resolves a drag pointer to one deterministic insertion slot. A slot is an
 * index in the card order after the dragged card has been removed.
 */
internal object DashboardGridSlots {
    data class CardBounds(
        val id: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val span: Int,
    )

    private data class Anchor(
        val insertAt: Int,
        val x: Float,
        val y: Float,
    )

    fun resolveInsertIndex(
        cards: List<CardBounds>,
        pointerX: Float,
        pointerY: Float,
        currentInsertAt: Int,
        hysteresisPx: Float,
        bottomGutterPx: Float = 0f,
    ): Int {
        if (cards.isEmpty()) return 0
        val maxBottom = cards.maxOf { it.bottom }
        if (bottomGutterPx > 0f && pointerY >= maxBottom - bottomGutterPx) {
            return cards.size
        }
        val anchors = buildAnchors(cards, bottomGutterPx)
        val current = anchors.firstOrNull { it.insertAt == currentInsertAt }
        val candidate = anchors.minBy { distanceSquared(it, pointerX, pointerY) }
        if (current == null || candidate.insertAt == currentInsertAt) return candidate.insertAt

        val candidateDistance = distanceSquared(candidate, pointerX, pointerY)
        val currentDistance = distanceSquared(current, pointerX, pointerY)
        return if (candidateDistance + hysteresisPx.pow(2) < currentDistance) {
            candidate.insertAt
        } else {
            currentInsertAt
        }
    }

    private fun buildAnchors(cards: List<CardBounds>, bottomGutterPx: Float): List<Anchor> {
        val anchors = ArrayList<Anchor>(cards.size + 1)
        val left = cards.minOf { it.left }
        val right = cards.maxOf { it.right }
        val maxBottom = cards.maxOf { it.bottom }
        val first = cards.first()
        anchors += if (first.span > 1) {
            Anchor(0, (left + right) / 2f, first.top)
        } else {
            Anchor(0, first.left, (first.top + first.bottom) / 2f)
        }
        for (index in 1 until cards.size) {
            val previous = cards[index - 1]
            val current = cards[index]
            val rowsOverlap = minOf(previous.bottom, current.bottom) -
                maxOf(previous.top, current.top) > 0f
            anchors += if (previous.span == 1 && current.span == 1 && rowsOverlap) {
                Anchor(
                    insertAt = index,
                    x = current.left,
                    y = ((previous.top + previous.bottom + current.top + current.bottom) / 4f),
                )
            } else {
                Anchor(
                    insertAt = index,
                    x = (left + right) / 2f,
                    y = (previous.bottom + current.top) / 2f,
                )
            }
        }
        anchors += Anchor(
            insertAt = cards.size,
            x = (left + right) / 2f,
            y = maxBottom + bottomGutterPx / 2f,
        )
        return anchors
    }

    private fun distanceSquared(anchor: Anchor, x: Float, y: Float): Float =
        (anchor.x - x).pow(2) + (anchor.y - y).pow(2)
}
