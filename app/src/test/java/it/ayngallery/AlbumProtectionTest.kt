package it.ayngallery

import android.app.Application
import android.content.Context
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AlbumProtectionTest {
    @Test fun freshAndPreviouslyAuthorizedStreamReferencesAreDeniedAfterRelocking() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val catalog = Catalog(app)
        try {
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri("protection.test", "root")
            app.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val source = SourceEntity("source", tree.toString(), "Root", "VISUAL")
            val parent = AlbumEntity("parent", "protection.test:root", android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "Root", "Root", null)
            val child = parent.copy(id = "child", documentKey = "protection.test:child", parentId = "parent")
            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "image")
            val image = MediaEntity("image", "protection.test:image", uri.toString(), "child", "image.png", "image/png", "IMAGE", 10, 1)
            catalog.dao.saveSource(source); catalog.dao.saveAlbums(listOf(parent, child)); catalog.dao.saveMedia(listOf(image))
            catalog.dao.saveMembership(listOf(MediaSource(image.id, source.id, 1)))
            catalog.dao.saveAlbumSources(listOf(AlbumSource(parent.id, source.id, 1), AlbumSource(child.id, source.id, 1)))
            catalog.dao.saveAncestors(listOf(AlbumAncestor(child.id, parent.id), AlbumAncestor(child.id, child.id)))
            val stale = catalog.readSnapshot()
            assertTrue(stale.usable(image))
            assertTrue(catalog.protection.protect(setOf(parent.id), "1234"))
            assertFalse(catalog.readMediaSnapshot(image.id).usable(image))
            assertThrows(SecurityException::class.java) { catalog.uri(image, stale) }
            assertThrows(SecurityException::class.java) { catalog.uri(image, stale, artwork = true) }
            assertTrue(catalog.protection.unlock("1234"))
            val unlocked = catalog.readSnapshot()
            assertEquals(uri, catalog.uri(image, unlocked))
            catalog.protection.lock()
            assertThrows(SecurityException::class.java) { catalog.uri(image, unlocked) }
            assertFalse(catalog.readMediaSnapshot(image.id).artworkAllowed(image))
        } finally { runBlocking { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }; catalog.db.close() }
    }
    @Test fun pinPersistsAsSaltedHashAndUnlockNeverSurvivesRestart() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = AlbumProtection(app)
        assertFalse(store.configured)
        assertTrue(store.protect(setOf("album"), "0123"))
        val saved = app.getSharedPreferences("album_protection", Context.MODE_PRIVATE)
        assertFalse(saved.all.values.contains("0123"))
        assertEquals(32, android.util.Base64.decode(saved.getString("hash", "")!!, android.util.Base64.NO_WRAP).size)
        assertFalse(store.unlock("9999"))
        assertTrue(store.unlock("0123"))
        assertEquals(setOf("album"), store.state.value.unlocked)
        val recreated = AlbumProtection(app)
        assertTrue(recreated.configured)
        assertEquals(setOf("album"), recreated.state.value.protected)
        assertTrue(recreated.state.value.unlocked.isEmpty())
        store.lock()
        assertTrue(store.state.value.unlocked.isEmpty())
        assertFalse(store.unprotect(setOf("album"), "9999"))
        assertTrue(store.unprotect(setOf("album"), "0123"))
        assertTrue(AlbumProtection(app).state.value.protected.isEmpty())
    }
    @Test fun repeatedFailuresThrottleEvenAfterStoreRecreation() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = AlbumProtection(app)
        store.protect(setOf("album"), "1234")
        repeat(5) { assertFalse(store.unlock("1111")) }
        val restarted = AlbumProtection(app)
        assertTrue(restarted.waitSeconds() > 0)
        assertFalse(restarted.unlock("1234"))
        assertFalse(restarted.unprotect(setOf("album"), "1234"))
        assertFalse(restarted.protect(setOf("another"), "1234"))
        app.getSharedPreferences("album_protection", 0).edit().putLong("retry_at", System.currentTimeMillis() - 1).commit()
        assertTrue(restarted.unlock("1234"))
        assertEquals(0, restarted.waitSeconds())
    }
    @Test fun malformedPinsCannotCreateProtection() = runBlocking {
        val store = AlbumProtection(RuntimeEnvironment.getApplication())
        for (pin in listOf("123", "12345", "12ab", "１２３４")) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.protect(setOf("album"), pin) } }
        }
        assertFalse(store.configured)
    }
}
