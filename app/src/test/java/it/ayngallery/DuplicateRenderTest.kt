package it.ayngallery

import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DuplicateRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var provider: OperationDocuments
    private lateinit var original: MediaEntity
    private lateinit var copy: MediaEntity
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun start(game: Boolean = false) {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.dupui"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.dupui", provider)
        val image = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(122, 89, 159))
        provider.nodes.getValue("image").file!!.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        val second = provider.add("destination", "second.png", provider.nodes.getValue("image").file!!.readBytes())
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.dupui", "root")
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true, gameMode = game))
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val source = SourceEntity("source", tree.toString(), "QA duplicates", "VISUAL")
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            original = app.catalog.dao.media().single { it.name == "image.png" }
            copy = app.catalog.dao.media().single { it.name == second.name }
            app.catalog.favoriteAll(listOf(original.id), true)
            app.catalog.collection("QA references", "MEDIA", listOf(original.id))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.media.size == 2 && it.byId[original.id]?.favorite == true } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-hub").performScrollToNode(hasTestTag("settings-category:TRASH"))
        compose.onNodeWithTag("settings-category:TRASH").performClick()
        compose.onNodeWithTag("recovery-tab:DUPLICATES").performClick(); compose.waitForIdle()
        assertEquals("TRASH", panel.windowModel.route.value.tab)
        assertEquals("IDLE", app.duplicates.state.value.phase)
    }
    private fun pump(done: () -> Boolean) { compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() } }
    @Test fun ftpIsAlwaysAvailableInToolsWithoutAddingItToHome() {
        start()
        compose.onNodeWithTag("recovery-tab:FTP").performClick(); compose.waitForIdle()
        assertEquals("FTP", app.session.recoveryTab)
        assertFalse(app.explorer.browsing.value)
        compose.onNodeWithTag("recovery-tab:FTP").assertIsOn()
        compose.onNodeWithTag("recovery-tab:BROWSE").performClick(); compose.waitForIdle()
        assertTrue(app.explorer.browsing.value)
        compose.onNodeWithTag("recovery-tab:BROWSE").assertIsOn()
        compose.onNodeWithTag("recovery-tab:FTP").performClick(); compose.waitForIdle()
        assertFalse(app.explorer.browsing.value)
        assertFalse(visibleNavbarTabs(app.preferences.state.value).any { it.key == "FTP" })
        compose.onNodeWithTag("recovery-tab:TRASH").performClick(); compose.waitForIdle()
        assertEquals("TRASH", app.session.recoveryTab)
    }
    @Test fun nativeSearchReferenceChoiceFrozenTrashAndChangedBytesUseProductionFlow() = verify("landscape")
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun duplicateControlsAndConfirmationRemainReachableWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f); verify("large-text")
    }
    @Test fun searchButtonFollowsTheScopeChoices() {
        start()
        val scope = compose.onNodeWithTag("duplicate-scope:ALL").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("duplicate-start").performScrollTo()
        val button = compose.onNodeWithTag("duplicate-start").fetchSemanticsNode().boundsInRoot
        assertTrue(button.top >= scope.bottom)
    }
    private fun verify(layout: String) {
        start()
        compose.onNodeWithTag("duplicate-start").performScrollTo().performClick()
        pump { app.duplicates.state.value.phase == "COMPLETED" }
        assertEquals(1, app.duplicates.state.value.groups.size)
        assertTrue(provider.deletions.isEmpty())
        activity!!.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("duplicates-list").performScrollToNode(hasTestTag("duplicate-group:${app.duplicates.state.value.groups.first().id}"))
        compose.onNodeWithTag("duplicate-group:${app.duplicates.state.value.groups.first().id}").performClick()
        compose.onNodeWithTag("duplicates-list").performScrollToNode(hasTestTag("duplicate-keep:${copy.id}"))
        compose.onNodeWithTag("duplicate-keep:${copy.id}").performScrollTo().performClick()
        compose.onNodeWithTag("duplicates-list").performScrollToNode(hasTestTag("duplicate-name:${original.id}"))
        compose.onNodeWithTag("duplicate-name:${original.id}").performScrollTo().assertIsDisplayed()
        capture(panel.window.decorView, "duplicates-$layout")
        compose.onNodeWithTag("duplicate-refs:${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("duplicate-transfer-confirm").assertIsDisplayed().performClick()
        pump { app.catalog.snapshot.value.byId[copy.id]?.favorite == true }
        assertFalse(app.catalog.snapshot.value.byId[original.id]!!.favorite)
        assertEquals(listOf(copy.id), app.catalog.snapshot.value.collectionItems.map { it.mediaId })
        assertTrue(provider.deletions.isEmpty())
        compose.onNodeWithTag("duplicate-trash:${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("file-action-target").assertTextEquals("image.png")
        compose.onNodeWithTag("duplicate-retained-target").assertTextContains("second.png", substring = true)
        app.session.selectMedia(copy.id); compose.waitForIdle()
        compose.onNodeWithTag("file-action-target").assertTextEquals("image.png")
        capture(ShadowDialog.getLatestDialog().window!!.decorView, "duplicate-trash-$layout")
        val originalBytes = provider.nodes.getValue("image").file!!.readBytes()
        provider.nodes.getValue("image").file!!.writeBytes(originalBytes.map { (it.toInt() xor 7).toByte() }.toByteArray())
        compose.onNodeWithTag("confirm-file-action").performClick()
        pump { app.files.operations.value.any { it.mediaId == original.id && it.phase == "FAILED" } }
        assertEquals("TRASH", panel.windowModel.route.value.tab)
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.deletions.isEmpty())
        assertFalse(runBlocking { app.catalog.dao.media(original.id)!!.trashed })
        assertEquals(0, runBlocking { app.catalog.dao.trash().size })
    }
    @Test fun nativeGameConsentScopeSelectionAndComparisonDoNotAutodelete() {
        start(game = true)
        compose.onNodeWithTag("duplicate-start").performScrollTo().performClick()
        compose.onNodeWithText("Leggere i file mentre giochi?").assertExists()
        compose.onNodeWithText("Annulla").performClick(); assertEquals("IDLE", app.duplicates.state.value.phase)
        compose.onNodeWithTag("duplicate-scope:MEDIA").performClick()
        compose.onNodeWithTag("duplicate-start").assertIsNotEnabled()
        compose.onNodeWithTag("duplicate-choose").performClick()
        compose.onNodeWithTag("duplicate-scope-item:${original.id}").performClick()
        compose.onNodeWithTag("duplicate-scope-item:${copy.id}").performClick()
        compose.onNodeWithText("Usa questa selezione").performClick()
        compose.onNodeWithTag("duplicate-start").performScrollTo().performClick()
        compose.onNodeWithText("Cerca anche ora").performClick()
        pump { app.duplicates.state.value.phase == "COMPLETED" }
        compose.onNodeWithTag("duplicates-list").performScrollToNode(hasTestTag("duplicate-group:${app.duplicates.state.value.groups.first().id}"))
        compose.onNodeWithTag("duplicate-group:${app.duplicates.state.value.groups.first().id}").performClick()
        compose.onNodeWithTag("duplicates-list").performScrollToNode(hasTestTag("duplicate-compare:${copy.id}"))
        compose.onNodeWithTag("duplicate-compare:${copy.id}").performScrollTo().performClick()
        compose.onNodeWithText("Confrontare queste due copie?").assertExists()
        compose.onNodeWithText("Apri confronto").performClick()
        assertEquals("COMPARE", panel.windowModel.route.value.tab)
        assertEquals(setOf(original.id, copy.id), app.comparison.state.value.panes.map { it.mediaId }.toSet())
        assertTrue(app.comparison.state.value.panes.all { it.pinned })
        assertTrue(provider.deletions.isEmpty()); assertTrue(app.files.operations.value.isEmpty())
    }
    private fun capture(view: android.view.View, name: String) {
        compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle()
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val file = File("../qa/rendered/$name.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
