package com.tjg.twidget.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class LocalisationResourcesTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    @Test fun packagedTranslationLanguagesAreRegistered() {
        val locales = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "xml/locales_config.xml")).getElementsByTagName("locale")
        val registered = (0 until locales.length).map {
            (locales.item(it) as Element).getAttribute("android:name").substringBefore('-')
        }.toSet()
        // A single accidental locale entry makes lint require the whole app in
        // that language, even when it is absent from the language pickers.
        res.listFiles { file -> file.isDirectory && file.name.matches(Regex("values-[a-z]{2,3}(?:-r[A-Z]{2})?")) }
            .orEmpty().forEach { folder ->
                val language = folder.name.removePrefix("values-").substringBefore('-')
                val hasTranslations = folder.listFiles { file -> file.extension == "xml" }.orEmpty().any { file ->
                    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                    root.getElementsByTagName("string").length > 0 || root.getElementsByTagName("plurals").length > 0
                }
                if (hasTranslations) assertTrue("Register $language before packaging translations", language in registered)
            }
    }
    private fun entries(language: String): Map<String, Element> = buildMap {
        val folder = File(res, if (language.isEmpty()) "values" else "values-$language")
        folder.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (index in 0 until nodes.length) {
                val item = nodes.item(index) as? Element ?: continue
                if (item.tagName in setOf("string", "plurals") && item.getAttribute("translatable") != "false") {
                    val key = item.getAttribute("name")
                    assertNull("Duplicate $language/$key", put(key, item))
                }
            }
        }
    }
    @Test fun everyRegisteredLanguageHasEveryResourceAndMatchingFormatArguments() {
        val expected = entries("")
        val locales = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "xml/locales_config.xml")).getElementsByTagName("locale")
        for (i in 0 until locales.length) {
            val lang = (locales.item(i) as Element).getAttribute("android:name")
            if (lang == "en") continue
            val actual = entries(lang)
            assertEquals("$lang resources", expected.keys, actual.keys)
            val format = Regex("%(?:[0-9]+\\$)?[a-zA-Z]")
            for ((key, original) in expected) {
                assertEquals("$lang/$key arguments", format.findAll(original.textContent).map { it.value }.toSet(),
                    format.findAll(actual.getValue(key).textContent).map { it.value }.toSet())
                if (original.tagName == "plurals" && lang in setOf("ca", "es", "fr")) {
                    val items = actual.getValue(key).getElementsByTagName("item")
                    val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }
                    assertTrue("$lang/$key requires many and other", quantities.containsAll(listOf("many", "other")))
                }
            }
        }
    }
}
