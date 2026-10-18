package it.ayngallery

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AlbumOrderTest {
    @Test fun exactGridPositionsSurviveRestartAndAreIndependentForAlbumAndVideo() {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        val cells = mapOf("a" to AlbumGridCell(1, 4, 4), "b" to AlbumGridCell(0, 0, 2))
        store.reorderAlbumGrid(listOf("hidden", "b", "a"), 4, cells, false)
        val reopened = PreferenceStore(RuntimeEnvironment.getApplication())
        assertEquals(AlbumGridPlacement(4, cells, listOf("hidden", "b", "a")), reopened.albumPlacement(false).value)
        assertTrue(reopened.albumPlacement(true).value.cells.isEmpty())
        assertEquals(listOf("hidden", "b", "a"), reopened.albumOrder.value)
    }
    @Test fun explicitInsertionSideIsStableAndPreservesFilteredAlbums() {
        val original = listOf("hidden", "a", "b", "c", "d", "e")
        assertEquals(listOf("hidden", "a", "c", "b", "d", "e"), moveAlbumBlock(original, setOf("b", "d"), "c", true))
        assertEquals(listOf("hidden", "a", "b", "d", "c", "e"), moveAlbumBlock(original, setOf("b", "d"), "c", false))
        assertEquals(original, moveAlbumBlock(original, setOf("b"), "c", false))
        assertEquals(listOf("hidden", "b", "a", "c", "d", "e"), moveAlbumBlock(original, setOf("b"), "a", false))
        assertEquals(original, moveAlbumBlock(original, setOf("b"), "b", true))
    }
    @Test fun movesWholeSelectionInBothDirectionsAndPreservesEveryId() {
        val original = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "c", "e", "b", "d"), moveAlbumBlock(original, setOf("b", "d"), "e"))
        assertEquals(listOf("c", "e", "a", "b", "d"), moveAlbumBlock(original, setOf("c", "e"), "a"))
        assertEquals(original, moveAlbumBlock(original, setOf("b", "d"), "d"))
        assertEquals(original, moveAlbumBlock(original, setOf("missing"), "a"))
    }
    @Test fun reorderSurvivesRestartAndNewAlbumsGoFirstWithoutReshufflingExistingOnes() {
        val context = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(context)
        store.rememberAlbums(listOf("a", "b", "c"))
        store.reorderAlbums(listOf("b", "a", "c"))
        val reopened = PreferenceStore(context)
        assertEquals(listOf("b", "a", "c"), reopened.albumOrder.value)
        reopened.rememberAlbums(listOf("a", "b", "c", "new"))
        assertEquals(listOf("new", "b", "a", "c"), reopened.albumOrder.value)
        assertEquals(6, reopened.trashColumns.value)
        reopened.galleryView(false, "TRASH", columns = 2)
        assertEquals(4, reopened.trashColumns.value)
    }
}
