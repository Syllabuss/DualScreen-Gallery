package it.ayngallery

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w620dp-h420dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AlbumToolsRenderTest {
    @Test fun settingsConfigureAndChangeTheOneCommonPin() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) { AlbumPinSettings(app) } }
        compose.onNodeWithText("Aggiungi PIN").performClick()
        repeat(2) { for (digit in "1234") compose.onNodeWithTag("pin:$digit").performClick() }
        pump { app.catalog.protection.configured && compose.onAllNodesWithTag("album-pin-dialog").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Cambia PIN").performClick()
        for (digit in "1234") compose.onNodeWithTag("pin:$digit").performClick()
        // The title can change before the authorization coroutine re-enables the keypad.
        pump { compose.onAllNodesWithText("Crea il tuo PIN").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasTestTag("pin:1") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        for (digit in "4321") compose.onNodeWithTag("pin:$digit").performClick()
        pump { compose.onAllNodesWithText("Ripeti il PIN").fetchSemanticsNodes().isNotEmpty() }
        for (digit in "4321") compose.onNodeWithTag("pin:$digit").performClick()
        pump { compose.onAllNodesWithTag("album-pin-dialog").fetchSemanticsNodes().isEmpty() }
        runBlocking { assertFalse(app.catalog.protection.unlock("1234")); assertTrue(app.catalog.protection.unlock("4321")) }
        controller.pause().stop().destroy()
    }
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { app.onTerminate() }
    private fun pump(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        while (!check() && System.currentTimeMillis() < end) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
            compose.waitForIdle(); Thread.sleep(15)
        }
        assertTrue(check())
    }
    @Test fun numericKeypadConfirmsNewPinAndFitsSmallLandscapePanel() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var accepted = false
        val protection = AlbumProtection(app)
        controller.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            AlbumPinDialog(protection, "PROTECT", setOf("album"), dismiss = {}, accepted = { accepted = true })
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Crea il tuo PIN").assertIsDisplayed()
        compose.onNodeWithTag("pin:0").assertIsDisplayed()
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.let { view ->
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File("../qa/current/pin-create.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        for (digit in "1234") compose.onNodeWithTag("pin:$digit").performClick()
        compose.onNodeWithText("Ripeti il PIN").assertIsDisplayed()
        for (digit in "1234") compose.onNodeWithTag("pin:$digit").performClick()
        pump { accepted }
        assertEquals(setOf("album"), protection.state.value.protected)
        assertTrue(protection.state.value.unlocked.isEmpty())
        controller.pause().stop().destroy()
    }
    @Test fun wrongPinKeepsTheDialogAndKeypadInPlace() {
        runBlocking { app.catalog.protection.configure("1234") }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { GalleryDesign(GalleryPreferences()) {
                AlbumPinDialog(app.catalog.protection, "UNLOCK", dismiss = {}, accepted = {})
            } }
            compose.waitForIdle()
            val dialog = compose.onNodeWithTag("album-pin-dialog").fetchSemanticsNode().boundsInRoot
            val keypad = compose.onNodeWithTag("pin:5").fetchSemanticsNode().boundsInRoot
            for (digit in "9999") compose.onNodeWithTag("pin:$digit").performClick()
            pump { compose.onAllNodesWithText("PIN non corretto. Riprova.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(dialog, compose.onNodeWithTag("album-pin-dialog").fetchSemanticsNode().boundsInRoot)
            assertEquals(keypad, compose.onNodeWithTag("pin:5").fetchSemanticsNode().boundsInRoot)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun sectionSwitchHidesOnlyItsOwnControlAndChronologyMenuPersistsChoice() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var draft = GalleryPreferences()
        controller.get().setContent {
            var prefs by remember { mutableStateOf(draft) }
            GalleryDesign(prefs) { Column {
                SectionToolsSettings("VIDEO", prefs) { prefs = it; draft = it }
                TimelineMenu(app, prefs, "VIDEO")
            } }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tool:VIDEO:ZOOM").performClick()
        assertFalse(InterfaceTools.shown(draft, "VIDEO", "ZOOM"))
        assertTrue(InterfaceTools.shown(draft, "PHOTO", "ZOOM"))
        compose.onNodeWithTag("timeline-menu").performClick()
        compose.onNodeWithTag("timeline:MONTH").performClick()
        pump { app.preferences.state.value.timelineModes["VIDEO"] == "MONTH" }
        assertEquals("MONTH", PreferenceStore(app).state.value.timelineModes["VIDEO"])
        controller.pause().stop().destroy()
    }
}
