package it.ayngallery

import android.app.Activity
import android.content.*
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
class MediaRelinkRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var docs: OperationDocuments
    private lateinit var uri: android.net.Uri
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun start(game: Boolean = false) {
        RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val info = ProviderInfo().apply { authority = "ayngallery.relink-ui"; exported = true; grantUriPermissions = true
            packageName = app.packageName; name = OperationDocuments::class.java.name
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission }
        docs = OperationDocuments(); docs.attachInfo(app, info); docs.nodes.getValue("image").file!!.writeBytes(IncomingFixture.png())
        ShadowContentResolver.registerProviderInternal(info.authority, docs); shadowOf(app.packageManager).addOrUpdateProvider(info)
        shadowOf(app.packageManager).addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        uri = DocumentsContract.buildDocumentUri(info.authority, "image")
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true, gameMode = game))
            val source = SourceEntity("pending", "content://${app.packageName}.pending-backup/tree/root", "Restored pictures", "VISUAL", reachable = false, restoreUri = "content://old-device/tree/root")
            val album = AlbumEntity("folder", "backup:folder", "content://old-device/document/folder", "Original folder", "Original folder", null)
            val item = MediaEntity("old-ref", "backup:old-ref", "content://old-device/document/image", album.id, "Original.png", "image/png", "IMAGE", 111, 1, favorite = true, available = false)
            app.catalog.dao.saveSource(source); app.catalog.dao.saveAlbums(listOf(album)); app.catalog.dao.saveAncestors(listOf(AlbumAncestor(album.id, album.id)))
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource(album.id, source.id, 0)))
            app.catalog.dao.saveMedia(listOf(item)); app.catalog.dao.saveMembership(listOf(MediaSource(item.id, source.id, 0)))
            app.catalog.dao.saveCollection(CollectionEntity("saved", "Restored collection", "MEDIA", item.id)); app.catalog.dao.saveCollectionItems(listOf(CollectionItem("saved", item.id, 0)))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.media.size == 1 } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val intent = shadowOf(launcher).nextStartedActivity; shadowOf(launcher).nextStartedActivityForResult
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible()
        panel.windowModel.go("SOURCES"); pump { compose.onAllNodesWithTag("sources-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sources-list").performScrollToNode(hasTestTag("source-relink:pending"))
        compose.onNodeWithTag("source-relink:pending").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("media-relink").assertIsDisplayed()
    }
    private fun pump(done: () -> Boolean) { compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() } }
    private fun choose() {
        compose.onNodeWithTag("relink-file:old-ref").performScrollTo().assertIsDisplayed().performClick()
        var next: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        pump { next = shadowOf(panel).nextStartedActivityForResult; next != null }
        val picker = next!!.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action); assertEquals("image/*", picker.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, picker.flags)
        shadowOf(panel).receiveResult(picker, Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        pump { compose.onAllNodesWithTag("relink-selected").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasTestTag("relink-discard") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    @Test fun nativePickerReviewRotationAndConfirmationKeepMetadataAndNeverAutoplayAtLargeText() {
        start(); choose()
        compose.onNodeWithTag("relink-original").assertTextEquals("Riferimento: Original.png")
        compose.onNodeWithTag("relink-confirm").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertNull(runBlocking { app.catalog.dao.relink("old-ref") })
        capture("media-relink-review-large-text")
        compose.onNodeWithTag("relink-verify").performScrollTo().performClick()
        compose.onNodeWithTag("relink-confirm").assertIsEnabled()
        activity!!.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("relink-original").assertTextEquals("Riferimento: Original.png")
        compose.onNodeWithTag("relink-confirm").assertIsDisplayed().assertIsEnabled().performClick()
        pump { runBlocking { app.catalog.dao.relink("old-ref") } != null }
        compose.waitForIdle()
        val id = runBlocking { app.catalog.dao.relink("old-ref") }!!
        assertTrue(runBlocking { app.catalog.dao.media(id)!!.favorite }); assertEquals(id, runBlocking { app.catalog.dao.collectionItems().single().mediaId })
        assertEquals(1, runBlocking { app.catalog.dao.media().size }); assertNull(app.music.state.value.id); assertTrue(app.session.state.ceded.isEmpty())
        assertEquals(1, app.contentResolver.persistedUriPermissions.size); assertFalse(app.contentResolver.persistedUriPermissions.single().isWritePermission)
        assertEquals(0, docs.creations); assertEquals(0, docs.childQueries); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun gameConsentStaleCatalogAndDiscardDoNotChangeFileOrAcquireGrant() {
        start(game = true)
        compose.onNodeWithTag("relink-file:old-ref").assertIsNotEnabled()
        compose.onNodeWithTag("relink-game").performScrollTo().performClick(); choose()
        compose.onNodeWithTag("relink-verify").performScrollTo().performClick()
        runBlocking { app.catalog.favorite("old-ref") }
        compose.onNodeWithTag("relink-confirm").performClick()
        pump { compose.onAllNodesWithTag("relink-message").fetchSemanticsNodes().any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { text -> text.text.contains("La libreria è cambiata") } } }
        compose.onNodeWithTag("relink-original").assertTextEquals("Riferimento: Original.png")
        compose.onNodeWithTag("relink-discard").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("relink-selected").assertDoesNotExist()
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertNull(runBlocking { app.catalog.dao.relink("old-ref") })
        assertFalse(runBlocking { app.catalog.dao.media("old-ref")!!.favorite }); assertTrue(docs.deletions.isEmpty()); assertEquals(0, docs.creations)
    }
    private fun capture(name: String) {
        compose.waitForIdle(); val view = panel.window.decorView
        view.invalidate(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/$name.png").absoluteFile; file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
}
