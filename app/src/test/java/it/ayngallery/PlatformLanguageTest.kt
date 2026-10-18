package it.ayngallery

import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestGalleryApp::class, qualifiers = "it-w360dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PlatformLanguageTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { app.onTerminate() }
    @Test
    fun androidThirteenUsesThePlatformAppLocaleWithoutWritingALegacyOverride() {
        val manager = app.getSystemService(android.app.LocaleManager::class.java)
        val preferences = app.preferences.state.value
        AppLanguage.select(app, "en")
        assertEquals("en", manager.applicationLocales.toLanguageTags())
        assertEquals("English", java.util.Locale.forLanguageTag(AppLanguage.current(app)).getDisplayLanguage(java.util.Locale.ENGLISH))
        assertEquals("Photos", MainTabs.first().title)
        assertFalse(app.getSharedPreferences("ui_preferences_v1", 0).contains("app_language"))
        AppLanguage.select(app, "it")
        assertEquals("Foto", MainTabs.first().title)
        AppLanguage.select(app, "")
        assertTrue(manager.applicationLocales.isEmpty)
        assertEquals(preferences, app.preferences.state.value)
    }

}
