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
}
