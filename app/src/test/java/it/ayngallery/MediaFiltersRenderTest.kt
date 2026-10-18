package it.ayngallery

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
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
class MediaFiltersRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun pump(done: () -> Boolean) = compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() }
    private fun start() {
        RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val provider = PreviewFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.format-fixture" })
        ShadowContentResolver.registerProviderInternal("ayngallery.format-fixture", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.format-fixture", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val picture = MediaEntity("png", "fixture:png", DocumentsContract.buildDocumentUriUsingTree(tree, "a").toString(), "album", "Portrait.PNG", "image/png", "IMAGE", 100, 1, favorite = true)
        val media = listOf(picture,
            picture.copy(id = "jpeg", documentKey = "fixture:jpeg", uri = DocumentsContract.buildDocumentUriUsingTree(tree, "b").toString(), name = "Portrait.JPEG", mime = "image/jpeg"),
            picture.copy(id = "gif", documentKey = "fixture:gif", name = "Animated.gif", mime = "image/gif", kind = "GIF"),
            picture.copy(id = "video", documentKey = "fixture:video", name = "Film.mp4", mime = "video/mp4", kind = "VIDEO"),
            picture.copy(id = "song", documentKey = "fixture:song", name = "Track.mp3", mime = "audio/mpeg", kind = "AUDIO"),
            picture.copy(id = "hidden", documentKey = "fixture:hidden", albumId = "private"),
            picture.copy(id = "child-png", documentKey = "fixture:child", albumId = "child", name = "Child.png", favorite = false))
            .map { it.copy(uri = DocumentsContract.buildDocumentUriUsingTree(tree, it.id).toString()) }
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true, gifMode = "OFF"))
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "Fixture", "MIXED"))
            val albums = listOf(AlbumEntity("album", "fixture:album", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "Fixture", "Fixture", null),
                AlbumEntity("child", "fixture:child", DocumentsContract.buildDocumentUriUsingTree(tree, "child").toString(), "Child", "Fixture/Child", "album"),
                AlbumEntity("private", "fixture:private", DocumentsContract.buildDocumentUriUsingTree(tree, "private").toString(), "Hidden", "Hidden", null, hidden = true))
            app.catalog.dao.saveAlbums(albums); app.catalog.dao.saveAlbumSources(albums.map { AlbumSource(it.id, "source", 1) })
            app.catalog.dao.saveMedia(media); app.catalog.dao.saveMembership(media.map { MediaSource(it.id, "source", 1) })
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.media.size == media.size } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        panel.windowModel.go("PHOTO")
        pump { panel.windowModel.listing.value.initialized }
    }
    private fun choose(key: String) {
        if (compose.onAllNodesWithTag("media-format-filter").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Cerca e filtra").performClick()
        compose.onNodeWithTag("media-format-filter").assertIsDisplayed().performClick()
        compose.onNodeWithTag("media-format:$key").performScrollTo().performClick()
        pump { panel.windowModel.listing.value.route.kind == key }
        compose.waitForIdle()
    }
    private fun ids() = panel.windowModel.listing.value.items.map { it.id }.toSet()
    @Test fun formatSearchAndRotationKeepAnExplicitFilterAndEmptyResultsOfferReset() {
        start(); val preferences = app.preferences.state.value
        choose("PNG"); assertEquals(setOf("png", "child-png"), ids())
        compose.onNodeWithTag("media-format-filter").assertIsSelected()
        compose.onNodeWithTag("library-search").performTextInput("Portrait")
        pump { panel.windowModel.listing.value.query == "Portrait" }; assertEquals(setOf("png"), ids())
        choose("JPEG"); assertEquals(setOf("jpeg"), ids())
        activity!!.recreate(); compose.waitForIdle()
        pump { panel.windowModel.listing.value.route.kind == "JPEG" && panel.windowModel.listing.value.query == "Portrait" }
        assertEquals(setOf("jpeg"), ids())
        compose.onNodeWithTag("library-filter-summary").assertTextContains("JPG / JPEG", substring = true)
        choose("WEBP"); assertTrue(ids().isEmpty())
        compose.onNodeWithText("Nessun media con questo filtro").assertIsDisplayed()
        compose.onNodeWithText("Mostra tutti i formati").performClick()
        pump { panel.windowModel.listing.value.route.kind == "ALL" }
        assertEquals("Portrait", panel.windowModel.query.value); assertEquals(setOf("png", "jpeg"), ids())
        assertEquals(preferences, app.preferences.state.value); assertTrue(app.files.operations.value.isEmpty()); assertNull(app.music.state.value.id)
    }
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun mixedGalleryAndFavoritesFilterVideoGifAndImagesWithoutBypassingHiddenAlbums() {
        start(); panel.windowModel.go("FAVORITE"); pump { panel.windowModel.listing.value.route.tab == "FAVORITE" }
        compose.onNodeWithTag("favorite-filter").assertIsDisplayed().performClick()
        compose.onNodeWithTag("favorite-filter:VIDEO").performClick()
        pump { panel.windowModel.listing.value.route.kind == "VIDEO" }; assertEquals(setOf("video"), ids())
        compose.onNodeWithTag("favorite-filter:GIF").performClick()
        pump { panel.windowModel.listing.value.route.kind == "GIF+VIDEO" }; assertEquals(setOf("gif", "video"), ids())
        compose.onNodeWithTag("favorite-filter:VIDEO").performClick()
        pump { panel.windowModel.listing.value.route.kind == "GIF" }; assertEquals(setOf("gif"), ids())
        compose.onNodeWithTag("favorite-filter:GIF").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        activity!!.recreate(); compose.waitForIdle()
        pump { panel.windowModel.listing.value.route.kind == "GIF" }; assertEquals(setOf("gif"), ids())
        panel.windowModel.go("ALBUM", album = "album"); pump { panel.windowModel.listing.value.route.album == "album" }
        choose("JPEG"); assertEquals(setOf("jpeg"), ids())
        val view = panel.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val capture = File("../qa/rendered/media-format-filter-large-text.png").absoluteFile
        capture.parentFile!!.mkdirs(); capture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        panel.windowModel.go("ALBUM", album = "child")
        pump { panel.windowModel.listing.value.route.album == "child" }
        assertEquals("JPEG", panel.windowModel.route.value.kind); assertTrue(ids().isEmpty())
        panel.windowModel.back(); pump { panel.windowModel.listing.value.route.album == "album" }
        assertEquals("JPEG", panel.windowModel.route.value.kind); assertEquals(setOf("jpeg"), ids())
        choose("ALL"); assertEquals(setOf("png", "jpeg", "gif", "video", "song"), ids())
        try { panel.windowModel.filter("invalid"); fail("Unknown filter accepted") } catch (_: IllegalArgumentException) { }
        assertEquals("ALL", panel.windowModel.route.value.kind)
        assertFalse("hidden" in ids()); assertNull(app.music.state.value.id)
    }
}
