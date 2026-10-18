package it.ayngallery

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class DuplicateSearchTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private lateinit var preferences: PreferenceStore
    private lateinit var session: GallerySession
    private lateinit var search: DuplicateSearch
    private lateinit var source: SourceEntity
    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.duplicates"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.duplicates", provider)
        catalog = Catalog(app); preferences = PreferenceStore(app); session = GallerySession(app)
        session.visiblePanels.value = 1
        source = SourceEntity("scope", DocumentsContract.buildTreeDocumentUri("ayngallery.duplicates", "root").toString(), "Fixture", "MIXED")
        app.contentResolver.takePersistableUriPermission(Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        withTimeout(5000) { catalog.snapshot.first { it.initialized && it.media.size == 1 } }
        search = DuplicateSearch(catalog, preferences, session)
    }
    @After fun close() = runBlocking {
        session.close(); catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close()
    }
    private suspend fun rescan() { SourceScanner(catalog).scan(source); withTimeout(5000) { catalog.snapshot.first { it.media.size == provider.nodes.values.count { it.file != null } } } }
    private suspend fun complete(scope: DuplicateScope = DuplicateScope()): DuplicateProgress {
        assertTrue(search.start(scope)); return withTimeout(8000) { search.state.first { it.phase == "COMPLETED" } }
    }
    @Test fun exactContentAcrossKindsUnknownSizesAndDifferentNamesExcludesLookalikes() = runBlocking {
        val duplicate = provider.add("destination", "different.png", provider.originalBytes)
        val changed = provider.add("child", "image.png", provider.originalBytes.map { (it.toInt() xor 7).toByte() }.toByteArray())
        val unique = provider.add("child", "unique.png", byteArrayOf(1, 2, 3))
        provider.unknownSizeIds.add(duplicate.id)
        rescan()
        val items = catalog.dao.media()
        val duplicateMedia = items.single { it.documentKey.endsWith(":${duplicate.id}") }
        catalog.dao.saveMedia(listOf(duplicateMedia.copy(kind = "VIDEO", mime = "video/mp4")))
        val reads = mutableListOf<String>()
        provider.beforeRead = { synchronized(reads) { reads.add(it) } }
        val report = complete()
        assertEquals(1, report.groups.size)
        assertEquals(setOf("image", duplicate.id), report.groups.single().files.map { it.media.documentKey.substringAfterLast(':') }.toSet())
        assertEquals(provider.originalBytes.size.toLong(), report.groups.single().digest.bytes)
        assertTrue(report.groups.single().files.any { it.media.kind == "VIDEO" })
        assertTrue(reads.contains(unique.id)) // Unknown length can match a known singleton; it must not be skipped.
        assertTrue(reads.contains(changed.id)); assertTrue(provider.deletions.isEmpty())
        assertEquals(0, catalog.dao.operations().size)
    }
    @Test fun selectedScopesFreshAccessVisibilityAndInvalidationShareCatalogPolicy() = runBlocking {
        provider.add("destination", "copy.png", provider.originalBytes)
        provider.add("child", "other.png", provider.originalBytes)
        rescan()
        val items = catalog.dao.media()
        val original = items.single { it.documentKey.endsWith(":image") }
        val copy = items.single { it.name == "copy.png" }
        assertEquals(2, complete(DuplicateScope(media = setOf(original.id, copy.id))).groups.single().files.size)
        assertEquals(2, complete(DuplicateScope(albums = setOf(original.albumId))).groups.single().files.size)
        val parent = catalog.dao.albums().single { it.parentId == null }
        assertEquals(3, complete(DuplicateScope(albums = setOf(parent.id))).groups.single().files.size)
        catalog.hideAlbums(listOf(parent.id), true)
        val full = catalog.readSnapshot(); val partial = catalog.readMediaSnapshot(original.id)
        assertEquals(full.usable(full.byId.getValue(original.id)), partial.usable(partial.byId.getValue(original.id)))
        assertFalse(partial.usable(partial.byId.getValue(original.id)))
        withTimeout(5000) { search.state.first { it.groups.isEmpty() } }
        assertTrue(complete().groups.isEmpty())
        catalog.hideAlbums(listOf(parent.id), false)
        catalog.context.contentResolver.releasePersistableUriPermission(Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val revoked = catalog.readMediaSnapshot(original.id)
        assertFalse(revoked.usable(revoked.byId.getValue(original.id)))
        assertTrue(complete().groups.isEmpty())
    }
    @Test fun gameBackgroundManualPauseCancellationAndInterruptedMarkerNeverAutostart() = runBlocking {
        provider.add("destination", "copy.png", provider.originalBytes); rescan()
        preferences.save(preferences.state.value.copy(gameMode = true))
        session.visiblePanels.value = 0
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val blocked = AtomicBoolean(false)
        provider.beforeRead = { reads.incrementAndGet(); if (blocked.compareAndSet(false, true)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        assertTrue(search.start())
        withTimeout(5000) { search.state.first { it.phase == "PAUSED" } }
        assertEquals(0, reads.get())
        assertFalse(search.start())
        search.resume(allowGame = true)
        withTimeout(5000) { search.state.first { it.notice.contains("non è visibile") } }
        assertEquals(0, reads.get())
        session.visiblePanels.value = 1
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        search.pause(); release.countDown()
        withTimeout(5000) { search.state.first { it.phase == "PAUSED" && it.notice == "Sospesa da te" } }
        val bytes = search.state.value.bytes; delay(100); assertEquals(bytes, search.state.value.bytes)
        search.resume(); session.visiblePanels.value = 0
        withTimeout(5000) { search.state.first { it.phase == "PAUSED" && it.notice.contains("non è visibile") } }
        search.cancel()
        withTimeout(5000) { search.state.first { it.phase == "CANCELLED" } }
        assertTrue(provider.deletions.isEmpty())
        val marker = catalog.context.getSharedPreferences("duplicate_search_v1", Context.MODE_PRIVATE)
        withTimeout(5000) { while (marker.getString("phase", "") != "CANCELLED") delay(10) }
        marker.edit().putString("phase", "RUNNING").commit()
        val interrupted = DuplicateSearch(catalog, preferences, session)
        assertEquals("INTERRUPTED", interrupted.state.value.phase); assertFalse(interrupted.state.value.active)
        assertTrue(interrupted.state.value.groups.isEmpty())
    }
    @Test fun explicitReferenceTransferIsVerifiedAtomicAndPreservesCollectionOrder() = runBlocking {
        provider.add("destination", "copy.png", provider.originalBytes)
        val other = provider.add("child", "other.png", byteArrayOf(1, 2, 3)); rescan()
        val group = complete().groups.single()
        val sourceFile = group.files.single { it.media.documentKey.endsWith(":image") }
        val keep = group.files.first { it.media.id != sourceFile.media.id }
        val otherId = catalog.dao.media().single { it.documentKey.endsWith(":${other.id}") }.id
        catalog.favoriteAll(listOf(sourceFile.media.id), true)
        val collection = catalog.collection("QA", "MEDIA", listOf(sourceFile.media.id, otherId, keep.media.id))
        search.transferReferences(keep, listOf(sourceFile))
        assertFalse(catalog.dao.media(sourceFile.media.id)!!.favorite); assertTrue(catalog.dao.media(keep.media.id)!!.favorite)
        assertEquals(listOf(keep.media.id, otherId), catalog.dao.collectionItems().filter { it.collectionId == collection }.sortedBy { it.position }.map { it.mediaId })
        assertTrue(provider.deletions.isEmpty())
        catalog.favoriteAll(listOf(sourceFile.media.id), true)
        catalog.addCollectionItems(collection, listOf(sourceFile.media.id))
        val before = catalog.dao.collectionItems()
        provider.nodes.getValue("image").file!!.writeBytes(provider.originalBytes.map { (it.toInt() xor 7).toByte() }.toByteArray())
        try { search.transferReferences(keep, listOf(sourceFile)); fail("Changed bytes accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(before, catalog.dao.collectionItems()); assertTrue(catalog.dao.media(sourceFile.media.id)!!.favorite)
    }
}
