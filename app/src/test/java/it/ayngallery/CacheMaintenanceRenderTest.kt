package it.ayngallery

import androidx.activity.compose.setContent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CacheMaintenanceRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var file: File
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun pump(done: () -> Boolean) = compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() }
    private fun start(game: Boolean = false) {
        RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        runBlocking {
            app.preferences.save(GalleryPreferences(themeId = "ayn_purple", reducedMotion = true, gameMode = game))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized } }
        }
        file = File(app.cacheDir, "artwork/qa.art").also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(96)) }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        panel.windowModel.go("TRASH"); compose.waitForIdle()
        pump { compose.onAllNodesWithTag("cache-clear").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun maintenanceAllowsResetAfterFailedTransferAndReportsCacheWithToast() {
        start()
        val preferences = app.preferences.state.value
        panel.setContent { GalleryDesign(preferences) { AppMaintenanceActions(app) } }
        compose.waitForIdle()
        runBlocking {
            app.catalog.dao.saveOperation(FileOperation("old-transfer", "old-file", "NETWORK_COPY", "source", "source", "album",
                "test.txt", "text/plain", 1, 0, null, null, "test.txt", true, "result", phase = "FAILED"))
        }
        compose.waitUntil(5000) { app.files.operations.value.any { it.id == "old-transfer" } }
        compose.waitForIdle()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("info-app-reset").assertIsEnabled(); true }.getOrDefault(false) }
        app.explorer.transfers.running.value = true; compose.waitForIdle()
        compose.onNodeWithTag("info-app-reset").assertIsNotEnabled()
        app.explorer.transfers.running.value = false; compose.waitForIdle()
        compose.onNodeWithTag("info-app-reset").assertIsEnabled()
        org.robolectric.shadows.ShadowToast.reset()
        compose.onNodeWithTag("info-cache-clear").performClick()
        pump { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() != null }
        assertEquals(app.getString(R.string.text_cache_cleared_3a29ae), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        compose.onNodeWithTag("info-cache-result").assertDoesNotExist()
    }
    @Test fun directCleanupWithLargeTextPreservesUserFilesAndPreferences() {
        start(); val preferences = app.preferences.state.value; val policy = app.session.state
        val original = File(app.filesDir, "personal-qa.txt").also { it.writeText("original") }
        compose.onNodeWithTag("startup-dual").assertDoesNotExist()
        compose.onNodeWithTag("cache-clear").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Aggiorna dimensioni").assertDoesNotExist()
        compose.onNodeWithText("Aggiorna l'indice delle cartelle").assertDoesNotExist()
        compose.onNodeWithTag("cache-clear").performClick()
        pump { !app.cache.status.value.busy && !file.exists() }
        compose.onNodeWithTag("cache-result").assertDoesNotExist()
        pump { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == "Cache pulita" }
        assertEquals("original", original.readText())
        assertEquals(1L, app.catalog.cacheRevision.value); assertEquals(preferences, app.preferences.state.value); assertEquals(policy, app.session.state)
        activity!!.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("cache-clear").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun explicitCleanupInGameModeDoesNotDisableTheModeOrChangeTheme() {
        start(game = true)
        compose.onNodeWithTag("cache-clear").performClick()
        pump { !app.cache.status.value.busy && !file.exists() }
        assertTrue(app.preferences.state.value.gameMode); assertEquals("ayn_purple", app.preferences.state.value.themeId)
    }
    @Test fun changingArtworkOnTheSameTrackNeverReusesPixelsFromThePreviousUri() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val provider = PreviewFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.cache-art"; exported = false })
        ShadowContentResolver.registerProviderInternal("ayngallery.cache-art", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.cache-art", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val first = MediaEntity("a", "cache:a", DocumentsContract.buildDocumentUriUsingTree(tree, "a").toString(), "album", "First.png", "image/png", "IMAGE", 20, 1)
        val second = first.copy(id = "b", documentKey = "cache:b", uri = DocumentsContract.buildDocumentUriUsingTree(tree, "b").toString(), name = "Second.png")
        val song = first.copy(id = "song", documentKey = "cache:song", uri = DocumentsContract.buildDocumentUriUsingTree(tree, "song").toString(), name = "Song.wav", mime = "audio/wav", kind = "AUDIO", musicAlbum = "Artwork fixture")
        listOf("a" to Color.RED, "b" to Color.BLUE).forEach { (id, color) ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
            File(app.cacheDir, "preview-$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "Fixture", "MIXED"))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("album", "cache:album", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "Fixture", "Fixture", null)))
            app.catalog.dao.saveMedia(listOf(first, second, song)); app.catalog.dao.saveMembership(listOf(first, second, song).map { MediaSource(it.id, "source", 1) })
            app.catalog.setMusicArtwork(musicAlbumTarget(song), first.id, null)
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        panel.windowModel.musicMode("ALBUMS")
        fun shows(color: Int): Boolean {
            val node = compose.onAllNodesWithContentDescription(song.name, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull() ?: return false
            val point = node.boundsInRoot.center
            val view = panel.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            return try { view.draw(Canvas(bitmap)); bitmap.getPixel(point.x.toInt(), point.y.toInt()) == color } finally { bitmap.recycle() }
        }
        pump { shows(Color.RED) }
        runBlocking { app.catalog.setMusicArtwork(musicAlbumTarget(song), second.id, null) }
        pump { shows(Color.BLUE) }
        val changed = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888); changed.eraseColor(Color.GREEN)
        File(app.cacheDir, "preview-b.png").outputStream().use { changed.compress(Bitmap.CompressFormat.PNG, 100, it) }; changed.recycle()
        runBlocking { app.catalog.dao.saveMedia(listOf(second.copy(modified = 2))) }
        pump { shows(Color.GREEN) } // The URI stays identical; the picture version changes while the song does not.
        assertEquals(song.id, app.catalog.snapshot.value.media.single { it.kind == "AUDIO" }.id)
        assertNull(app.music.state.value.id)
        app.cache.requestClear(false)
        pump { !app.cache.status.value.busy && shows(Color.GREEN) }
        assertEquals(second.uri, app.catalog.artwork(song, app.catalog.snapshot.value))
    }
}
