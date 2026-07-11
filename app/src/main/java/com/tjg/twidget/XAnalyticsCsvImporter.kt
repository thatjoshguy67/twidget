package com.tjg.twidget

import java.io.Reader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class XAnalyticsMovement(
    val date: LocalDate,
    val newFollows: Long,
    val unfollows: Long,
    val impressions: Long? = null,
    val likes: Long? = null,
    val engagements: Long? = null,
    val bookmarks: Long? = null,
    val shares: Long? = null,
    val replies: Long? = null,
    val reposts: Long? = null,
    val profileVisits: Long? = null,
    val postsCreated: Long? = null,
    val videoViews: Long? = null,
    val mediaViews: Long? = null,
)

data class XAnalyticsImport(
    val samples: List<HistorySample>,
    val movements: List<XAnalyticsMovement>,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val detectedFollowers: Long?,
)

class AnalyticsCsvException(
    val cachedFollowers: Long,
    val detectedFollowers: Long?,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Parses X Premium's Account overview CSV export. The export does not identify
 * its account and generally contains daily analytics plus follow/unfollow
 * movements rather than a follower total, so the caller must explicitly choose
 * an account and supply its live follower count as the trusted anchor.
 */
object XAnalyticsCsvImporter {
    private const val MAX_CSV_CHARS = 1_000_000
    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(
        reader: Reader,
        anchorFollowers: Long,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): XAnalyticsImport {
        var detectedFollowers: Long? = null
        try {
            require(anchorFollowers >= 0L) { "The selected account has no usable follower count yet." }
            val records = parseCsv(reader)
            require(records.size >= 2) { "The file does not contain any analytics rows." }

            val header = records.first().mapIndexed { index, value ->
                value.removePrefix("\uFEFF").trim().lowercase(Locale.US) to index
            }.toMap()
            val dateIndex = header["date"] ?: throw IllegalArgumentException("The Date column is missing.")
            val followsIndex = header["new follows"]
                ?: throw IllegalArgumentException("The New follows column is missing.")
            val unfollowsIndex = header["unfollows"]
                ?: throw IllegalArgumentException("The Unfollows column is missing.")
            val followerCountIndex = listOf("followers", "follower count", "total followers")
                .firstNotNullOfOrNull(header::get)
            val requiredWidth = maxOf(dateIndex, followsIndex, unfollowsIndex) + 1
            val dataRecords = records.drop(1).filterNot { row -> row.all { it.isBlank() } }
            detectedFollowers = followerCountIndex?.let { index ->
                dataRecords.firstOrNull()?.getOrNull(index)?.trim()?.replace(",", "")?.toLongOrNull()
            }

            val days = dataRecords.mapIndexed { offset, row ->
                require(row.size >= requiredWidth) { "Row ${offset + 2} is incomplete." }
                val movement = XAnalyticsMovement(
                    date = runCatching { LocalDate.parse(row[dateIndex].trim(), dateFormatter) }
                        .getOrElse { throw IllegalArgumentException("Row ${offset + 2} has an invalid date.") },
                    newFollows = unsignedLong(row[followsIndex], offset + 2, "New follows"),
                    unfollows = unsignedLong(row[unfollowsIndex], offset + 2, "Unfollows"),
                    impressions = optionalUnsignedLong(row, header, offset + 2, "Impressions"),
                    likes = optionalUnsignedLong(row, header, offset + 2, "Likes"),
                    engagements = optionalUnsignedLong(row, header, offset + 2, "Engagements"),
                    bookmarks = optionalUnsignedLong(row, header, offset + 2, "Bookmarks"),
                    shares = optionalUnsignedLong(row, header, offset + 2, "Shares"),
                    replies = optionalUnsignedLong(row, header, offset + 2, "Replies"),
                    reposts = optionalUnsignedLong(row, header, offset + 2, "Reposts"),
                    profileVisits = optionalUnsignedLong(row, header, offset + 2, "Profile visits"),
                    postsCreated = optionalUnsignedLong(row, header, offset + 2, "Create Post"),
                    videoViews = optionalUnsignedLong(row, header, offset + 2, "Video views"),
                    mediaViews = optionalUnsignedLong(row, header, offset + 2, "Media views"),
                )
                validateMetricConsistency(movement, offset + 2)
                movement
            }.sortedByDescending { it.date }

            require(days.isNotEmpty()) { "The file does not contain any analytics rows." }
            require(days.size <= 366) { "The export contains more than one year of data." }
            require(days.map { it.date }.distinct().size == days.size) { "The export contains duplicate dates." }
            require(days.first().date == today) {
                "The newest row must be today so the live follower count can anchor the import."
            }
            days.zipWithNext().forEach { (newer, older) ->
                require(older.date == newer.date.minusDays(1)) {
                    "The export has a gap between ${older.date} and ${newer.date}."
                }
            }

            detectedFollowers?.let { detected ->
                if (abs(detected - anchorFollowers) > XAnalyticsImportPolicy.trendTolerance(0, anchorFollowers)) {
                    throw AnalyticsValidationException(
                        code = "analytics_follower_mismatch",
                        expectedFollowers = anchorFollowers,
                        detectedFollowers = detected,
                        message = "The follower count in the CSV does not match the cached account count.",
                    )
                }
            }

            var followers = anchorFollowers
            var netMovement = 0L
            val samples = days.map { day ->
                if (followers < 0L) impossibleCount(anchorFollowers, netMovement, day.date)
                val sample = HistorySample(
                    dayLabel = day.date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)),
                    followers = followers,
                    following = 0L,
                    posts = 0L,
                    likes = 0L,
                    timestamp = day.date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    followersKnown = true,
                    followingKnown = false,
                    postsKnown = false,
                    likesKnown = false,
                    imported = true,
                )
                val movement = day.newFollows - day.unfollows
                netMovement += movement
                followers -= movement
                sample
            }.asReversed()
            if (followers < 0L) impossibleCount(anchorFollowers, netMovement, days.last().date.minusDays(1))

            return XAnalyticsImport(
                samples = samples,
                movements = days.asReversed(),
                firstDate = days.last().date,
                lastDate = days.first().date,
                detectedFollowers = detectedFollowers,
            )
        } catch (error: AnalyticsValidationException) {
            throw error
        } catch (error: AnalyticsCsvException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw AnalyticsCsvException(
                cachedFollowers = anchorFollowers,
                detectedFollowers = detectedFollowers,
                message = error.message ?: "The analytics file could not be read.",
                cause = error,
            )
        }
    }

    private fun impossibleCount(cachedFollowers: Long, netMovement: Long, date: LocalDate): Nothing {
        val reconstructed = cachedFollowers - netMovement
        throw AnalyticsCsvException(
            cachedFollowers = cachedFollowers,
            detectedFollowers = null,
            message = "The CSV reports a net gain of ${formatSigned(netMovement)} followers through $date. " +
                "Working back from the cached count of $cachedFollowers produces an impossible count of $reconstructed.",
        )
    }

    private fun formatSigned(value: Long): String = if (value >= 0L) "+$value" else value.toString()

    private fun unsignedLong(value: String, row: Int, column: String): Long {
        val clean = value.trim()
        require(clean.matches(Regex("[0-9]+"))) { "Row $row has an invalid $column value." }
        return clean.toLongOrNull() ?: throw IllegalArgumentException("Row $row has an invalid $column value.")
    }

    private fun optionalUnsignedLong(
        row: List<String>,
        header: Map<String, Int>,
        rowNumber: Int,
        column: String,
    ): Long? {
        val index = header[column.lowercase(Locale.US)] ?: return null
        require(index < row.size) { "Row $rowNumber is missing its $column value." }
        return unsignedLong(row[index], rowNumber, column)
    }

    private fun validateMetricConsistency(movement: XAnalyticsMovement, rowNumber: Int) {
        val engagements = movement.engagements ?: return
        val visibleEngagements = listOf(
            movement.likes,
            movement.bookmarks,
            movement.shares,
            movement.replies,
            movement.reposts,
        ).sumOf { it ?: 0L }
        require(engagements >= visibleEngagements) {
            "Row $rowNumber reports $engagements engagements, fewer than its $visibleEngagements visible interaction actions."
        }
    }

    private fun parseCsv(reader: Reader): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        val text = buildString {
            val buffer = CharArray(4_096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(length + count <= MAX_CSV_CHARS) { "The analytics file is too large." }
                append(buffer, 0, count)
            }
        }
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row += field.toString()
                    field.setLength(0)
                }
                !quoted && (char == '\n' || char == '\r') -> {
                    row += field.toString()
                    field.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows += row.toList()
                    row.clear()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "The file contains an unterminated quoted value." }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotEmpty() }) rows += row.toList()
        }
        return rows
    }

}

object XAnalyticsImportPolicy {
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    fun validate(
        imported: List<HistorySample>,
        trusted: List<HistorySample>,
        currentFollowers: Long,
    ): Int {
        require(imported.isNotEmpty()) { "The import contains no follower history." }
        val newest = imported.maxBy { it.timestamp }
        if (abs(newest.followers - currentFollowers) > trendTolerance(0, currentFollowers)) {
            throw AnalyticsValidationException(
                code = "analytics_follower_mismatch",
                expectedFollowers = currentFollowers,
                detectedFollowers = newest.followers,
                message = "The latest follower count does not match this account.",
            )
        }
        val importedByDay = imported.associateBy { it.timestamp }
        val anchors = trusted
            .filter { !it.estimated && (!it.imported || it.sharedImport) && it.followersKnown }
            .filter { importedByDay.containsKey(it.timestamp) }
            .sortedBy { it.timestamp }
        val historical = anchors.filter { it.timestamp < newest.timestamp }
        require(historical.isNotEmpty()) {
            "There is not enough trusted local history to verify this CSV yet."
        }
        anchors.forEach { anchor ->
            val reconstructed = importedByDay.getValue(anchor.timestamp)
            val days = (abs(newest.timestamp - anchor.timestamp).toDouble() / DAY_MILLIS).roundToInt()
            val tolerance = trendTolerance(days, currentFollowers)
            if (abs(anchor.followers - reconstructed.followers) > tolerance) {
                throw AnalyticsValidationException(
                    code = "analytics_trend_mismatch",
                    expectedFollowers = anchor.followers,
                    detectedFollowers = reconstructed.followers,
                    message = "The CSV follower trend differs from trusted local history on ${anchor.dayLabel}.",
                )
            }
        }
        return anchors.size
    }

    fun trendTolerance(days: Int, currentFollowers: Long = 0L): Long =
        maxOf(
            3L,
            ceil(currentFollowers * 0.001).toLong(),
            ceil(maxOf(0, days) / 30.0).toLong() * 2L,
        )
}

class AnalyticsValidationException(
    val code: String,
    val expectedFollowers: Long?,
    val detectedFollowers: Long?,
    message: String,
) : IllegalArgumentException(message)
