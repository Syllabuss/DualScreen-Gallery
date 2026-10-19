package it.ayngallery

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AlbumDragRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }

    private fun start(grid: Boolean, video: Boolean = false, reducedMotion: Boolean = true,
        ids: List<String> = listOf("a", "b", "c", "d"), columns: Int = 4, horizontal: Boolean = false, carousel: Boolean = false) {
        runBlocking { app.preferences.save(GalleryPreferences(reducedMotion = reducedMotion, animatedAlbumCovers = false, horizontalAlbums = horizontal, albumCarousel = carousel)) }
        app.preferences.reorderAlbums(listOf("hidden") + ids)
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri("ayngallery.dragui", "root")
        fun uri(id: String) = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
        val albums = ids.map { AlbumEntity(it, it, uri(it), it, it, null) }
        val source = SourceEntity("source", tree.toString(), "QA", "VISUAL")
        val media = ids.map { MediaEntity("media:$it", "media:$it", uri("media:$it"), it, it, "video/mp4", "VIDEO", 1, 1) }
        val data = CatalogSnapshot(sources = listOf(source), albums = albums, media = media,
            membership = media.map { MediaSource(it.id, "source", 1) },
            albumMembership = ids.map { AlbumSource(it, "source", 1) }, initialized = true, protectedAlbumIds = ids.toSet())
        Shadows.shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, Shadows.shadowOf(launcher).nextStartedActivity).setup().visible().windowFocusChanged(true)
        panel.windowModel.go(if (video) "VIDEO" else "ALBUM")
        panel.windowModel.albumGrid(grid)
        panel.windowModel.albumColumns(columns)
        if (video) app.preferences.videoAlbumColumns(columns)
        val prefs = app.preferences.state.value
        panel.setContent { GalleryDesign(prefs) { AlbumScreen(panel, data, {}, videoOnly = video) } }
        compose.waitForIdle()
    }

    private fun point(id: String, after: Boolean = false): Offset {
        val bounds = compose.onNodeWithTag("album:$id").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("album-reorder-surface").fetchSemanticsNode().boundsInRoot
        return (if (after) Offset(bounds.right - 12f, bounds.bottom - 12f) else bounds.center) - surface.topLeft
    }
    private fun hold(id: String, origin: Offset = point(id)) {
        compose.onNodeWithTag("album-reorder-surface").performTouchInput { down(origin) }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("album-reorder-surface").performTouchInput {
            advanceEventTime(650); moveTo(origin)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("album-drag-preview").assertDoesNotExist()
    }
    private fun move(destination: Offset) {
        compose.onNodeWithTag("album-reorder-surface").performTouchInput { moveTo(destination, delayMillis = 100) }
        compose.waitForIdle()
    }
    private fun release() {
        compose.onNodeWithTag("album-reorder-surface").performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test fun horizontalAlbumsKeepTheirPreviewAndReorderAsAList() {
        start(grid = true, carousel = true)
        app.preferences.albumSize(setOf("b"), 4)
        compose.waitForIdle()
        compose.onNodeWithTag("albums-horizontal").assertIsDisplayed()
        val selectedWidth = compose.onNodeWithTag("album:a").fetchSemanticsNode().boundsInRoot.width
        val otherWidth = compose.onNodeWithTag("album:b").fetchSemanticsNode().boundsInRoot.width
        assertEquals(1.5f, selectedWidth / otherWidth, .02f)
        val destination = point("c")
        hold("a")
        move(destination)
        release()
        assertEquals(listOf("hidden", "b", "c", "a", "d"), app.preferences.albumOrder.value)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
    }

    @Test fun horizontalGridKeepsRowsAndListRemainsVertical() {
        start(grid = true, horizontal = true)
        compose.onNodeWithTag("albums-horizontal-grid").assertIsDisplayed()
        val first = compose.onNodeWithTag("album:a").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("album:b").fetchSemanticsNode().boundsInRoot
        assertTrue(second.top > first.top)
        val destination = point("b")
        hold("a"); move(destination); release()
        assertEquals(listOf("hidden", "b", "a", "c", "d"), app.preferences.albumOrder.value)
        compose.runOnIdle { panel.windowModel.albumGrid(false) }
        compose.onNodeWithTag("albums-list").assertIsDisplayed()
        compose.onNodeWithTag("albums-horizontal-grid").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "it-w400dp-h800dp-port")
    fun portraitCarouselKeepsCoversLeftAndUsesTheCommonCaption() {
        start(grid = true, carousel = true)
        compose.onNodeWithTag("albums-vertical-carousel").assertIsDisplayed()
        val before = compose.onNodeWithTag("album:b").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("carousel-label:b").assertDoesNotExist()
        compose.onNodeWithText("b").assertIsDisplayed()
        compose.onNodeWithTag("album-reorder-surface").performTouchInput { down(point("b")) }
        compose.mainClock.advanceTimeBy(220)
        val after = compose.onNodeWithTag("album:b").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, 1f)
        compose.onNodeWithText("b").assertIsDisplayed()
        compose.onNodeWithTag("album-reorder-surface").performTouchInput { cancel() }
    }

    @Test fun pairReorderSwapsTwoAlbumsWithoutOpeningThemAndBackExits() {
        start(grid = true)
        val original = app.preferences.albumOrder.value
        app.session.albumCommand = "REORDER" to setOf("a")
        compose.waitForIdle()
        compose.onNodeWithTag("album-swap-marker:a").assertExists()
        compose.onNodeWithTag("album:a").performClick()
        assertNull(panel.windowModel.route.value.album)
        compose.onNodeWithTag("album:c").performClick()
        compose.waitForIdle()
        assertEquals("a", app.session.previewAlbumId)
        assertEquals("a", panel.windowModel.albumAnchor)
        compose.onNodeWithTag("album:a").assertIsFocused()
        val expected = original.toMutableList()
        java.util.Collections.swap(expected, expected.indexOf("a"), expected.indexOf("c"))
        assertEquals(expected, app.preferences.albumOrder.value)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        assertNull(panel.windowModel.route.value.album)
        compose.runOnUiThread { panel.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("album-swap-marker:a").assertDoesNotExist()
    }
    @Test fun stationaryHoldOpensOptionsWithoutSelectingAlbum() {
        start(grid = true)
        hold("a"); release()
        assertEquals("a", app.session.albumActionTarget)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
    }

    @Test fun singleListDragPreviewsTheOrderWithoutRepeatedSwapsAndClearsSelection() {
        start(grid = false)
        panel.windowModel.selectAlbums(setOf("a")); compose.waitForIdle()
        val destination = point("c", after = true)
        val before = point("b")
        hold("a"); move(destination)
        assertTrue(point("b").y < before.y)
        val preview = point("b")
        assertEquals(listOf("hidden", "a", "b", "c", "d"), app.preferences.albumOrder.value)
        move(destination); assertEquals(preview, point("b")); release()
        assertEquals(listOf("hidden", "b", "c", "a", "d"), app.preferences.albumOrder.value)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
    }
    @Test fun videoGridDragPreservesTheSelectedBlock() {
        start(grid = true, video = true)
        panel.windowModel.selectAlbums(setOf("a", "b")); compose.waitForIdle()
        val destination = point("d", after = true)
        hold("a"); move(destination); release()
        assertEquals(listOf("hidden", "c", "d", "a", "b"), app.preferences.albumOrder.value)
        assertEquals(setOf("a", "b"), panel.windowModel.selectedAlbums.value)
    }
    @Test fun threeSelectedAlbumsKeepTheirOrderAndDoNotOscillateDuringDrop() {
        start(grid = true, video = true)
        panel.windowModel.selectAlbums(setOf("a", "b", "c")); compose.waitForIdle()
        val destination = point("d", after = true)
        hold("a"); move(destination)
        val stable = point("d")
        repeat(4) { move(destination); assertEquals(stable, point("d")) }
        release()
        assertEquals(listOf("hidden", "d", "a", "b", "c"), app.preferences.albumOrder.value)
        assertEquals(setOf("a", "b", "c"), panel.windowModel.selectedAlbums.value)
    }
    @Test fun cancelledDragRestoresSelectionAndOrder() {
        start(grid = true)
        hold("a"); move(point("c", after = true))
        compose.onNodeWithTag("album-reorder-surface").performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals(listOf("hidden", "a", "b", "c", "d"), app.preferences.albumOrder.value)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        compose.onNodeWithTag("album-drag-preview").assertDoesNotExist()
    }
    @Test fun mixedSizeGridCommitsExactlyItsPreviewForAlbumsAndVideoFolders() {
        start(grid = true)
        app.preferences.albumSize(setOf("a"), 4); compose.waitForIdle()
        val target = point("d", after = true)
        hold("a"); move(target)
        val preview = listOf("a", "b", "c", "d").associateWith { point(it) }
        repeat(3) { move(target); preview.forEach { (id, position) -> assertEquals(position, point(id)) } }
        release()
        preview.forEach { (id, position) -> assertEquals(position, point(id)) }
        assertEquals(listOf("hidden", "b", "c", "d", "a"), app.preferences.albumOrder.value)
        assertEquals(app.preferences.albumOrder.value, PreferenceStore(app).albumOrder.value)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
    }
    @Test fun largeVideoFolderCanBeDroppedUsingAnyGrabPoint() {
        start(grid = true, video = true)
        app.preferences.videoAlbumSize(setOf("a"), 4); compose.waitForIdle()
        val source = compose.onNodeWithTag("album:a").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("album-reorder-surface").fetchSemanticsNode().boundsInRoot
        val destination = point("c") - Offset(12f, 0f)
        hold("a", source.bottomRight - Offset(12f, 12f) - surface.topLeft)
        move(destination)
        val preview = listOf("a", "b", "c", "d").associateWith { point(it) }
        release()
        preview.forEach { (id, position) -> assertEquals(position, point(id)) }
        assertEquals(listOf("hidden", "b", "c", "a", "d"), app.preferences.albumOrder.value)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
    }
    @Test fun animatedGridMovesCellsAndFocusFollowsTheDraggedAlbum() {
        start(grid = true, reducedMotion = false)
        compose.onNodeWithTag("album:d").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        assertEquals("d", app.session.previewAlbumId)
        val destination = point("c", after = true)
        val before = point("b")
        hold("a")
        assertEquals("a", app.session.previewAlbumId)
        assertEquals("a", panel.windowModel.albumAnchor)
        compose.onNodeWithTag("album:d").assertIsNotFocused()
        move(destination)
        assertTrue(point("b").x < before.x)
        val preview = point("b")
        compose.mainClock.advanceTimeBy(500)
        move(destination)
        assertEquals(preview, point("b"))
        release()
        assertEquals(listOf("hidden", "b", "c", "a", "d"), app.preferences.albumOrder.value)
        assertEquals("a", app.session.previewAlbumId)
    }
    @Test fun singleVideoDropAdoptsTheTargetSlotWithoutEnteringSelectionMode() {
        start(grid = true, video = true)
        val bounds = compose.onNodeWithTag("album:c").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("album-reorder-surface").fetchSemanticsNode().boundsInRoot
        hold("a"); move(Offset(bounds.left + 12f, bounds.center.y) - surface.topLeft)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        release()
        assertEquals(listOf("hidden", "b", "c", "a", "d"), app.preferences.albumOrder.value)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
    }
    @Test fun stationaryListHoldOpensOptionsWithoutReordering() {
        start(grid = false)
        hold("a"); release()
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        assertEquals("a", app.session.albumActionTarget)
        assertEquals(listOf("hidden", "a", "b", "c", "d"), app.preferences.albumOrder.value)
    }
    private fun reorderThirdRow(video: Boolean) {
        val ids = listOf("telegram", "movies", "gif-test", "decorations", "screenshots", "a", "b", "c", "d", "e", "f", "g", "telegram-copy")
        start(grid = true, video = video, ids = ids, columns = 6)
        if (video) app.preferences.videoAlbumSize(setOf("telegram"), 4) else app.preferences.albumSize(setOf("telegram"), 4)
        compose.waitForIdle()
        val before = point("e") - Offset(20f, 0f)
        hold("telegram-copy"); move(before)
        val preview = ids.associateWith { point(it) }
        repeat(3) { move(before); preview.forEach { (id, position) -> assertEquals(position, point(id)) } }
        release()
        preview.forEach { (id, position) -> assertEquals(position, point(id)) }
        assertEquals(listOf("hidden") + ids.toMutableList().apply { remove("telegram-copy"); add(indexOf("e"), "telegram-copy") }, app.preferences.albumOrder.value)
        assertEquals(app.preferences.albumOrder.value, PreferenceStore(app).albumOrder.value)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
    }
    @Test fun thirdRowDropDoesNotDisplaceAnUnrelatedGifAlbum() = reorderThirdRow(false)
    @Test fun thirdRowDropHasTheSameStablePackingInVideoFolders() = reorderThirdRow(true)
}
