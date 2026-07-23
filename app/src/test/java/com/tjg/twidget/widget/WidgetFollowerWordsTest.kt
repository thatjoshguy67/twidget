package com.tjg.twidget.widget

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
}
