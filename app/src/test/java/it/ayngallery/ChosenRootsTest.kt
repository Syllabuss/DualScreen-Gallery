package it.ayngallery

import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Looper
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
class ChosenRootsTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { app.onTerminate() }
    @Test fun chosenMusicFolderReadsAndCachesTitleAndArtistWithoutCopyingArtwork() = runBlocking {
        val provider = OperationDocuments()
        val authority = "ayngallery.audio-tags"
        provider.attachInfo(app, ProviderInfo().apply { this.authority = authority; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        provider.nodes["image"] = provider.nodes.getValue("image").copy(name = "01. Never Wanted To Dance.mp3", mime = "audio/mpeg")
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "image")
        val metadata = org.robolectric.shadows.util.DataSource.toDataSource(app, uri)
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(metadata, android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, "Never Wanted To Dance")
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(metadata, android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST, "Mindless Self Indulgence")
        val source = SourceEntity("source", tree.toString(), "Music", "MUSIC")
        val folder = AlbumEntity("album", "$authority:child", DocumentsContract.buildDocumentUriUsingTree(tree, "child").toString(), "Album", "Music / Album", null)
        val scanner = SourceScanner(app.catalog)
        val fresh = scanner.browse(source, folder, CatalogSnapshot(initialized = true))
        val song = fresh.media.single()
        assertEquals("Never Wanted To Dance", song.title)
        assertEquals("Mindless Self Indulgence", song.artist)
        assertNull(song.artworkUri)
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(metadata, android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, "Must not reread unchanged tags")
        assertEquals(song, scanner.browse(source, folder, fresh).media.single())
    }
    private fun waitFor(check: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!check() && System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10)
        }
        assertTrue(check())
    }
    @Test fun onlyChosenTreesAreReadAndTheCompleteListingIsCached() = runBlocking {
        val global = DeviceMediaFixture()
        global.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("media", global)
        Shadows.shadowOf(app).grantPermissions(*DeviceGallery.permissions())
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.roots"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.roots", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.roots", "child")
        app.catalog.addSource(tree, SourceRole.VISUAL, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.albums.show(0, GalleryRoute(tab = "ALBUM"))
        waitFor { app.catalog.snapshot.value.visibleMedia.size == 1 }
        val first = app.catalog.snapshot.value
        assertEquals("image.png", first.visibleMedia.single().name)
        assertEquals(listOf("Origin"), first.topAlbums().map { it.name }) // Files directly in the chosen root remain accessible.
        assertFalse(first.albums.any { it.name == "Destination" })
        assertEquals(1, app.catalog.dao.media().size)
        val id = first.visibleMedia.single().id
        app.catalog.favorite(id)
        val source = app.catalog.dao.sources().single()
        app.catalog.enableSource(source.id, false)
        assertTrue(app.catalog.readSnapshot().visibleMedia.isEmpty())
        assertTrue(app.catalog.dao.media(id)!!.favorite)
        app.catalog.addSource(tree, SourceRole.VISUAL, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        waitFor { app.catalog.snapshot.value.visibleMedia.any { it.id == id } }
        assertTrue(app.catalog.snapshot.value.byId.getValue(id).favorite)
        assertTrue(provider.deletions.isEmpty())
        assertTrue(global.queried.isEmpty())
    }
    @Test fun tenThousandChosenFilesKeepStableIdsInThePersistentListing() = runBlocking {
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.root-scale"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.root-scale", provider)
        val template = provider.nodes.remove("image")!!
        repeat(10_000) { index -> provider.nodes["file-$index"] = template.copy(id = "file-$index", name = "$index.png") }
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.root-scale", "child")
        app.catalog.addSource(tree, SourceRole.VISUAL, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.albums.show(0, GalleryRoute(tab = "ALBUM"))
        waitFor { app.catalog.snapshot.value.visibleMedia.size == 10_000 }
        val ids = app.catalog.snapshot.value.visibleMedia.map { it.id }.toSet()
        assertEquals(10_000, ids.size)
        val sourceId = app.catalog.dao.sources().single().id
        val before = app.catalog.browsed.value[sourceId]
        app.albums.refreshSource(sourceId)
        waitFor { app.catalog.browsed.value[sourceId] !== before }
        assertEquals(ids, app.catalog.browsed.value.getValue(sourceId).media.map { it.id }.toSet())
        assertEquals(10_000, app.catalog.dao.media().size); assertFalse(app.catalog.dao.albums().isEmpty())
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun committedFileOperationWinsOverAnOldFolderRead() {
        val moved = MediaEntity("same", "provider:new", "content://provider/new", "new-folder", "image.png", "image/png", "IMAGE", 10, 1, favorite = true)
        val stale = moved.copy(documentKey = "provider:old", uri = "content://provider/old", albumId = "old-folder")
        val data = DirectAlbums.merge(CatalogSnapshot(media = listOf(moved)), mapOf("source" to CatalogSnapshot(media = listOf(stale))))
        assertEquals(moved, data.byId.getValue("same"))
    }
    @Test fun cachedListingSurvivesAMemoryResetAndIncompleteReadsAndPreservesFavoritesOnReplacement() = runBlocking {
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.cached"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.cached", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.cached", "child")
        app.catalog.addSource(tree, SourceRole.VISUAL, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.albums.show(0, GalleryRoute())
        waitFor { app.albums.ready.value && app.catalog.snapshot.value.visibleMedia.size == 1 }
        val original = app.catalog.snapshot.value.visibleMedia.single()
        app.catalog.favorite(original.id)
        val source = app.catalog.dao.sources().single()
        val queries = provider.childQueries
        app.albums.leave(0); app.albums.show(0, GalleryRoute(tab = "VIDEO"))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400)); Thread.sleep(100)
        assertEquals(queries, provider.childQueries)
        app.albums.leave(0)
        app.catalog.browsed.value = emptyMap()
        provider.revoked = true
        val cold = DirectAlbums(app)
        cold.show(8, GalleryRoute())
        waitFor { cold.ready.value }
        assertEquals(original.id, app.catalog.readStoredSnapshot().visibleMedia.single().id)
        assertTrue(app.catalog.dao.media(original.id)!!.favorite)
        cold.leave(8)
        provider.revoked = false
        provider.nodes.getValue("image").file!!.writeBytes(ByteArray(20))
        app.albums.show(0, GalleryRoute())
        app.albums.refreshSource(source.id)
        waitFor { app.catalog.snapshot.value.visibleMedia.singleOrNull()?.size == 20L }
        val replacement = app.catalog.snapshot.value.visibleMedia.single()
        assertNotEquals(original.id, replacement.id)
        assertFalse(replacement.favorite)
        val old = app.catalog.dao.media(original.id)!!
        assertTrue(old.favorite); assertFalse(old.available)
        provider.nodes.remove("image")
        app.albums.refreshSource(source.id)
        waitFor { app.catalog.snapshot.value.visibleMedia.isEmpty() }
        assertTrue(app.catalog.readStoredSnapshot().topAlbums().isEmpty())
        assertEquals(setOf("Origin"), app.catalog.readStoredSnapshot().topAlbums(GalleryPreferences(showEmptyFolders = true)).map { it.name }.toSet())
        assertNotNull(app.catalog.dao.media(original.id))
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun rootAddressIsHiddenWhenItsSubfoldersAreTheAlbums() = runBlocking {
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.subroots"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.subroots", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.subroots", "root")
        app.catalog.addSource(tree, SourceRole.VISUAL, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.albums.show(0, GalleryRoute(tab = "ALBUM"))
        waitFor { app.catalog.snapshot.value.visibleMedia.size == 1 }
        val data = app.catalog.snapshot.value
        assertEquals(setOf("Origin"), data.topAlbums().map { it.name }.toSet())
        assertEquals(setOf("Origin", "Destination"), data.topAlbums(GalleryPreferences(showEmptyFolders = true)).map { it.name }.toSet())
        assertTrue(data.topAlbums().none { it.id in data.rootAlbumIds })
        assertEquals(1, app.catalog.dao.media().size); assertFalse(app.catalog.dao.albums().isEmpty())
        assertTrue(provider.deletions.isEmpty())
    }
}
