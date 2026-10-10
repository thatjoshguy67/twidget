package com.tjg.twidget.widget

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetFollowerWordsTest {
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
        assertEquals(
            "Fünf Millionen, Neun Tausend, Fünf Hundert und Fünf und Vierzig",
            TwidgetWidget.followersInWords(5_009_545L, de),
        )
        assertEquals(
            "Eine Milliarde, Zwei Hundert und Vier und Dreißig Millionen, Fünf Hundert und Sieben und Sechzig Tausend, Acht Hundert und Neunzig",
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
}
