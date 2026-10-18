package it.ayngallery

import android.app.Activity
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PreferencesTransferRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    private lateinit var provider: SettingsFixtureProvider
    private val uri = Uri.parse("content://ayngallery.transfer/settings.json")
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun start() {
        provider = SettingsFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.transfer"; exported = false })
        ShadowContentResolver.registerProviderInternal("ayngallery.transfer", provider)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        runBlocking {
            app.preferences.save(GalleryPreferences(themeId = GalleryThemes.WHITE, reducedMotion = true))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        panel.windowModel.go("SETTINGS"); compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-transfer"))
        compose.onNodeWithTag("settings-transfer").performClick(); compose.waitForIdle()
        assertEquals("TRANSFER", panel.windowModel.route.value.tab)
    }
    private fun choose(raw: String) {
        pump { compose.onAllNodesWithTag("choose-settings").fetchSemanticsNodes().singleOrNull()?.config
            ?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        provider.document.writeText(raw)
        compose.waitForIdle()
        compose.onNodeWithTag("choose-settings").performScrollTo().assertIsEnabled().performClick()
        val picker = shadowOf(panel).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
        shadowOf(panel).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        pump { compose.onAllNodesWithTag("settings-preview").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Operazione non riuscita.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun pump(done: () -> Boolean) { compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() } }
    @Test fun actualPickerPreviewRotationSaveAndExportUseOnlyCommittedPreferences() = workflow("landscape")
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun previewAndSaveRemainReachableWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f); workflow("large-text")
    }
    private fun workflow(layout: String) {
        start()
        val original = app.preferences.state.value
        val display = app.session.state
        val different = PadProfile().assign(PadContext.DIALOG, PadAction.CONFIRM, PadBinding(setOf(PadButton.R1)))
        val imported = original.copy(themeId = "rose_milk", compact = true, pad = PadSettings(mapOf("default" to different, "foreign-pad" to different)))
        choose(" ".repeat(200_000) + PreferencesTransfer.encode(imported))
        assertEquals(original, app.preferences.state.value)
        compose.onNodeWithTag("import-common-profile").assertIsOff()
        activity!!.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("apply-settings-import").assertIsDisplayed().assertIsEnabled()
        capture(panel.window.decorView, "settings-import-$layout")
        compose.onNodeWithTag("apply-settings-import").performClick()
        pump { app.preferences.state.value.themeId == "rose_milk" }
        assertEquals(original.pad.profile(null), app.preferences.state.value.pad.profile(null))
        assertEquals(different, app.preferences.state.value.pad.profile("foreign-pad"))
        assertEquals(display, app.session.state)
        val frozen = app.preferences.state.value
        compose.onNodeWithTag("export-settings").performScrollTo().performClick()
        val picker = shadowOf(panel).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.action)
        assertEquals("application/json", picker.intent.type)
        assertNotNull(picker.options)
        val expectedOptions = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(app.session.current(panel)!!.id).toBundle()
        expectedOptions.keySet().forEach { assertEquals(expectedOptions.get(it), picker.options!!.get(it)) }
        activity!!.recreate(); compose.waitForIdle()
        runBlocking { app.preferences.update { it.copy(themeId = "stocking_pink") } }
        shadowOf(panel).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        pump { compose.onAllNodesWithText("Impostazioni esportate e verificate.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(frozen, PreferencesTransfer.decode(provider.document.readText()))
        assertEquals("stocking_pink", app.preferences.state.value.themeId)
        assertEquals(display, app.session.state)
    }
    @Test fun invalidStaleDiscardCancelledAndUnverifiedExportsHaveNoImportEffects() {
        start()
        val original = app.preferences.state.value
        choose("invalid json")
        compose.onNodeWithTag("apply-settings-import").assertDoesNotExist()
        assertEquals(original, app.preferences.state.value)
        val raw = PreferencesTransfer.encode(original.copy(themeId = "sky_milk"))
        choose(raw)
        runBlocking { app.preferences.update { it.copy(compact = true) } }; compose.waitForIdle()
        compose.onNodeWithTag("apply-settings-import").assertIsNotEnabled()
        compose.onNodeWithTag("discard-settings-import").performClick()
        assertEquals(original.copy(compact = true), app.preferences.state.value)
        choose(raw)
        compose.onNodeWithTag("discard-settings-import").performClick()
        compose.onNodeWithTag("choose-settings").performScrollTo().performClick()
        val cancelled = shadowOf(panel).nextStartedActivityForResult
        shadowOf(panel).receiveResult(cancelled.intent, Activity.RESULT_CANCELED, null); compose.waitForIdle()
        compose.onNodeWithTag("apply-settings-import").assertDoesNotExist()
        compose.onNodeWithTag("export-settings").performScrollTo().performClick()
        val export = shadowOf(panel).nextStartedActivityForResult
        provider.corruptNextRead = true
        shadowOf(panel).receiveResult(export.intent, Activity.RESULT_OK, Intent().setData(uri))
        pump { compose.onAllNodesWithText("Esportazione non verificata.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Impostazioni esportate e verificate.", substring = true).assertDoesNotExist()
        assertEquals(original.copy(compact = true), app.preferences.state.value)
    }
    private fun capture(view: View, name: String) {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val directory = File("../qa/rendered").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}

/** Only app-private QA fixture bytes. No personal media/provider access. */
class SettingsFixtureProvider : ContentProvider() {
    val document get() = File(context!!.filesDir, "qa-transfer-settings.json")
    var corruptNextRead = false
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(uri.lastPathSegment == "settings.json")
        if (mode == "r" && corruptNextRead) { corruptNextRead = false; document.writeText("corrupted fixture") }
        return ParcelFileDescriptor.open(document, ParcelFileDescriptor.parseMode(mode))
    }
    override fun getType(uri: Uri) = "application/json"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
