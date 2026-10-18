package it.ayngallery

import android.app.LocaleManager
import android.os.LocaleList
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
class LocalizationResourcesTest {
    private val folders = linkedMapOf("en" to "values-en", "ru" to "values-ru", "ko" to "values-ko", "zh-Hans" to "values-b+zh+Hans",
        "ja" to "values-ja", "de" to "values-de", "fr" to "values-fr", "pt-BR" to "values-pt-rBR", "es" to "values-es")
    private fun strings(folder: String): Map<String, String> = buildMap {
        File("src/main/res/$folder").listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as org.w3c.dom.Element
                if (node.getAttribute("translatable") == "false") continue
                val key = node.getAttribute("name")
                assertNull("Duplicate $folder/$key", put(key, node.textContent))
            }
        }
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun arguments(text: String) = Regex("(?<!%)%(?:\\d+\\$)?[sdf]").findAll(text).map { it.value }.groupingBy { it }.eachCount()

    @Test fun englishStaysCompleteAndTranslatedKeysPreserveArgumentsAndReviewedSource() {
        val source = strings("values")
        val revisions = JSONObject(File("../localization/revisions.json").readText())
        assertEquals("Record reviewed revisions for new/removed keys", source.keys, revisions.keys().asSequence().toSet())
        assertEquals("English must follow every copy change", source.keys, strings("values-en").keys)
        folders.forEach { (locale, folder) ->
            val translations = strings(folder)
            assertEquals("Every language must follow every copy change: $locale", source.keys, translations.keys)
            source.forEach { (key, text) ->
                if (revisions.getJSONObject(key).getString("source") != hash(text))
                    assertTrue("Translate changed copy $locale/$key", key in translations)
            }
            translations.forEach { (key, text) ->
                assertTrue("Obsolete $locale/$key", key in source)
                assertEquals("Arguments $locale/$key", arguments(source.getValue(key)), arguments(text))
                val previous = revisions.getJSONObject(key)
                assertTrue("Record reviewed translation $locale/$key", previous.has(locale))
                assertFalse("Italian changed without reviewing $locale/$key", previous.getString("source") != hash(source.getValue(key)) && previous.getString(locale) == hash(text))
                assertEquals("Record reviewed source $key", previous.getString("source"), hash(source.getValue(key)))
                assertEquals("Record reviewed translation $locale/$key", previous.getString(locale), hash(text))
            }
        }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/locales_config.xml")).getElementsByTagName("locale")
        val tags = (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name") }.toSet()
        assertEquals(AppLanguage.choices.filter(String::isNotEmpty).toSet(), tags)
    }

    @Test fun everyNewLocaleUsesItsTranslatedResourcesBeforeAndroid13() {
        checkLocales()
    }

    @Test @Config(sdk = [33]) fun everyNewLocalePreservesScriptAndRegionOnAndroid13() {
        checkLocales()
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en-US")
        assertEquals("en", AppLanguage.current(app))
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-CN")
        assertEquals("zh-Hans", AppLanguage.current(app))
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-Hant-TW")
        assertEquals("", AppLanguage.current(app))
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
    }

    private fun checkLocales() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val names = mapOf("ru" to "Язык", "ko" to "언어", "zh-Hans" to "语言", "ja" to "言語", "de" to "Sprache", "fr" to "Langue", "pt-BR" to "Idioma", "es" to "Idioma")
        try {
            names.forEach { (tag, title) ->
                AppLanguage.select(app, tag)
                assertEquals(tag, AppLanguage.current(app))
                assertEquals(title, localized(R.string.language_title, "Lingua"))
                assertNotEquals("Validation messages must be translated too", app.resources.getString(R.string.text_unsupported_audio_recipe_d81e86), localized(R.string.text_unsupported_audio_recipe_d81e86, ""))
            }
        } finally { AppLanguage.select(app, ""); app.onTerminate() }
    }
}
