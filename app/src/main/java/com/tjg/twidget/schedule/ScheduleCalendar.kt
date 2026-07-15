package com.tjg.twidget.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object ScheduleCalendar {
    fun dateFor(post: ScheduledPost, zoneId: ZoneId): LocalDate? =
        post.scheduledAt?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }

    fun postsInMonth(
        posts: List<ScheduledPost>,
        month: YearMonth,
        zoneId: ZoneId,
    ): List<ScheduledPost> = posts.filter { post ->
        dateFor(post, zoneId)?.let(YearMonth::from) == month
    }.sortedBy { it.scheduledAt }

    fun postsOnDate(
        posts: List<ScheduledPost>,
        date: LocalDate,
        zoneId: ZoneId,
    ): List<ScheduledPost> = posts.filter { dateFor(it, zoneId) == date }
        .sortedBy { it.scheduledAt }

    fun countByDate(
        posts: List<ScheduledPost>,
        month: YearMonth,
        zoneId: ZoneId,
    ): Map<LocalDate, Int> = postsInMonth(posts, month, zoneId)
        .mapNotNull { dateFor(it, zoneId) }
        .groupingBy { it }
        .eachCount()
}
