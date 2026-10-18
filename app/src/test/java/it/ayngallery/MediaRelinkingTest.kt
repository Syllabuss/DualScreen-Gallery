package it.ayngallery

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class MediaRelinkingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var catalog: Catalog
    private lateinit var docs: OperationDocuments
    private lateinit var old: MediaEntity
    private lateinit var target: IncomingFile
    private lateinit var engine: MediaRelinking
    @Before fun start(): Unit = runBlocking {
        val info = ProviderInfo().apply { authority = "ayngallery.relink-docs"; exported = true; grantUriPermissions = true
            packageName = app.packageName; name = OperationDocuments::class.java.name
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission }
        docs = OperationDocuments(); docs.attachInfo(app, info)
        ShadowContentResolver.registerProviderInternal(info.authority, docs)
        shadowOf(app.packageManager).addOrUpdateProvider(info)
        shadowOf(app.packageManager).addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        catalog = Catalog(app); engine = MediaRelinking(catalog)
        val uri = DocumentsContract.buildDocumentUri(info.authority, "image")
        target = IncomingMedia.inspect(app, IncomingSelection(UUID.randomUUID().toString(), listOf(uri.toString()))).single()
        val source = SourceEntity("pending", "content://${app.packageName}.pending-backup/tree/old", "Backup fixture", "VISUAL", reachable = false, restoreUri = "content://old-device/tree/root")
        val parent = AlbumEntity("old-parent", "backup:parent", "content://old-device/document/parent", "Private parent", "Private parent", null, hidden = true)
        val album = AlbumEntity("old-album", "backup:album", "content://old-device/document/album", "Saved album", "Saved album", parent.id)
        old = MediaEntity("old-ref", "backup:old-ref", "content://old-device/document/image", album.id, "Original.png", "image/png", "IMAGE", target.size, target.modified, favorite = true, available = false, favoriteAddedAt = 12345L)
        catalog.dao.saveSource(source); catalog.dao.saveAlbums(listOf(parent, album.copy(coverMediaId = old.id)))
        catalog.dao.saveAncestors(listOf(AlbumAncestor(album.id, parent.id), AlbumAncestor(album.id, album.id)))
        catalog.dao.saveAlbumSources(listOf(AlbumSource(album.id, source.id, 0)))
        catalog.dao.saveMedia(listOf(old)); catalog.dao.saveMembership(listOf(MediaSource(old.id, source.id, 0)))
        catalog.dao.saveCollection(CollectionEntity("saved", "Kept collection", "MEDIA", old.id)); catalog.dao.saveCollectionItems(listOf(CollectionItem("saved", old.id, 0)))
        catalog.dao.saveArtworkChoice(ArtworkChoiceEntity(ArtworkOrders.folder(album.id), old.id, null))
    }
    @After fun close() {
        val job = catalog.scope.coroutineContext[Job]!!; job.cancel()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!job.isCompleted && System.nanoTime() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10) }
        check(job.isCompleted); catalog.db.close()
    }
    @Test fun previewDoesNotPersistGrantAndConfirmationMovesAllMetadataWithoutChangingOriginalBytes() = runBlocking {
        val bytes = docs.nodes.getValue("image").file!!.readBytes()
        val candidate = engine.preview(old.id, target, true)
        assertEquals(candidate, RelinkCandidate.decode(candidate.encode())); assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        assertEquals(1, catalog.dao.media().size)
        val id = engine.confirm(candidate); assertNotEquals(old.id, id)
        val data = catalog.readSnapshot(); assertEquals(1, data.media.size); assertNull(catalog.dao.media(old.id))
        assertEquals(12345L, data.byId.getValue(old.id).favoriteAddedAt); assertTrue(data.byId.getValue(old.id).favorite); assertEquals(id, data.byId.getValue(old.id).id)
        assertEquals(id, catalog.dao.collectionItems().single().mediaId); assertEquals(id, catalog.dao.collections().single().coverMediaId)
        assertTrue(data.albums.filter { it.coverMediaId != null }.all { it.coverMediaId == id })
        assertTrue(data.artworkChoices.all { it.coverMediaId == id }); assertFalse(data.visible(data.byId.getValue(id)))
        assertEquals(id, catalog.readMediaSnapshot(old.id).byId.getValue(old.id).id)
        assertEquals(id, catalog.dao.relink(old.id)); assertFalse(catalog.dao.source("pending")!!.enabled)
        val grant = app.contentResolver.persistedUriPermissions.single(); assertTrue(grant.isReadPermission); assertFalse(grant.isWritePermission)
        assertEquals(target.uri, grant.uri.toString()); assertEquals(0, docs.childQueries); assertEquals(0, docs.creations); assertTrue(docs.deletions.isEmpty())
        assertArrayEquals(bytes, docs.nodes.getValue("image").file!!.readBytes())
        // Export must have no dangling covers or links after the merge.
        assertEquals(1, LibraryBackup.decode(LibraryBackup(catalog).export()).data.media.size)
    }
    @Test fun alreadyIndexedCandidateIsMergedAndCollectionOrderDeduplicatedKeepingNativeIdentity() = runBlocking {
        val id = DocumentLinks(catalog).link(target, true)
        catalog.dao.saveCollectionItems(listOf(CollectionItem("saved", id, 1)))
        val candidate = engine.preview(old.id, target, true)
        assertEquals(id, engine.confirm(candidate)); assertEquals(1, catalog.dao.media().size)
        assertEquals(listOf(CollectionItem("saved", id, 0)), catalog.dao.collectionItems())
        assertTrue(catalog.dao.media(id)!!.favorite); assertEquals(1, app.contentResolver.persistedUriPermissions.size)
    }
    @Test fun reimportingTheSameForeignBackupRetainsConfirmedLinksInsteadOfCreatingMissingDuplicates() = runBlocking {
        val backup = LibraryBackup(catalog)
        val document = LibraryBackup.decode(backup.export()).copy(origin = UUID.randomUUID().toString())
        backup.restore(document, backup.preview(document).baseline)
        val imported = catalog.dao.media().single { it.id != old.id }
        val id = engine.confirm(engine.preview(imported.id, target, true))
        backup.restore(document, backup.preview(document).baseline)
        assertEquals(2, catalog.dao.media().size); assertEquals(id, catalog.readSnapshot().byId.getValue(imported.id).id)
        val foreignItems = catalog.dao.collectionItems().filter { it.collectionId != "saved" }
        assertEquals(listOf(id), foreignItems.map { it.mediaId })
        assertTrue(catalog.dao.media(id)!!.favorite)
        assertEquals(1, catalog.dao.media().count { !it.available })
        assertEquals(1, app.contentResolver.persistedUriPermissions.size)
        val source = catalog.dao.sources().single { it.singleDocument }
        catalog.dao.saveSource(source.copy(enabled = false))
        val excluded = catalog.readSnapshot()
        assertFalse(MediaRelinking.missing(excluded.byId.getValue(id), excluded))
        assertFalse(MediaRelinking.missing(excluded).any { it.id == id })
    }
    @Test fun stalePreviewChangedFileAndWrongKindLeaveMetadataAndGrantsUnchanged() = runBlocking {
        val candidate = engine.preview(old.id, target, true)
        catalog.favorite(old.id)
        assertTrue(runCatching { engine.confirm(candidate) }.exceptionOrNull()!!.message!!.contains("anteprima"))
        val fresh = engine.preview(old.id, target, true)
        docs.nodes.getValue("image").file!!.appendText("changed")
        assertTrue(runCatching { engine.confirm(fresh) }.isFailure)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertNull(catalog.dao.relink(old.id)); assertEquals(1, catalog.dao.media().size)
        assertTrue(runCatching { engine.preview(old.id, target.copy(mime = "video/mp4", kind = "VIDEO"), true) }.isFailure)
        assertTrue(runCatching { engine.preview(old.id, target, false) }.isFailure)
    }
    @Test fun linkAndMetadataCallbackRollbackTogetherAndReleaseOnlyTheNewReadGrant() = runBlocking {
        val candidate = engine.preview(old.id, target, true)
        assertTrue(runCatching { catalog.writer.lock(); try { DocumentLinks(catalog).linkLocked(target, true) { error("forced metadata failure") } } finally { catalog.writer.unlock() } }.isFailure)
        assertEquals(listOf("pending"), catalog.dao.sources().map { it.id }); assertEquals(1, catalog.dao.media().size)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertEquals(candidate.baseline, LibraryBackup.fingerprint(catalog.readSnapshot()))
        app.contentResolver.takePersistableUriPermission(target.uri.let(android.net.Uri::parse), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(runCatching { catalog.writer.lock(); try { DocumentLinks(catalog).linkLocked(target, true) { error("again") } } finally { catalog.writer.unlock() } }.isFailure)
        assertEquals(1, app.contentResolver.persistedUriPermissions.size)
    }
    @Test fun revokedExistingDocumentCanRecoverItsOwnIdAndLaterRecreationResolvesOldPins() = runBlocking {
        val id = engine.confirm(engine.preview(old.id, target, true))
        val canonical = catalog.dao.media(id)!!
        app.contentResolver.releasePersistableUriPermission(target.uri.let(android.net.Uri::parse), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(MediaRelinking.missing(canonical, catalog.readSnapshot()))
        assertEquals(id, engine.confirm(engine.preview(id, target, true)))
        assertEquals(1, catalog.dao.media().size); assertEquals(id, catalog.dao.relink(old.id))
        val comparison = ComparisonStore(app, catalog.scope, catalog)
        comparison.update(ComparisonState().pin(old.id, listOf(old.id))!!); comparison.save("Old pin")
        val recreated = ComparisonStore(app, catalog.scope, catalog); assertTrue(recreated.load("Old pin"))
        val pin = recreated.state.value.panes.first { it.mediaId != null }
        assertEquals(id, catalog.readSnapshot().byId.getValue(pin.mediaId!!).id)
        val exported = LibraryBackup.decode(LibraryBackup(catalog).export())
        val savedPin = ComparisonStore.decode(exported.comparisons.single().state)!!.panes.first { it.mediaId != null }
        assertEquals(id, savedPin.mediaId); assertEquals(listOf(id), savedPin.context)
    }
    @Test fun redirectChainsFlattenAndCyclesStayUnavailableWithoutDuplicatingVisibleMedia() = runBlocking {
        val id = engine.confirm(engine.preview(old.id, target, true))
        val snapshot = catalog.readSnapshot()
        val linked = snapshot.copy(mediaLinks = listOf(MediaRelink("earlier", old.id), MediaRelink(old.id, id), MediaRelink("cycle-a", "cycle-b"), MediaRelink("cycle-b", "cycle-a")))
        assertEquals(id, linked.byId.getValue("earlier").id); assertNull(linked.byId["cycle-a"])
        assertEquals(1, linked.media.size)
        catalog.dao.saveRelink(MediaRelink("cycle-a", "cycle-b")); catalog.dao.saveRelink(MediaRelink("cycle-b", "cycle-a"))
        assertTrue(runCatching { catalog.readMediaSnapshot("cycle-a") }.isFailure)
    }
    @Test fun room9MigrationKeepsSourcesMetadataAndBuildsEmptyRedirectTable(): Unit = runBlocking {
        val db = app.openOrCreateDatabase("relink-migration.db", 0, null)
        val entities = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/9.json").readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        db.execSQL("INSERT INTO sources VALUES('old','content://fixture/tree/root','Preserve','MUSIC',0,0,NULL,123,'content://old-device/tree/root')")
        db.version = 9; db.close()
        val migrated = androidx.room.Room.databaseBuilder(app, CatalogDatabase::class.java, "relink-migration.db").addMigrations(CatalogDatabase.MIGRATION_9_10, CatalogDatabase.MIGRATION_10_11, CatalogDatabase.MIGRATION_11_12, CatalogDatabase.MIGRATION_12_13, CatalogDatabase.MIGRATION_13_14).build()
        try { assertEquals(14, migrated.openHelper.readableDatabase.version); assertTrue(migrated.catalog().relinks().isEmpty()); assertEquals("content://old-device/tree/root", migrated.catalog().source("old")!!.restoreUri) }
        finally { migrated.close() }
    }
}
