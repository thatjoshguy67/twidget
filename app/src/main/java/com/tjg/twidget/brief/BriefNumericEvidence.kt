package com.tjg.twidget.brief

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Formatting and explicit number words may change; a count's value, sign and percentage unit may not. */
internal object BriefNumericEvidence {
    fun accepts(value: String, evidence: String, strings: BriefStrings): Boolean {
        return accepts(value, evidence, strings.locale)
    }

    fun accepts(value: String, evidence: String, locale: Locale): Boolean {
        val allowed = facts(evidence, locale).toSet()
        return facts(value, locale).all { it in allowed }
    }

    /** Only used by the opt-in Brief debug run; never persisted during normal generation. */
    internal fun diagnosticFacts(value: String, strings: BriefStrings): List<String> =
        facts(value, strings.locale)

    private fun facts(value: String, locale: Locale): List<String> {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val grouping = symbols.groupingSeparator
        // French number formatting uses a nonbreaking space; accept its ordinary-space equivalent too.
        val spaceGrouping = grouping.isWhitespace() || grouping == '\u00a0'
        val percentage = "(?:[\\p{Zs}\\t]*%)?"
        val pattern = if (spaceGrouping) Regex("[+-]?\\d+(?:[.,]\\d+)*(?:[ \\u00a0\\u202f]\\d{3}(?!\\d))*$percentage")
            else Regex("[+-]?\\d+(?:[.,]\\d+)*$percentage")
        val digits = pattern.findAll(value).map { match ->
            val percent = match.value.endsWith('%')
            var number = match.value.removeSuffix("%").trim()
            if (spaceGrouping) number = number.replace(Regex("[ \\u00a0\\u202f]"), "")
            else {
                val unsigned = number.removePrefix("+").removePrefix("-")
                val integer = unsigned.substringBefore(symbols.decimalSeparator)
                // Remove grouping only when it has the expected three-digit structure.
                if (integer.contains(grouping)) {
                    val grouped = Regex("\\d{1,3}(?:${Regex.escape(grouping.toString())}\\d{3})+")
                    if (!grouped.matches(integer)) return@map "literal:${match.value}"
                    number = number.replace(grouping.toString(), "")
                }
            }
            if (symbols.decimalSeparator != '.') number = number.replace(symbols.decimalSeparator, '.')
            val canonical = runCatching { BigDecimal(number).stripTrailingZeros().toPlainString() }
                .getOrElse { "literal:${match.value}" }
            canonical + if (percent) "%" else ""
        }.toList()
        return digits + writtenNumberFacts(value, locale)
    }

    /**
     * Brief templates sometimes spell out short durations ("three days"), while the
     * generation contract asks models to use digits ("3 days"). Treat those equivalent
     * values as evidence without accepting a different number.
     */
    private fun writtenNumberFacts(value: String, locale: Locale): List<String> =
        numberWords(locale).flatMap { (word, number) ->
            Regex("(?<![\\p{L}])${Regex.escape(word)}(?![\\p{L}])", RegexOption.IGNORE_CASE)
                .findAll(value)
                .map { number.toString() }
                .toList()
        }

    private fun numberWords(locale: Locale): Map<String, Int> = when (locale.language) {
        "de" -> mapOf(
            "null" to 0, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5,
            "funf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10,
            "elf" to 11, "zwölf" to 12, "zwolf" to 12, "dreizehn" to 13, "vierzehn" to 14,
            "fünfzehn" to 15, "funfzehn" to 15, "sechzehn" to 16, "siebzehn" to 17,
            "achtzehn" to 18, "neunzehn" to 19, "zwanzig" to 20,
        )
        "fr" -> mapOf(
            "zéro" to 0, "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3,
            "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9,
            "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14,
            "quinze" to 15, "seize" to 16, "dix-sept" to 17, "dix-huit" to 18,
            "dix-neuf" to 19, "vingt" to 20,
        )
        "es" -> mapOf(
            "cero" to 0, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
            "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
            "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
            "dieciséis" to 16, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18,
            "diecinueve" to 19, "veinte" to 20,
        )
        "ca" -> mapOf(
            "zero" to 0, "un" to 1, "una" to 1, "dos" to 2, "tres" to 3, "quatre" to 4,
            "cinc" to 5, "sis" to 6, "set" to 7, "vuit" to 8, "nou" to 9, "deu" to 10,
            "onze" to 11, "dotze" to 12, "tretze" to 13, "catorze" to 14, "quinze" to 15,
            "setze" to 16, "disset" to 17, "divuit" to 18, "dinou" to 19, "vint" to 20,
        )
        else -> mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
            "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        )
    }
}

/** Check factual narration, not proposed experiments or the author's suggested tweet. */
internal object BriefReviewNumbers {
    fun accepts(feedback: BriefCoachFeedback, packet: BriefCoachPacket): Boolean =
        BriefReviewMeasurements.accepts(feedback, packet)
}
