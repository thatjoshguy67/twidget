package com.tjg.twidget.followers

/** Rank checkpoints scale down for shorter lists, including filtered results. */
internal fun followerCheckpointPositions(count: Int): List<Int> {
    if (count <= 0) return emptyList()
    val step = when {
        count <= 10 -> 1
        count <= 50 -> 5
        count <= 100 -> 10
        count <= 500 -> 25
        count <= 1000 -> 50
        else -> 100
    }
    return (listOf(0) + (step - 1 until count step step)).distinct()
}
