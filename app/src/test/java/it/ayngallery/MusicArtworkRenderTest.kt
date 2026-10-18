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
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MusicArtworkRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun release() { app.onTerminate() }
    @Test fun albumArtworkDraftCancelRotationAndSaveUseLocalReferences() = verify(false)
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun artworkChoiceAndPriorityRemainReachableWithLargeText() = verify(true)
    private fun verify(large: Boolean) {
        if (large) RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val provider = PreviewFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.artui"; exported = false })
        ShadowContentResolver.registerProviderInternal("ayngallery.artui", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.artui", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val picture = MediaEntity("a", "ayngallery.artui:a", DocumentsContract.buildDocumentUriUsingTree(tree, "a").toString(), "folder", "Local cover.png", "image/png", "IMAGE", 100, 1)
        val song = picture.copy(id = "song", documentKey = "ayngallery.artui:song", uri = DocumentsContract.buildDocumentUriUsingTree(tree, "song").toString(), name = "Local track.wav", mime = "audio/wav", kind = "AUDIO", artist = "Aster", musicAlbum = "Volume I")
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "Local", "MIXED"))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("folder", "artui:root", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "Local", "Local", null)))
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource("folder", "source", 1)))
            app.catalog.dao.saveMedia(listOf(picture, song)); app.catalog.dao.saveMembership(listOf(picture, song).map { MediaSource(it.id, "source", 1) })
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val vm = controller.get().windowModel
        vm.musicMode("ALBUMS"); compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Volume I").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Volume I").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("music-group-artwork").fetchSemanticsNodes().isNotEmpty() }
        fun choose() {
            compose.onNodeWithTag("music-group-artwork").performClick()
            compose.onNodeWithTag("artwork-choose").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("collection-choose:a").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("collection-choose:a").performScrollTo().performClick()
            compose.onNodeWithTag("collection-add-save").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("artwork-save").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artwork-save").assertIsDisplayed()
        }
        choose()
        assertTrue(runBlocking { app.catalog.dao.artworkChoices().isEmpty() })
        compose.onNodeWithText("Annulla").performClick()
        assertTrue(runBlocking { app.catalog.dao.artworkChoices().isEmpty() })
        choose()
        compose.onNodeWithTag("artwork-priority").performScrollTo().performClick()
        compose.onNodeWithTag("artwork-order:FOLDER,MANUAL,EMBEDDED").performScrollTo().performClick()
        controller.recreate(); compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("artwork-save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Local cover.png").assertExists()
        compose.onNodeWithTag("artwork-priority").assertTextContains(ArtworkOrders.label("FOLDER,MANUAL,EMBEDDED"))
        capture(large)
        assertTrue(runBlocking { app.catalog.dao.artworkChoices().isEmpty() })
        compose.onNodeWithTag("artwork-save").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { app.catalog.snapshot.value.artworkChoices.isNotEmpty() }
        val choice = app.catalog.snapshot.value.artworkChoices.single()
        assertEquals(musicAlbumTarget(song), choice.target); assertEquals("a", choice.coverMediaId); assertEquals("FOLDER,MANUAL,EMBEDDED", choice.priority)
        val data = runBlocking { app.catalog.readSnapshot() }
        assertEquals(picture.uri, app.catalog.artwork(song, data))
        assertEquals(listOf(picture, song), runBlocking { app.catalog.dao.media() }.map { it.copy(audioMetadataRead = false) })
        assertNull(app.music.state.value.id)
        controller.pause().stop().destroy()
    }
    private fun capture(large: Boolean) {
        compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle()
        val view = ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/music-artwork-${if (large) "large-text" else "landscape"}.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
}
