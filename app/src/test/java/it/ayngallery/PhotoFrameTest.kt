package it.ayngallery

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class PhotoFrameTest {
    private fun data(): CatalogSnapshot {
        val source = SourceEntity("source", "content://fixture/tree/root", "Fixture", "MIXED")
        val folders = listOf("root", "child", "hidden", "offline").map { AlbumEntity(it, "fixture:$it", "content://fixture/$it", it, it, null, hidden = it == "hidden") }
        fun media(id: String, folder: String = "root", kind: String = "IMAGE") =
            MediaEntity(id, "fixture:$id", "content://fixture/$id", folder, "$id.png", "image/png", kind, 100, 1)
        val items = listOf(media("a").copy(favorite = true), media("b", "child", "GIF"), media("video", kind = "VIDEO"),
            media("song", kind = "AUDIO"), media("hidden", "hidden"), media("offline", "offline"),
            media("gone").copy(available = false), media("trash").copy(trashed = true), media("busy"))
        return CatalogSnapshot(listOf(source, source.copy(id = "offline", reachable = false)), folders, items,
            items.map { MediaSource(it.id, if (it.albumId == "offline") "offline" else source.id, 0) },
            folders.map { AlbumAncestor(it.id, it.id) } + AlbumAncestor("child", "root"),
            collections = listOf(CollectionEntity("collection", "Visual", "MEDIA")),
            collectionItems = listOf(CollectionItem("collection", "b", 0), CollectionItem("collection", "video", 1), CollectionItem("collection", "hidden", 2)),
            mutating = setOf("busy"))
    }
    @Test fun onlyAuthorizedVisibleVisualsAreAdmittedAcrossAlbumsCollectionsAndFavorites() {
        val data = data()
        assertEquals(listOf("a", "b"), FrameConfig().media(data).map { it.id })
        assertEquals(listOf("a", "b"), FrameConfig(scope = "SELECTED", albums = setOf("root")).media(data).map { it.id })
        assertEquals(listOf("b"), FrameConfig(scope = "SELECTED", collections = setOf("collection")).media(data).map { it.id })
        assertEquals(listOf("a"), FrameConfig(scope = "FAVORITE").media(data).map { it.id })
        assertTrue(FrameConfig(scope = "SELECTED", albums = setOf("hidden", "offline", "missing")).media(data).isEmpty())
    }
    @Test fun sequentialNavigationRemovalAndRestoredCurrentRemainDeterministic() {
        var cursor = FrameCursor(current = "b").reconcile(listOf("a", "b", "c"), false)
        assertEquals("c", cursor.nextId())
        cursor = cursor.move(1); assertEquals("c", cursor.current)
        cursor = cursor.move(-1); assertEquals("b", cursor.current)
        cursor = cursor.move(1); assertEquals("c", cursor.current)
        cursor = cursor.reconcile(listOf("a", "b"), false)
        assertFalse(cursor.current == "c"); assertFalse("c" in cursor.previous || "c" in cursor.forward || "c" in cursor.bag)
        assertEquals("a", cursor.reconcile(listOf("a"), true).current)
        val single = cursor.reconcile(listOf("a"), true); assertEquals(single, single.move(1)); assertNull(single.nextId())
        assertNull(cursor.reconcile(emptyList(), true).current)
    }
    @Test fun shuffledDeckVisitsEveryFilePerCycleWithoutAnImmediateRepeatAndReplaysBackHistory() {
        val ids = (0 until 12).map(Int::toString)
        val random = Random(123)
        var cursor = FrameCursor().reconcile(ids, true, random)
        val visits = mutableListOf(cursor.current!!)
        repeat(59) { cursor = cursor.move(1, random); assertNotEquals(visits.last(), cursor.current); visits += cursor.current!! }
        visits.chunked(12).forEach { assertEquals(ids.toSet(), it.toSet()) }
        val last = cursor.current
        cursor = cursor.move(-1); assertEquals(visits[58], cursor.current)
        cursor = cursor.move(1); assertEquals(last, cursor.current)
        cursor = cursor.reconcile(ids.drop(4) + "new", true, random)
        repeat(20) { cursor = cursor.move(1, random); assertTrue(cursor.current in ids.drop(4) + "new") }
    }
    @Test fun configRoundTripValidatesIntervalsAndSeparatesDraftFromSavedSelection() {
        val config = FrameConfig("SELECTED", setOf("album"), setOf("collection"), 30, true, true, false, true, true, true)
        assertEquals(config, FrameConfig.decode(config.encode()))
        assertThrows(IllegalArgumentException::class.java) { FrameConfig(seconds = 1).encode() }
        assertThrows(IllegalArgumentException::class.java) { FrameConfig(scope = "OTHER").encode() }
        assertThrows(IllegalArgumentException::class.java) { FrameConfig(scope = "SELECTED").encode() }
        val draft = FrameConfig(scope = "SELECTED")
        assertEquals(draft, FrameConfig.decode(draft.encode(requireSelection = false), requireSelection = false))
        assertThrows(IllegalArgumentException::class.java) { FrameConfig.decode(draft.encode(requireSelection = false)) }
        assertThrows(IllegalArgumentException::class.java) { FrameConfig.decode(config.encode().replace("\"version\":1", "\"version\":2")) }
    }
}
