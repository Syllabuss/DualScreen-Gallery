package it.ayngallery

import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class SourceScannerTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: FixtureDocuments
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        provider = FixtureDocuments()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "ayngallery.tests"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("ayngallery.tests", provider)
        catalog = Catalog(app)
    }
    @After fun cleanup() { if (::catalog.isInitialized) { runBlocking { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }; catalog.db.close() } }
    private fun source(id: String, root: String, role: String) = SourceEntity(id,
        DocumentsContract.buildTreeDocumentUri("ayngallery.tests", root).toString(), root, role).also { catalog.context.contentResolver.takePersistableUriPermission(Uri.parse(it.treeUri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }

    @Test fun openingOneFolderNeverWalksDescendantsOrWritesTheIndex() = runBlocking {
        val root = source("root", "root", "VISUAL")
        val album = AlbumEntity("root-album", "ayngallery.tests:root", DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root.treeUri), "root").toString(), "Root", "Root", null)
        val first = SourceScanner(catalog).browse(root, album)
        assertEquals(listOf("root"), provider.queriedFolders)
        assertTrue(first.media.isEmpty()); assertEquals(2, first.albums.size)
        val child = first.albums.single { it.parentId == album.id }
        val next = SourceScanner(catalog).browse(root, child)
        assertEquals(listOf("root", "child"), provider.queriedFolders)
        assertEquals("image.png", next.media.single().name)
        assertEquals(next.media.single().id, SourceScanner(catalog).browse(root, child).media.single().id)
        assertTrue(catalog.dao.media().isEmpty()); assertTrue(catalog.dao.albums().isEmpty())
        assertTrue(catalog.dao.membership().isEmpty()); assertTrue(catalog.dao.sources().isEmpty())
    }
    @Test fun refreshUpdatesRenamedFolderWithoutLosingIdCoverOrHiddenState() = runBlocking {
        val source = source("root", "root", "VISUAL")
        val root = AlbumEntity("root-album", "ayngallery.tests:root", DocumentsContract.buildDocumentUriUsingTree(Uri.parse(source.treeUri), "root").toString(), "Root", "Root", null)
        val child = SourceScanner(catalog).browse(source, root).albums.single { it.parentId == root.id }
        val cached = child.copy(name = "Old", path = "Root / Old", hidden = true, coverMediaId = "cover")
        val fresh = SourceScanner(catalog).browse(source, root, CatalogSnapshot(albums = listOf(root, cached))).albums.single { it.id == child.id }
        assertEquals("Child", fresh.name); assertEquals("Root / Child", fresh.path)
        assertTrue(fresh.hidden); assertEquals("cover", fresh.coverMediaId)
    }
    @Test fun missingAudioTagsAreCachedUntilTheDocumentChanges() = runBlocking {
        val source = source("root", "root", "MUSIC")
        val root = AlbumEntity("root-album", "ayngallery.tests:child", DocumentsContract.buildDocumentUriUsingTree(Uri.parse(source.treeUri), "child").toString(), "Child", "Child", null)
        provider.mediaMime = "audio/mpeg"
        val first = SourceScanner(catalog).browse(source, root).media.single()
        assertTrue(first.audioMetadataRead)
        assertNull(first.title)
        val cached = first.copy(artist = "Cached artist")
        val next = SourceScanner(catalog).browse(source, root, CatalogSnapshot(media = listOf(cached))).media.single()
        assertEquals(cached, next)
        provider.modified++
        val replacement = SourceScanner(catalog).browse(source, root, CatalogSnapshot(media = listOf(cached))).media.single()
        assertNotEquals(first.id, replacement.id)
        assertNull(replacement.artist)
        assertTrue(replacement.audioMetadataRead)
    }
    @Test fun overlappingTreesShareOneLogicalMediaAndPreserveFavorite() = runBlocking {
        val root = source("root", "root", "VISUAL")
        catalog.dao.saveSource(root); SourceScanner(catalog).scan(root)
        val media = catalog.dao.media().single()
        catalog.dao.favorite(media.id, true)
        val nested = source("nested", "child", "VISUAL")
        catalog.dao.saveSource(nested); SourceScanner(catalog).scan(nested)
        assertEquals(1, catalog.dao.media().size)
        assertTrue(catalog.dao.media().single().favorite)
        assertEquals(2, catalog.dao.membership().size)
        val data = catalog.readSnapshot()
        assertEquals(1, data.visibleMedia.size)
        val parent = data.albums.single { it.documentKey.endsWith(":root") }
        catalog.hideAlbums(listOf(parent.id), true)
        assertTrue(catalog.readSnapshot().visibleMedia.isEmpty())
        catalog.hideAlbums(listOf(parent.id), false)
        assertTrue(catalog.readSnapshot().visibleMedia.single().favorite)
    }
    @Test fun sameNameReplacementKeepsOldFavoriteUnavailable() = runBlocking {
        val root = source("root", "root", "VISUAL")
        catalog.dao.saveSource(root); SourceScanner(catalog).scan(root)
        val old = catalog.dao.media().single()
        catalog.dao.favorite(old.id, true)
        provider.modified = 2
        SourceScanner(catalog).scan(catalog.dao.source(root.id)!!)
        val data = catalog.readSnapshot()
        assertEquals(2, data.media.size)
        assertTrue(data.byId[old.id]!!.favorite)
        assertFalse(data.byId[old.id]!!.available)
        assertFalse(data.usable(data.byId[old.id]!!))
        assertEquals(1, data.visibleMedia.count { it.available })
    }
    @Test fun missingDocumentDoesNotDeleteFavoriteRecord() = runBlocking {
        val root = source("root", "root", "VISUAL")
        catalog.dao.saveSource(root); SourceScanner(catalog).scan(root)
        val old = catalog.dao.media().single()
        catalog.dao.favorite(old.id, true)
        provider.present = false
        SourceScanner(catalog).scan(catalog.dao.source(root.id)!!)
        assertEquals(1, catalog.dao.media().size)
        assertTrue(catalog.dao.media().single().favorite)
        assertFalse(catalog.dao.media().single().available)
    }
    @Test fun emptyFoldersRemainVisibleAndMultiHideChangesNoFiles() = runBlocking {
        provider.present = false
        val root = source("root", "root", "VISUAL")
        catalog.dao.saveSource(root); SourceScanner(catalog).scan(root)
        val data = catalog.readSnapshot()
        assertEquals(2, data.visibleAlbums().size)
        assertTrue(data.visibleMedia.isEmpty())
        catalog.hideAlbums(data.albums.map { it.id }, true)
        assertTrue(catalog.readSnapshot().visibleAlbums().isEmpty())
        catalog.hideAlbums(data.albums.map { it.id }, false)
        assertEquals(2, catalog.readSnapshot().visibleAlbums().size)
    }
}

open class FixtureDocuments : DocumentsProvider() {
    val queriedFolders = mutableListOf<String>()
    var modified = 1L
    var present = true
    var mediaMime = "image/png"
    override fun onCreate() = true
    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == documentId || (parentDocumentId == "root" && documentId in setOf("child", "image")) ||
            (parentDocumentId == "child" && documentId == "image")
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        queriedFolders += parentDocumentId
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val rows = when (parentDocumentId) {
            "root" -> listOf(listOf("child", "Child", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 1L))
            "child" -> if (present) listOf(listOf("image", "image.png", mediaMime, 100L, modified)) else emptyList()
            else -> emptyList()
        }
        return MatrixCursor(columns).apply { rows.forEach { addRow(it) } }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        throw java.io.FileNotFoundException("Synthetic index fixture: no file content")
}
