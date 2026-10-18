package it.ayngallery

import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Offset
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
import android.app.Activity
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ImageEditorRenderTest {
    @Test fun displayCropPresetsUseLiveDimensionsAndRespectRotation() {
        start()
        org.robolectric.shadows.ShadowDisplayManager.changeDisplay(0, "w1920dp-h1080dp-land-mdpi")
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1240dp-h1080dp-land-mdpi")
        compose.runOnIdle { app.session.refreshDisplays() }
        compose.onNodeWithText("Schermo inferiore").performScrollTo().assertIsEnabled().performClick()
        val lower = ImageRecipe().ratio(240, 160, 1240f / 1080).rect(240, 160)
        compose.onNodeWithTag("image-recipe-summary").assertTextContains("ritaglio ${lower.width()}×${lower.height()}", substring = true)
        compose.onNodeWithTag("image-rotate").performScrollTo().performClick()
        org.robolectric.shadows.ShadowDisplayManager.changeDisplay(0, "w2000dp-h1400dp-land-mdpi")
        compose.runOnIdle { app.session.refreshDisplays() }
        compose.onNodeWithText("Schermo superiore").performScrollTo().performClick()
        val rotated = ImageRecipe().ratio(240, 160, 1400f / 2000).rect(240, 160)
        compose.onNodeWithTag("image-recipe-summary").assertTextContains("ritaglio ${rotated.width()}×${rotated.height()}", substring = true)
        compose.onNodeWithText("Verticale").assertDoesNotExist()
    }
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var provider: OperationDocuments
    private lateinit var original: MediaEntity
    private lateinit var destination: AlbumEntity
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun start(game: Boolean = false, gif: Boolean = false, previewDelay: Long = 0) {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.editorui"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.editorui", provider)
        provider.beforeRead = { if (previewDelay > 0) Thread.sleep(previewDelay) }
        val image = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        for (y in 0 until 160) for (x in 0 until 240) image.setPixel(x, y, if (x < 120) Color.rgb(171, 100, 156) else if (y < 80) Color.rgb(96, 172, 196) else Color.TRANSPARENT)
        provider.nodes.getValue("image").file!!.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        if (gif) {
            val node = provider.nodes.getValue("image")
            node.file!!.writeBytes(PhotoGifProvider().bytes)
            provider.nodes["image"] = node.copy(name = "animation.gif", mime = "image/gif")
        }
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.editorui", "root")
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true, gameMode = game))
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val source = SourceEntity("source", tree.toString(), "QA editor", "VISUAL")
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            original = app.catalog.dao.media().single(); destination = app.catalog.dao.albums().single { it.name == "Destination" }
            app.catalog.favoriteAll(listOf(original.id), true)
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.byId[original.id]?.favorite == true } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        panel.windowModel.open(original.id); compose.waitForIdle()
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        if (gif) compose.onNodeWithText("Modifica immagine…").assertDoesNotExist()
        compose.onNodeWithText(if (gif) "Esporta fotogramma…" else "Modifica immagine…").performScrollTo().performClick()
        pump { compose.onAllNodesWithTag("image-crop-canvas").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(app.files.operations.value.isEmpty()); assertTrue(provider.deletions.isEmpty())
    }
    private fun pump(done: () -> Boolean) {
        try { compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() } }
        catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            capture("image-editor-timeout")
            println("Editor timeout: ${compose.onAllNodesWithTag("image-editor-error").fetchSemanticsNodes().map { it.config }}")
            println("Confirm state: ${compose.onAllNodesWithTag("image-export-confirm").fetchSemanticsNodes().map { it.config }}")
            println("Destination: ${compose.onAllNodesWithTag("image-destination").fetchSemanticsNodes().map { it.config }}")
            println("Name: ${compose.onAllNodesWithTag("image-export-name").fetchSemanticsNodes().map { it.config }}")
            println("Crop canvas: ${compose.onAllNodesWithTag("image-crop-canvas").fetchSemanticsNodes().size}; preparing: ${compose.onAllNodesWithText("Preparazione della richiesta…").fetchSemanticsNodes().size}")
            println("Source unavailable: ${compose.onAllNodesWithText("La sorgente è cambiata o non è disponibile. Riapri l'editor.").fetchSemanticsNodes().size}")
            println("Preview loading: ${compose.onAllNodesWithText("Preparazione dell'anteprima…").fetchSemanticsNodes().size}")
            println("Fresh catalog: ${runBlocking { app.catalog.readSnapshot() }.let { it.sources to it.albumMembership }}")
            throw failure
        }
    }
    private fun chooseDestination() {
        val picker = shadowOf(panel).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.intent.action)
        assertNotNull(picker.intent.getParcelableExtra<android.net.Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.editorui", "destination")
        shadowOf(panel).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(tree)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        compose.waitForIdle()
    }
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun delayedGifPreviewKeepsCropReachableAndCancelLeavesOriginalIntact() {
        RuntimeEnvironment.setFontScale(1.4f)
        start(gif = true, previewDelay = 200)
        compose.onNodeWithTag("image-crop-canvas").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("image-editor-cancel").assertIsDisplayed().performClick()
        assertTrue(app.files.operations.value.isEmpty())
        assertArrayEquals(PhotoGifProvider().bytes, provider.nodes.getValue("image").file!!.readBytes())
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun touchCropRotationDraftRecreationAndWorkerCreateOnlyANewVerifiedImage() = verify("landscape")
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun largeTextEditorKeepsSaveVisibleAndOriginalIntact() {
        RuntimeEnvironment.setFontScale(1.4f); verify("large-text")
    }
    private fun verify(layout: String) {
        start(); val bytes = provider.nodes.getValue("image").file!!.readBytes()
        compose.onNodeWithTag("image-export-confirm").assertIsDisplayed().assertIsEnabled()
        capture("image-editor-$layout")
        compose.onNodeWithTag("image-crop-canvas").performScrollTo().performTouchInput {
            val fittedWidth = minOf(width.toFloat(), height * 1.5f)
            val fittedHeight = fittedWidth / 1.5f
            val origin = Offset((width - fittedWidth) / 2f, (height - fittedHeight) / 2f)
            down(origin + Offset(1f, 1f)); moveTo(origin + Offset(fittedWidth * 0.3f, fittedHeight * 0.3f), 200); up()
        }
        compose.onNodeWithTag("image-recipe-summary").performScrollTo().assertTextContains("rotazione 0°", substring = true)
        compose.onNodeWithTag("image-rotate").performScrollTo().performClick()
        compose.onNodeWithTag("image-recipe-summary").assertTextContains("rotazione 90°", substring = true)
        compose.onNodeWithTag("image-mirror").performScrollTo().performClick()
        compose.onNodeWithTag("image-export-name").performScrollTo().performTextReplacement("QA edited")
        activity!!.recreate(); compose.waitForIdle()
        pump { compose.onAllNodesWithTag("image-crop-canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-recipe-summary").performScrollTo().assertTextContains("rotazione 90°", substring = true)
        pump { compose.onAllNodes(hasTestTag("image-export-confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-export-confirm").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("image-result-mode").performScrollTo().performClick()
        compose.onNodeWithTag("image-crop-canvas").performScrollTo(); capture("image-result-$layout")
        compose.onNodeWithTag("image-export-confirm").performClick()
        chooseDestination()
        pump { app.files.operations.value.any { it.kind == "EXPORT_IMAGE" && it.phase == "COMPLETED" } }
        val op = app.files.operations.value.single { it.kind == "EXPORT_IMAGE" }
        val recipe = ImageRecipe.decode(op.recipe!!); assertEquals(1, recipe.turns); assertTrue(recipe.mirror)
        assertTrue(recipe.left > 0.15f && recipe.top > 0.15f)
        val crop = recipe.rect(240, 160)
        val exported = provider.node(op.publishedUri!!).file!!.readBytes()
        val image = android.graphics.BitmapFactory.decodeByteArray(exported, 0, exported.size)
        assertEquals(crop.height(), image.width); assertEquals(crop.width(), image.height); image.recycle()
        assertEquals("QA edited.png", op.finalName); assertEquals("OPERATIONS", panel.windowModel.route.value.tab)
        assertArrayEquals(bytes, provider.nodes.getValue("image").file!!.readBytes()); assertTrue(provider.deletions.isEmpty())
        assertTrue(runBlocking { app.catalog.dao.media(original.id)!!.favorite })
        assertFalse(runBlocking { app.catalog.dao.media(op.resultMediaId)!!.favorite })
    }
    @Test fun gameConsentCancelAndSourceHideNeverStartAnUnrequestedExport() {
        start(game = true)
        compose.onNodeWithTag("image-export-confirm").performClick()
        compose.onNodeWithText("Esportare mentre giochi?").assertExists()
        compose.onNodeWithText("Non ora").performClick(); assertTrue(app.files.operations.value.isEmpty())
        runBlocking { app.catalog.hideAlbums(listOf(original.albumId), true) }
        pump { !app.catalog.snapshot.value.usable(original) }
        compose.onNodeWithTag("image-export-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("image-editor-cancel").performClick()
        assertTrue(app.files.operations.value.isEmpty()); assertTrue(provider.deletions.isEmpty())
    }
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun explicitGifFrameDraftRecreatesAndWritesOnlyAStaticCopyAtLargeText() {
        RuntimeEnvironment.setFontScale(1.4f)
        start(gif = true)
        val bytes = provider.nodes.getValue("image").file!!.readBytes()
        compose.onNodeWithText("Esporta fotogramma GIF").assertIsDisplayed()
        compose.onNodeWithTag("gif-export-frame").performScrollTo().assertTextContains("1 /", substring = true)
        compose.onNodeWithTag("image-export-confirm").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("image-export-name").performScrollTo().assertTextContains("animation-fotogramma")
        compose.onNodeWithTag("image-editor-cancel").performClick()
        assertTrue(app.files.operations.value.isEmpty())
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        compose.onNodeWithText("Esporta fotogramma…").performScrollTo().performClick()
        pump { compose.onAllNodesWithTag("image-crop-canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-format:JPEG").performScrollTo().performClick()
        compose.onNodeWithTag("image-export-name").performScrollTo().performTextReplacement("GIF frame QA")
        activity!!.recreate(); compose.waitForIdle()
        pump { compose.onAllNodesWithTag("image-crop-canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-format:JPEG").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("gif-export-frame").performScrollTo(); capture("gif-frame-export-large-text")
        compose.onNodeWithTag("image-export-confirm").assertIsDisplayed().assertIsEnabled().performClick()
        chooseDestination()
        pump { app.files.operations.value.any { it.kind == "EXPORT_IMAGE" && it.phase == "COMPLETED" } }
        val op = app.files.operations.value.single { it.kind == "EXPORT_IMAGE" }
        assertEquals("GIF_FIRST_FRAME", ImageRecipe.decode(op.recipe!!).input)
        assertEquals("GIF frame QA.jpg", op.finalName)
        assertEquals("IMAGE", runBlocking { app.catalog.dao.media(op.resultMediaId)!!.kind })
        assertArrayEquals(bytes, provider.nodes.getValue("image").file!!.readBytes())
        assertTrue(runBlocking { app.catalog.dao.media(original.id)!!.favorite }); assertTrue(provider.deletions.isEmpty())
        assertEquals("OPERATIONS", panel.windowModel.route.value.tab)
    }
    private fun capture(name: String) {
        compose.waitForIdle(); val view = panel.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(image))
        val file = File("../qa/rendered/$name.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
