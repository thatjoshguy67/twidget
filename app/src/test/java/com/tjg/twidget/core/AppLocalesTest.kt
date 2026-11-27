package com.tjg.twidget.core

import com.tjg.twidget.data.TwidgetStore
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocalesTest {
    @Test fun catalanCompactThousandsUseKWithoutChangingOtherUnitsOrLanguages() {
        val ca = Locale.forLanguageTag("ca-ES")
        for (space in listOf("", " ", "\u00a0", "\u202f")) {
            assertEquals("17,5k", AppLocales.compactLabel("17,5${space}mil", ca))
            assertEquals("-17,5k", AppLocales.compactLabel("-17,5${space}mil", ca))
        }
        for (value in listOf("999", "1,25 M", "1 mil M", "1 bilió")) {
            assertEquals(value, AppLocales.compactLabel(value, ca))
        }
        assertEquals("17,5 mil", AppLocales.compactLabel("17,5 mil", Locale.forLanguageTag("es-ES")))
    }

    @Test fun catalanIsSupportedAndInheritsTheDeviceRegion() {
        val ca = Locale.forLanguageTag("ca-ES")
        assertEquals(ca, AppLocales.supportedLocale(ca))
        assertEquals(ca, AppLocales.regionalLocale(Locale.forLanguageTag("ca"), Locale.forLanguageTag("es-ES")))
        assertEquals(ca, AppLocales.resolve("ca-ES"))
    }

    @Test fun dutchIsSupportedForAppFormattingAndExplicitWidgetLanguages() {
        val dutch = Locale.forLanguageTag("nl-NL")
        val belgianDutch = Locale.forLanguageTag("nl-BE")
        assertEquals(dutch, AppLocales.supportedLocale(dutch))
        assertEquals(belgianDutch, AppLocales.resolve("nl-BE"))
        assertEquals("12.345", AppLocales.integer(12_345, dutch))
    }

    @Test fun unsupportedLanguagesUseEnglishButSupportedRegionsKeepTheirFormatting() {
        assertEquals(Locale.forLanguageTag("en-JP"), AppLocales.supportedLocale(Locale.JAPAN))
        assertEquals(Locale.GERMANY, AppLocales.supportedLocale(Locale.GERMANY))
        assertEquals(Locale.FRANCE, AppLocales.supportedLocale(Locale.FRANCE))
        assertEquals(Locale.UK, AppLocales.supportedLocale(Locale.UK))
        assertEquals(Locale.forLanguageTag("es-ES"), AppLocales.supportedLocale(Locale.forLanguageTag("es-ES")))
        assertEquals(Locale.forLanguageTag("es-MX"), AppLocales.supportedLocale(Locale.forLanguageTag("es-MX")))
    }

    @Test fun languageOnlyChoicesInheritRegionAndNumberingPreferences() {
        assertEquals(Locale.forLanguageTag("es-MX"), AppLocales.regionalLocale(Locale.forLanguageTag("es"), Locale.forLanguageTag("es-MX")))
        assertEquals(Locale.forLanguageTag("es-ES"), AppLocales.regionalLocale(Locale.forLanguageTag("es"), Locale.forLanguageTag("en-ES")))
        assertEquals(Locale.UK, AppLocales.regionalLocale(Locale.ENGLISH, Locale.UK))
        assertEquals(Locale.forLanguageTag("fr-CA"), AppLocales.regionalLocale(Locale.FRENCH, Locale.CANADA))
        assertEquals(Locale.forLanguageTag("en-IN-u-nu-deva"), AppLocales.regionalLocale(Locale.ENGLISH, Locale.forLanguageTag("hi-IN-u-nu-deva")))
    }

    @Test fun explicitRegionsTakePrecedenceAndUnsupportedLanguagesKeepTheDeviceRegion() {
        assertEquals(Locale.forLanguageTag("es-MX"), AppLocales.regionalLocale(Locale.forLanguageTag("es-MX"), Locale.forLanguageTag("es-ES")))
        assertEquals(Locale.forLanguageTag("en-JP"), AppLocales.regionalLocale(AppLocales.supportedLocale(Locale.JAPAN), Locale.UK))
    }

    @Test fun calendarsUseTheRegionsFirstWeekday() {
        assertEquals(java.time.DayOfWeek.SUNDAY, AppLocales.firstDayOfWeek(Locale.US))
        assertEquals(java.time.DayOfWeek.MONDAY, AppLocales.firstDayOfWeek(Locale.UK))
        assertEquals(java.time.DayOfWeek.SUNDAY, AppLocales.firstDayOfWeek(Locale.forLanguageTag("es-MX")))
        assertEquals(java.time.DayOfWeek.MONDAY, AppLocales.firstDayOfWeek(Locale.forLanguageTag("es-ES")))
    }
}
