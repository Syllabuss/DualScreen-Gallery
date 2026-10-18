package it.ayngallery

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class MusicArtworkTest {
    private lateinit var catalog: Catalog
    private val musicTree = DocumentsContract.buildTreeDocumentUri("artfixture", "root")
    private val imagesTree = DocumentsContract.buildTreeDocumentUri("artfixture", "images")
    private lateinit var embedded: File
    @Before fun seed() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        catalog = Catalog(app)
        listOf(musicTree, imagesTree).forEach { app.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catalog.dao.saveSource(SourceEntity("music", musicTree.toString(), "Music", "MUSIC"))
        catalog.dao.saveSource(SourceEntity("images", imagesTree.toString(), "Images", "VISUAL"))
        catalog.dao.saveAlbums(listOf("music", "images").map { AlbumEntity(it, "artfixture:$it", DocumentsContract.buildDocumentUriUsingTree(musicTree, it).toString(), it, it, null) })
        catalog.dao.saveAlbumSources(listOf(AlbumSource("music", "music", 1), AlbumSource("images", "images", 1)))
        embedded = File(app.cacheDir, "artwork/song1.art").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val songs = (1..3).map { i -> MediaEntity("song$i", "artfixture:song$i", DocumentsContract.buildDocumentUriUsingTree(musicTree, "song$i").toString(), "music", "song$i.wav", "audio/wav", "AUDIO", 100, 1,
            artist = if (i == 2) "Another" else "Aster", musicAlbum = "Volume I", artworkUri = if (i == 1) embedded.toURI().toString() else null, artworkMediaId = "folder") }
        val pictures = listOf("folder", "manual", "other").map { id -> MediaEntity(id, "artfixture:$id", DocumentsContract.buildDocumentUriUsingTree(if (id == "folder") musicTree else imagesTree, id).toString(),
            if (id == "folder") "music" else "images", "$id.png", "image/png", "IMAGE", 100, 1) }
        catalog.dao.saveMedia(songs + pictures)
        catalog.dao.saveMembership((songs + pictures).map { MediaSource(it.id, if (it.albumId == "music") "music" else "images", 1) })
        Unit
    }
    @After fun release() { if (::catalog.isInitialized) { runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin() }; catalog.db.close() } }
    private suspend fun art(id: String): String? { val data = catalog.readSnapshot(); return catalog.artwork(data.byId.getValue(id), data) }
    @Test fun directTracksUsePrivateEmbeddedCacheWithoutStoredArtworkMetadata() = runBlocking {
        val data = catalog.readSnapshot()
        val track = data.byId.getValue("song1").copy(artworkUri = null)
        assertEquals(embedded.toURI().toString(), catalog.loadArtworkReference(track, data)?.first)
        catalog.hideAlbums(listOf("music"), true)
        assertNull(catalog.loadArtworkReference(track, catalog.readSnapshot()))
        assertTrue(embedded.isFile)
    }
    @Test fun prioritiesAndAlbumIdentityKeepOverridesSeparateAndOriginalMetadataUnchanged() = runBlocking {
        val data = catalog.readSnapshot(); val original = catalog.dao.media()
        val target = musicAlbumTarget(data.byId.getValue("song1"))
        catalog.setMusicArtwork(target, "manual", null)
        assertTrue(art("song1")!!.endsWith("/manual")); assertTrue(art("song3")!!.endsWith("/manual"))
        assertTrue(art("song2")!!.endsWith("/folder")) // Same album title, different artist.
        catalog.setMusicArtwork(target, "manual", "EMBEDDED,MANUAL,FOLDER")
        assertEquals(embedded.toURI().toString(), art("song1")); assertTrue(art("song3")!!.endsWith("/manual"))
        catalog.setMusicArtwork(ArtworkOrders.track("song1"), "other", "FOLDER,MANUAL,EMBEDDED")
        assertTrue(art("song1")!!.endsWith("/folder"))
        catalog.setMusicArtwork(ArtworkOrders.track("song1"), null, null)
        assertEquals(embedded.toURI().toString(), art("song1"))
        assertEquals(original, catalog.dao.media())
    }
    @Test fun missingHiddenRevokedOrMutatingPicturesFallbackWithoutDeletingChoices() = runBlocking {
        catalog.setMusicArtwork(ArtworkOrders.track("song1"), "manual", null)
        catalog.context.contentResolver.releasePersistableUriPermission(imagesTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(embedded.toURI().toString(), art("song1"))
        assertEquals("manual", catalog.dao.artworkChoices().single().coverMediaId)
        catalog.context.contentResolver.takePersistableUriPermission(imagesTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val data = catalog.readSnapshot()
        assertEquals(embedded.toURI().toString(), catalog.artwork(data.byId.getValue("song1"), data.copy(mutating = setOf("manual", "folder"))))
        catalog.hideAlbums(listOf("images"), true)
        assertEquals(embedded.toURI().toString(), art("song1"))
        catalog.hideAlbums(listOf("music"), true); assertNull(art("song1"))
        catalog.hideAlbums(listOf("music", "images"), false)
        assertTrue(art("song1")!!.endsWith("/manual"))
    }
    @Test fun embeddedCacheIsAppOwnedAndUnknownUrisCannotBypassSourceAuthorization() = runBlocking {
        var data = catalog.readSnapshot(); val song = data.byId.getValue("song1")
        assertFalse(data.visible(data.byId.getValue("folder"))) // A cover is not a Photo.
        for (bad in listOf("https://example.invalid/cover.png", File(catalog.context.filesDir, "private.bin").toURI().toString(), "file://elsewhere${embedded.absolutePath}")) {
            assertTrue(catalog.artwork(song.copy(artworkUri = bad), data)!!.endsWith("/folder"))
        }
        embedded.delete(); assertTrue(art("song1")!!.endsWith("/folder"))
        data = catalog.readSnapshot()
        val movedLegacy = data.copy(media = data.media.map { if (it.id == "folder") it.copy(albumId = "images") else it })
        assertNull(catalog.artwork(song.copy(artworkUri = null), movedLegacy)) // A stale automatic reference cannot borrow another folder's cover.
        assertNull(catalog.artwork(song.copy(artworkUri = "content://unknown/document/folder", artworkMediaId = null), data))
        assertTrue(catalog.artwork(song.copy(artworkUri = data.byId.getValue("folder").uri, artworkMediaId = null), data)!!.endsWith("/folder"))
    }
    @Test fun invalidTargetsKindsAndPrioritiesAreRejectedAndGlobalSettingsPersistOnlyOnSave() = runBlocking {
        val data = catalog.readSnapshot(); val folder = ArtworkOrders.folder("music")
        for (failure in listOf<suspend () -> Unit>(
            { catalog.setMusicArtwork("TRACK:missing", "manual", null) },
            { catalog.setMusicArtwork(folder, "song1", null) },
            { catalog.setMusicArtwork(folder, "manual", "MANUAL,MANUAL,FOLDER") })) assertTrue(runCatching { failure() }.isFailure)
        assertTrue(catalog.dao.artworkChoices().isEmpty())
        val store = PreferenceStore(catalog.context)
        val draft = store.state.value.copy(artworkOrder = "FOLDER,EMBEDDED,MANUAL")
        assertEquals(embedded.toURI().toString(), art("song1"))
        store.save(draft); assertTrue(art("song1")!!.endsWith("/folder"))
        assertEquals(draft, PreferenceStore(catalog.context).state.value)
        assertTrue(runCatching { store.save(draft.copy(artworkOrder = "invalid")) }.isFailure)
        assertEquals(draft, PreferenceStore(catalog.context).state.value)
        assertEquals(data.media, catalog.dao.media())
    }
}
