package it.ayngallery

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExplorerScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var window: ActivityController<ComponentActivity>? = null
    private var main: ActivityController<MainPanelActivity>? = null
    private var partner: ActivityController<OtherPanelActivity>? = null
    @After fun close() { partner?.pause()?.stop()?.destroy(); main?.pause()?.stop()?.destroy(); window?.pause()?.stop()?.destroy(); app.onTerminate() }

    @Test fun bothDisplaysHaveDifferentPaneIdentitiesBeforeAndAfterSwap() {
        Shadows.shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        main = Robolectric.buildActivity(MainPanelActivity::class.java, Shadows.shadowOf(launcher).nextStartedActivity).setup().visible().windowFocusChanged(true)
        val first = main!!.get()
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        partner = Robolectric.buildActivity(OtherPanelActivity::class.java, Intent(first.intent).apply { removeExtra("ticket") }).setup().visible()
        val second = partner!!.get()
        val key = app.session.screens.first { it.key != first.screenKey }.key
        org.robolectric.util.ReflectionHelpers.setField(second, "screenKey", key)
        org.robolectric.util.ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed").add(key)
        app.explorer.browse(true)
        first.setContent { GalleryDesign(GalleryPreferences()) { NetworkScreen(first, upper = app.session.state.swapped) } }
        second.setContent { GalleryDesign(GalleryPreferences()) { NetworkScreen(second, upper = !app.session.state.swapped) } }
        compose.waitForIdle()
        assertTrue(app.session.canSwap())
        compose.onAllNodesWithTag("explorer-local").assertCountEquals(1)
        compose.onAllNodesWithTag("explorer-remote").assertCountEquals(1)
        compose.runOnIdle { app.session.swap() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("explorer-local").assertCountEquals(1)
        compose.onAllNodesWithTag("explorer-remote").assertCountEquals(1)
        assertEquals(ExplorerNavigation(), app.explorer.state.value)
        app.explorer.errors.value = mapOf(ExplorerSide.REMOTE to R.string.network_auth_error)
        compose.waitForIdle()
        compose.onAllNodesWithTag("explorer-connection-error").assertCountEquals(1)
        app.explorer.connectionForm.value = true
        compose.waitForIdle()
        compose.onNodeWithTag("explorer-connection-error").assertDoesNotExist()
    }

    @Test fun touchAndControllerBrowseOnlyTheGrantedTreeAndKeepTheOtherPaneUnchanged() {
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.explorerui"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.explorerui", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.explorerui", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.explorer.browse(true)
        app.explorer.choose(tree)
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window!!.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            ExplorerPane(app, ExplorerSide.LOCAL, Modifier.fillMaxSize())
        } }
        compose.waitUntil(10_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); app.explorer.state.value.local.entries.any { it.name == "Origin" } }
        compose.onNodeWithText("Origin").performClick()
        assertEquals(app.explorer.state.value.toString(), "child", DocumentsContract.getDocumentId(android.net.Uri.parse(app.explorer.state.value.local.location!!.directoryId)))
        try { compose.waitUntil(10_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); app.explorer.state.value.local.entries.any { it.name == "image.png" } || app.explorer.errors.value.isNotEmpty() } }
        catch (failure: Throwable) { throw AssertionError(app.explorer.state.value.toString(), failure) }
        assertEquals(app.explorer.state.value.toString(), emptyMap<ExplorerSide, Int>(), app.explorer.errors.value)
        compose.onNodeWithText("image.png").assertIsDisplayed().performClick()
        assertEquals(1, app.explorer.state.value.local.selected.size)
        compose.runOnIdle { assertTrue(app.explorer.controller(PadAction.BACK)) }
        compose.waitUntil(10_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); app.explorer.state.value.local.entries.any { it.name == "Origin" } }
        assertEquals(ExplorerPaneState(), app.explorer.state.value.remote)
        assertTrue(provider.deletions.isEmpty())
        val restored = ExplorerSession(app)
        try {
            restored.browse(true)
            compose.waitUntil(10_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); restored.state.value.local.entries.any { it.name == "Origin" } }
            assertEquals(tree.toString(), app.getSharedPreferences("network-explorer", android.content.Context.MODE_PRIVATE).getString("tree", null))
        } finally { restored.close() }
    }

    @Test fun invalidShareStopsLoadingClearsPasswordAndReportsGuidanceWithoutNetworkIo() {
        val password = "private".toCharArray()
        app.explorer.connect("PC/../Photos", "user", password)
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window!!.get().setContent { GalleryDesign(GalleryPreferences()) { ExplorerPane(app, ExplorerSide.REMOTE, Modifier.fillMaxSize()) } }
        compose.waitUntil(10_000) { app.explorer.errors.value[ExplorerSide.REMOTE] != null }
        assertFalse(app.explorer.state.value.remote.loading)
        assertTrue(password.all { it == '\u0000' })
        assertNull(app.explorer.state.value.remote.location)
        assertTrue(app.getSharedPreferences("network-explorer", android.content.Context.MODE_PRIVATE).all.isEmpty())

    }
    @Test fun unsubmittedConnectionDraftSurvivesLeavingAndReopeningTheForm() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window!!.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            if (visible.value) ExplorerConnectionDialog(false, app.explorer.lastAddress, app.explorer.lastUser,
                { visible.value = false }, draft = app.explorer::draftConnection) { _, _, secret -> secret.fill('\u0000') }
        } }
        compose.onNodeWithTag("explorer-address").assertTextContains("smb://192.168.1.100/DSGallery").performTextReplacement("smb://PC/Photos")
        compose.onNodeWithTag("explorer-user").assertTextContains("PC\\user").performTextReplacement("user")
        compose.onNodeWithTag("explorer-password").performTextInput("private")
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("explorer-address").assertTextContains("smb://PC/Photos")
        compose.onNodeWithTag("explorer-user").assertTextContains("user")
        assertEquals("", compose.onNodeWithTag("explorer-password").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertTrue(app.getSharedPreferences("network-explorer", android.content.Context.MODE_PRIVATE).all.isEmpty())
    }
    @Test fun connectionFailureRetainsShareAndUserWithoutSavingPassword() {
        val password = "private".toCharArray()
        app.explorer.connect("smb://127.0.0.1/Photos", "user", password)
        compose.waitUntil(10_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            app.explorer.errors.value[ExplorerSide.REMOTE] != null
        }
        assertTrue(password.all { it == '\u0000' })
        val restored = ExplorerSession(app)
        try {
            assertEquals("smb://127.0.0.1/Photos", restored.lastAddress)
            assertEquals("user", restored.lastUser)
            assertEquals(setOf("address", "user"), app.getSharedPreferences("network-explorer", android.content.Context.MODE_PRIVATE).all.keys)
        } finally { restored.close() }
    }
    @Test @Config(qualifiers = "it-w568dp-h320dp-land") fun connectionFormKeepsItsActionVisibleWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        var submitted = false
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window!!.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            ExplorerConnectionDialog(false, "smb://PC/Photos", "user", {}) { address, user, secret ->
                assertEquals("smb://PC/Photos", address); assertEquals("user", user)
                submitted = true; secret.fill('\u0000')
            }
        } }
        compose.onAllNodesWithText(localized(R.string.explorer_connect, "")).filter(hasClickAction()).onFirst()
            .assertIsDisplayed().performClick()
        assertTrue(submitted)
    }
}
