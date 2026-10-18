package it.ayngallery

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MusicWidgetConfigTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun release() { app.onTerminate() }
    @Test fun trackSearchSelectsOnlyThisWidgetsStartingSong() {
        val documents = OperationDocuments()
        documents.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "fixture"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("fixture", documents)
        for (key in listOf("one", "two")) {
            val file = File(app.filesDir, "$key.mp3").apply { writeBytes(ByteArray(100)) }
            documents.nodes[key] = OperationDocuments.Node(key, "root", "$key.mp3", "audio/mpeg", file)
        }
        val source = SourceEntity("music", "content://fixture/tree/root", "Music", "MUSIC")
        app.contentResolver.takePersistableUriPermission(android.net.Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val songs = listOf(
            MediaEntity("one", "fixture:one", "content://fixture/one", "album", "one.mp3", "audio/mpeg", "AUDIO", 100, 1, duration = 1000L, title = "First song", artist = "Other"),
            MediaEntity("two", "fixture:two", "content://fixture/two", "album", "two.mp3", "audio/mpeg", "AUDIO", 100, 1, duration = 1000L, title = "Desired song", artist = "Accurate artist"))
        runBlocking {
            app.catalog.dao.saveSource(source); app.catalog.dao.saveMedia(songs)
            app.catalog.dao.saveMembership(songs.map { MediaSource(it.id, source.id, 1) })
        }
        val host = shadowOf(AppWidgetManager.getInstance(app))
        val id = host.createWidget(MusicFullWidget::class.java, R.layout.music_widget_artwork)
        host.putWidgetInfo(id, AppWidgetProviderInfo().apply { provider = ComponentName(app, MusicFullWidget::class.java) })
        val intent = Intent(app, MusicWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); app.catalog.snapshot.value.visibleMedia.count { app.catalog.snapshot.value.usable(it) } == 2 }
        val controller = Robolectric.buildActivity(MusicWidgetConfigActivity::class.java, intent).setup().visible()
        compose.waitForIdle()
        compose.onNodeWithTag("widget-start:true").performScrollTo().performClick()
        compose.onNodeWithTag("widget-track:two").assertExists()
        compose.onNodeWithTag("widget-track-search").performTextInput("accurate desired")
        compose.onNodeWithTag("widget-track:one").assertDoesNotExist()
        compose.onNodeWithContentDescription("Vista griglia").performClick()
        compose.onNodeWithTag("widget-track:two").performClick()
        assertNull(app.widgets.settings.get(id).initialTrack)
        compose.onNodeWithContentDescription("Salva").performClick()
        compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); controller.get().isFinishing }
        assertEquals("two", app.widgets.settings.get(id).initialTrack)
        assertNull(app.widgets.settings.get(id + 100).initialTrack)
        controller.pause().stop().destroy()
    }
    @Test fun barsUseArtworkColorsWithoutOpeningAPickerAndAppearanceIsAutomatic() {
        val host = shadowOf(AppWidgetManager.getInstance(app))
        val id = host.createWidget(MusicFullWidget::class.java, R.layout.music_widget_artwork)
        host.putWidgetInfo(id, AppWidgetProviderInfo().apply { provider = ComponentName(app, MusicFullWidget::class.java) })
        val controller = Robolectric.buildActivity(MusicWidgetConfigActivity::class.java,
            Intent(app, MusicWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)).setup().visible()
        compose.onNodeWithTag("widget-effect:BARS").performScrollTo().performClick()
        compose.onNodeWithTag("widget-bars-brightness").assertDoesNotExist()
        compose.onNodeWithTag("widget-appearance:LIGHT").assertDoesNotExist()
        compose.onNodeWithContentDescription("Salva").performClick()
        compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); controller.get().isFinishing }
        assertNull(app.widgets.settings.get(id).barsColor)
        assertEquals("BARS", app.widgets.settings.get(id).effect)
        assertEquals("SYSTEM", app.widgets.settings.get(id).background)
        controller.pause().stop().destroy()
    }
    @Test fun draftSurvivesRotationButOnlySaveChangesThisWidget() {
        RuntimeEnvironment.setFontScale(1.4f)
        runBlocking { app.preferences.save(GalleryPreferences(themeId = GalleryThemes.WHITE)) }
        val host = shadowOf(AppWidgetManager.getInstance(app))
        val id = host.createWidget(MusicMinimalWidget::class.java, R.layout.music_widget_minimal)
        host.putWidgetInfo(id, AppWidgetProviderInfo().apply { provider = ComponentName(app, MusicMinimalWidget::class.java) })
        val intent = Intent(app, MusicWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val controller = Robolectric.buildActivity(MusicWidgetConfigActivity::class.java, intent).setup().visible()
        compose.waitForIdle()
        compose.onNodeWithTag("widget-effect:COIN").performScrollTo().performClick()
        assertEquals("SYSTEM", app.widgets.settings.get(id).background)
        controller.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("widget-effect:COIN").assertIsSelected()
        compose.onNodeWithContentDescription("Salva").assertIsDisplayed().assertIsEnabled()
        val view = controller.get().window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/widget-config-large-text.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        compose.onNodeWithContentDescription("Annulla").performClick()
        compose.onNodeWithText("Modifiche non salvate").assertIsDisplayed()
        compose.onNodeWithText("Continua a modificare").performClick()
        assertFalse(controller.get().isFinishing)
        compose.onNodeWithContentDescription("Annulla").performClick()
        compose.onNodeWithText("Esci senza salvare").performClick()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
        assertEquals("SYSTEM", app.widgets.settings.get(id).background)
        controller.pause().stop().destroy()
        val reopened = Robolectric.buildActivity(MusicWidgetConfigActivity::class.java, intent).setup().visible()
        compose.waitForIdle()
        compose.onNodeWithTag("widget-config-list").performScrollToNode(hasTestTag("widget-start:false"))
        compose.onNodeWithTag("widget-start:false").assertIsSelected()
        compose.onNodeWithTag("widget-effect:COIN").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Salva").performClick()
        compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); reopened.get().isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(reopened.get()).resultCode)
        assertEquals(id, shadowOf(reopened.get()).resultIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals("COIN", app.widgets.settings.get(id).effect); assertEquals("SYSTEM", app.widgets.settings.get(id).background); assertNull(app.widgets.settings.get(id).color)
        assertEquals(MusicWidgetConfig(), app.widgets.settings.get(id + 100))
        assertNull(shadowOf(reopened.get()).nextStartedActivity)
        reopened.pause().stop().destroy()
    }
}
