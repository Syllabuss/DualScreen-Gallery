package it.ayngallery

import android.app.ActivityOptions
import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
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
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land", shadows = [WindowsFileProviderPaths::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExternalMediaRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var provider: OperationDocuments
    private lateinit var original: MediaEntity
    private lateinit var other: MediaEntity
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun start() {
        WindowsFileProviderPaths.reset()
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.externalui"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.externalui", provider)
        val image = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(122, 89, 159))
        provider.nodes.getValue("image").file!!.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        provider.add("destination", "second.png", provider.nodes.getValue("image").file!!.readBytes())
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.externalui", "root")
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val source = SourceEntity("source", tree.toString(), "QA external", "VISUAL")
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            original = app.catalog.dao.media().single { it.name == "image.png" }; other = app.catalog.dao.media().single { it.name == "second.png" }
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.media.size == 2 } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val initial = shadowOf(launcher).nextStartedActivity
        // Instrumentation keeps separate global queues for startActivity and its options record.
        assertEquals(initial.component, shadowOf(launcher).nextStartedActivityForResult.intent.component)
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, initial).setup().visible()
        panel.windowModel.go("PHOTO")
        compose.waitForIdle()
    }
    private fun pump(timeoutMillis: Long = 10000, done: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); compose.mainClock.advanceTimeByFrame(); done() } }
        catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            println("Sharing state: started=${panel.isStartedFlow.value}, error=${app.session.error}, route=${panel.windowModel.route.value}")
            println(compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { it.config.toString() })
            compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { println(compose.onAllNodes(isRoot())[it].printToString()) }
            throw failure
        }
    }
    private fun menu(action: String) {
        panel.windowModel.open(original.id); compose.waitForIdle()
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        compose.onNodeWithText(action).performScrollTo().performClick(); compose.waitForIdle()
    }
    @Test fun detailsLoadsPixelDimensionsAndKeepsSizeAndPathsOutOfTheActions() {
        start(); menu("Dettagli")
        compose.onNodeWithTag("media-details").assertIsDisplayed()
        // Cold native decoding can outlast the usual UI deadline on the Linux runner.
        // Keep the real resolution assertion and a bounded wait; never substitute cached metadata.
        pump(timeoutMillis = 30000) { compose.onAllNodesWithText("240 × 180 px").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Peso").assertIsDisplayed()
        compose.onNodeWithText("Cartella principale").performScrollTo().assertIsDisplayed()
        assertTrue(provider.deletions.isEmpty()); assertTrue(app.files.operations.value.isEmpty())
    }
    @Test fun emptyFirstLaunchWelcomesAndOpensFolderPickerWithoutGlobalPermission() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val initial = shadowOf(launcher).nextStartedActivity
        assertEquals(initial.component, shadowOf(launcher).nextStartedActivityForResult.intent.component)
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, initial).setup().visible()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("tutorial-next").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tutorial-dot:0:true").assertExists()
        compose.waitForIdle()
        captureTutorial("tutorial-shortcuts")
        compose.onNodeWithTag("tutorial-dot:1:false").assertExists()
        fun shoulder(code: Int, repeat: Int = 0) {
            val before = (0..7).first { compose.onAllNodesWithTag("tutorial-page:$it").fetchSemanticsNodes().isNotEmpty() }
            panel.receiveControllerKey(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, repeat))
            if (repeat == 0) panel.receiveControllerKey(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 3))
            compose.waitForIdle()
            compose.onNodeWithTag("tutorial-page:$before").assertExists()
            panel.receiveControllerKey(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            compose.waitForIdle()
        }
        shoulder(android.view.KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithTag("tutorial-page:0").assertExists()
        assertFalse(app.preferences.tutorialCompleted.value)
        shoulder(android.view.KeyEvent.KEYCODE_BUTTON_L1)
        compose.onNodeWithTag("tutorial-page:0").assertExists()
        shoulder(android.view.KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithTag("tutorial-page:1").assertExists()
        shoulder(android.view.KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithTag("tutorial-page:1").assertExists()
        shoulder(android.view.KeyEvent.KEYCODE_BUTTON_R1, 1)
        compose.onNodeWithTag("tutorial-page:1").assertExists()
        shoulder(android.view.KeyEvent.KEYCODE_BUTTON_L1)
        compose.onNodeWithTag("tutorial-page:0").assertExists()
        repeat(6) { step ->
            compose.onNodeWithTag("tutorial-next").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("tutorial-page:${step + 1}").assertExists()
                .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            compose.onNodeWithTag("tutorial-next").assertIsDisplayed()
            captureTutorial("tutorial-step-${step + 1}")
        }
        assertNull(shadowOf(panel).nextStartedActivityForResult)
        compose.onNodeWithTag("tutorial-folder-count").assertTextEquals("0 cartelle selezionate")
        shoulder(android.view.KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithTag("tutorial-page:7").assertExists()
        compose.onNodeWithTag("tutorial-skip").assertDoesNotExist()
        shoulder(android.view.KeyEvent.KEYCODE_BUTTON_L1)
        compose.onNodeWithTag("tutorial-page:6").assertExists()
        assertFalse(app.preferences.tutorialCompleted.value)
        compose.onNodeWithTag("welcome-choose-folder").assertIsDisplayed()
        compose.onNodeWithTag("welcome-choose-music").assertIsDisplayed()
        captureTutorial("tutorial-folders")
        compose.onNodeWithTag("welcome-choose-folder").performClick()
        assertFalse(app.preferences.tutorialCompleted.value)
        val folderPicker = shadowOf(panel).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, folderPicker.intent.action)
        val documents = OperationDocuments()
        documents.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.onboarding"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.onboarding", documents)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.onboarding", "root")
        shadowOf(panel).receiveResult(folderPicker.intent, android.app.Activity.RESULT_OK, Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        pump { compose.onAllNodesWithText("1 cartelle selezionate").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("ALBUM", app.session.libraryModel()!!.route.value.tab)
        compose.onNodeWithTag("tutorial-folder-recap").assertIsDisplayed()
        runBlocking {
            (1..9).forEach { index -> app.catalog.dao.saveSource(SourceEntity("tutorial-extra-$index", "content://tutorial/folder-$index", "Folder $index", if (index % 2 == 0) "MUSIC" else "VISUAL")) }
        }
        pump { compose.onAllNodesWithText("10 cartelle selezionate").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tutorial-page:6").assertExists()
        compose.onNodeWithTag("tutorial-folder-overflow").assertIsDisplayed()
        compose.onNodeWithText("Folder 1").assertHeightIsAtLeast(12.dp)
        compose.onNodeWithTag("tutorial-next").assertIsDisplayed()
        compose.onNodeWithTag("welcome-choose-folder").assertIsDisplayed()
        compose.onNodeWithTag("welcome-choose-music").assertIsDisplayed()
        captureTutorial("tutorial-linked-folders")
        compose.onNodeWithTag("tutorial-next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tutorial-page:7").assertExists()
        compose.onNodeWithTag("tutorial-skip").assertDoesNotExist()
        compose.onNodeWithTag("tutorial-next").performClick()
        pump { app.preferences.tutorialCompleted.value }
        assertFalse(app.session.tutorialInProgress)
        assertEquals("ALBUM", app.session.libraryModel()!!.route.value.tab)
    }
    @Test @Config(qualifiers = "it-w800dp-h480dp-land")
    fun compactTutorialKeepsEveryStepAndFolderRecapVisibleWithoutScrolling() {
        emptyFirstLaunchWelcomesAndOpensFolderPickerWithoutGlobalPermission()
    }
    @Test @Config(sdk = [33], shadows = [SharedStorageDocumentsTest.StorageEnvironment::class, WindowsFileProviderPaths::class, TutorialDisplayContext::class, TutorialActivityManager::class])
    fun tutorialOffersDownloadPermissionAndReturnsToSharedStoragePicker() {
        SharedStorageDocumentsTest.StorageEnvironment.permitted = false
        try {
            val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
            activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
            assertFalse("Panel rejected: ${app.session.error}; intent=${panel.intent.extras}", panel.isFinishing)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("tutorial-next").fetchSemanticsNodes().isNotEmpty() }
            repeat(6) { compose.onNodeWithTag("tutorial-next").performClick(); compose.waitForIdle() }
            compose.onNodeWithTag("welcome-choose-folder").performClick()
            compose.onNodeWithTag("folder-guidance-continue").performClick()
            val permission = shadowOf(panel).nextStartedActivityForResult
            assertEquals(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, permission.intent.action)
            SharedStorageDocumentsTest.StorageEnvironment.permitted = true
            shadowOf(panel).receiveResult(permission.intent, android.app.Activity.RESULT_OK, null)
            compose.waitForIdle()
            val picker = shadowOf(panel).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.intent.action)
            assertEquals(DocumentsContract.buildRootUri(SharedStorageDocuments.AUTHORITY, SharedStorageDocuments.ROOT), picker.intent.getParcelableExtra<android.net.Uri>(DocumentsContract.EXTRA_INITIAL_URI))
            assertTrue(app.session.tutorialInProgress)
        } finally { SharedStorageDocumentsTest.StorageEnvironment.permitted = false }
    }
    private fun captureTutorial(name: String) {
        val view = ShadowDialog.getLatestDialog().window!!.decorView
        assertTrue("Tutorial must use almost the whole screen width", view.width >= panel.resources.displayMetrics.widthPixels * .9f)
        assertTrue("Tutorial needs vertical breathing room", view.height <= panel.resources.displayMetrics.heightPixels - (48 * panel.resources.displayMetrics.density).toInt())
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            File("../qa/rendered/$name-${panel.resources.configuration.screenHeightDp}.png").apply { parentFile.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
    @Test fun configuredButEmptyRootDoesNotRepeatFirstLaunchTutorial() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val documents = OperationDocuments()
        documents.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.empty-root"; exported = true; grantUriPermissions = true;
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.empty-root", documents)
        documents.nodes.remove("image")
        runBlocking { app.catalog.addSource(DocumentsContract.buildTreeDocumentUri("ayngallery.empty-root", "root"), SourceRole.VISUAL, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        pump { app.albums.ready.value }
        compose.onNodeWithTag("tutorial-next").assertDoesNotExist()
        assertTrue(app.catalog.snapshot.value.visibleMedia.isEmpty())
    }
    @Test fun tutorialCanGoBackAndSkipWithoutRequestingPermission() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("tutorial-next").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tutorial-next").performClick()
        compose.onNodeWithTag("tutorial-dot:1:true").assertIsDisplayed()
        compose.onNodeWithTag("tutorial-previous").performClick()
        compose.onNodeWithTag("tutorial-dot:1:false").assertIsDisplayed()
        compose.onNodeWithTag("tutorial-skip").performClick()
        pump { app.preferences.tutorialCompleted.value }
        assertTrue(app.preferences.tutorialCompleted.value)
        assertNotEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(panel).nextStartedActivityForResult?.intent?.action)
        assertTrue(app.catalog.snapshot.value.sources.isEmpty())
    }
    @Test fun previewSideSheetOnlyOffersRelevantActionsAndGuidedFileDestinations() {
        start()
        panel.windowModel.open(original.id); compose.waitForIdle()
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        compose.onNodeWithTag("preview-side-sheet").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("preview-side-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left > 0f)
        assertTrue(bounds.height > bounds.width)
        listOf("Chiudi", "Aggiungi ai Preferiti", "Usa in un widget fotografico…", "Condividi ridimensionata…",
            "Condividi senza posizione…", "Usa come copertina dell'album", "Usa come copertina di una raccolta",
            "Nascondi questo album").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        pump { compose.onAllNodesWithText("Copia in…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Copia in…").performScrollTo().performClick()
        compose.onNodeWithTag("file-action-target").assertTextEquals(original.name)
        compose.onNodeWithText("Annulla").performClick()
        menu("Sposta in…")
        compose.onNodeWithTag("file-action-target").assertTextEquals(original.name)
        compose.onNodeWithText("Annulla").performClick()
        assertTrue(provider.deletions.isEmpty()); assertTrue(app.files.operations.value.isEmpty())
    }
    @Test fun nativeShareFrozenSelectionSurvivesRotationAndReturnsWithoutDisplayExpansion() = verify("landscape")
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun shareConfirmationAndCancelRemainReachableWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f); verify("large-text")
    }
    @Suppress("DEPRECATION") private fun verify(layout: String) {
        start()
        val policy = app.session.state
        menu("Condividi…")
        compose.onNodeWithTag("external-confirm").assertDoesNotExist()
        var launched: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        pump { launched = launched ?: shadowOf(panel).nextStartedActivityForResult; launched != null }
        val chooser = launched!!.intent
        assertEquals("Launch: $chooser", Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(original.uri, send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!.toString())
        assertEquals(1, send.clipData!!.itemCount); assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        val expectedOptions = ActivityOptions.makeBasic().setLaunchDisplayId(app.session.current(panel)!!.id).toBundle()
        expectedOptions.keySet().forEach { key -> assertEquals("Display option $key", expectedOptions.get(key), launched!!.options.get(key)) }
        assertTrue(chooser.filterEquals(shadowOf(panel).nextStartedActivity))
        app.session.selectMedia(other.id)
        activity!!.recreate(); compose.waitForIdle()
        assertEquals(policy, app.session.state); assertNull(shadowOf(panel).nextStartedActivity)
        activity!!.pause().stop().start().resume().visible(); compose.waitForIdle()
        assertEquals(policy, app.session.state); assertNull(shadowOf(panel).nextStartedActivity)
        assertTrue(provider.deletions.isEmpty()); assertTrue(app.files.operations.value.isEmpty())
        menu("Apri con…"); compose.onNodeWithTag("external-confirm").assertDoesNotExist()
        var viewChooser: Intent? = null
        pump { viewChooser = viewChooser ?: shadowOf(panel).nextStartedActivity; viewChooser != null }
        val view = viewChooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, view.action); assertEquals(original.uri, view.data!!.toString())
    }
    @Test fun multipleSelectionOpensTheNativeChooserDirectlyWithEveryFrozenReadOnlyUri() {
        start()
        compose.onNodeWithTag("media:${original.id}").performTouchInput { longClick() }
        compose.onNodeWithTag("media:${other.id}").performClick()
        compose.onNodeWithContentDescription("Opzioni media selezionati").performClick()
        compose.onNodeWithText("Condividi…").performClick()
        // Compose must launch the share effect before polling Android's activity queue.
        compose.waitForIdle()
        var chooser: Intent? = null
        pump { chooser = chooser ?: shadowOf(panel).nextStartedActivity; chooser != null }
        @Suppress("DEPRECATION") val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals(2, send.clipData!!.itemCount)
        assertEquals(setOf(original.uri, other.uri), (0 until 2).map { send.clipData!!.getItemAt(it).uri.toString() }.toSet())
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        compose.onNodeWithTag("external-count").assertDoesNotExist()
        assertTrue(provider.deletions.isEmpty()); assertTrue(app.files.operations.value.isEmpty())
    }
    private fun copyDialog() {
        val encoded = ExternalRequest(ExternalAction.SHARE, listOf(ExternalTarget.from(original)), ShareCopyOptions()).encode()
        panel.setContent {
            val data by app.catalog.snapshot.collectAsState()
            val prefs by app.preferences.state.collectAsState()
            GalleryDesign(prefs) { ExternalMediaDialog(panel, encoded, data, prefs) {} }
        }
        compose.waitForIdle()
    }
    @Test fun wallpaperShowsSystemScopeAndUnavailableCropperLeavesTheConfirmationOpen() {
        start(); menu("Imposta come sfondo…")
        compose.onNodeWithTag("external-confirm").assertIsDisplayed().performClick()
        pump { compose.onAllNodesWithTag("external-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("external-error").assertTextContains("ritaglio", substring = true)
        assertNull(shadowOf(panel).nextStartedActivity)
        capture("wallpaper-unavailable")
        compose.onNodeWithTag("external-cancel").performClick()
        assertTrue(provider.deletions.isEmpty()); assertTrue(app.files.operations.value.isEmpty())
    }
    @Test @Config(qualifiers = "it-w360dp-h640dp-port")
    @Suppress("DEPRECATION") fun explicitImageCopyExportRetainsAReachableReviewBeforeNativeChooser() {
        RuntimeEnvironment.setFontScale(1.4f)
        start(); val bytes = provider.nodes.getValue("image").file!!.readBytes()
        copyDialog()
        compose.onNodeWithTag("share-copy").performScrollTo().performClick()
        compose.onNodeWithTag("share-format:JPEG").performScrollTo().performClick()
        compose.onNodeWithTag("share-edge:1024").performScrollTo().performClick()
        capture("share-copy-options-large-text")
        compose.onNodeWithTag("external-confirm").assertIsDisplayed().performClick()
        pump { compose.onAllNodesWithTag("share-copies-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("external-confirm").assertIsEnabled()
        compose.onNodeWithTag("share-copy-size:${original.id}").performScrollTo().assertTextContains("240 × 180", substring = true)
        assertNull(shadowOf(panel).nextStartedActivity)
        assertEquals(1, File(app.filesDir, "share-copies").listFiles().orEmpty().size)
        compose.onNodeWithTag("external-content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10000f) }
        compose.onNodeWithTag("share-copy-size:${original.id}").assertIsDisplayed()
        compose.onNodeWithTag("share-copy-preview:${original.id}").assertIsDisplayed()
        capture("share-copy-review-large-text")
        compose.onNodeWithTag("external-confirm").assertIsDisplayed().performClick()
        var chooser: Intent? = null
        pump { chooser = chooser ?: shadowOf(panel).nextStartedActivity; chooser != null }
        val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertEquals("image/jpeg", send.type)
        val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${app.packageName}.share", uri.authority)
        assertEquals("image-001.jpg", uri.lastPathSegment)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertNotEquals(original.uri, uri.toString())
        val decoded = app.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }!!
        assertEquals(240, decoded.width); assertEquals(180, decoded.height); decoded.recycle()
        assertArrayEquals(bytes, provider.nodes.getValue("image").file!!.readBytes())
        assertTrue(provider.deletions.isEmpty()); assertTrue(app.files.operations.value.isEmpty())
    }
    @Test fun changingTheCopyDraftAndHidingItsAlbumDiscardPreviewsWithoutLaunchingARecipient() {
        start(); copyDialog()
        compose.onNodeWithTag("share-copy").performScrollTo().performClick()
        runBlocking { app.preferences.save(app.preferences.state.value.copy(gameMode = true)) }
        compose.waitForIdle()
        compose.onNodeWithTag("external-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("share-game-consent").performScrollTo().performClick()
        compose.onNodeWithTag("external-confirm").performClick()
        pump { compose.onAllNodesWithTag("share-copies-ready").fetchSemanticsNodes().isNotEmpty() }
        val first = File(app.filesDir, "share-copies").listFiles()!!.single()
        compose.onNodeWithTag("share-format:JPEG").performScrollTo().performClick()
        compose.onNodeWithTag("share-copies-ready").assertDoesNotExist()
        pump { !first.exists() }
        compose.waitForIdle()
        compose.onNodeWithTag("external-confirm").assertIsEnabled().assertIsDisplayed().performClick()
        pump { compose.onAllNodesWithTag("share-copies-ready").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { app.catalog.hideAlbums(listOf(original.albumId), true) }
        pump { !app.catalog.snapshot.value.usable(original) && File(app.filesDir, "share-copies").listFiles().orEmpty().isEmpty() }
        compose.onNodeWithTag("share-copy-preview:${original.id}").assertDoesNotExist()
        compose.onNodeWithTag("external-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("external-cancel").assertIsDisplayed().performClick()
        assertNull(shadowOf(panel).nextStartedActivity)
        assertTrue(File(app.filesDir, "share-copy-ledgers").listFiles().orEmpty().isEmpty())
        assertTrue(provider.deletions.isEmpty())
    }
    private fun capture(name: String) {
        compose.waitForIdle(); val view = ShadowDialog.getLatestDialog().window!!.decorView
        view.invalidate(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); compose.waitForIdle()
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(image))
        val file = File("../qa/rendered/$name.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}

/** Robolectric application-backed Activity bases need an explicit display on API 30+. */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
class TutorialDisplayContext {
    @Implementation fun getDisplay(): android.view.Display = RuntimeEnvironment.getApplication().getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
}

@Implements(android.app.ActivityManager::class)
class TutorialActivityManager : org.robolectric.shadows.ShadowActivityManager() {
    @Implementation fun isActivityStartAllowedOnDisplay(context: Context, displayId: Int, intent: Intent) = true
}
