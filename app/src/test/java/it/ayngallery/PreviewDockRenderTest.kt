package it.ayngallery

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
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
class PreviewDockRenderTest {
    @Test fun musicCanParkByDoubleTappingTheRightSideAndByDraggingLeft() {
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            PreviewDock("preview-music", R.drawable.ph_music_notes, true) { drag -> Surface(Modifier.size(240.dp, 64.dp).then(drag).testTag("music-dock-content")) {} }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("music-dock-content").performTouchInput { doubleClick(Offset(width - 8f, height / 2f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("preview-music-dock").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("music-dock-content").performTouchInput { swipe(center, center - Offset(200f, 0f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("preview-music-dock").assertIsDisplayed()
    }
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var window: ActivityController<ComponentActivity>
    @After fun close() { if (::window.isInitialized) window.pause().stop().destroy(); (RuntimeEnvironment.getApplication() as GalleryApp).onTerminate() }
    @Test fun fixedStripCannotMoveAndMusicKeepsClearOfIt() {
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            Box(Modifier.fillMaxSize()) {
                PreviewDock("gif-strip", R.drawable.ph_film_strip, true, fixed = true) { drag -> Surface(Modifier.size(200.dp, 64.dp).then(drag).testTag("fixed-strip")) {} }
                PreviewDock("preview-music", R.drawable.ph_music_notes, true) { drag -> Surface(Modifier.size(240.dp, 70.dp).then(drag).testTag("music-content")) {} }
            }
        } }
        compose.waitForIdle()
        val strip = compose.onNodeWithTag("fixed-strip")
        val before = strip.fetchSemanticsNode().boundsInRoot
        strip.performTouchInput { swipe(center, center + Offset(100f, -100f)) }
        compose.waitForIdle()
        assertEquals(before, strip.fetchSemanticsNode().boundsInRoot)
        val music = compose.onNodeWithTag("music-content").fetchSemanticsNode().boundsInRoot
        assertTrue(music.bottom <= before.top - 10f || music.right <= before.left - 10f || music.left >= before.right + 10f)
    }
    @Test fun overlaysKeepAMarginFromNavigatorWithoutMovingOtherPositions() {
        val navigator = androidx.compose.ui.geometry.Rect(610f, 400f, 788f, 588f)
        val size = androidx.compose.ui.unit.IntSize(220, 70)
        val safe = avoidPreviewNavigator(Offset(560f, 500f), size, navigator, 12f, 800f)
        assertTrue(safe.x + size.width <= navigator.left - 12f || safe.y + size.height <= navigator.top - 12f)
        val untouched = Offset(20f, 20f)
        assertEquals(untouched, avoidPreviewNavigator(untouched, size, navigator, 12f, 800f))
        val tab = avoidPreviewNavigator(Offset(774f, 500f), androidx.compose.ui.unit.IntSize(26, 64), navigator, 12f, 800f, fixedEdge = true)
        assertEquals(774f, tab.x); assertTrue(tab.y + 64 <= navigator.top - 12f)
    }
    @Test fun pauseParksOnlyOnTransitionAndRecreatedClosedSessionStartsUndocked() {
        val parked = androidx.compose.runtime.mutableStateOf(false)
        val shown = androidx.compose.runtime.mutableStateOf(true)
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            if (shown.value) PreviewDock("pause-state", R.drawable.ph_music_notes, true, parked = parked.value) { drag -> Surface(Modifier.size(120.dp, 64.dp).then(drag).testTag("pause-content")) {} }
        } }
        compose.waitForIdle(); parked.value = true; compose.waitForIdle()
        compose.onNodeWithTag("pause-state-dock").assertIsDisplayed()
        shown.value = false; compose.waitForIdle()
        (RuntimeEnvironment.getApplication() as GalleryApp).session.previewDocks.clear()
        shown.value = true; compose.waitForIdle()
        compose.onNodeWithTag("pause-content").assertIsDisplayed()
        compose.onNodeWithTag("pause-state-dock").assertDoesNotExist()
    }
    @Test fun consecutiveDragsContinueFromTheLastPositionAndKeepVerticalInset() {
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            PreviewDock("repeat-drag", R.drawable.ph_music_notes, true) { drag -> Surface(Modifier.size(120.dp, 64.dp).then(drag).testTag("repeat-content")) {} }
        } }
        compose.waitForIdle()
        val item = compose.onNodeWithTag("repeat-content")
        item.performTouchInput { swipe(center, center + Offset(80f, -40f)) }; compose.waitForIdle()
        val first = item.fetchSemanticsNode().boundsInRoot
        item.performTouchInput { swipe(center, center + Offset(80f, -40f)) }; compose.waitForIdle()
        val second = item.fetchSemanticsNode().boundsInRoot
        assertTrue("Second drag must continue to the right", second.left > first.left + 50f)
        assertTrue("Second drag must continue upward", second.top < first.top - 20f)
        assertTrue(second.top > 0f)
    }
    @Test fun overlayParksAndRestoresWithoutSwipingTheUnderlyingMedia() {
        var underlyingSwipes = 0
        window = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        window.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); underlyingSwipes++ } })
                PreviewDock("test-overlay", R.drawable.ph_film_strip, true) { drag ->
                    Surface(Modifier.size(240.dp, 64.dp).then(drag).testTag("test-content")) {}
                }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("test-content").performTouchInput { swipe(center, center - Offset(300f, 0f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("test-content").assertDoesNotExist()
        compose.onNodeWithTag("test-overlay-dock").assertIsDisplayed()
        assertEquals(0, underlyingSwipes)
        compose.onNodeWithTag("test-overlay-dock").performTouchInput { swipe(center, center + Offset(100f, 0f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("test-content").assertIsDisplayed()
        compose.onNodeWithTag("test-overlay-dock").assertDoesNotExist()
        assertEquals(0, underlyingSwipes)
    }
}
