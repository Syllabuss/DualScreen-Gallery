package it.ayngallery

import android.app.Activity
import android.app.ActivityOptions
import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.work.WorkManager
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ImportMediaRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var incoming: IncomingFixture
    private lateinit var destination: OperationDocuments
    private lateinit var album: AlbumEntity
    private lateinit var original: MediaEntity
    private lateinit var first: Uri
    private lateinit var second: Uri
    private lateinit var bytes: ByteArray
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun start(game: Boolean = false) {
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        incoming = IncomingFixture(); incoming.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.incoming-fixture"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("ayngallery.incoming-fixture", incoming)
        destination = OperationDocuments(); destination.attachInfo(app, ProviderInfo().apply {
            authority = "ayngallery.import-destination"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("ayngallery.import-destination", destination)
        bytes = IncomingFixture.png(); first = incoming.add("Selected drawing.png", bytes = bytes); second = incoming.add("Other drawing.png", bytes = bytes)
        destination.nodes.getValue("image").file!!.writeBytes(bytes)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.import-destination", "root")
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true, gameMode = game))
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val source = SourceEntity("destination", tree.toString(), "QA import", "VISUAL")
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            album = app.catalog.dao.albums().single { it.name == "Destination" }; original = app.catalog.dao.media().single()
            app.catalog.favorite(original.id); original = app.catalog.dao.media(original.id)!!
            app.catalog.collection("Keep originals", "MEDIA", listOf(original.id))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.byId[original.id]?.favorite == true } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val intent = shadowOf(launcher).nextStartedActivity
        shadowOf(launcher).nextStartedActivityForResult
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible()
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
    }
    private fun pump(done: () -> Boolean) { compose.waitUntil(10000) { done() } }
    @Suppress("DEPRECATION") private fun choose(uris: List<Uri> = listOf(first, second)) {
        panel.chooseImport()
        val picker = shadowOf(panel).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action); assertEquals("*/*", picker.intent.type)
        assertTrue(picker.intent.hasCategory(Intent.CATEGORY_OPENABLE)); assertTrue(picker.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertArrayEquals(arrayOf("image/*", "video/*"), picker.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, picker.intent.flags)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(app.session.current(panel)!!.id).toBundle()
        options.keySet().forEach { assertEquals(options.get(it), picker.options!!.get(it)) }
        val result = Intent().setData(uris.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.clipData = ClipData.newRawUri("chosen", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        shadowOf(panel).receiveResult(picker.intent, Activity.RESULT_OK, result)
        pump { compose.onAllNodesWithTag("import-count").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun pickerFrozenReviewRotationAndWorkerCopyRemainUsableWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f); start()
        val policy = app.session.state; val grants = app.contentResolver.persistedUriPermissions.map { it.uri }
        choose(); val frozen = IncomingSelection.decode(panel.windowModel.incomingRequest.value!!)
        val targets = runBlocking { IncomingMedia.inspect(app, frozen) }
        compose.onNodeWithTag("import-count").assertTextEquals("2 di 2 file scelti")
        compose.onNodeWithTag("import-choice:${targets.last().id}").performClick()
        compose.onNodeWithTag("import-count").assertTextEquals("1 di 2 file scelti")
        compose.onNodeWithTag("import-name").performScrollTo().performClick()
        compose.onNodeWithTag("import-name-input").performTextReplacement("Imported drawing.jpg")
        compose.onNodeWithTag("import-name-save").performClick()
        compose.onNodeWithText("Mantieni l'estensione originale: rinominare non converte il file").assertIsDisplayed()
        compose.onNodeWithTag("import-name-input").performTextReplacement("Imported drawing.png")
        compose.onNodeWithTag("import-name-save").assertIsDisplayed().performClick()
        compose.onNodeWithTag("import-destination").performScrollTo().performClick()
        compose.onNodeWithTag("import-folder:${album.id}").performScrollTo().performClick()
        activity!!.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("import-count").assertTextEquals("1 di 2 file scelti")
        compose.onNodeWithTag("import-name").performScrollTo().assertTextEquals("Nome: Imported drawing.png")
        compose.onNodeWithTag("import-confirm").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("import-cancel").assertIsDisplayed()
        assertTrue(app.files.operations.value.isEmpty()); assertEquals(policy, app.session.state)
        capture("import-copy-large-text")
        compose.onNodeWithTag("import-confirm").performClick()
        pump { app.files.operations.value.any { it.kind == "IMPORT" && it.phase == "COMPLETED" } }
        val op = app.files.operations.value.single { it.kind == "IMPORT" }
        assertTrue(WorkManager.getInstance(app).getWorkInfosByTag("file:${op.id}").get().isNotEmpty())
        val copied = runBlocking { app.catalog.dao.media(op.resultMediaId)!! }
        assertEquals("Imported drawing.png", copied.name); assertFalse(copied.favorite); assertArrayEquals(bytes, destination.node(copied.uri).file!!.readBytes())
        assertEquals(1, destination.creations); assertTrue(File(app.filesDir, "imports").listFiles().orEmpty().isEmpty())
        assertEquals(grants, app.contentResolver.persistedUriPermissions.map { it.uri }); assertEquals(policy, app.session.state)
        assertEquals(original, runBlocking { app.catalog.dao.media(original.id) }); assertEquals(original.id, runBlocking { app.catalog.dao.collectionItems().single().mediaId })
        assertNull(panel.windowModel.incomingRequest.value); assertEquals("OPERATIONS", panel.windowModel.route.value.tab)
    }
    @Test fun hiddenDestinationAndCancellationNeverStartAnImportOrKeepIncomingGrants() {
        start(); choose(listOf(first))
        compose.onNodeWithTag("import-destination").performScrollTo().performClick()
        compose.onNodeWithTag("import-folder:${album.id}").performClick()
        compose.onNodeWithTag("import-confirm").assertIsEnabled()
        runBlocking { app.catalog.hideAlbums(listOf(album.id), true) }
        pump { app.catalog.snapshot.value.excludedAlbums.contains(album.id) }
        compose.onNodeWithTag("import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("import-cancel").assertIsDisplayed().performClick()
        assertNull(panel.windowModel.incomingRequest.value); assertTrue(app.files.operations.value.isEmpty()); assertEquals(0, destination.creations)
        assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == first })
        assertArrayEquals(bytes, incoming.entries.getValue(first.lastPathSegment!!).file.readBytes())
    }
    @Test fun unavailableFilesAreListedAndGameModeNeedsExplicitConsentBeforeConfirmation() {
        start(game = true)
        val text = incoming.add("notes.txt", "text/plain", "private fixture".toByteArray())
        choose(listOf(first, text))
        compose.onNodeWithTag("import-count").assertTextEquals("1 di 2 file scelti")
        compose.onNodeWithTag("import-unavailable").performScrollTo().assertTextContains("formato non gestito", substring = true)
        compose.onNodeWithTag("import-destination").performScrollTo().performClick()
        compose.onNodeWithTag("import-folder:${album.id}").performClick()
        compose.onNodeWithTag("import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("import-game-consent").performScrollTo().performClick()
        compose.onNodeWithTag("import-confirm").assertIsEnabled()
        activity!!.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("import-confirm").assertIsEnabled()
        compose.onNodeWithTag("import-cancel").performClick()
        assertTrue(app.files.operations.value.isEmpty()); assertEquals(0, destination.creations)
    }
    private fun capture(name: String) {
        compose.waitForIdle(); val view = ShadowDialog.getLatestDialog().window!!.decorView
        view.invalidate(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); compose.waitForIdle()
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(image))
        val file = File("../qa/rendered/$name.png").absoluteFile; file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
