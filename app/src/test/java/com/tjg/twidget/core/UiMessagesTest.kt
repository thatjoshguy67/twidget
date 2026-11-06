package com.tjg.twidget.core

import com.tjg.twidget.brief.TestBriefStrings
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class UiMessagesTest {
    private fun render(raw: String, locale: Locale): String {
        val copy = TestBriefStrings(locale)
        return UiMessages.render(raw, locale, "fallback") { key, quantity, args ->
            if (key.plural) copy.quantityText(key.resource, quantity, *args) else copy.text(key.resource, *args)
        }
    }
    @Test fun persistedErrorsRenderInTheCurrentLanguageWithLocalisedArguments() {
        val stored = UiMessages.message(UiMessages.Key.ERROR_TEXT_LIMIT, 25000)
        assertTrue(render(stored, Locale.GERMAN).contains("25.000 Zeichen"))
        assertTrue(render(stored, Locale.FRENCH).contains("caractères"))
        assertTrue(render(stored, Locale.forLanguageTag("es")).contains("caracteres"))
        assertEquals(stored, UiMessages.message(UiMessages.decode(stored).single().key, 25000))
    }
    @Test fun payloadHandlesUnicodeEmptyArgumentsAndDamagedOrUnknownMessages() {
        val payload = UiMessages.message(UiMessages.Key.ERROR_CSV_COLUMN, "a:ñ~\n", "")
        assertEquals(listOf("a:ñ~\n", ""), UiMessages.decode(payload).single().arguments)
        assertEquals("fallback", render("A provider returned English details", Locale.FRENCH))
        assertEquals("fallback", render("~twidget1:UNKNOWN_KEY:a~", Locale.FRENCH))
        assertEquals("fallback", render("~twidget1:ERROR_CSV_COLUMN:a~", Locale.FRENCH))
    }
    @Test fun legacyStoredValidationErrorsAlsoFollowTheCurrentLanguage() {
        assertTrue(render("The scheduled time must be in the future.", Locale.forLanguageTag("es")).contains("futura"))
        assertTrue(render("Text must be 280 characters or fewer.", Locale.GERMAN).contains("280 Zeichen"))
    }

}
