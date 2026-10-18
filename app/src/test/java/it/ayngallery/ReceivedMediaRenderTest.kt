package it.ayngallery

import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
import org.robolectric.shadows.ShadowDisplayManager
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReceivedMediaRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var incoming: IncomingFixture
    private lateinit var first: Uri
    private lateinit var second: Uri
    @Before fun start() {
        RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        incoming = IncomingFixture()
        incoming.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.incoming-fixture"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("ayngallery.incoming-fixture", incoming)
        first = incoming.add("Selected drawing.png", bytes = IncomingFixture.png())
        second = incoming.add("Other drawing.png", bytes = IncomingFixture.png())
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized } }
        }
    }
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun forwarded(request: Intent): Intent? {
        val receiver = Robolectric.buildActivity(ReceiveMediaActivity::class.java, request).create()
        val intent = shadowOf(receiver.get()).nextStartedActivity
        assertTrue(receiver.get().isFinishing)
        receiver.destroy()
        return intent
    }
    private fun open(request: Intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
        val intent = forwarded(request)!!
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible()
        pump { compose.onAllNodesWithTag("received-open").fetchSemanticsNodes().isNotEmpty() &&
            !compose.onNodeWithTag("received-open").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) &&
            compose.onAllNodesWithText("Selected drawing.png").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun pump(done: () -> Boolean) { compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() } }
    @Test fun receivedPreviewUsesSavedTouchPreferencesAndPinchWithoutAddingToCatalog() {
        val base = TouchSettings(doubleTap = TouchAction.NONE, longPress = TouchAction.FAVORITE)
        val touch = base.copy(threeFinger = base.threeFinger + (PadContext.VIEWER to (base.threeFinger.getValue(PadContext.VIEWER) + (TouchDirection.LEFT to TouchAction.MENU))))
        runBlocking { app.preferences.update { it.copy(touch = touch) } }
        open(); compose.onNodeWithTag("received-open").performClick()
        pump { compose.onAllNodesWithTag("received-surface").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("received-surface").performTouchInput { doubleClick(center) }
        compose.onNodeWithText("100% · reset").assertExists()
        compose.onNodeWithTag("received-surface").performTouchInput {
            down(0, center + androidx.compose.ui.geometry.Offset(-20f, 0f)); down(1, center + androidx.compose.ui.geometry.Offset(20f, 0f))
            updatePointerTo(0, center + androidx.compose.ui.geometry.Offset(-40f, 0f)); updatePointerTo(1, center + androidx.compose.ui.geometry.Offset(40f, 0f)); move(100); up(0); up(1)
        }
        compose.onNodeWithText("100% · reset").assertDoesNotExist()
        compose.onNodeWithTag("received-surface").performTouchInput { longClick(center, 650) }
        compose.onNodeWithTag("received-error").assertTextContains("Collega il file", substring = true)
        assertTrue(app.catalog.snapshot.value.media.isEmpty())
        compose.onNodeWithTag("received-surface").performTouchInput {
            for (i in 0..2) down(i, center + androidx.compose.ui.geometry.Offset((i - 1) * 10f, (i - 1) * 5f))
            for (i in 0..2) updatePointerTo(i, center + androidx.compose.ui.geometry.Offset((i - 1) * 10f - 80f, (i - 1) * 5f))
            move(100); up(0); up(1); up(2)
        }
        compose.onNodeWithTag("received-viewer").assertDoesNotExist()
        assertTrue(app.catalog.snapshot.value.media.isEmpty())
        assertNull(shadowOf(panel).nextStartedActivity)
    }
    @Test fun temporaryImageReviewHasReachableChoicesAndClosingSurvivesRotationWithoutImport() {
        open()
        assertEquals("RECEIVED", panel.windowModel.route.value.tab)
        assertFalse(panel.intent.hasExtra("received_media"))
        compose.onNodeWithTag("received-open").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("received-import").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("received-link").assertIsDisplayed().assertIsEnabled()
        capture("received-review-large-text")
        compose.onNodeWithTag("received-open").performClick()
        pump { compose.onAllNodesWithTag("received-viewer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("received-viewer").assertIsDisplayed()
        panel.padAction?.invoke(PadAction.ZOOM_IN)
        panel.padAction?.invoke(PadAction.PAN_LEFT)
        compose.waitForIdle(); capture("received-image-large-text")
        panel.padAction?.invoke(PadAction.DELETE); panel.padAction?.invoke(PadAction.FAVORITE)
        panel.padAction?.invoke(PadAction.MEDIA_NEXT)
        pump { compose.onAllNodesWithText("Other drawing.png").fetchSemanticsNodes().size > 1 }
        compose.onNodeWithTag("received-viewer").assertDoesNotExist()
        activity!!.recreate(); compose.waitForIdle()
        assertEquals("RECEIVED", panel.windowModel.route.value.tab)
        assertTrue(panel.windowModel.back()); compose.waitForIdle()
        activity!!.recreate(); compose.waitForIdle()
        assertEquals("ALBUM", panel.windowModel.route.value.tab); assertNull(panel.windowModel.received.value)
        assertTrue(runBlocking { app.catalog.dao.media().isEmpty() }); assertTrue(runBlocking { app.catalog.dao.sources().isEmpty() })
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        assertTrue(incoming.modes.all { it == "r" })
    }
    @Test fun receiverLaunchesOnlySafeAnchorDespiteRememberedPairAndRefusesOwnUris() {
        ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val launched = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        assertEquals(2, launched.size)
        activity = Robolectric.buildActivity(MainPanelActivity::class.java,
            launched.single { it.component!!.className == MainPanelActivity::class.java.name }).setup().visible()
        app.session.rememberStartupPair(panel, true)
        val safe = app.session.screens.first(); val ceded = app.session.screens.last()
        assertTrue(app.session.save(DisplayState(anchor = safe.key, ceded = setOf(ceded.key))))
        val intent = forwarded(Intent(Intent.ACTION_VIEW).setDataAndType(first, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))!!
        assertEquals(safe.key, intent.getStringExtra("display_key"))
        assertEquals(MainPanelActivity::class.java.name, intent.component!!.className)
        assertEquals(setOf(ceded.key), app.session.state.ceded)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        assertNull(forwarded(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://${app.packageName}.shares/file/private"), "image/png")))
        assertNull(forwarded(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.com")))
        assertTrue(runBlocking { app.catalog.dao.sources().isEmpty() })
    }
    @Test fun exactDocumentLinkRequiresVisibleConfirmationAndRotationDoesNotTakeGrantEarly() {
        val docs = OperationDocuments()
        val info = ProviderInfo().apply { authority = "ayngallery.received-docs"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
            packageName = app.packageName; name = OperationDocuments::class.java.name }
        docs.attachInfo(app, info); docs.nodes.getValue("image").file!!.writeBytes(IncomingFixture.png())
        ShadowContentResolver.registerProviderInternal(info.authority, docs)
        shadowOf(app.packageManager).addOrUpdateProvider(info)
        shadowOf(app.packageManager).addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        val uri = DocumentsContract.buildDocumentUri(info.authority, "image")
        val request = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val intent = forwarded(request)!!
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible()
        pump { compose.onAllNodesWithText("image.png").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("received-link").performClick()
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertTrue(runBlocking { app.catalog.dao.sources().isEmpty() })
        activity!!.recreate(); compose.waitForIdle()
        pump { runCatching { compose.onNodeWithTag("received-link-confirm").isDisplayed() }.getOrDefault(false) }
        compose.onNodeWithTag("received-link-confirm").assertIsDisplayed().assertIsEnabled()
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        compose.onNodeWithTag("received-link-confirm").performClick()
        pump { panel.windowModel.received.value == null }
        assertEquals("PHOTO", panel.windowModel.route.value.tab)
        val source = runBlocking { app.catalog.dao.sources().single() }; assertTrue(source.singleDocument)
        assertEquals(uri.toString(), source.treeUri); assertEquals(0, docs.childQueries)
        val grant = app.contentResolver.persistedUriPermissions.single(); assertTrue(grant.isReadPermission); assertFalse(grant.isWritePermission)
        assertEquals(0, docs.creations); assertTrue(docs.deletions.isEmpty())
        activity!!.recreate(); compose.waitForIdle(); assertEquals("PHOTO", panel.windowModel.route.value.tab)
    }
    private fun capture(name: String) {
        compose.waitForIdle(); val view = panel.window.decorView
        view.invalidate(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); compose.waitForIdle()
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(image))
        val file = File("../qa/rendered/$name.png").absoluteFile; file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
