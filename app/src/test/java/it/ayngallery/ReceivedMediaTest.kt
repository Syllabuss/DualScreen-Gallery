package it.ayngallery

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class ReceivedMediaTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var catalog: Catalog
    private lateinit var docs: OperationDocuments
    private lateinit var uri: Uri
    private lateinit var file: IncomingFile
    @Before fun start(): Unit = runBlocking {
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        docs = OperationDocuments()
        val info = ProviderInfo().apply {
            authority = "ayngallery.received-docs"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
            packageName = app.packageName; name = OperationDocuments::class.java.name
        }
        docs.attachInfo(app, info); ShadowContentResolver.registerProviderInternal(info.authority, docs)
        shadowOf(app.packageManager).addOrUpdateProvider(info)
        shadowOf(app.packageManager).addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        catalog = Catalog(app); uri = DocumentsContract.buildDocumentUri(info.authority, "image")
        file = IncomingMedia.inspect(app, IncomingSelection(UUID.randomUUID().toString(), listOf(uri.toString()))).single()
    }
    @After fun close() = runBlocking { if (::catalog.isInitialized) { catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close() } }
    @Test fun receivedActionPayloadsAreBoundedDeduplicatedAndForwardedWithoutWriteOrPrefixGrants() {
        val other = DocumentsContract.buildDocumentUri(uri.authority, "other")
        val incoming = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, other, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        incoming.clipData = ClipData.newRawUri("selected", uri)
        val request = ReceivedMedia.from(incoming)
        assertEquals(listOf(uri.toString(), other.toString()), request.selection.uris); assertTrue(request.canPersist)
        assertEquals(request, ReceivedMedia.decode(request.encode()))
        val forwarded = Intent(); request.put(forwarded)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, forwarded.flags)
        assertEquals(2, forwarded.clipData!!.itemCount)
        val send = ReceivedMedia.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri))
        assertFalse(send.canPersist); assertEquals(listOf(uri.toString()), send.selection.uris)
        assertEquals(listOf(uri.toString()), ReceivedMedia.from(Intent(Intent.ACTION_VIEW, uri)).selection.uris)
        assertThrows(IllegalArgumentException::class.java) { ReceivedMedia.from(Intent(Intent.ACTION_VIEW, Uri.parse("file:///private/file.png"))) }
        assertThrows(IllegalArgumentException::class.java) { ReceivedMedia.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.com")) }
        assertThrows(IllegalArgumentException::class.java) { ReceivedMedia.from(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(List(129) { uri }))) }
        Unit
    }
    @Test fun linkingReadsOnlyOneDocumentPersistsOnlyReadAndKeepsStableIdAndReferences() = runBlocking {
        val bytes = docs.nodes.getValue("image").file!!.readBytes()
        val id = DocumentLinks(catalog).link(file, true)
        val source = catalog.dao.sources().single(); val item = catalog.dao.media(id)!!
        assertTrue(source.singleDocument); assertEquals(uri.toString(), source.treeUri); assertFalse(item.favorite)
        assertEquals(1, catalog.dao.media().size); assertEquals(0, docs.childQueries); assertEquals(0, docs.creations); assertTrue(docs.deletions.isEmpty())
        val grant = app.contentResolver.persistedUriPermissions.single(); assertEquals(uri, grant.uri); assertTrue(grant.isReadPermission); assertFalse(grant.isWritePermission)
        val data = catalog.readSnapshot(); assertTrue(data.usable(item)); assertEquals(uri, catalog.uri(item, data))
        catalog.favorite(id); catalog.collection("Received reference", "MEDIA", listOf(id)); catalog.hideAlbums(listOf(item.albumId), true)
        val repeated = DocumentLinks(catalog).link(file, true)
        assertEquals(id, repeated); assertTrue(catalog.dao.media(id)!!.favorite); assertEquals(id, catalog.dao.collectionItems().single().mediaId)
        assertFalse(catalog.readSnapshot().visible(catalog.dao.media(id)!!)); assertArrayEquals(bytes, docs.nodes.getValue("image").file!!.readBytes())
    }
    @Test fun deniedPersistentCapabilityOrChangedSelectionCreatesNoSourceOrGrant(): Unit = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { DocumentLinks(catalog).link(file, false) } }
        docs.nodes.getValue("image").file!!.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { DocumentLinks(catalog).link(file, true) } }
        assertTrue(catalog.dao.sources().isEmpty()); assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun revokedSingleFileGrantPreservesFavoritesAndRelinkingRestoresAccessWithoutTreeScan() = runBlocking {
        val id = DocumentLinks(catalog).link(file, true); catalog.favorite(id)
        app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(catalog.readMediaSnapshot(id).usable(catalog.dao.media(id)!!))
        catalog.refreshAccess(); assertFalse(catalog.dao.sources().single().reachable); assertTrue(catalog.dao.media(id)!!.favorite)
        assertEquals(id, DocumentLinks(catalog).link(file, true)); assertTrue(catalog.readMediaSnapshot(id).usable(catalog.dao.media(id)!!))
        assertEquals(0, docs.childQueries); assertTrue(catalog.dao.media(id)!!.favorite)
    }
    @Test fun documentReplacementRetainsOldReferencesUnavailableAndNeverMovesFavoritesOntoNewContent() = runBlocking {
        val old = DocumentLinks(catalog).link(file, true); catalog.favorite(old); catalog.collection("Keep old", "MEDIA", listOf(old))
        catalog.hideAlbums(listOf(catalog.dao.media(old)!!.albumId), true)
        docs.nodes.getValue("image").file!!.appendText("new content")
        val source = catalog.dao.sources().single(); val current = DocumentLinks(catalog).scan(source)
        assertNotEquals(old, current); assertFalse(catalog.dao.media(old)!!.available); assertTrue(catalog.dao.media(old)!!.favorite)
        assertFalse(catalog.dao.media(current)!!.favorite); assertEquals(old, catalog.dao.collectionItems().single().mediaId)
        assertEquals(0, docs.childQueries); assertEquals(2, catalog.dao.media().size)
        assertFalse(catalog.readSnapshot().visible(catalog.dao.media(current)!!))
    }
    @Test fun linkedDocumentsAreNeverWritableDestinationsAndCannotDeleteOriginalsButCanBeCopied() = runBlocking {
        val id = DocumentLinks(catalog).link(file, true); val item = catalog.dao.media(id)!!
        val files = FileOperations(catalog)
        for (kind in listOf("MOVE", "RENAME", "TRASH")) {
            assertTrue(runCatching { files.enqueue(item, kind, item.albumId, "renamed.png", start = false) }.exceptionOrNull()?.message!!.contains("sola lettura"))
        }
        assertTrue(runCatching { files.enqueue(item, "COPY", item.albumId, start = false) }.isFailure)
        assertTrue(catalog.dao.operations().isEmpty()); assertTrue(docs.deletions.isEmpty()); assertEquals(0, docs.creations)
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, "destination")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("destination", tree.toString(), "Destination", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        val destination = catalog.dao.albums().single { it.name == "Destination" }.id
        val copy = files.enqueue(item, "COPY", destination, start = false); files.execute(copy)
        val op = catalog.dao.operation(copy)!!; assertEquals(op.message, "COMPLETED", op.phase)
        assertArrayEquals(docs.nodes.getValue("image").file!!.readBytes(), docs.node(op.publishedUri!!).file!!.readBytes())
        assertTrue(docs.deletions.isEmpty()); assertNotEquals(id, op.resultMediaId)
    }
    @Test fun overlappingFolderKeepsCanonicalIdAndWritableCopiesNeverInheritSingleFileGrant() = runBlocking {
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("folder", tree.toString(), "Folder", "VISUAL")
        catalog.dao.saveSource(source)
        SourceScanner(catalog).scan(source)
        val album = catalog.dao.albums().first { it.name == "Folder" }.id
        val original = catalog.dao.media().single(); catalog.favorite(original.id)
        assertEquals(original.id, DocumentLinks(catalog).link(file, true))
        val data = catalog.readSnapshot(); assertFalse(data.sourceFor(data.byId.getValue(original.id))!!.singleDocument)
        assertTrue(catalog.dao.media(original.id)!!.favorite)
        val files = FileOperations(catalog); val opId = files.enqueue(catalog.dao.media(original.id)!!, "COPY", original.albumId, "other.png", start = false)
        files.execute(opId); val op = catalog.dao.operation(opId)!!
        assertEquals(op.message, "COMPLETED", op.phase)
        assertEquals(listOf(source.id), catalog.dao.membership().filter { it.mediaId == op.resultMediaId }.map { it.sourceId })
        assertNotNull(catalog.dao.albums().find { it.id == album })
    }
}
