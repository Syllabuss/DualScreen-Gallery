package it.ayngallery

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import coil3.SingletonImageLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PhotoFrameRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var app: GalleryApp
    private lateinit var controller: ActivityController<MainPanelActivity>
    private lateinit var provider: FrameFixtureProvider
    private val panel get() = controller.get()
    @After fun close() {
        if (::controller.isInitialized && !controller.get().isDestroyed) controller.pause().stop().destroy()
        (RuntimeEnvironment.getApplication() as GalleryApp).onTerminate()
    }
    private fun start(config: FrameConfig = FrameConfig(seconds = 2), playing: Boolean = false, motion: Boolean = false, pad: PadSettings = PadSettings()) {
        app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).grantPermissions("it.ayngallery.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        provider = FrameFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.framefixture"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("ayngallery.framefixture", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.framefixture", "root")
        val images = listOf("a", "b", "c").map { id -> MediaEntity(id, "ayngallery.framefixture:$id", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
            if (id == "c") "other" else "folder", "$id.png", "image/png", "IMAGE", 100, 1) }
        runBlocking {
            app.preferences.save(GalleryPreferences(themeId = "rose_milk", reducedMotion = !motion, pad = pad))
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "Frame fixture", "VISUAL"))
            app.catalog.dao.saveAlbums(listOf("folder", "other").map { AlbumEntity(it, "fixture:$it", tree.toString(), it, it, null) })
            app.catalog.dao.saveAncestors(listOf("folder", "other").map { AlbumAncestor(it, it) })
            app.catalog.dao.saveAlbumSources(listOf("folder", "other").map { AlbumSource(it, "source", 1) })
            app.catalog.dao.saveMedia(images); app.catalog.dao.saveMembership(images.map { MediaSource(it.id, "source", 1) })
            app.catalog.collection("Frame collection", "MEDIA", listOf("b", "c"))
            withTimeout(10_000) { app.catalog.snapshot.first { it.initialized && it.media.size == 3 && it.collections.size == 1 } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).create()
        panel.windowModel.configureFrame(config); panel.windowModel.startFrame(); panel.windowModel.frameConfiguring(false); panel.windowModel.framePlaying(playing)
        controller.start().resume().visible().windowFocusChanged(true)
        pump { panel.windowModel.frameCursor.value.current != null && compose.onAllNodesWithTag("photo-frame").fetchSemanticsNodes().isNotEmpty() }
        ready()
    }
    private fun pump(done: () -> Boolean) { try { compose.waitUntil(10_000) {
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done()
    } } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
        println("Frame cursor=${panel.windowModel.frameCursor.value}, playing=${panel.windowModel.framePlaying.value}, started=${panel.isStartedFlow.value}, reads=${provider.reads}")
        println(compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { it.config.toString() })
        throw failure
    } }
    private fun ready() {
        pump { compose.onAllNodesWithTag("frame-picture").fetchSemanticsNodes().any {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { "" } == "Immagine pronta"
        } }
        compose.waitForIdle()
    }
    private fun advance(millis: Long) { compose.mainClock.advanceTimeBy(millis); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); compose.waitForIdle() }
    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        panel.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, 40, 0, 0, InputDevice.SOURCE_GAMEPAD))
        panel.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, 40, 0, 0, InputDevice.SOURCE_GAMEPAD))
        compose.waitForIdle()
    }
    @Test fun timerPrefetchManualPauseAndVisibilityUseTheRealSurfaceAndDoNotExpandDisplays() {
        start(playing = true)
        val first = panel.windowModel.frameCursor.value.current
        ready()
        compose.onNodeWithContentDescription("Pausa cornice").performClick()
        val paused = panel.windowModel.frameCursor.value.current
        advance(5_000); assertEquals(paused, panel.windowModel.frameCursor.value.current)
        SingletonImageLoader.get(app).memoryCache?.clear(); provider.reads.clear()
        compose.onNodeWithContentDescription("Avvia cornice").performClick()
        pump { provider.reads.isNotEmpty() }
        assertEquals(listOf(panel.windowModel.frameCursor.value.nextId()), synchronized(provider.reads) { provider.reads.distinct() })
        advance(2_100)
        pump { panel.windowModel.frameCursor.value.current != first }
        ready()
        capture(panel.window.decorView, "frame-landscape")
        controller.pause().stop(); compose.waitForIdle()
        val hidden = panel.windowModel.frameCursor.value.current
        provider.reads.clear(); advance(5_000)
        assertEquals(hidden, panel.windowModel.frameCursor.value.current); assertTrue(provider.reads.isEmpty())
        controller.start().resume().visible().windowFocusChanged(true); ready()
        advance(2_100); pump { panel.windowModel.frameCursor.value.current != hidden }
        assertNull(shadowOf(panel).nextStartedActivity)
    }
    @Test fun hiddenCurrentAndRevokedSourceAreRemovedAndControllerUsesFrameRatherThanSharedSelection() {
        start(pad = PadSettings(profiles = mapOf("default" to PadProfile().assign(PadContext.VIEWER, PadAction.FAVORITE, PadBinding(setOf(PadButton.R3))))))
        app.session.selectMedia("c")
        key(KeyEvent.KEYCODE_BUTTON_R2); assertEquals("b", panel.windowModel.frameCursor.value.current)
        assertEquals("c", app.session.selectedMediaId)
        key(KeyEvent.KEYCODE_BUTTON_THUMBR)
        pump { app.catalog.snapshot.value.byId["b"]?.favorite == true }
        assertFalse(app.catalog.snapshot.value.byId["c"]!!.favorite)
        runBlocking { app.catalog.hideAlbums(listOf("folder"), true) }
        pump { panel.windowModel.frameCursor.value.current == "c" }
        compose.onNodeWithContentDescription("a.png").assertDoesNotExist()
        compose.onNodeWithContentDescription("b.png").assertDoesNotExist()
        compose.onNodeWithContentDescription("Foto successiva").assertIsNotEnabled()
        val c = panel.windowModel.frameCursor.value.current; advance(10_000); assertEquals(c, panel.windowModel.frameCursor.value.current)
        val tree = Uri.parse(app.catalog.snapshot.value.sources.single().treeUri)
        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runBlocking { app.catalog.refreshAccess() }
        pump { panel.windowModel.frameCursor.value.current == null }
        compose.onNodeWithContentDescription("c.png").assertDoesNotExist()
        compose.onNodeWithText("Scegli le immagini della cornice").assertExists()
        assertTrue(app.catalog.snapshot.value.byId["b"]!!.favorite)
    }
    @Test @Config(qualifiers = "it-w360dp-h640dp-port")
    fun guidedSourceAndEffectDraftSurviveRotationAndOnlyStartAfterPlay() {
        RuntimeEnvironment.setFontScale(1.4f)
        start()
        compose.onNodeWithContentDescription("Configura cornice").performClick()
        compose.onNodeWithTag("frame-setup").assertExists()
        compose.onNodeWithTag("frame-next").assertIsNotEnabled()
        compose.onNodeWithTag("frame-album:folder").performClick()
        compose.onNodeWithTag("frame-next").performClick()
        compose.onNodeWithTag("frame-transition-menu").performClick()
        compose.onNodeWithTag("frame-effect:LEFT").performClick()
        assertFalse(panel.windowModel.framePlaying.value)
        controller.recreate(); controller.windowFocusChanged(true); compose.waitForIdle()
        compose.onNodeWithTag("frame-transition-menu").performClick()
        compose.onNodeWithTag("frame-effect:LEFT").assertExists()
        assertEquals("LEFT", panel.windowModel.frameConfig.value.transition)
        compose.onNodeWithTag("frame-save").performClick()
        assertTrue(panel.windowModel.framePlaying.value)
        assertEquals(setOf("folder"), panel.windowModel.frameConfig.value.albums)
        assertEquals("LEFT", panel.windowModel.frameConfig.value.transition)
        compose.onNodeWithTag("photo-frame").assertExists()
    }
    @Test fun choosingOneAlbumReplacesLegacyMultipleSourcesAndExcludesCollections() {
        val preserved = (1..127).map { "unavailable-$it" }.toSet()
        start(FrameConfig(scope = "SELECTED", albums = preserved + "folder"))
        compose.onNodeWithContentDescription("Configura cornice").performClick()
        compose.onNodeWithTag("frame-album:other").performClick()
        compose.onNodeWithTag("frame-next").performClick()
        compose.onNodeWithTag("frame-save").performClick()
        assertEquals(setOf("other"), panel.windowModel.frameConfig.value.albums)
        assertTrue(panel.windowModel.frameConfig.value.collections.isEmpty())
    }
    @Test fun powerSavingGameModeDialogAndKeepAwakeDoNotLeaveBackgroundWorkOrFlags() {
        start(FrameConfig(seconds = 2, keepAwake = true), playing = true)
        assertTrue(panel.window.decorView.keepScreenOn)
        val manager = app.getSystemService(PowerManager::class.java)
        shadowOf(manager).setIsPowerSaveMode(true)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)); shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle()
        assertFalse(panel.window.decorView.keepScreenOn)
        compose.onNodeWithTag("frame-status").assertTextContains("risparmio energetico", substring = true)
        val current = panel.windowModel.frameCursor.value.current; advance(5_000); assertEquals(current, panel.windowModel.frameCursor.value.current)
        shadowOf(manager).setIsPowerSaveMode(false)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)); shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle()
        runBlocking { app.preferences.save(app.preferences.state.value.copy(gameMode = true)) }; compose.waitForIdle()
        assertFalse(panel.window.decorView.keepScreenOn)
        val inGame = panel.windowModel.frameCursor.value.current; advance(5_000); assertEquals(inGame, panel.windowModel.frameCursor.value.current)
        runBlocking { app.preferences.save(app.preferences.state.value.copy(gameMode = false)) }; compose.waitForIdle()
        compose.onNodeWithContentDescription("Configura cornice").performClick(); compose.waitForIdle()
        assertFalse(panel.window.decorView.keepScreenOn)
        val inDialog = panel.windowModel.frameCursor.value.current; advance(5_000); assertEquals(inDialog, panel.windowModel.frameCursor.value.current)
        compose.onNodeWithTag("frame-setup").assertExists()
        panel.windowModel.frameConfiguring(false); compose.waitForIdle()
        assertTrue(panel.window.decorView.keepScreenOn)
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        assertFalse(panel.window.decorView.keepScreenOn); assertNull(panel.frameAction)
    }
    @Test fun realFadeFinishesAndUnreadableFilesHaveARetryWithoutChangingOriginals() {
        start()
        compose.mainClock.autoAdvance = false
        runBlocking { app.preferences.save(app.preferences.state.value.copy(reducedMotion = false)) }
        advance(32)
        key(KeyEvent.KEYCODE_BUTTON_R2)
        advance(160)
        compose.onNodeWithTag("frame-media:a").assertExists()
        compose.onNodeWithTag("frame-media:b").assertExists()
        advance(500); ready()
        compose.onNodeWithTag("frame-media:a").assertDoesNotExist()
        compose.onNodeWithTag("frame-media:b").assertExists()
        runBlocking { app.preferences.save(app.preferences.state.value.copy(reducedMotion = true)) }
        advance(32); compose.mainClock.autoAdvance = true
        panel.windowModel.go("SETTINGS"); compose.waitForIdle()
        // Listing preparation runs outside Compose's clock; wait for the outgoing surface to leave.
        pump { compose.onAllNodesWithTag("photo-frame").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("photo-frame").assertDoesNotExist()
        provider.failIds += listOf("a", "b", "c"); SingletonImageLoader.get(app).memoryCache?.clear()
        panel.windowModel.startFrame(); panel.windowModel.frameConfiguring(false)
        pump { compose.onAllNodesWithTag("photo-frame").fetchSemanticsNodes().isNotEmpty() && panel.windowModel.frameCursor.value.current == null }
        compose.onNodeWithText("Nessuna anteprima leggibile").assertExists()
        assertTrue(app.catalog.snapshot.value.media.all { it.available && !it.trashed })
        provider.failIds.clear()
        compose.onNodeWithText("Riprova").performClick(); ready()
        assertEquals("a", panel.windowModel.frameCursor.value.current)
    }
    private fun dialogView(): android.view.View {
        val view = ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView
        val root: Any = ReflectionHelpers.callInstanceMethod(view, "getViewRootImpl")
        Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(true)
        controller.windowFocusChanged(false)
        compose.waitForIdle()
        assertTrue(view.hasWindowFocus())
        return view
    }
    private fun capture(view: android.view.View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/$name.png").absoluteFile; file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
}

class FrameFixtureProvider : ContentProvider() {
    val reads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val failIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    @Volatile var beforeOpen: ((String) -> Unit)? = null
    private lateinit var delegate: PreviewFixtureProvider
    override fun onCreate(): Boolean {
        val appContext = context!!
        delegate = PreviewFixtureProvider().apply { attachInfo(appContext, ProviderInfo().apply { authority = "ayngallery.framepixels" }) }; return true
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = DocumentsContract.getDocumentId(uri); reads += id
        beforeOpen?.invoke(id)
        if (id in failIds) throw java.io.FileNotFoundException("Fixture unavailable")
        return delegate.openFile(uri, mode)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        if (uri.pathSegments.lastOrNull() == "children") return delegate.query(uri, projection, selection, args, sort) ?: MatrixCursor(projection ?: emptyArray())
        val id = DocumentsContract.getDocumentId(uri); val columns = projection ?: emptyArray()
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> { when (it) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id; DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (id == "root") "Frame fixture" else "$id.png"
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == "root") DocumentsContract.Document.MIME_TYPE_DIR else "image/png"
            DocumentsContract.Document.COLUMN_SIZE -> 100L; DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1L; else -> 0
        } }.toTypedArray()) }
    }
    override fun getType(uri: Uri) = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
