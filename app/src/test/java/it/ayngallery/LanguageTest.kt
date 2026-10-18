package it.ayngallery

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "en-rUS-w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { app.onTerminate() }

    @Test fun englishResourcesIncludeEveryMessageAndFormatEveryArgument() {
        assertEquals("DS Gallery", app.applicationInfo.loadLabel(app.packageManager).toString())
        assertEquals("it.ayngallery", app.packageName)
        assertEquals("DS Gallery · Icon Player", app.getString(R.string.widget_full_name))
        assertEquals("DS Gallery · Photo frame", app.getString(R.string.photo_widget_name))
        assertEquals("Open here, single screen", app.getString(R.string.single_shortcut))
        assertEquals(listOf("Photos", "Albums", "Videos", "Music", "Favorites", "Online TV"), MainTabs.map { it.title })
        checkMessages()
    }

    @Test @Config(qualifiers = "it-w360dp-h640dp-port")
    fun italianResourcesKeepTheExistingInterfaceAndFormatting() {
        assertEquals("DS Gallery", app.applicationInfo.loadLabel(app.packageManager).toString())
        assertEquals(listOf("Foto", "Album", "Video", "Musica", "Preferiti", "TV online"), MainTabs.map { it.title })
        assertEquals("DS Gallery · Cornice fotografica", app.getString(R.string.photo_widget_name))
        checkMessages()
    }

    private fun checkMessages() {
        val fields = R.string::class.java.fields.filter { it.name.startsWith("text_") }
        assertTrue(fields.size > 1500)
        fields.forEach { field ->
            val text = app.getString(field.getInt(null))
            val count = Regex("%(\\d+)\\\$s").findAll(text).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
            if (count > 0) {
                val args = Array<Any>(count) { "folder-$it/100%.png" }
                val formatted = localized(field.getInt(null), "fallback", *args)
                assertFalse(field.name, formatted.contains(Regex("%\\d+\\\$s")))
                args.forEach { assertTrue(field.name, formatted.contains(it.toString())) }
            }
        }
        assertTrue(localized(R.string.text_encoding_segment_1_s_a411b2, "", 50).endsWith("50%"))
    }

    @Test fun localeChangesUpdateLabelsWithoutChangingSavedControlIdentifiers() {
        val settings = PadSettings()
        val encoded = settings.encode()
        val theme = GalleryThemes.get("rose_milk")
        assertEquals("Confirm", PadAction.CONFIRM.title)
        assertEquals("Preview", PadContext.VIEWER.title)
        assertEquals("Rose milk", theme.name)
        RuntimeEnvironment.setQualifiers("it-w360dp-h640dp-port")
        assertEquals("Conferma", PadAction.CONFIRM.title)
        assertEquals("Anteprima", PadContext.VIEWER.title)
        assertEquals("Latte e rosa", theme.name)
        assertEquals("Foto", MainTabs.first().title)
        assertEquals(encoded, settings.encode())
        assertEquals("CONFIRM", PadAction.CONFIRM.name)
        assertEquals("rose_milk", theme.id)
    }

    @Test fun englishSettingsAndSourcesFitTheSmallPanelWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val panel = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        try {
            panel.get().windowModel.go("SETTINGS_HUB")
            compose.waitForIdle()
            compose.onNodeWithTag("settings-category:THEME").assertDoesNotExist()
            compose.onNodeWithText("Interface").assertIsDisplayed()
            compose.onNodeWithTag("settings-hub").performScrollToNode(hasTestTag("settings-category:INFO"))
            compose.onNodeWithText("Info and permissions").assertIsDisplayed()
            capture(panel.get().window.decorView, "english-settings-large-text")
            panel.get().windowModel.go("SETTINGS_PERMISSIONS")
            compose.waitForIdle()
            compose.onNodeWithTag("settings-permissions").assertExists()
            compose.onNodeWithTag("settings-permissions").performScrollToNode(hasText("Detecting when you return Home"))
            compose.onNodeWithText("Detecting when you return Home").assertIsDisplayed()
            capture(panel.get().window.decorView, "english-permissions-large-text")
            panel.get().windowModel.go("SOURCES")
            compose.waitForIdle()
            compose.onNodeWithText("Your roots").assertIsDisplayed()
            compose.onNodeWithText("Add roots").assertIsDisplayed()
            compose.onNodeWithText("No folders yet").assertIsDisplayed()
            capture(panel.get().window.decorView, "english-sources-large-text")
        } finally { panel.pause().stop().destroy() }
    }

    @Test fun englishPhotoWidgetConfigurationUsesTheActualHostActivity() {
        val manager = AppWidgetManager.getInstance(app)
        val host = shadowOf(manager)
        val id = host.createWidget(PhotoWidgetProvider::class.java, R.layout.photo_widget)
        host.putWidgetInfo(id, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, PhotoWidgetProvider::class.java); initialLayout = R.layout.photo_widget
        })
        val activity = Robolectric.buildActivity(PhotoWidgetConfigActivity::class.java,
            Intent(app, PhotoWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)).setup().visible()
        try {
            compose.waitForIdle()
            compose.onNodeWithText("Photo widget").assertIsDisplayed()
            compose.onNodeWithText("Single photo").assertIsDisplayed()
            compose.onNodeWithText("Album").assertIsDisplayed()
            compose.onNodeWithTag("photo-config-save").assertIsNotEnabled()
            capture(activity.get().window.decorView, "english-photo-widget-config")
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "it-w360dp-h640dp-port")
    fun languageDraftWaitsForSaveSurvivesRotationAndUpdatesTheRealSettingsPage() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized } }
        }
        app.preferences.galleryView(true, grid = true, columns = 6)
        val preferences = app.preferences.state.value
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val panel = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val display = app.session.state
        try {
            app.session.openSettings(panel.get())
            compose.waitForIdle()
            compose.onNodeWithTag("settings-category:INTERFACE").performClick()
            compose.onNodeWithTag("settings-category:LANGUAGE").performClick()
            compose.onNodeWithTag("language-save").assertIsNotEnabled()
            compose.onNodeWithTag("language:en").performClick()
            assertEquals("", AppLanguage.current(app))
            panel.recreate(); compose.waitForIdle()
            compose.onNodeWithTag("language:en").assertIsSelected()
            compose.onNodeWithTag("language-save").performClick()
            compose.waitForIdle()
            assertEquals("en", AppLanguage.current(app))
            compose.onNodeWithText("Language").assertIsDisplayed()
            assertEquals("Photos", MainTabs.first().title)
            assertEquals("en", app.getSharedPreferences("ui_preferences_v1", 0).getString("app_language", null))
            assertEquals(preferences, app.preferences.state.value)
            assertEquals(6, app.preferences.albumColumns.value)
            assertEquals(display, app.session.state)
            assertNull(shadowOf(panel.get()).nextStartedActivity)
            capture(panel.get().window.decorView, "language-setting-english")
            compose.onNodeWithTag("language:system").performClick()
            compose.onNodeWithTag("language-save").performClick()
            compose.waitForIdle()
            assertEquals("", AppLanguage.current(app))
            compose.onNodeWithText("Lingua").assertIsDisplayed()
            assertEquals("Foto", MainTabs.first().title)
        } finally { panel.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "it-w360dp-h640dp-port")
    fun aboutShowsInstalledPackageVersionAndAuthorFromTheSettingsHub() {
        RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val panel = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        try {
            app.session.openSettings(panel.get())
            compose.waitForIdle()
            compose.onNodeWithTag("settings-hub").performScrollToNode(hasTestTag("settings-category:INFO"))
            compose.onNodeWithTag("settings-category:INFO").performClick()
            compose.onNodeWithText("DS Gallery").assertIsDisplayed()
            compose.onNodeWithText("Realizzato da Syllabuss").assertIsDisplayed()
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            compose.onNodeWithText(packageInfo.versionName!!, substring = true).assertIsDisplayed()
            compose.onNodeWithTag("info-support").assertIsDisplayed()
            compose.onNodeWithText("Build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo)} · ${packageInfo.versionName}", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("settings-save").assertDoesNotExist()
            capture(panel.get().window.decorView, "app-info-large-text")
        } finally { panel.pause().stop().destroy() }
    }

    private fun capture(view: android.view.View, name: String) {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/$name.png").absoluteFile
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
