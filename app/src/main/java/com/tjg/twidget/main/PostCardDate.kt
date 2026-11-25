package com.tjg.twidget.main

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object PostCardDate {
    fun format(timestamp: Long, today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val includeYear = date.year != today.year
        if (locale.language != "en") return date.format(DateTimeFormatter.ofPattern(
            if (includeYear) "d MMM yyyy" else "d MMM", locale))
        val day = date.dayOfMonth
        val suffix = if (day % 100 in 11..13) "th" else when (day % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        val month = date.format(DateTimeFormatter.ofPattern("MMM", locale))
        return "$month $day$suffix" + if (includeYear) ", ${date.year}" else ""
    }
}
