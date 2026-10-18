package it.ayngallery

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class FolderRelinkingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var catalog: Catalog
    private lateinit var docs: OperationDocuments
    private lateinit var engine: FolderRelinking
    private lateinit var tree: Uri
    private lateinit var originals: List<MediaEntity>
    @Before fun start() = runBlocking {
        val info = ProviderInfo().apply { authority = "ayngallery.folder-fixture"; exported = true; grantUriPermissions = true
            packageName = app.packageName; name = OperationDocuments::class.java.name
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission }
        docs = OperationDocuments(); docs.attachInfo(app, info)
        ShadowContentResolver.registerProviderInternal(info.authority, docs); shadowOf(app.packageManager).addOrUpdateProvider(info)
        docs.add("destination", "image.png", docs.originalBytes)
        catalog = Catalog(app); engine = FolderRelinking(catalog); tree = DocumentsContract.buildTreeDocumentUri(info.authority, "root")
        val source = SourceEntity("pending", "content://${app.packageName}.pending-backup/tree/root", "Old pictures", "VISUAL", reachable = false, restoreUri = "content://old-device/tree/old-root")
        val root = AlbumEntity("old-root", "backup:root", "content://old-device/document/old-root", "Old pictures", "Old pictures", null)
        val origin = AlbumEntity("old-origin", "backup:origin", "content://old-device/document/old-origin", "Origin", "Old pictures / Origin", root.id, hidden = true, coverMediaId = "old-a")
        val other = AlbumEntity("old-other", "backup:other", "content://old-device/document/old-other", "Absent", "Old pictures / Absent", root.id)
        val a = MediaEntity("old-a", "backup:a", "content://old-device/document/a", origin.id, "image.png", "image/png", "IMAGE", docs.originalBytes.size.toLong(), 1, favorite = true, available = false)
        originals = listOf(a, a.copy(id = "old-b", documentKey = "backup:b", uri = "content://old-device/document/b", albumId = other.id),
            a.copy(id = "old-c", documentKey = "backup:c", uri = "content://old-device/document/c", name = "gone.png", albumId = other.id))
        catalog.dao.saveSource(source); catalog.dao.saveAlbums(listOf(root, origin, other))
        catalog.dao.saveAncestors(listOf(AlbumAncestor(root.id, root.id), AlbumAncestor(origin.id, root.id), AlbumAncestor(origin.id, origin.id), AlbumAncestor(other.id, root.id), AlbumAncestor(other.id, other.id)))
        catalog.dao.saveAlbumSources(listOf(root, origin, other).map { AlbumSource(it.id, source.id, 0) })
        catalog.dao.saveMedia(originals); catalog.dao.saveMembership(originals.map { MediaSource(it.id, source.id, 0) })
        catalog.dao.saveCollection(CollectionEntity("saved", "Saved order", "MEDIA", a.id))
        catalog.dao.saveCollectionItems(originals.mapIndexed { i, item -> CollectionItem("saved", item.id, i) })
        catalog.dao.saveArtworkChoice(ArtworkChoiceEntity(ArtworkOrders.folder(origin.id), a.id, null))
    }
    @After fun close() {
        if (!::catalog.isInitialized) return
        val job = catalog.scope.coroutineContext[Job]!!; job.cancel(); val end = System.nanoTime() + 10_000_000_000L
        while (!job.isCompleted && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        try { assertTrue(job.isCompleted) } finally { catalog.db.close() }
    }
    private suspend fun review() = engine.preview("pending", "old-root", tree.toString(), true)
    @Test fun previewHasExactAmbiguousAndMissingRowsWithoutPersistingGrantOrTouchingCatalog() = runBlocking {
        val before = catalog.readSnapshot(); val grants = app.contentResolver.persistedUriPermissions.toList(); val created = docs.creations
        val review = review(); val index = FolderRelinkIndex(review)
        assertEquals(listOf("old-root"), FolderRelinking.roots(before, "pending").map { it.id })
        assertEquals(1, index.candidates(review.references.single { it.id == "old-a" }).size)
        assertEquals(2, index.candidates(review.references.single { it.id == "old-b" }).size)
        assertTrue(index.candidates(review.references.single { it.id == "old-c" }).isEmpty())
        assertEquals(setOf("old-a"), index.uniqueChoices().keys)
        assertEquals(review, FolderRelinkReview.decode(review.encode())); assertEquals(before, catalog.readSnapshot())
        assertEquals(grants, app.contentResolver.persistedUriPermissions); assertEquals(created, docs.creations); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun explicitPartialBatchKeepsFavoritesOrderCoverHiddenAncestorsAndSavedPinsWithOnlyReadAccess() = runBlocking {
        val comparison = ComparisonStore(app, catalog.scope, catalog); comparison.update(ComparisonState().pin("old-a", listOf("old-a", "old-b"))!!); comparison.save("Saved pin")
        val review = review(); val choice = FolderRelinkIndex(review).uniqueChoices(); val created = docs.creations
        assertTrue(runCatching { engine.confirm(review, choice, false) }.isFailure); assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        val result = engine.confirm(review, choice, true); val id = result.getValue("old-a"); val data = catalog.readSnapshot()
        assertEquals(id, data.byId.getValue("old-a").id); assertTrue(data.byId.getValue(id).favorite)
        assertFalse(data.visible(data.byId.getValue(id))); assertTrue(data.albumsById.getValue(data.byId.getValue(id).albumId).hidden)
        assertEquals(listOf(id, "old-b", "old-c"), data.collectionItems.sortedBy { it.position }.map { it.mediaId })
        assertEquals(id, data.collections.single().coverMediaId); assertEquals(id, data.albumsById.getValue("old-origin").coverMediaId)
        assertEquals(id, data.artworkByTarget.getValue(ArtworkOrders.folder(data.byId.getValue(id).albumId)).coverMediaId)
        val unselected = data.media.single { it.name == "image.png" && it.id != id && it.available }; assertFalse(unselected.favorite)
        assertFalse(data.byId.getValue("old-b").available); assertFalse(data.byId.getValue("old-c").available)
        assertEquals(1, app.contentResolver.persistedUriPermissions.size); assertTrue(app.contentResolver.persistedUriPermissions.single().isReadPermission); assertFalse(app.contentResolver.persistedUriPermissions.single().isWritePermission)
        val saved = ComparisonStore.decode(catalog.dao.comparisonSession("Saved pin")!!.state)!!
        assertEquals(id, data.byId.getValue(saved.panes.first { it.mediaId != null }.mediaId!!).id)
        val exported = LibraryBackup.decode(LibraryBackup(catalog).export()); assertEquals(id, ComparisonStore.decode(exported.comparisons.single().state)!!.panes.first { it.mediaId != null }.mediaId)
        assertEquals(created, docs.creations); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun allSelectedFilesMergeTogetherAndRemainingPendingSourceIsDisabledOnlyWhenEmpty() = runBlocking {
        docs.nodes["image"]!!.name = "image.png"
        val extra = docs.nodes.values.single { it.parent == "destination" && it.file != null }
        catalog.dao.saveMedia(listOf(originals[1].copy(name = "image.png")))
        val review = review(); val index = FolderRelinkIndex(review)
        val b = review.references.single { it.id == "old-b" }; val chosenB = index.candidates(b).single { it.key.endsWith(":${extra.id}") }
        val result = engine.confirm(review, index.uniqueChoices() + (b.id to chosenB.key), true)
        assertEquals(2, result.size); assertEquals(2, catalog.dao.relinks().size)
        assertEquals(listOf(result.getValue("old-a"), result.getValue("old-b"), "old-c"), catalog.dao.collectionItems().sortedBy { it.position }.map { it.mediaId })
        assertTrue(catalog.dao.source("pending")!!.enabled)
        docs.add("destination", "gone.png", docs.originalBytes)
        val last = review(); val c = last.references.single { it.id == "old-c" }
        val target = FolderRelinkIndex(last).candidates(c).single()
        engine.confirm(last, mapOf(c.id to target.key), true)
        assertFalse(catalog.dao.source("pending")!!.enabled)
        assertEquals(3, catalog.dao.relinks().size)
    }
    @Test fun staleCatalogChangedTargetDuplicateAssignmentsAndRevocationDoNotProduceFalseSuccess() = runBlocking {
        val review = review(); val choice = FolderRelinkIndex(review).uniqueChoices()
        assertTrue(runCatching { engine.confirm(review, choice + ("old-b" to choice.getValue("old-a")), true) }.isFailure)
        docs.nodes["image"]!!.name = "changed.png"
        assertTrue(runCatching { engine.confirm(review, choice, true) }.isFailure)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertTrue(catalog.dao.relinks().isEmpty())
        docs.nodes["image"]!!.name = "image.png"; catalog.dao.favorite("old-a", false)
        assertTrue(runCatching { engine.confirm(review, choice, true) }.exceptionOrNull()!!.message!!.contains("anteprima"))
        val fresh = review(); docs.revoked = true
        assertTrue(runCatching { engine.confirm(fresh, FolderRelinkIndex(fresh).uniqueChoices(), true) }.isFailure)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertEquals(3, catalog.dao.media().size); assertTrue(catalog.dao.relinks().isEmpty())
    }
    @Test fun transactionFailureReleasesOnlyNewReadGrantAndPreservesExistingWritePermission() = runBlocking {
        val review = review(); val choice = FolderRelinkIndex(review).uniqueChoices(); val before = catalog.readSnapshot()
        catalog.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_folder_redirect BEFORE INSERT ON media_relinks BEGIN SELECT RAISE(ABORT,'fixture rollback'); END")
        assertTrue(runCatching { engine.confirm(review, choice, true) }.isFailure)
        assertEquals(before, catalog.readSnapshot()); assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(runCatching { engine.confirm(review, choice, true) }.isFailure)
        assertEquals(before, catalog.readSnapshot()); assertTrue(app.contentResolver.persistedUriPermissions.single().isWritePermission)
        assertEquals(3, catalog.dao.media().size); assertTrue(catalog.dao.relinks().isEmpty()); assertTrue(docs.deletions.isEmpty())
    }
    @Test fun cancellationGameConsentAndPrivateDraftsCannotAcquireAccessOrEscapeDirectory() = runBlocking {
        val before = catalog.readSnapshot()
        assertTrue(runCatching { engine.preview("pending", "old-root", tree.toString(), true, progress = { throw CancellationException("fixture") }) }.isFailure)
        assertEquals(before, catalog.readSnapshot()); assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        val game = FolderRelinking(catalog) { true }
        assertTrue(runCatching { game.preview("pending", "old-root", tree.toString(), true) }.isFailure)
        val allowed = game.preview("pending", "old-root", tree.toString(), true, true)
        assertTrue(runCatching { game.confirm(allowed, FolderRelinkIndex(allowed).uniqueChoices(), true) }.isFailure)
        val drafts = FolderRelinkDrafts(File(app.filesDir, "draft-fixture")); val id = drafts.create(allowed)
        assertEquals(allowed, drafts.read(id)); val selections = FolderRelinkIndex(allowed).uniqueChoices(); drafts.choices(id, selections); assertEquals(selections, drafts.choices(id))
        assertTrue(runCatching { drafts.read("../../secret") }.isFailure); drafts.discard(id); assertTrue(runCatching { drafts.read(id) }.isFailure)
        assertTrue(runCatching { FileDocuments(app).children(DocumentsContract.buildDocumentUriUsingTree(tree, "root"), 1) }.isFailure)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty()); assertEquals(before, catalog.readSnapshot())
    }
}
