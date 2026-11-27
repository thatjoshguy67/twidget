package com.tjg.twidget.widget

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFollowerWordsTest {
    @Test fun spellsCatalanFollowerCounts() {
        val ca = Locale.forLanguageTag("ca-ES")
        for ((value, words) in listOf(0L to "Zero", 1L to "Un", 21L to "Vint-i-un", 31L to "Trenta-un",
            100L to "Cent", 101L to "Cent un", 200L to "Dos-cents", 1234L to "Mil dos-cents trenta-quatre",
            1_000_000L to "Un milió", 2_000_000L to "Dos milions", 1_000_000_000L to "Mil milions",
            1_000_000_000_000L to "Un bilió")) assertEquals(words, TwidgetWidget.followersInWords(value, ca))
        assertTrue(TwidgetWidget.followersInWords(Long.MAX_VALUE, ca).isNotBlank())
    }

    @Test fun spellsDutchFollowerCounts() {
        val dutch = Locale.forLanguageTag("nl-NL")
        val examples = listOf(
            0L to "Nul",
            1L to "Een",
            22L to "Tweeëntwintig",
            93L to "Drieënnegentig",
            101L to "Honderdeen",
            222L to "Tweehonderdtweeëntwintig",
            1_000L to "Duizend",
            3_025L to "Drieduizend vijfentwintig",
            1_234_567L to "Een miljoen tweehonderdvierendertigduizend vijfhonderdzevenenzestig",
            1_000_000_000L to "Een miljard",
            1_000_000_000_000L to "Een biljoen",
            1_000_000_000_000_000L to "Een biljard",
            1_000_000_000_000_000_000L to "Een triljoen",
        )
        for ((value, words) in examples) {
            assertEquals(words, TwidgetWidget.followersInWords(value, dutch))
        }
        assertTrue(TwidgetWidget.followersInWords(Long.MAX_VALUE, dutch).startsWith("Negen triljoen "))
    }

    @Test
    fun spellsFollowerCountsBeyondOneMillion() {
        assertEquals("Zero", TwidgetWidget.followersInWords(0L))
        assertEquals("One Million", TwidgetWidget.followersInWords(1_000_000L))
        assertEquals(
            "Five Million, Nine Thousand, Five Hundred and Forty Five",
            TwidgetWidget.followersInWords(5_009_545L),
        )
        assertEquals(
            "One Billion, Two Hundred and Thirty Four Million, Five Hundred and Sixty Seven Thousand, Eight Hundred and Ninety",
            TwidgetWidget.followersInWords(1_234_567_890L),
        )
    }

    @Test
    fun spellsGermanFollowerCounts() {
        val de = Locale.GERMAN
        assertEquals("Null", TwidgetWidget.followersInWords(0L, de))
        assertEquals("Eine Million", TwidgetWidget.followersInWords(1_000_000L, de))
        assertEquals("Neun Tausend Fünf Hundert Fünf und Vierzig", TwidgetWidget.followersInWords(9_545L, de))
        assertEquals("Zwölf Tausend Drei Hundert Fünf und Vierzig", TwidgetWidget.followersInWords(12_345L, de))
        assertEquals("Ein Hundert Drei und Zwanzig Tausend, Vier Hundert Fünf und Sechzig", TwidgetWidget.followersInWords(123_465L, de))
        assertEquals(
            "Fünf Millionen, Neun Tausend, Fünf Hundert Fünf und Vierzig",
            TwidgetWidget.followersInWords(5_009_545L, de),
        )
        assertEquals(
            "Eine Milliarde, Zwei Hundert Vier und Dreißig Millionen, Fünf Hundert Sieben und Sechzig Tausend, Acht Hundert Neunzig",
            TwidgetWidget.followersInWords(1_234_567_890L, de),
        )
    }

    @Test
    fun spellsFrenchFollowerCounts() {
        val fr = Locale.FRENCH
        assertEquals("Zéro", TwidgetWidget.followersInWords(0L, fr))
        assertEquals("Vingt et Un", TwidgetWidget.followersInWords(21L, fr))
        assertEquals("Soixante et Onze", TwidgetWidget.followersInWords(71L, fr))
        assertEquals("Soixante Dix Sept", TwidgetWidget.followersInWords(77L, fr))
        assertEquals("Quatre Vingts", TwidgetWidget.followersInWords(80L, fr))
        assertEquals("Quatre Vingt Un", TwidgetWidget.followersInWords(81L, fr))
        assertEquals("Quatre Vingt Onze", TwidgetWidget.followersInWords(91L, fr))
        assertEquals("Cent", TwidgetWidget.followersInWords(100L, fr))
        assertEquals("Deux Cents", TwidgetWidget.followersInWords(200L, fr))
        assertEquals("Deux Cent Un", TwidgetWidget.followersInWords(201L, fr))
        assertEquals("Mille", TwidgetWidget.followersInWords(1_000L, fr))
        assertEquals("Sept Mille Six Cent Soixante et Onze", TwidgetWidget.followersInWords(7_671L, fr))
        assertEquals("Quatre Vingt Mille", TwidgetWidget.followersInWords(80_000L, fr))
        assertEquals("Deux Cent Mille", TwidgetWidget.followersInWords(200_000L, fr))
        assertEquals("Un Million", TwidgetWidget.followersInWords(1_000_000L, fr))
        assertEquals("Deux Cents Millions", TwidgetWidget.followersInWords(200_000_000L, fr))
        assertEquals(
            "Cinq Millions Neuf Mille Cinq Cent Quarante Cinq",
            TwidgetWidget.followersInWords(5_009_545L, fr),
        )
        assertEquals(
            "Un Milliard Deux Cent Trente Quatre Millions Cinq Cent Soixante Sept Mille Huit Cent Quatre Vingt Dix",
            TwidgetWidget.followersInWords(1_234_567_890L, fr),
        )
    }
    @Test fun spellsSpanishCountsBeforeMasculineNouns() {
        val es = Locale.forLanguageTag("es")
        val cases = mapOf(0L to "Cero", 1L to "Un", 21L to "Veintiún", 31L to "Treinta y un",
            100L to "Cien", 101L to "Ciento un", 500L to "Quinientos", 700L to "Setecientos",
            1000L to "Mil", 21_000L to "Veintiún mil", 7_671L to "Siete mil seiscientos setenta y un",
            1_000_000L to "Un millón", 21_000_000L to "Veintiún millones", 1_000_000_000L to "Mil millones",
            1_000_000_000_000L to "Un billón", 1_000_000_000_000_000_000L to "Un trillón")
        for ((count, expected) in cases) assertEquals("$count", expected, TwidgetWidget.followersInWords(count, es))
        assertTrue(TwidgetWidget.followersInWords(Long.MAX_VALUE, es).startsWith("Nueve trillones"))
    }
}
