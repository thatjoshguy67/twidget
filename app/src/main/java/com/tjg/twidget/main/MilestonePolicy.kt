package com.tjg.twidget.main

import java.util.Locale

data class MilestoneSettings(
    val target: Long? = null,
    val labelRaw: String = "",
    val showPercent: Boolean = true,
)

data class MilestoneCardSpec(
    val label: String,
    val value: String,
    val detail: String,
    val progress: Int?,
)

data class MilestoneInput(
    val target: Long?,
    val labelRaw: String,
    val valid: Boolean,
)

object MilestonePolicy {
    const val MAX_TARGET = 999_999_999L

    fun isTargetAboveFollowers(target: Long, followersCount: Long, followersKnown: Boolean): Boolean =
        !followersKnown || target >= followersCount

    fun parseInput(raw: String): MilestoneInput {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return MilestoneInput(null, "", valid = true)
        val digitsOnly = trimmed.replace(",", "").replace(" ", "")
        if (digitsOnly.isNotEmpty() && digitsOnly.all { it.isDigit() }) {
            val value = digitsOnly.toLongOrNull()
            return when {
                value == null || value <= 0L || value > MAX_TARGET ->
                    MilestoneInput(null, trimmed, valid = false)
                else -> MilestoneInput(value, trimmed, valid = true)
            }
        }
        val parsed = parseWords(trimmed)
        return if (parsed == null || parsed <= 0L || parsed > MAX_TARGET) {
            MilestoneInput(null, trimmed, valid = false)
        } else {
            MilestoneInput(parsed, trimmed, valid = true)
        }
    }

    fun formatDisplay(
        target: Long,
        labelRaw: String,
        compactNumber: (Long) -> String,
    ): String = if (labelRaw.any { it.isLetter() }) {
        wordsFor(target) ?: compactNumber(target)
    } else {
        compactNumber(target)
    }

    fun resolveCardSpec(
        followersCount: Long,
        followersKnown: Boolean,
        settings: MilestoneSettings?,
        autoNextMilestone: (Long) -> Long,
        autoPreviousMilestone: (Long) -> Long,
        compactNumber: (Long) -> String,
        goalReachedText: String,
        unknownFollowersText: String,
        toNextMilestone: (remaining: String, target: String) -> String,
        milestoneLabel: String,
    ): MilestoneCardSpec {
        val customTarget = settings?.target?.takeIf { it > 0L }
        if (customTarget != null) {
            val remaining = (customTarget - followersCount).coerceAtLeast(0L)
            val progress = if (followersKnown) {
                ((followersCount.coerceAtMost(customTarget) * 100) / customTarget).toInt().coerceIn(0, 100)
            } else {
                0
            }
            val display = formatDisplay(
                customTarget,
                settings.labelRaw,
                compactNumber,
            )
            val detail = when {
                !followersKnown -> unknownFollowersText
                remaining == 0L -> goalReachedText
                else -> toNextMilestone(compactNumber(remaining), display)
            }
            return MilestoneCardSpec(
                milestoneLabel,
                display,
                detail,
                progress.takeIf { settings.showPercent },
            )
        }

        val milestone = autoNextMilestone(followersCount)
        val previous = autoPreviousMilestone(milestone)
        val remaining = (milestone - followersCount).coerceAtLeast(0L)
        val progress = if (milestone == previous) {
            100
        } else {
            (((followersCount - previous).coerceAtLeast(0L) * 100) / (milestone - previous)).toInt()
        }
        val showPercent = settings?.showPercent != false
        return MilestoneCardSpec(
            label = milestoneLabel,
            value = compactNumber(milestone),
            detail = toNextMilestone(compactNumber(remaining), compactNumber(milestone)),
            progress = progress.takeIf { showPercent },
        )
    }

    internal fun parseWords(raw: String): Long? {
        val tokens = raw.lowercase(Locale.US)
            .replace("-", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it != "and" }
        if (tokens.isEmpty()) return null

        var total = 0L
        var current = 0L
        for (token in tokens) {
            when (token) {
                "hundred" -> {
                    if (current == 0L) current = 1L
                    current *= 100L
                }
                "thousand" -> {
                    if (current == 0L) current = 1L
                    total += current * 1_000L
                    current = 0L
                }
                "million" -> {
                    if (current == 0L) current = 1L
                    total += current * 1_000_000L
                    current = 0L
                }
                else -> {
                    val value = wordValue(token) ?: return null
                    current += value
                }
            }
        }
        total += current
        return total.takeIf { it > 0L }
    }

    internal fun wordsFor(value: Long): String? {
        if (value <= 0L || value > MAX_TARGET) return null
        val parts = mutableListOf<String>()
        var remaining = value
        for ((scale, name) in listOf(1_000_000L to "million", 1_000L to "thousand")) {
            val chunk = remaining / scale
            if (chunk > 0L) {
                parts += chunkWords(chunk)
                parts += name
                remaining %= scale
            }
        }
        if (remaining > 0L) parts += chunkWords(remaining)
        return parts.joinToString(" ") { word ->
            word.split(' ').joinToString(" ") { piece ->
                piece.replaceFirstChar { it.titlecase(Locale.US) }
            }
        }
    }

    private fun chunkWords(value: Long): String {
        if (value < 100L) return underHundred(value.toInt())
        val hundreds = (value / 100).toInt()
        val rest = (value % 100).toInt()
        return buildString {
            append(underHundred(hundreds))
            append(" hundred")
            if (rest > 0) {
                append(" ")
                append(underHundred(rest))
            }
        }
    }

    private fun underHundred(value: Int): String = when {
        value < 20 -> ones[value]
        value < 100 -> {
            val tensPart = tens.getValue(value / 10)
            val onesPart = value % 10
            if (onesPart == 0) tensPart else "$tensPart-${ones[onesPart]}"
        }
        else -> error("out of range")
    }

    private fun wordValue(token: String): Long? = when (token) {
        "zero" -> 0L
        in ones.indices.map { ones[it] } -> ones.indexOf(token).toLong()
        in tens.values -> tens.entries.first { it.value == token }.key.toLong() * 10L
        else -> null
    }

    private val ones = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )

    private val tens = mapOf(
        2 to "twenty",
        3 to "thirty",
        4 to "forty",
        5 to "fifty",
        6 to "sixty",
        7 to "seventy",
        8 to "eighty",
        9 to "ninety",
    )
}
