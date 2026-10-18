package it.ayngallery

import android.content.ComponentName
import android.content.Intent
import android.app.Activity
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.time.Duration

/** Real Compose and provider bytes; scheduling and journal are observed, not Thor certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FileRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun release() { (RuntimeEnvironment.getApplication() as GalleryApp).onTerminate() }
    @Test fun frozenFileConfirmationTrashAndRestoreRenderAndPreserveReferences() = verify("landscape")
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun fileActionsRemainReachableWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f)
        verify("portrait-large-text")
    }
    private fun verify(layout: String) {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "ayngallery.operations"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("ayngallery.operations", provider)
        val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(126, 93, 165))
        provider.nodes.getValue("image").file!!.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.eraseColor(Color.rgb(83, 160, 154))
        val other = provider.add("child", "Other illustration.png", byteArrayOf())
        other.file!!.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val source = SourceEntity("scope", DocumentsContract.buildTreeDocumentUri("ayngallery.operations", "root").toString(), "Fixture", "VISUAL")
        val media = runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.contentResolver.takePersistableUriPermission(android.net.Uri.parse(source.treeUri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            withTimeout(10_000) { app.catalog.snapshot.first { it.initialized && it.visibleMedia.size == 2 } }
            app.catalog.dao.media().single { it.name == "image.png" }.also { app.catalog.dao.favorite(it.id, true) }
        }
        val second = runBlocking { app.catalog.dao.media().single { it.name == other.name } }
        runBlocking { withContext(Dispatchers.IO) {
            val result = coil3.SingletonImageLoader.get(app).execute(coil3.request.ImageRequest.Builder(app).data(media.uri).size(72, 72).build())
            assertTrue("Provider thumbnail: ${(result as? coil3.request.ErrorResult)?.throwable}", result is coil3.request.SuccessResult)
        } }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val panel = controller.get()
        panel.windowModel.open(media.id); compose.waitForIdle()
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sposta nel cestino…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sposta nel cestino…").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-file-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("file-action-target").assertTextEquals(media.name)
        app.session.selectMedia(second.id); compose.waitForIdle()
        compose.onNodeWithTag("file-action-target").assertTextEquals(media.name)
        capture(ShadowDialog.getLatestDialog().window!!.decorView, "file-confirm-$layout")
        compose.onNodeWithTag("confirm-file-action").performClick()
        compose.waitUntil(10_000) { app.files.operations.value.any { it.mediaId == media.id && it.kind == "TRASH" } }
        val trashOperation = app.files.operations.value.first { it.mediaId == media.id && it.kind == "TRASH" }
        compose.waitUntil(10_000) { WorkManager.getInstance(app).getWorkInfosByTag("file:${trashOperation.id}").get().isNotEmpty() }
        // Pump the UI looper while the actual scheduled CoroutineWorker releases playback handles.
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            app.files.operations.value.any { it.id == trashOperation.id && it.phase == "COMPLETED" }
        }
        runBlocking { withTimeout(10_000) { app.catalog.snapshot.first { it.byId[media.id]?.trashed == true } } }
        assertTrue(provider.nodes.containsKey(other.id)); assertFalse(provider.nodes.containsKey("image"))
        assertTrue(runBlocking { app.catalog.dao.media(media.id)!!.favorite })
        panel.windowModel.go("TRASH"); compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("trash-item:${media.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("trash-item:${media.id}").performClick()
        capture(panel.window.decorView, "trash-$layout")
        compose.onNodeWithTag("trash-restore-selected").performClick()
        compose.onNodeWithTag("trash-restore-elsewhere").performClick()
        compose.onNodeWithTag("confirm-file-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Scegli un'altra cartella…").performScrollTo().performClick()
        val folderPicker = shadowOf(panel).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, folderPicker.intent.action)
        val options = folderPicker.options // ActivityResultRegistry consumes the extra before launching the system picker.
        assertNotNull(options)
        val expectedOptions = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(app.session.current(panel)!!.id).toBundle()
        expectedOptions.keySet().forEach { assertEquals(expectedOptions.get(it), options!!.get(it)) }
        val uri = DocumentsContract.buildTreeDocumentUri("ayngallery.operations", "destination")
        shadowOf(panel).receiveResult(folderPicker.intent, Activity.RESULT_OK, Intent().setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasText("Destinazione:", substring = true).and(hasText("Destination", substring = true))).fetchSemanticsNodes().isNotEmpty()
        }
        capture(ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView, "restore-confirm-$layout")
        compose.onNodeWithText("Nome: ${media.name}").performScrollTo().performClick()
        compose.onNodeWithTag("file-action-name-input").assertIsDisplayed().performTextReplacement("Restored illustration.jpg")
        compose.onNodeWithText("Usa questo nome").performClick()
        compose.onNodeWithText("Mantieni l'estensione originale: rinominare non converte il file").assertIsDisplayed()
        compose.onNodeWithTag("file-action-name-input").performTextReplacement("Restored illustration.png")
        compose.onNodeWithText("Usa questo nome").performClick()
        compose.onNodeWithText("Crea un nuovo album vuoto").performScrollTo().performClick()
        compose.onNodeWithTag("file-folder-name-input").assertIsDisplayed().performTextReplacement("Revisions")
        capture(ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView, "create-folder-$layout")
        compose.onNodeWithText("Crea").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasText("Destinazione:", substring = true).and(hasText("Revisions", substring = true))).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(provider.nodes.values.any { it.name == "Revisions" && it.file == null })
        compose.onNodeWithTag("confirm-file-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("confirm-file-action").performClick()
        compose.waitUntil(10_000) { app.files.operations.value.any { it.kind == "RESTORE" } }
        val restore = app.files.operations.value.first { it.kind == "RESTORE" }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            app.files.operations.value.any { it.id == restore.id && it.phase == "COMPLETED" }
        }
        runBlocking { withTimeout(10_000) { app.catalog.snapshot.first { it.byId[media.id]?.trashed == false } } }
        assertTrue(runBlocking { app.catalog.dao.media(media.id)!!.favorite })
        assertEquals("Restored illustration.png", runBlocking { app.catalog.dao.media(media.id)!!.name })
        assertNull(runBlocking { app.catalog.dao.trash(media.id) })
        panel.windowModel.go("OPERATIONS"); compose.waitForIdle()
        compose.onAllNodesWithText("Completata").assertCountEquals(3)
        capture(panel.window.decorView, "operations-$layout")
        controller.pause().stop().destroy()
    }
    private fun capture(view: android.view.View, name: String) {
        compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var colors = emptySet<Int>()
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            view.draw(Canvas(bitmap))
            colors = (0 until bitmap.height step 12).flatMap { y -> (0 until bitmap.width step 12).map { x -> bitmap.getPixel(x, y) } }.toSet()
            if (colors.size <= 10) Thread.sleep(20)
        } while (colors.size <= 10 && System.nanoTime() < deadline)
        val target = File("../qa/rendered/$name.png").absoluteFile
        target.parentFile!!.mkdirs()
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        assertTrue("$name: actual rendered UI", colors.size > 10)
    }
}
