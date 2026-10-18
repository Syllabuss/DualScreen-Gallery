package it.ayngallery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = TestGalleryApp::class)
class ExplorerTreeTest {
    @org.junit.After fun close() { (org.robolectric.RuntimeEnvironment.getApplication() as GalleryApp).onTerminate() }
    private val root = ExplorerLocation("fixture", "")
    private fun entry(id: String, directory: Boolean) = ExplorerEntry(root, id, id, directory, if (directory) null else 3L, 1, true)
    @Test fun frozenPlanRoundTripsAndRejectsForgedNamesAndRepeatedLocators() {
        val entry = ExplorerEntry(ExplorerLocation("smb:host/share", ""), "photo.png", "photo.png", false, 3, 1, true)
        val plan = listOf(ExplorerTree(entry))
        assertEquals(plan, explorerTreeFromJson(explorerTreeJson(plan)))
        val duplicate = explorerTreeJson(plan + plan)
        assertTrue(runCatching { explorerTreeFromJson(duplicate) }.isFailure)
        val forged = explorerTreeJson(plan)
        forged.getJSONObject(0).put("name", "../escape")
        assertTrue(runCatching { explorerTreeFromJson(forged) }.isFailure)
    }
    @Test fun hierarchyIsFrozenAndCyclesAreRejectedBeforePublication() = runBlocking {
        val folder = entry("folder", true); val file = entry("file.png", false)
        val frozen = explorerTree(listOf(folder)) { listOf(file) }
        assertEquals(file, frozen.single().children.single().entry)
        assertTrue(runCatching { explorerTree(listOf(folder)) { listOf(folder) } }.isFailure)
    }
    @Test fun excessiveDepthAndUnsafeNamesAreRejected() = runBlocking {
        assertTrue(runCatching { explorerTree(listOf(entry("folder", true))) { at -> listOf(entry(at.directoryId + "x", true)) } }.isFailure)
        assertTrue(runCatching { explorerTree(listOf(entry("../unsafe", false))) { emptyList() } }.isFailure)
    }
}
