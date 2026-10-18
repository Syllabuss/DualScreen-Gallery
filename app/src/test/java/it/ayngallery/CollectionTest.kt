package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class CollectionTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.collections", "root")
    private val coverTree = DocumentsContract.buildTreeDocumentUri("ayngallery.collections", "child")
    @Before fun seed() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.collections"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.collections", provider)
        listOf(tree, coverTree).forEach { app.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catalog = Catalog(app)
        catalog.dao.saveSource(SourceEntity("mixed", tree.toString(), "Mixed", "MIXED"))
        catalog.dao.saveSource(SourceEntity("covers", coverTree.toString(), "Music only", "MUSIC"))
        catalog.dao.saveAlbums(listOf("first", "second").map { AlbumEntity(it, it, "content://ayngallery.collections/tree/root/document/$it", it, it, null) })
        val media = listOf("one" to "AUDIO", "two" to "AUDIO", "picture" to "IMAGE", "cover" to "IMAGE").map { (id, kind) ->
            MediaEntity(id, id, "content://ayngallery.collections/tree/root/document/$id", if (id == "two") "second" else "first", "$id.dat", if (kind == "AUDIO") "audio/wav" else "image/png", kind, 100, 1, favorite = id == "one")
        }
        catalog.dao.saveMedia(media)
        catalog.dao.saveMembership(media.map { MediaSource(it.id, if (it.id == "cover") "covers" else "mixed", 1) })
        Unit
    }
    @After fun release() { if (::catalog.isInitialized) { runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin() }; catalog.db.close() } }
    private suspend fun ids(id: String) = catalog.dao.collectionItems().filter { it.collectionId == id }.map { it.mediaId }
    @Test fun concurrentAppendsPreserveReferencesAndDuplicatesAreNotAdded() = runBlocking {
        val id = catalog.collection("  My picks  ", "MEDIA", listOf("one", "one"))
        coroutineScope {
            launch(Dispatchers.Default) { catalog.addCollectionItems(id, listOf("two", "one")) }
            launch(Dispatchers.Default) { catalog.addCollectionItems(id, listOf("picture")) }
        }
        assertEquals("one", ids(id).first()); assertEquals(setOf("one", "two", "picture"), ids(id).toSet()); assertEquals(3, ids(id).size)
        catalog.hideAlbums(listOf("second"), true)
        catalog.renameCollection(id, "Renamed")
        catalog.addCollectionItems(id, listOf("picture"))
        assertTrue("Hidden references survive edits", "two" in ids(id))
        assertEquals("Renamed", catalog.dao.collections().single().name)
        assertTrue(catalog.dao.media("one")!!.favorite); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun reorderRemoveAndDeleteNeverMutateOriginalsOrOtherCollections() = runBlocking {
        val id = catalog.collection("Mixed", "MEDIA", listOf("one", "two", "picture"))
        val other = catalog.collection("Other", "PLAYLIST", listOf("one"))
        val before = catalog.dao.media(); val membership = catalog.dao.membership()
        catalog.hideAlbums(listOf("second"), true)
        catalog.moveCollectionItem(id, "picture", "one")
        assertEquals(listOf("picture", "one", "two"), ids(id))
        catalog.moveCollectionItem(id, "picture", null)
        assertEquals(listOf("one", "two", "picture"), ids(id))
        val links = catalog.dao.collectionItems()
        assertTrue(runCatching { catalog.moveCollectionItem(id, "one", "missing") }.isFailure)
        assertEquals(links, catalog.dao.collectionItems())
        catalog.removeCollectionItem(id, "picture")
        assertEquals(listOf("one", "two"), ids(id))
        catalog.deleteCollection(id)
        assertEquals(listOf("one"), ids(other)); assertTrue(ids(id).isEmpty())
        assertEquals(before, catalog.dao.media()); assertEquals(membership, catalog.dao.membership())
        assertTrue(provider.deletions.isEmpty()); assertEquals(0, provider.creations)
    }
    @Test fun invalidNamesTypesTargetsAndChangedVisibilityCannotOverwriteValidState() = runBlocking {
        val id = catalog.collection("Songs", "PLAYLIST", listOf("one"))
        for (name in listOf("", "   ", "bad\u0000name", "n".repeat(121))) assertTrue(runCatching { catalog.renameCollection(id, name) }.isFailure)
        assertTrue(runCatching { catalog.collection("Unknown", "invalid", emptyList()) }.isFailure)
        assertTrue(runCatching { catalog.collection("Broken", "MEDIA", listOf("missing")) }.isFailure)
        assertTrue(runCatching { catalog.addCollectionItems(id, listOf("picture")) }.isFailure)
        catalog.hideAlbums(listOf("second"), true)
        assertTrue(runCatching { catalog.addCollectionItems(id, listOf("one", "two")) }.isFailure)
        assertEquals(listOf("one"), ids(id)); assertEquals("Songs", catalog.dao.collections().single().name)
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun manualMusicFolderCoverRemainsLocalAndRespectsPermissionsAndVisibility() = runBlocking {
        val id = catalog.collection("Songs", "PLAYLIST", listOf("one", "two"))
        catalog.setCollectionCover(id, "cover")
        var data = catalog.readSnapshot(); var collection = data.collections.single()
        assertFalse(data.visible(data.byId.getValue("cover")))
        assertEquals("cover", data.collectionCover(collection)!!.id)
        assertTrue(runCatching { catalog.setCollectionCover(id, "one") }.isFailure)
        catalog.context.contentResolver.releasePersistableUriPermission(coverTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        data = catalog.readSnapshot(); collection = data.collections.single()
        assertEquals("one", data.collectionCover(collection)!!.id)
        assertEquals("cover", collection.coverMediaId)
        catalog.context.contentResolver.takePersistableUriPermission(coverTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        data = catalog.readSnapshot(); assertEquals("cover", data.collectionCover(data.collections.single())!!.id)
        catalog.setCollectionCover(id, null)
        assertNull(catalog.dao.collections().single().coverMediaId)
        assertEquals(listOf("one", "two"), ids(id)); assertTrue(provider.deletions.isEmpty())
    }
}
