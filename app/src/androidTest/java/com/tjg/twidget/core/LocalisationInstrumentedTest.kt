package com.tjg.twidget.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LocalisationInstrumentedTest {
    private fun context(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(config)
    }
    @Test fun storedFailuresAndQuantityMessagesRenderWithEachContextsLanguage() {
        for (tag in listOf("ca", "en", "de", "fr", "es")) {
            val c = context(tag)
            val stored = UiMessages.message(UiMessages.Key.ERROR_TEXT_LIMIT, 25000)
            val count = AppLocales.integer(25000, AppLocales.formattingLocale(c))
            assertEquals(c.getString(R.string.l10n_error_text_limit, count), UiMessages.resolve(c, stored))

            assertFalse(AppLocales.dateTime(c, 1792843200000).contains(" at "))
        }
    }
    @Test fun catalanResourcesAndBriefUseTheRegisteredLocale() {
        val c = AppLocales.wrap(context("ca-ES"), "ca-ES")
        assertEquals("Català", c.getString(R.string.language_catalan))
        assertEquals("Objectius del compte", c.getString(R.string.milestone_account_goals))
        assertEquals("Seguidor", c.resources.getQuantityString(R.plurals.widget_count_followers, 1))
        assertEquals("Seguidors", c.resources.getQuantityString(R.plurals.widget_count_followers, 2))
        assertEquals("de seguidors", c.getString(R.string.l10n_widget_followers_of))
        val strings = com.tjg.twidget.brief.BriefStrings.from(c, "ca-ES")
        assertEquals("ca-ES", strings.languageTag)
        assertEquals("Valor actual", strings.text(R.string.brief_goal_current_value))
    }

    @Test fun spanishArtworkAndGoalNounsHaveSingularPluralAndMillionForms() {
        val c = context("es")
        assertEquals("Seguidor", c.resources.getQuantityString(R.plurals.widget_count_followers, 1))
        assertEquals("Seguidores", c.resources.getQuantityString(R.plurals.widget_count_followers, 2))
        assertEquals("seguidor verificado", c.resources.getQuantityString(R.plurals.goal_noun_verified, 1))
        assertEquals("seguidores verificados", c.resources.getQuantityString(R.plurals.goal_noun_verified, 2))
        assertEquals("de seguidores", c.getString(R.string.l10n_widget_followers_of))
    }
}
