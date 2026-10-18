package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class MusicLibraryTest {
    private val source = SourceEntity("music", "content://test/tree/root", "Music", "MUSIC")
    private val folders = listOf(AlbumEntity("a", "a", "a", "Music", "Root / A", null), AlbumEntity("b", "b", "b", "Music", "Root / B", null))
    private fun song(id: String, folder: String = "a", artist: String? = "Aster", album: String? = "Volume I") =
        MediaEntity(id, id, id, folder, "$id.wav", "audio/wav", "AUDIO", 100, 1, title = id, artist = artist, musicAlbum = album)
    private fun data(items: List<MediaEntity>) = CatalogSnapshot(listOf(source), folders, items, items.map { MediaSource(it.id, source.id, 1) },
        albumMembership = folders.map { AlbumSource(it.id, source.id, 1) }, initialized = true)
    @Test fun sameAlbumTitleStaysSeparateAcrossArtistsAndFoldersWhileArtistGroupingIsCanonical() {
        val items = listOf(song("one"), song("two", artist = " aster "), song("three", folder = "b"), song("four", artist = "Vega"))
        val snapshot = data(items)
        val albums = musicGroups(snapshot, "ALBUMS")
        assertEquals(3, albums.size); assertEquals(2, albums.single { it.tracks.size == 2 }.tracks.size)
        val artists = musicGroups(snapshot, "ARTISTS")
        assertEquals(2, artists.size); assertEquals(3, artists.single { it.title == "Aster" }.tracks.size)
        assertEquals(albums.map { it.id }, musicGroups(snapshot.copy(media = items.reversed()), "ALBUMS").map { it.id })
    }
    @Test fun missingMetadataHasExplicitFallbackAndFoldersWithNoTracksRemainBrowsable() {
        val snapshot = data(listOf(song("one", artist = null, album = null)))
        assertEquals("Artista non specificato", musicGroups(snapshot, "ARTISTS").single().title)
        assertEquals("Album non specificato", musicGroups(snapshot, "ALBUMS").single().title)
        assertEquals(2, musicGroups(snapshot, "FOLDERS").size)
        assertTrue(musicGroups(snapshot, "FOLDERS").single { it.id == "folder:b" }.tracks.isEmpty())
    }
    @Test fun hiddenContentAndVisualOnlySourcesNeverEnterMusicGroups() {
        val snapshot = data(listOf(song("one"), song("two", folder = "b")))
        val hidden = snapshot.copy(albums = folders.map { it.copy(hidden = it.id == "a") })
        assertEquals(listOf("two"), musicGroups(hidden, "ARTISTS").single().tracks.map { it.id })
        assertTrue(musicGroups(snapshot.copy(sources = listOf(source.copy(role = "VISUAL"))), "ARTISTS").isEmpty())
    }
    @Test fun playlistOrderIsPersistentAndSearchingDoesNotRewriteItsReferences() {
        val refs = listOf(CollectionItem("list", "two", 0), CollectionItem("list", "one", 1))
        val snapshot = data(listOf(song("one"), song("two"))).copy(collections = listOf(CollectionEntity("list", "Local set", "PLAYLIST")), collectionItems = refs)
        assertEquals(listOf("two", "one"), musicGroups(snapshot, "PLAYLISTS", "aster").single().tracks.map { it.id })
        assertEquals(refs, snapshot.collectionItems)
        assertTrue(musicGroups(snapshot, "PLAYLISTS", "not present").isEmpty())
    }
}
