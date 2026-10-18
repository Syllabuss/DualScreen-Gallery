package it.ayngallery

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
class CatalogInvalidationTest {
    @Test fun journalProgressDoesNotInvalidateCatalogButDeletionStillBlocksMedia() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CatalogDatabase::class.java).build()
        val changes = Channel<Int>(Channel.UNLIMITED)
        val mutation = Channel<List<String>>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dao = db.catalog()
            scope.launch { dao.observeCatalogChanges(SimpleSQLiteQuery("SELECT 1")).collect { changes.send(it) } }
            scope.launch { dao.observeMutatingMedia().distinctUntilChanged().collect { mutation.send(it) } }
            assertEquals(1, withTimeout(5000) { changes.receive() })
            assertEquals(emptyList<String>(), withTimeout(5000) { mutation.receive() })
            val op = FileOperation("op", "photo", "TRASH", "content://test/photo", "photo", "album", "photo.png", "image/png", 1000, 0, null, null, "photo.png", true, "photo", phase = "COPYING")
            dao.saveOperation(op)
            dao.saveOperation(op.copy(bytes = 500))
            assertNull(withTimeoutOrNull(500) { changes.receive() })
            assertNull(withTimeoutOrNull(200) { mutation.receive() })
            dao.saveOperation(op.copy(phase = "DELETING"))
            assertEquals(listOf("photo"), withTimeout(5000) { mutation.receive() })
            assertEquals(listOf("photo"), dao.mutatingMedia())
            dao.saveOperation(op.copy(phase = "COMPLETED"))
            assertEquals(emptyList<String>(), withTimeout(5000) { mutation.receive() })
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
}
