package com.tjg.twidget.brief

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BriefNumericEvidenceTest {
    @Test fun equivalentGroupingAndExplicitPositiveSignsPreserveTheObservedValue() {
        val strings = TestBriefStrings()
        assertTrue(BriefNumericEvidence.accepts("You have 1,200 followers, up 2 today.", "1200 followers, +2 today", strings))
        assertTrue(BriefNumericEvidence.accepts("Progress: 80.0%", "80%", strings))
        assertFalse(BriefNumericEvidence.accepts("You have 1,201 followers.", "1200 followers", strings))
        assertFalse(BriefNumericEvidence.accepts("Your progress is 80.", "80%", strings))
        assertFalse(BriefNumericEvidence.accepts("Your change is 2.", "-2", strings))
        assertFalse(BriefNumericEvidence.accepts("You have 1.2 followers.", "1200", strings))
        assertFalse(BriefNumericEvidence.accepts("You have 12,00 followers.", "1200", strings))
    }

    @Test fun decimalAndGroupingSeparatorsFollowTheAppLanguage() {
        val german = TestBriefStrings(Locale.GERMAN)
        assertTrue(BriefNumericEvidence.accepts("1.200 Follower, 3,5%", "1200 Follower, 3,50%", german))
        assertFalse(BriefNumericEvidence.accepts("3,5%", "35%", german))
        val french = TestBriefStrings(Locale.FRENCH)
        assertTrue(BriefNumericEvidence.accepts("1 200 abonnés", "1\u202f200 abonnés", french))
        assertFalse(BriefNumericEvidence.accepts("1 201 abonnés", "1200 abonnés", french))
    }

    @Test fun shortWrittenNumbersInSourceAllowTheirDigitEquivalent() {
        listOf(
            Triple(Locale.US, "more than three days", "more than 3 days"),
            Triple(Locale.GERMAN, "mehr als drei Tage", "mehr als 3 Tage"),
            Triple(Locale.FRENCH, "plus de trois jours", "plus de 3 jours"),
            Triple(Locale.forLanguageTag("es"), "más de tres días", "más de 3 días"),
            Triple(Locale.forLanguageTag("ca"), "més de tres dies", "més de 3 dies"),
        ).forEach { (locale, evidence, rendered) ->
            val strings = TestBriefStrings(locale)
            assertTrue("$locale should accept its equivalent digit", BriefNumericEvidence.accepts(rendered, evidence, strings))
            assertFalse("$locale must still reject an invented digit", BriefNumericEvidence.accepts(rendered.replace("3", "4"), evidence, strings))
        }
    }
    @Test fun compoundNumbersMustNotAuthorizeTheirComponents() {
        listOf(
            Triple(Locale.US, "twenty-three days", "3 days"),
            Triple(Locale.US, "twenty-three days", "20 days"),
            Triple(Locale.US, "three hundred days", "3 days"),
            Triple(Locale.FRENCH, "dix-sept jours", "7 jours"),
            Triple(Locale.FRENCH, "vingt et trois jours", "3 jours"),
            Triple(Locale.forLanguageTag("es"), "veinte y tres días", "3 días"),
            Triple(Locale.forLanguageTag("ca"), "vint-i-tres dies", "3 dies"),
        ).forEach { (locale, evidence, rendered) ->
            assertFalse(evidence, BriefNumericEvidence.accepts(rendered, evidence, locale))
        }
        assertTrue(BriefNumericEvidence.accepts("17 jours", "dix-sept jours", Locale.FRENCH))
        assertFalse(BriefNumericEvidence.accepts("twenty-three days", "3 days", Locale.US))
    }
}
