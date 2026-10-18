package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AlbumMarkersTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private lateinit var source: SourceEntity
    private lateinit var folder: AlbumEntity
    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "protection.markers"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("protection.markers", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("protection.markers", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        catalog = Catalog(app)
        source = SourceEntity("source", tree.toString(), "Root", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        folder = catalog.dao.albums().single { it.documentKey == "protection.markers:child" }
    }
    @After fun close() { if (::catalog.isInitialized) { runBlocking { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }; catalog.db.close() } }
    @Test fun sidecarIsIgnoredByGalleryAndExternalDeletionRestoresAccessWithoutAlteringOriginals() = runBlocking {
        val original = provider.nodes.getValue("image").file!!.readBytes()
        assertTrue(catalog.protection.configure("1234"))
        assertTrue(catalog.protection.protect(setOf(folder.id), "1234"))
        val marker = provider.nodes.values.single { it.name == AlbumProtection.MARKER }
        assertFalse(marker.file!!.readText().contains("1234"))
        val page = SourceScanner(catalog).browse(source, folder)
        assertEquals(listOf("image.png"), page.media.map { it.name })
        assertEquals(true, page.protectionMarkers[folder.id])
        provider.deleteDocument(marker.id)
        val fresh = SourceScanner(catalog).browse(source, folder)
        catalog.protection.observeMarkers(fresh.protectionMarkers)
        assertFalse(folder.id in catalog.protection.state.value.protected)
        assertArrayEquals(original, provider.nodes.getValue("image").file!!.readBytes())
        assertTrue(catalog.readSnapshot().visibleMedia.any { it.name == "image.png" })
    }
    @Test fun unlockDeletesOnlyMarkerAndProviderFailureNeverClaimsAlbumWasUnprotected() = runBlocking {
        catalog.protection.protect(setOf(folder.id), "1234")
        val marker = provider.nodes.values.single { it.name == AlbumProtection.MARKER }
        assertFalse(catalog.protection.unprotect(setOf(folder.id), "9999"))
        assertTrue(provider.deletions.isEmpty())
        provider.denyDelete = true
        assertThrows(SecurityException::class.java) { runBlocking { catalog.protection.unprotect(setOf(folder.id), "1234") } }
        assertTrue(folder.id in catalog.protection.state.value.protected)
        provider.denyDelete = false
        assertTrue(catalog.protection.unprotect(setOf(folder.id), "1234"))
        assertEquals(listOf(marker.id), provider.deletions)
        assertTrue(provider.nodes.containsKey("image"))
        assertTrue(catalog.protection.configure("4321", "1234"))
        assertFalse(catalog.protection.unlock("1234"))
        assertTrue(catalog.protection.unlock("4321"))
    }
}
