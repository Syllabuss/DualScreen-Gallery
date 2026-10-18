package it.ayngallery

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
class AlbumSelectionTest {
    @Test fun explicitAlbumSelectionReadsDescendantsWithoutWritingAnIndexAndRejectsRevokedAccess() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        try {
            val provider = ReadableFolderFixtureDocuments()
            provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.tests"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
            ShadowContentResolver.registerProviderInternal("ayngallery.tests", provider)
            val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.tests", "root")
            val source = SourceEntity("scope", tree.toString(), "Root", "VISUAL")
            val root = AlbumEntity("root-album", "ayngallery.tests:root", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "Root", "Root", null)
            // Match addSource's writer lock: startup access probing must not see a source
            // committed after it captured the earlier, empty permission set.
            app.catalog.writer.withLock {
                app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.catalog.dao.saveSource(source)
                app.catalog.dao.saveAlbums(listOf(root))
                app.catalog.dao.saveAlbumSources(listOf(AlbumSource(root.id, source.id, 1)))
            }
            val selected = app.albums.albumContents(setOf(root.id))
            assertEquals(listOf("root", "child"), provider.queriedFolders)
            assertEquals(listOf("image.png"), selected.map { it.name })
            assertTrue(app.catalog.dao.media().isEmpty())
            assertEquals(listOf(root), app.catalog.dao.albums())
            app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertTrue(runCatching { app.albums.albumContents(setOf(root.id)) }.isFailure)
        } finally { app.onTerminate() }
    }
}

private class ReadableFolderFixtureDocuments : FixtureDocuments() {
    override fun queryDocument(documentId: String, projection: Array<out String>?): android.database.Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        return android.database.MatrixCursor(columns).apply {
            if (documentId !in setOf("root", "child", "image") || documentId == "image" && !present) return@apply
            addRow(columns.map { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (documentId == "image") "image.png" else documentId
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (documentId == "image") "image/png" else DocumentsContract.Document.MIME_TYPE_DIR
                DocumentsContract.Document.COLUMN_SIZE -> if (documentId == "image") 100L else 0L
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modified
                else -> 0
            } }.toTypedArray())
        }
    }
}
