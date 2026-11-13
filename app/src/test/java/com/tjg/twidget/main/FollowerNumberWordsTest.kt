package com.tjg.twidget.main

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowerNumberWordsTest {
    @Test fun spellsWholeCountsWithoutDroppingGroups() {
        assertEquals("seven thousand, six hundred and seventy one", followerNumberWords(7671))
        assertEquals("eight thousand, fifty one", followerNumberWords(8051))
        assertEquals("zero", followerNumberWords(0))
        assertEquals("one million, one", followerNumberWords(1_000_001))
        assertEquals("nine quintillion, two hundred and twenty three quadrillion, three hundred and seventy two trillion, " +
            "thirty six billion, eight hundred and fifty four million, seven hundred and seventy five thousand, " +
            "eight hundred and seven", followerNumberWords(Long.MAX_VALUE))
    }
}
