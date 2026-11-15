package com.tjg.twidget.followers

import java.util.Locale

internal enum class FollowerSort { FOLLOWERS, NAME }
internal data class FollowerFilters(val sort: FollowerSort = FollowerSort.FOLLOWERS, val minimum: Long = 0) {
    val activeCount get() = (if (sort != FollowerSort.FOLLOWERS) 1 else 0) + (if (minimum > 0) 1 else 0)
    fun apply(ranked: List<RankedTopFollower>, query: String): List<RankedTopFollower> {
        val filtered = TopFollowersBrowserPolicy.filter(ranked, query).filter { it.follower.followers >= minimum }
        return if (sort == FollowerSort.NAME) filtered.sortedWith(
            compareBy<RankedTopFollower> { it.follower.name.ifBlank { it.follower.username }.lowercase(Locale.ROOT) }
                .thenBy { it.follower.username.lowercase(Locale.ROOT) }.thenBy { it.rank }
        ) else filtered
    }
    companion object {
        fun restored(sort: String?, minimum: Long) = FollowerFilters(
            FollowerSort.entries.firstOrNull { it.name == sort } ?: FollowerSort.FOLLOWERS,
            minimum.takeIf { it in listOf(0L, 10_000L, 100_000L) } ?: 0L,
        )
    }
}
