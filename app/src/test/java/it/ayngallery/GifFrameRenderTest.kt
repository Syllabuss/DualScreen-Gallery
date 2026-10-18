package it.ayngallery
import androidx.compose.ui.semantics.getOrNull

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GifFrameRenderTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createEmptyComposeRule(effectContext = kotlinx.coroutines.Dispatchers.Main)
    private lateinit var window: ActivityController<ComponentActivity>
    private lateinit var gif: GifPlayback
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { if (::window.isInitialized) window.pause().stop().destroy(); if (::gif.isInitialized) gif.close(); app.onTerminate() }
    @Test fun pauseButtonCollapsesTheStripAndPlayRestoresItsWidth() {
        gif = GifPlayback.fromBytes(javaClass.getResourceAsStream("/gif/three-frames.gif")!!.use { it.readBytes() }, app.catalog.scope)!!
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var shown by mutableStateOf(true)
        window.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) { if (shown) GifFrameStrip(gif) } }
        compose.waitForIdle()
        val width = gif.stripWidth
        compose.onNodeWithContentDescription("Pausa").performClick(); compose.waitForIdle()
        assertTrue(gif.paused); assertEquals(84f, gif.stripWidth)
        compose.onNodeWithTag("gif-strip-frames").assertDoesNotExist()
        shown = false; compose.waitForIdle(); shown = true; compose.waitForIdle()
        compose.onNodeWithContentDescription("Riproduci").performClick(); compose.waitForIdle()
        assertFalse(gif.paused); assertEquals(width, gif.stripWidth)
        compose.onNodeWithTag("gif-strip-frames").assertExists()
    }
    @Test fun draggingStripPausesAndShowsTheCentralFrameAtDifferentWidths() {
        val bytes = javaClass.getResourceAsStream("/gif/three-frames.gif")!!.use { it.readBytes() }
        gif = GifPlayback.fromBytes(bytes, app.catalog.scope)!!
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            LaunchedEffect(gif.requestRevision) { gif.animate(false) }
            Box(Modifier.fillMaxSize()) { GifFrameStrip(gif) }
        } }
        compose.waitForIdle()
        compose.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); gif.bitmap != null }
        for (width in listOf(266f, 420f)) {
            compose.runOnIdle { gif.stripWidth = width }
            compose.onNodeWithTag("gif-strip-frames").performTouchInput {
                swipe(centerRight - androidx.compose.ui.geometry.Offset(10f, 0f), centerLeft + androidx.compose.ui.geometry.Offset(10f, 0f), 800)
            }
            compose.waitForIdle()
            fun centralFrame(): Int {
                val bounds = compose.onNodeWithTag("gif-strip-frames").fetchSemanticsNode().boundsInRoot
                val visible = compose.onAllNodes(hasTestTagPrefix("gif-frame:") or hasTestTagPrefix("gif-copy:"))
                    .fetchSemanticsNodes().filter { it.boundsInRoot.overlaps(bounds) }
                val middle = visible.minBy { kotlin.math.abs(it.boundsInRoot.center.x - bounds.center.x) }
                return middle.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].substringAfter(':').toInt() % gif.count
            }
            // Layout, the centre selection and the real decoder settle independently.
            try {
            compose.waitUntil(5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val frame = centralFrame()
                gif.paused && gif.selected == frame && gif.index == frame
            }
            } catch (failure: Throwable) {
                val visible = compose.onAllNodes(hasTestTagPrefix("gif-frame:") or hasTestTagPrefix("gif-copy:")).fetchSemanticsNodes()
                throw AssertionError("width=$width paused=${gif.paused} selected=${gif.selected} decoded=${gif.index} failed=${gif.failed} centre=${centralFrame()} strip=${compose.onNodeWithTag("gif-strip-frames").fetchSemanticsNode().boundsInRoot} frames=${visible.map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] to it.boundsInRoot }}", failure)
            }
            assertTrue(gif.paused); assertEquals(centralFrame(), gif.selected); assertEquals(gif.selected, gif.index)
        }
    }
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
    @Test fun filmstripSelectionAndPlayResumeTheSameDisplayedFrame() {
        val bytes = javaClass.getResourceAsStream("/gif/three-frames.gif")!!.use { it.readBytes() }
        gif = GifPlayback.fromBytes(bytes, app.catalog.scope)!!
        var visible by mutableStateOf(true)
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            LaunchedEffect(gif.requestRevision) { gif.animate(false) }
            Box(Modifier.fillMaxSize()) { if (visible) GifFrameStrip(gif) }
        } }
        compose.waitForIdle()
        compose.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); gif.bitmap != null }
        val initialWidth = compose.onNodeWithTag("gif-frame-strip").fetchSemanticsNode().boundsInRoot.width
        assertTrue("The resize handle must follow the filmstrip", compose.onNodeWithTag("gif-strip-resize").fetchSemanticsNode().boundsInRoot.left >=
            compose.onNodeWithTag("gif-strip-frames").fetchSemanticsNode().boundsInRoot.right)
        compose.onNodeWithTag("gif-strip-resize").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(80f, 0f)) }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("gif-frame-strip").fetchSemanticsNode().boundsInRoot.width > initialWidth)
        compose.onNodeWithTag("gif-frame:2").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); gif.index == 2 }
        compose.onNodeWithTag("gif-frame:2").assertIsSelected()
        assertTrue(gif.paused)
        compose.runOnIdle { gif.stripWidth = 180f; visible = false }; compose.waitForIdle()
        assertTrue(gif.paused); assertEquals(2, gif.index)
        compose.runOnIdle { visible = true }; compose.waitForIdle()
        assertTrue(gif.paused)
        compose.onNodeWithTag("gif-frame:2").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Riproduci").performClick()
        compose.waitForIdle()
        assertFalse(gif.paused); assertNull(gif.selected); assertEquals(2, gif.index)
        compose.onNodeWithTag("gif-frame:2").assertIsNotSelected()
    }
}
