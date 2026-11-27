package com.tjg.twidget.main

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class OnboardingHandleInputTest {
    @Test fun optionalAndRepeatedPrefixesProduceTheSameLookup() {
        for (input in listOf("Example_Name", "@Example_Name", "@@Example_Name", "  @Example_Name  ")) {
            assertEquals("example_name", OnboardingHandleInput.normalize(input))
            assertTrue(OnboardingHandleInput.isValid(input))
        }
    }

    @Test fun fullLengthHandlesArePreservedWithTheirPrefix() {
        val name = "Abcdefghijklmno"
        assertEquals(15, name.length)
        assertTrue(("@" + name).length < OnboardingHandleInput.MAX_INPUT_LENGTH)
        assertEquals("abcdefghijklmno", OnboardingHandleInput.normalize("@$name"))
        assertTrue(OnboardingHandleInput.isValid("@$name"))
        assertFalse(OnboardingHandleInput.isValid(name + "p"))
    }

    @Test fun comparisonAcceptsCaseVariantsButNeverDifferentAccounts() {
        assertTrue(OnboardingHandleInput.sameAccount("@Example_Name", "example_name"))
        assertFalse(OnboardingHandleInput.sameAccount("example_name2", "example_name"))
        assertFalse(OnboardingHandleInput.sameAccount("", "@"))
        for (input in listOf("", "@", "first last", "name@example.com", "example-name", "éxample")) {
            assertFalse(input, OnboardingHandleInput.isValid(input))
        }
    }

    @Test fun lookupNormalizationDoesNotDependOnThePhoneLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("initial_i", OnboardingHandleInput.normalize("@INITIAL_I"))
        } finally { Locale.setDefault(original) }
    }
}
