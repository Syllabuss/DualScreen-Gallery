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
class FolderRelinkRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var docs: OperationDocuments
    private lateinit var tree: android.net.Uri
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun start(game: Boolean = false) {
        RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val info = ProviderInfo().apply { authority = "ayngallery.folder-ui"; exported = true; grantUriPermissions = true
            packageName = app.packageName; name = OperationDocuments::class.java.name
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission }
        docs = OperationDocuments(); docs.attachInfo(app, info)
        docs.nodes.getValue("image").file!!.writeBytes(IncomingFixture.png())
        docs.add("destination", "image.png", IncomingFixture.png())
        ShadowContentResolver.registerProviderInternal(info.authority, docs); shadowOf(app.packageManager).addOrUpdateProvider(info)
        shadowOf(app.packageManager).addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        tree = DocumentsContract.buildTreeDocumentUri(info.authority, "root")
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true, gameMode = game))
            val source = SourceEntity("pending", "content://${app.packageName}.pending-backup/tree/root", "Old pictures", "VISUAL", reachable = false, restoreUri = "content://old-device/tree/old-root")
            val root = AlbumEntity("old-root", "backup:root", "content://old-device/document/old-root", "Old pictures", "Old pictures", null)
            val origin = root.copy(id = "old-origin", documentKey = "backup:origin", documentUri = "content://old-device/document/old-origin", name = "Origin", path = "Old pictures / Origin", parentId = root.id)
            val absent = origin.copy(id = "old-absent", documentKey = "backup:absent", documentUri = "content://old-device/document/old-absent", name = "Absent", path = "Old pictures / Absent")
            val a = MediaEntity("old-a", "backup:a", "content://old-device/document/a", origin.id, "image.png", "image/png", "IMAGE", IncomingFixture.png().size.toLong(), 1, favorite = true, available = false)
            app.catalog.dao.saveSource(source); app.catalog.dao.saveAlbums(listOf(root, origin, absent))
            app.catalog.dao.saveAncestors(listOf(AlbumAncestor(root.id, root.id), AlbumAncestor(origin.id, origin.id), AlbumAncestor(origin.id, root.id), AlbumAncestor(absent.id, absent.id), AlbumAncestor(absent.id, root.id)))
            app.catalog.dao.saveAlbumSources(listOf(root, origin, absent).map { AlbumSource(it.id, source.id, 0) })
            app.catalog.dao.saveMedia(listOf(a, a.copy(id = "old-b", documentKey = "backup:b", uri = "content://old-device/document/b", albumId = absent.id)))
            app.catalog.dao.saveMembership(listOf(MediaSource("old-a", source.id, 0), MediaSource("old-b", source.id, 0)))
            app.catalog.dao.saveCollection(CollectionEntity("saved", "Saved order", "MEDIA", a.id))
            app.catalog.dao.saveCollectionItems(listOf(CollectionItem("saved", "old-a", 0), CollectionItem("saved", "old-b", 1)))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.media.size == 2 } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val intent = shadowOf(launcher).nextStartedActivity; shadowOf(launcher).nextStartedActivityForResult
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible()
        panel.windowModel.relinkSource("pending")
        pump { compose.onAllNodesWithTag("relink-folder:pending").fetchSemanticsNodes().isNotEmpty() }
        if (game) compose.onNodeWithTag("relink-game").performScrollTo().performClick()
        compose.onNodeWithTag("relink-folder:pending").performScrollTo().performClick()
        compose.onNodeWithTag("folder-relink").assertIsDisplayed()
    }
    private fun pump(done: () -> Boolean) { compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() } }
    private fun choose() {
        compose.onNodeWithTag("folder-choose-root").performScrollTo().assertIsDisplayed().performClick()
        var next: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        pump { next = shadowOf(panel).nextStartedActivityForResult; next != null }
        val picker = next!!.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.action)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, picker.flags)
        shadowOf(panel).receiveResult(picker, Activity.RESULT_OK, Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        pump { compose.onAllNodesWithTag("folder-review-list").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasTestTag("folder-relink-discard") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun selectUnique() {
        compose.onNodeWithTag("folder-review-list").performScrollToNode(hasTestTag("folder-select-unique"))
        compose.onNodeWithTag("folder-select-unique").performClick()
        pump { compose.onAllNodes(hasTestTag("folder-relink-discard") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-review-list").performScrollToNode(hasTestTag("folder-selected-count"))
        compose.onNodeWithTag("folder-selected-count").assertTextEquals("1 corrispondenze selezionate")
        compose.onNodeWithTag("folder-review-list").performScrollToNode(hasTestTag("folder-relink-verify"))
        compose.onNodeWithTag("folder-relink-verify").performClick()
        compose.onNodeWithTag("folder-relink-confirm").assertIsEnabled()
    }
    @Test fun folderPickerReviewRotationAndPartialConfirmationAtLargeTextNeverAutoplayOrWriteFiles() {
        start(); choose()
        compose.onNodeWithTag("folder-selected-count").assertTextEquals("0 corrispondenze selezionate")
        compose.onNodeWithTag("folder-relink-confirm").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        capture("folder-relink-review-large-text")
        selectUnique(); activity!!.recreate()
        pump { compose.onAllNodes(hasTestTag("folder-relink-confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-relink-confirm").assertIsDisplayed().performClick()
        pump { runBlocking { app.catalog.dao.relink("old-a") } != null }
        val id = runBlocking { app.catalog.dao.relink("old-a") }!!
        assertTrue(runBlocking { app.catalog.dao.media(id)!!.favorite }); assertNull(runBlocking { app.catalog.dao.relink("old-b") })
        assertEquals(listOf(id, "old-b"), runBlocking { app.catalog.dao.collectionItems().sortedBy { it.position }.map { it.mediaId } })
        assertNull(app.music.state.value.id); assertTrue(app.session.state.ceded.isEmpty())
        assertEquals(1, app.contentResolver.persistedUriPermissions.size); assertFalse(app.contentResolver.persistedUriPermissions.single().isWritePermission)
        assertEquals(1, docs.creations); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun gameConsentAmbiguousChoiceInvalidatesApprovalAndDiscardKeepsOriginalsAndGrants() {
        start(game = true)
        compose.onNodeWithTag("folder-choose-root").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("folder-relink-game").performScrollTo().performClick(); choose(); selectUnique()
        compose.onNodeWithTag("folder-review-list").performScrollToNode(hasTestTag("folder-candidates:old-b"))
        compose.onNodeWithTag("folder-candidates:old-b").performClick()
        val target = docs.nodes.values.single { it.parent == "destination" && it.file != null }
        compose.onNodeWithTag("folder-target:${tree.authority}:${target.id}").performScrollTo().performClick()
        compose.onNodeWithTag("folder-associate").assertIsEnabled().performClick()
        pump { compose.onAllNodes(hasTestTag("folder-relink-discard") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-review-list").performScrollToNode(hasTestTag("folder-selected-count"))
        compose.onNodeWithTag("folder-selected-count").assertTextEquals("2 corrispondenze selezionate")
        compose.onNodeWithTag("folder-relink-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("folder-review-list").performScrollToNode(hasTestTag("folder-relink-verify"))
        compose.onNodeWithTag("folder-relink-verify").assertIsOff()
        compose.onNodeWithTag("folder-relink-discard").assertIsDisplayed().performClick()
        pump { compose.onAllNodesWithTag("folder-choose-root").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertTrue(runBlocking { app.catalog.dao.relinks().isEmpty() })
        assertEquals(2, runBlocking { app.catalog.dao.media().size }); assertEquals(1, docs.creations); assertTrue(docs.deletions.isEmpty())
        assertNull(app.music.state.value.id)
    }
    private fun capture(name: String) {
        compose.waitForIdle(); val view = panel.window.decorView
        view.invalidate(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/$name.png").absoluteFile; file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
}
