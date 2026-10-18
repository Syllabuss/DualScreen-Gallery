package it.ayngallery

import android.app.Application
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class CatalogSnapshotTest {
    private lateinit var catalog: Catalog
    @After fun release() { if (::catalog.isInitialized) { runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin() }; catalog.db.close() } }
    @Test fun observedSnapshotsKeepTransactionsAtomicAndNoticePrivacyChanges() = runBlocking {
        catalog = Catalog(RuntimeEnvironment.getApplication())
        val source = SourceEntity("source", "content://fixture/tree/root", "0", "MUSIC")
        val album = AlbumEntity("album", "fixture:root", "content://fixture/tree/root/document/root", "Music", "Music", null)
        val song = MediaEntity("song", "fixture:song", "content://fixture/tree/root/document/song", album.id, "song.wav", "audio/wav", "AUDIO", 100, 1, title = "0")
        catalog.db.withTransaction {
            catalog.context.contentResolver.takePersistableUriPermission(android.net.Uri.parse(source.treeUri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            catalog.dao.saveSource(source); catalog.dao.saveAlbums(listOf(album)); catalog.dao.saveMedia(listOf(song))
            catalog.dao.saveMembership(listOf(MediaSource(song.id, source.id, 1)))
        }
        withTimeout(5000) { catalog.snapshot.first { it.byId.containsKey(song.id) } }
        val observer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            catalog.snapshot.collect { data ->
                data.byId[song.id]?.let { assertEquals("Never expose half a transaction", it.title, data.sources.single().title) }
            }
        }
        try {
            for (version in 1..5) {
                catalog.db.withTransaction {
                    catalog.dao.saveSource(source.copy(title = "$version"))
                    delay(30) // Give independent table observers time to expose an invalid intermediate state.
                    catalog.dao.saveMedia(listOf(song.copy(title = "$version")))
                }
                withTimeout(5000) { catalog.snapshot.first { it.byId[song.id]?.title == "$version" } }
            }
            catalog.favorite(song.id)
            withTimeout(5000) { catalog.snapshot.first { it.byId[song.id]?.favorite == true } }
            catalog.hideAlbums(listOf(album.id), true)
            val hidden = withTimeout(5000) { catalog.snapshot.first { album.id in it.excludedAlbums } }
            assertFalse(hidden.usable(hidden.byId.getValue(song.id)))
            assertTrue(hidden.byId.getValue(song.id).favorite)
        } finally { observer.cancelAndJoin() }
    }
}
