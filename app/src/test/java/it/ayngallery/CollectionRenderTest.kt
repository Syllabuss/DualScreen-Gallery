package it.ayngallery

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
class CollectionRenderTest {
    // Queue recomposition on the test scheduler instead of resuming on Room's callback thread.
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun release() { app.onTerminate() }
    @Test fun createAppendRenameCoverAndRemoveOnlyReferences() = verify(false)
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun collectionEditingFitsSmallPanelWithLargeText() = verify(true)
    private fun verify(large: Boolean) {
        if (large) RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val provider = PreviewFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.collectionui"; exported = false })
        ShadowContentResolver.registerProviderInternal("ayngallery.collectionui", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.collectionui", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val media = listOf("a" to "IMAGE", "b" to "IMAGE", "song" to "AUDIO").map { (id, kind) ->
            MediaEntity(id, "${tree.authority}:$id", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), "album", if (kind == "AUDIO") "song.wav" else "$id.png",
                if (kind == "AUDIO") "audio/wav" else "image/png", kind, 100, 1, favorite = true)
        }
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "QA local", "MIXED"))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("album", "root", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "QA local", "QA local", null)))
            app.catalog.dao.saveMedia(media); app.catalog.dao.saveMembership(media.map { MediaSource(it.id, "source", 1) })
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        try {
            controller.get().windowModel.go("COLLECTION"); compose.waitForIdle()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("collection-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("collection-create").performClick()
            compose.onNodeWithTag("collection-name").performTextReplacement("References")
            assertTrue(runBlocking { app.catalog.dao.collections().isEmpty() })
            controller.recreate(); compose.waitForIdle()
            compose.onNodeWithTag("collection-name").assertTextEquals("References")
            compose.onNodeWithTag("collection-save").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { app.catalog.snapshot.value.collections.size == 1 }
            val id = app.catalog.snapshot.value.collections.single().id
            compose.waitUntil(5000) { compose.onAllNodesWithTag("collection:$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("collection:$id").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("collection-add").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("collection-add").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("collection-choose:a").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("collection-choose:a").performScrollTo().performClick()
            compose.onNodeWithTag("collection-choose:song").performScrollTo().performClick()
            capture(ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView, "selection", large)
            compose.onNodeWithTag("collection-add-save").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { app.catalog.snapshot.value.collectionItems.count { it.collectionId == id } == 2 }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("collection-item:song").fetchSemanticsNodes().isNotEmpty() }
            assertNull(app.music.state.value.id)
            capture(controller.get().window.decorView, "detail", large)
            compose.onNodeWithContentDescription("Gestisci raccolta").performClick()
            compose.onNodeWithTag("collection-rename").performClick()
            compose.onNodeWithTag("collection-name").performTextReplacement("References v2")
            compose.onNodeWithTag("collection-save").performClick()
            compose.waitUntil(5000) { app.catalog.snapshot.value.collections.single().name == "References v2" }
            compose.onNodeWithContentDescription("Gestisci raccolta").performClick()
            compose.onNodeWithTag("collection-cover").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("collection-choose:b").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("collection-choose:b").performScrollTo().performClick()
            compose.onNodeWithTag("collection-add-save").performClick()
            compose.waitUntil(5000) { app.catalog.snapshot.value.collections.single().coverMediaId == "b" }
            compose.onAllNodesWithContentDescription("Azioni sull'elemento della raccolta")[0].performClick()
            compose.onNodeWithText("Rimuovi dalla raccolta").performClick()
            compose.waitUntil(5000) { app.catalog.snapshot.value.collectionItems.count { it.collectionId == id } == 1 }
            compose.onNodeWithContentDescription("Gestisci raccolta").performClick()
            compose.onNodeWithTag("collection-delete").performClick()
            compose.onNodeWithText("Annulla").performClick()
            assertEquals(1, app.catalog.snapshot.value.collections.size)
            compose.onNodeWithContentDescription("Gestisci raccolta").performClick()
            compose.onNodeWithTag("collection-delete").performClick()
            compose.onNodeWithTag("collection-delete-confirm").assertIsDisplayed()
            capture(ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView, "remove", large)
            compose.onNodeWithTag("collection-delete-confirm").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { app.catalog.snapshot.value.collections.isEmpty() }
            // Background scanning may populate the derived metadata-read flag; collection actions preserve every user field.
            assertEquals(media, runBlocking { app.catalog.dao.media() }.map { it.copy(audioMetadataRead = false) })
            assertEquals(3, runBlocking { app.catalog.dao.membership().size })
            assertTrue(runBlocking { app.catalog.dao.collectionItems().isEmpty() })
            assertNull(app.music.state.value.id)
        } finally {
            controller.pause().stop().destroy()
        }
    }
    private fun capture(view: android.view.View, stage: String, large: Boolean) {
        compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/collection-$stage-${if (large) "large-text" else "landscape"}.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
}
