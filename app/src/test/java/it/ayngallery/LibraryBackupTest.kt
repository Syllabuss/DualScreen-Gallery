package it.ayngallery

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.net.Uri
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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class LibraryBackupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var catalog: Catalog
    private lateinit var backup: LibraryBackup
    private lateinit var docs: OperationDocuments
    private lateinit var item: MediaEntity
    private lateinit var collection: String
    @Before fun start(): Unit = runBlocking {
        docs = OperationDocuments(); docs.attachInfo(app, ProviderInfo().apply {
            authority = "ayngallery.backup-fixture"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        }); ShadowContentResolver.registerProviderInternal("ayngallery.backup-fixture", docs)
        catalog = Catalog(app); backup = LibraryBackup(catalog)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.backup-fixture", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("original", tree.toString(), "Fixture", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        item = catalog.dao.media().single(); catalog.favorite(item.id); item = catalog.dao.media(item.id)!!
        catalog.hideAlbums(listOf(item.albumId), true)
        // Restorable hidden references already existed before the album was hidden.
        collection = "saved-collection"
        catalog.dao.saveCollection(CollectionEntity(collection, "Original name", "MEDIA", item.id))
        catalog.dao.saveCollectionItems(listOf(CollectionItem(collection, item.id, 0)))
        catalog.dao.albumCover(item.albumId, item.id)
    }
    @After fun close() {
        if (!::catalog.isInitialized) return
        val job = catalog.scope.coroutineContext[Job]!!; job.cancel()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!job.isCompleted && System.nanoTime() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5) }
        try { assertTrue("Catalog jobs did not stop before closing the fixture database", job.isCompleted) }
        finally { catalog.db.close() }
    }
    private suspend fun document(foreign: Boolean = false) = LibraryBackup.decode(backup.export()).let { if (foreign) it.copy(origin = UUID.randomUUID().toString()) else it }
    private fun comparison() = ComparisonStore(app, catalog.scope, catalog)
    private fun fourPaneState() = ComparisonState(
        listOf(ComparisonPane("pane_1", item.id, true, listOf(item.id, "missing-reference"), ViewTransform(3.5f, -.2f, .3f)),
            ComparisonPane("pane_2", null, false, view = ViewTransform(2f, .1f, -.1f)),
            ComparisonPane("pane_3", "missing-reference", true, listOf("missing-reference"), ViewTransform(5f, .4f, .2f)),
            ComparisonPane("pane_4", pinned = true)), active = "pane_3", linked = true, enabled = true)
    @Test fun comparisonBackupPreservesFourPanesAndTransformsWithoutOpeningTheSavedSession() = runBlocking {
        val store = comparison(); val original = fourPaneState(); store.update(original); store.save("Varianti OC")
        val document = document(); assertEquals(1, document.comparisons.size)
        assertEquals(original, ComparisonStore.decode(document.comparisons.single().state))
        store.delete("Varianti OC"); store.update(ComparisonState()); val current = store.state.value
        val preview = backup.preview(document); assertEquals(1, preview.comparisons)
        backup.restore(document, preview.baseline)
        assertEquals(current, store.state.value)
        val recreated = comparison(); assertTrue(recreated.load("Varianti OC"))
        val loaded = recreated.state.value
        assertEquals(original.panes.size, loaded.panes.size); assertEquals(original.active, loaded.active); assertTrue(loaded.linked)
        assertEquals(original.panes.map { it.view }, loaded.panes.map { it.view })
        assertEquals(item.id, loaded.panes.first().mediaId)
        assertNotEquals("missing-reference", loaded.panes[2].mediaId)
        assertNull(catalog.readSnapshot().byId[loaded.panes[2].mediaId])
    }
    @Test fun foreignSessionsKeepSameNameLocalSessionAndMapPinsToUnavailableRestoredReferences() = runBlocking {
        val store = comparison(); store.update(fourPaneState()); store.save("Varianti OC")
        val document = document(true); val grants = app.contentResolver.persistedUriPermissions.map { it.uri }
        val current = store.state.value; val rawLocal = catalog.dao.comparisonSession("Varianti OC")!!
        backup.restore(document, backup.preview(document).baseline)
        val imported = catalog.dao.comparisonSession("Varianti OC (2)")!!
        assertNotEquals(rawLocal.id, imported.id); assertEquals(rawLocal, catalog.dao.comparisonSession("Varianti OC"))
        val state = ComparisonStore.decode(imported.state)!!; val ref = catalog.dao.media().single { it.id != item.id }
        assertEquals(ref.id, state.panes.first().mediaId); assertEquals(ref.id, state.panes.first().context.first())
        assertEquals(fourPaneState().panes.map { it.view }, state.panes.map { it.view }); assertFalse(ref.available)
        assertFalse(catalog.readSnapshot().usable(ref)); assertEquals(current, store.state.value)
        assertEquals(grants, app.contentResolver.persistedUriPermissions.map { it.uri }); assertEquals(0, docs.creations); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun comparisonMergeIsIdempotentAndExplicitReplacementAffectsOnlyImportedSession() = runBlocking {
        val store = comparison(); store.update(fourPaneState()); store.save("Varianti OC"); val document = document(true)
        backup.restore(document, backup.preview(document).baseline)
        val imported = catalog.dao.comparisonSession("Varianti OC (2)")!!
        val localEdit = imported.copy(name = "Edited locally", state = ComparisonStore.encode(ComparisonState(linked = false)).toString())
        catalog.dao.saveComparisonSessions(listOf(localEdit))
        store.save("Local-only")
        backup.restore(document, backup.preview(document).baseline)
        assertEquals(localEdit, catalog.dao.comparisonSession("Edited locally")); assertEquals(3, catalog.dao.comparisonSessions().size)
        backup.restore(document, backup.preview(document).baseline, true)
        val replaced = catalog.dao.comparisonSession("Varianti OC (2)")!!
        assertEquals(imported.id, replaced.id); assertEquals(imported.state, replaced.state)
        assertNotNull(catalog.dao.comparisonSession("Local-only")); assertEquals(3, catalog.dao.comparisonSessions().size)
    }
    @Test fun comparisonChangesInvalidateReviewAndLegacyV1DoesNotDeleteSessions() = runBlocking {
        val store = comparison(); store.update(fourPaneState()); store.save("Saved")
        val document = document(); val preview = backup.preview(document)
        store.delete("Saved")
        assertTrue(runCatching { backup.restore(document, preview.baseline) }.exceptionOrNull()!!.message!!.contains("anteprima"))
        assertTrue(catalog.dao.comparisonSessions().isEmpty())
        store.save("Keep local")
        val legacy = JSONObject(LibraryBackup.encode(document)).put("version", 1).apply { remove("comparisons") }.toString()
        val old = LibraryBackup.decode(legacy); assertTrue(old.comparisons.isEmpty())
        backup.restore(old, backup.preview(old).baseline, true)
        assertEquals(listOf("Keep local"), catalog.dao.comparisonSessions().map { it.name })
    }
    @Test fun invalidComparisonTransferRejectsSilentRepairsUnexpectedFieldsAndAmbiguousPanes() = runBlocking {
        val store = comparison(); store.update(fourPaneState()); store.save("Saved"); val raw = backup.export()
        fun corrupt(edit: (JSONObject) -> Unit): String = JSONObject(raw).apply { edit(getJSONArray("comparisons").getJSONArray(0).getJSONObject(2)) }.toString()
        val invalid = listOf(corrupt { it.put("active", "unknown") }, corrupt { it.put("linked", "true") },
            corrupt { it.put("permissions", JSONArray()) }, corrupt { it.getJSONArray("panes").getJSONObject(0).put("zoom", 99) },
            corrupt { it.getJSONArray("panes").getJSONObject(0).put("x", -.6) },
            corrupt { it.getJSONArray("panes").getJSONObject(0).put("pinned", "true") },
            corrupt { it.getJSONArray("panes").getJSONObject(1).put("id", "pane_1") },
            corrupt { it.getJSONArray("panes").getJSONObject(0).put("context", JSONArray(listOf("same", "same"))) },
            JSONObject(raw).apply { getJSONArray("comparisons").put(getJSONArray("comparisons").getJSONArray(0)) }.toString())
        invalid.forEach { assertTrue(runCatching { LibraryBackup.decode(it) }.isFailure) }
        assertEquals(1, catalog.dao.comparisonSessions().size); assertEquals(1, catalog.dao.media().size)
    }
    @Test fun comparisonInsertFailureRollsBackMetadataCollectionsAndSessionsTogether() = runBlocking {
        val store = comparison(); store.update(fourPaneState()); store.save("Saved"); val document = document(true)
        val before = catalog.readSnapshot(); val saved = catalog.dao.comparisonSessions()
        catalog.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_comparison BEFORE INSERT ON comparison_sessions WHEN NEW.name='Saved (2)' BEGIN SELECT RAISE(ABORT,'fixture rejected comparison'); END")
        assertTrue(runCatching { backup.restore(document, backup.preview(document).baseline) }.isFailure)
        assertEquals(before, catalog.readSnapshot()); assertEquals(saved, catalog.dao.comparisonSessions())
        assertEquals(0, docs.creations); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun metadataRoundTripPreservesHierarchyReferencesAndExclusionsWithoutMediaOrRuntimeData() = runBlocking {
        val beforeQueries = docs.childQueries; val beforeReads = docs.creations
        catalog.dao.saveMedia(listOf(item.copy(artworkUri = File(app.cacheDir, "private-artwork.art").toURI().toString())))
        val raw = backup.export(); val d = LibraryBackup.decode(raw)
        assertEquals(item.id, d.data.media.single().id); assertTrue(d.data.media.single().favorite)
        assertTrue(d.data.albums.first { it.id == item.albumId }.hidden)
        assertEquals(item.id, d.data.collections.single().coverMediaId); assertEquals(item.id, d.data.collectionItems.single().mediaId)
        assertFalse(raw.contains("private-artwork")); assertFalse(raw.contains("file://")); assertFalse(raw.contains("grant")); assertFalse(raw.contains("operations"))
        assertEquals(beforeQueries, docs.childQueries); assertEquals(beforeReads, docs.creations)
        assertEquals(d, LibraryBackup.decode(LibraryBackup.encode(d)))
    }
    @Test fun foreignSameUrisStayOfflineEvenWithExistingGrantsAndKeepHiddenReferences() = runBlocking {
        val document = document(true); val grants = app.contentResolver.persistedUriPermissions.map { it.uri to it.isWritePermission }
        val preview = backup.preview(document); assertEquals(1, preview.sourcesToLink); assertEquals(1, preview.unavailable)
        val queries = docs.childQueries; backup.restore(document, preview.baseline)
        val restored = catalog.dao.media().single { it.id != item.id }
        assertFalse(restored.available); assertTrue(restored.favorite); assertNull(restored.deviceUri)
        val data = catalog.readSnapshot(); assertFalse(data.usable(restored)); assertFalse(data.visible(restored))
        val source = catalog.dao.sources().single { it.restoreUri != null }
        assertEquals(document.data.sources.single().treeUri, source.restoreUri)
        catalog.refreshAccess(); assertFalse(catalog.readSnapshot().sources.first { it.id == source.id }.reachable)
        assertTrue(runCatching { SourceScanner(catalog).scan(source) }.isFailure)
        assertEquals(queries, docs.childQueries); assertEquals(grants, app.contentResolver.persistedUriPermissions.map { it.uri to it.isWritePermission })
        assertEquals(1, docs.nodes.values.count { it.file != null }); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun repeatedImportsAreIdempotentAndCollectionMergePreservesLocalEditsUntilReplacementChosen() = runBlocking {
        val document = document(true); backup.restore(document, backup.preview(document).baseline)
        val added = catalog.dao.collections().single { it.id != collection }
        catalog.renameCollection(added.id, "Edited locally")
        backup.restore(document, backup.preview(document).baseline)
        assertEquals(2, catalog.dao.collections().size); assertEquals(2, catalog.dao.media().size); assertEquals(2, catalog.dao.sources().size)
        assertEquals("Edited locally", catalog.dao.collections().first { it.id == added.id }.name)
        backup.restore(document, backup.preview(document).baseline, true)
        assertEquals("Original name", catalog.dao.collections().first { it.id == added.id }.name)
        assertEquals(2, catalog.dao.collectionItems().size)
    }
    @Test fun sameLibraryReusesExactIdsAndNeverDeletesUnrelatedCollectionsOrUnhidesAlbums() = runBlocking {
        val document = document(); catalog.renameCollection(collection, "Current name")
        catalog.dao.saveCollection(CollectionEntity("unrelated", "Keep me", "MEDIA"))
        backup.restore(document, backup.preview(document).baseline)
        assertEquals(1, catalog.dao.media().size); assertEquals(1, catalog.dao.sources().size)
        assertEquals("Current name", catalog.dao.collections().first { it.id == collection }.name)
        backup.restore(document, backup.preview(document).baseline, true)
        assertEquals("Original name", catalog.dao.collections().first { it.id == collection }.name)
        assertTrue(catalog.dao.albums().first { it.id == item.albumId }.hidden)
        assertNotNull(catalog.dao.collections().find { it.id == "unrelated" })
        assertTrue(catalog.dao.media(item.id)!!.available)
    }
    @Test fun laterForeignBackupKeepsReplacedFileReferencesSeparateAndPreservesTheirCollections() = runBlocking {
        val first = document(true)
        backup.restore(first, backup.preview(first).baseline)
        val previous = catalog.dao.media().single { it.id != item.id }
        val later = first.copy(data = first.data.copy(media = first.data.media.map { it.copy(size = it.size + 17, modified = it.modified + 1, favorite = false) }))
        backup.restore(later, backup.preview(later).baseline)
        val all = catalog.dao.media()
        assertEquals(3, all.size)
        val changed = all.single { it.id != item.id && it.id != previous.id }
        assertNotEquals(previous.documentKey, changed.documentKey)
        assertTrue(all.single { it.id == previous.id }.favorite); assertFalse(changed.favorite)
        assertFalse(changed.available); assertFalse(previous.available)
        val restoredCollection = catalog.dao.collections().single { it.id != collection }
        assertEquals(listOf(previous.id, changed.id), catalog.dao.collectionItems().filter { it.collectionId == restoredCollection.id }.sortedBy { it.position }.map { it.mediaId })
        backup.restore(later, backup.preview(later).baseline)
        assertEquals(3, catalog.dao.media().size)
        assertTrue(docs.deletions.isEmpty()); assertEquals(0, docs.creations)
    }
    @Test fun tenThousandReferencesRestoreInOneTransactionWithoutReadingMediaOrDroppingOrder() = runBlocking {
        val source = SourceEntity("scale", "content://scale-fixture/tree/root", "Scale fixture", "VISUAL")
        val albums = (0 until 100).map { i -> AlbumEntity("folder-$i", "scale:folder-$i", "content://scale-fixture/document/folder-$i", "Album $i", "Album $i", null, hidden = i % 10 == 0) }
        val media = (0 until 10_000).map { i -> MediaEntity("item-$i", "scale:item-$i", "content://scale-fixture/document/item-$i", albums[i % 100].id, "Image-$i.png", "image/png", "IMAGE", 1000, 1, favorite = i % 13 == 0, added = 1) }
        val collections = (0 until 200).map { CollectionEntity("collection-$it", "Selection $it", "MEDIA") }
        val items = media.mapIndexed { i, m -> CollectionItem(collections[i % 200].id, m.id, i / 200) }
        val document = LibraryDocument(UUID.randomUUID().toString(), CatalogSnapshot(listOf(source), albums, media, media.map { MediaSource(it.id, source.id, 0) },
            albums.map { AlbumAncestor(it.id, it.id) }, collections, items, true, albums.map { AlbumSource(it.id, source.id, 0) }))
        val queries = docs.childQueries
        val started = System.nanoTime(); val raw = LibraryBackup.encode(document)
        val encoded = System.nanoTime(); val frozen = LibraryBackup.decode(raw)
        val decoded = System.nanoTime(); val preview = backup.preview(frozen)
        val reviewed = System.nanoTime(); backup.restore(frozen, preview.baseline)
        val restored = System.nanoTime(); val next = catalog.readSnapshot()
        assertEquals(10_001, next.media.size); assertEquals(201, next.collections.size)
        val restoredSource = next.sources.single { it.restoreUri == source.treeUri }
        val ids = next.membership.filter { it.sourceId == restoredSource.id }.map { it.mediaId }.toSet()
        assertEquals(10_000, ids.size); assertTrue(next.media.filter { it.id in ids }.all { !it.available })
        assertEquals(media.count { it.favorite }, next.media.count { it.id in ids && it.favorite })
        assertEquals(10_001, next.collectionItems.size)
        val names = next.media.associate { it.id to it.name }
        next.collections.filter { it.id != collection }.forEach { c ->
            val originalIndex = c.name.removePrefix("Selection ").toInt()
            assertEquals((0 until 50).map { "Image-${originalIndex + it * 200}.png" }, next.collectionItems.filter { it.collectionId == c.id }.sortedBy { it.position }.map { names.getValue(it.mediaId) })
        }
        assertEquals(queries, docs.childQueries); assertTrue(docs.deletions.isEmpty()); assertEquals(0, docs.creations)
        fun ms(from: Long, to: Long) = (to - from) / 1_000_000
        File("../qa/library-backup-scale.json").writeText(JSONObject().put("runner", "Robolectric SDK 28; not Thor performance")
            .put("references", 10_000).put("collections", 200).put("bytes", raw.toByteArray(Charsets.UTF_8).size)
            .put("encodeValidatedMs", ms(started, encoded)).put("decodeMs", ms(encoded, decoded)).put("previewMs", ms(decoded, reviewed)).put("restoreMs", ms(reviewed, restored)).toString(2))
    }
    @Test fun changedCatalogRejectsStalePreviewAndVersionMismatchNeverTransfersFavoriteOntoNewContent() = runBlocking {
        val document = document(); val oldPreview = backup.preview(document)
        catalog.favorite(item.id)
        assertTrue(runCatching { backup.restore(document, oldPreview.baseline) }.exceptionOrNull()!!.message!!.contains("anteprima"))
        assertFalse(catalog.dao.media(item.id)!!.favorite)
        catalog.dao.saveMedia(listOf(catalog.dao.media(item.id)!!.copy(size = item.size + 100)))
        backup.restore(document, backup.preview(document).baseline)
        assertFalse(catalog.dao.media(item.id)!!.favorite)
        val recoveredReference = catalog.dao.media().single { it.id != item.id }
        assertTrue(recoveredReference.favorite); assertFalse(recoveredReference.available)
        assertEquals(0, docs.creations); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun malformedTypesDanglingReferencesCyclesAndUnexpectedFieldsAreRejected() = runBlocking {
        val valid = backup.export()
        val invalid = listOf(valid + " trailing", JSONObject(valid).put("version", 3).toString(),
            JSONObject(valid).put("permissions", JSONArray()).toString(), JSONObject(valid).apply { getJSONArray("media").getJSONArray(0).put(7, "300000") }.toString(),
            JSONObject(valid).apply { getJSONArray("items").getJSONArray(0).put(1, "missing") }.toString(),
            JSONObject(valid).apply { getJSONArray("albums").getJSONArray(0).put(5, getJSONArray("albums").getJSONArray(0).getString(0)) }.toString(),
            JSONObject(valid).apply { getJSONArray("media").getJSONArray(0).put(2, "file:///secret") }.toString(),
            JSONObject(valid).apply { getJSONArray("media").getJSONArray(0).put(14, "true") }.toString())
        invalid.forEach { assertTrue(runCatching { LibraryBackup.decode(it) }.isFailure) }
        assertEquals(1, catalog.dao.media().size); assertEquals(0, docs.creations)
    }
    @Test fun providerReadsRejectMalformedUtf8AndDraftIdentityCannotEscapePrivateDirectory() = runBlocking {
        val raw = backup.export(); val uri = Uri.parse("content://backup.read/selected")
        var closed = false
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(raw.toByteArray()) { override fun close() { closed = true; super.close() } })
        assertEquals(raw, backup.read(uri)); assertTrue(closed)
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        assertTrue(runCatching { backup.read(uri) }.isFailure)
        val drafts = LibraryDrafts(File(app.filesDir, "draft-fixture")); val id = drafts.create(raw)
        assertEquals(raw, drafts.read(id)); assertTrue(runCatching { drafts.read("../../secret") }.isFailure)
        drafts.discard(id); assertTrue(runCatching { drafts.read(id) }.isFailure)
        assertTrue(runCatching { backup.read(Uri.parse("file:///private")) }.isFailure)
    }
    @Test fun schema8UpgradeAddsOnlyNullableRestoreLocatorAndKeepsExistingSources(): Unit = runBlocking {
        val db = app.openOrCreateDatabase("restore-migration.db", 0, null)
        val entities = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/8.json").readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        db.execSQL("INSERT INTO sources VALUES('stable','content://fixture/tree/root','Keep source','MUSIC',0,0,'old error',123)")
        db.version = 8; db.close()
        val migrated = androidx.room.Room.databaseBuilder(app, CatalogDatabase::class.java, "restore-migration.db").addMigrations(CatalogDatabase.MIGRATION_8_9, CatalogDatabase.MIGRATION_9_10, CatalogDatabase.MIGRATION_10_11, CatalogDatabase.MIGRATION_11_12, CatalogDatabase.MIGRATION_12_13, CatalogDatabase.MIGRATION_13_14).build()
        try {
            assertEquals(14, migrated.openHelper.readableDatabase.version)
            val source = migrated.catalog().source("stable")!!
            assertEquals("Keep source", source.title); assertFalse(source.enabled); assertEquals(123L, source.lastScan); assertNull(source.restoreUri)
        } finally { migrated.close() }
    }
}
