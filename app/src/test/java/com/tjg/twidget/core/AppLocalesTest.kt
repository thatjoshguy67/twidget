package com.tjg.twidget.core

import com.tjg.twidget.data.TwidgetStore
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocalesTest {
    @Test fun unsupportedLanguagesUseEnglishButSupportedRegionsKeepTheirFormatting() {
        assertEquals(Locale.ENGLISH, AppLocales.supportedLocale(Locale.JAPAN))
        assertEquals(Locale.GERMANY, AppLocales.supportedLocale(Locale.GERMANY))
        assertEquals(Locale.FRANCE, AppLocales.supportedLocale(Locale.FRANCE))
        assertEquals(Locale.UK, AppLocales.supportedLocale(Locale.UK))
    }

    @Test fun datePatternsFollowTheLocaleLanguage() {
        assertEquals("de", AppLocales.formatDate(0L, "'de'", "'en'", "'fr'", Locale.GERMANY))
        assertEquals("fr", AppLocales.formatDate(0L, "'de'", "'en'", "'fr'", Locale.FRANCE))
        assertEquals("en", AppLocales.formatDate(0L, "'de'", "'en'", "'fr'", Locale.UK))
        assertEquals("en", AppLocales.formatDate(0L, "'de'", "'en'", "'fr'", Locale.JAPAN))
    }

    @Test fun widgetDeltasFollowTheExplicitWidgetLocale() {
        assertEquals("+1.234", TwidgetStore.signedNumber(1234, Locale.GERMAN))
        assertEquals("-1.234", TwidgetStore.signedNumber(-1234, Locale.GERMAN))
        assertEquals("+1,234", TwidgetStore.signedNumber(1234, Locale.ENGLISH))
    }
}
