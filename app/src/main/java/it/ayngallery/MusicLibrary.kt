package it.ayngallery

import java.text.Normalizer
import java.util.Locale

data class MusicGroup(val id: String, val title: String, val origin: String, val tracks: List<MediaEntity>)
data class MusicGroupListing(val view: String = "TRACKS", val query: String = "", val groups: List<MusicGroup> = emptyList(), val initialized: Boolean = false)

private fun musicKey(value: String?) = Normalizer.normalize(value.orEmpty().trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
private fun musicIdentity(parts: List<String>) = parts.joinToString("") { "${it.length}:$it" }
fun musicAlbumTarget(item: MediaEntity) = "ALBUM:" + musicIdentity(listOf(item.albumId, musicKey(item.artist), musicKey(item.musicAlbum)))

/** Grouping uses local metadata only. Albums with the same title never merge across folders or artists. */
fun musicGroups(data: CatalogSnapshot, view: String, query: String = ""): List<MusicGroup> {
    if (view !in setOf("ARTISTS", "ALBUMS", "FOLDERS", "PLAYLISTS")) return emptyList()
    val audio = data.visibleMedia.filter { it.kind == "AUDIO" }
    val byFolder = if (view == "FOLDERS") audio.groupBy { it.albumId } else emptyMap()
    val groups = when (view) {
        "ARTISTS" -> audio.groupBy { musicKey(it.artist) }.map { (key, items) ->
            MusicGroup("artist:$key", items.first().artist?.trim()?.takeIf(String::isNotEmpty) ?: localized(R.string.text_unspecified_artist_53b8a6, "Artista non specificato"), localized(R.string.text_artist_7e6ef2, "Artista"), items)
        }
        "ALBUMS" -> audio.groupBy { musicAlbumTarget(it).removePrefix("ALBUM:") }.map { (key, items) ->
            val first = items.first()
            MusicGroup("album:$key", first.musicAlbum?.trim()?.takeIf(String::isNotEmpty) ?: localized(R.string.text_unspecified_album_f59acd, "Album non specificato"),
                "${first.artist?.trim()?.takeIf(String::isNotEmpty) ?: localized(R.string.text_unspecified_artist_53b8a6, "Artista non specificato")} · ${data.albumsById[first.albumId]?.path ?: localized(R.string.text_folder_unavailable_1f6e37, "Cartella non disponibile")}", items)
        }
        "FOLDERS" -> data.visibleAlbums(musical = true).filter { it.id !in data.rootAlbumIds || byFolder[it.id].orEmpty().isNotEmpty() }.map { album ->
            MusicGroup("folder:${album.id}", album.name, album.path, byFolder[album.id].orEmpty())
        }
        "PLAYLISTS" -> data.collections.filter { it.kind == "PLAYLIST" }.map { list ->
            val tracks = data.collectionItems.filter { it.collectionId == list.id }.sortedBy { it.position }
                .mapNotNull { ref -> data.byId[ref.mediaId]?.takeIf { it.kind == "AUDIO" && data.visible(it) } }
            MusicGroup("playlist:${list.id}", list.name, localized(R.string.text_local_playlist_091df5, "Playlist locale"), tracks)
        }
        else -> emptyList()
    }
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return groups.filter { group -> words.all { word -> group.title.contains(word, true) || group.origin.contains(word, true) ||
        group.tracks.any { listOfNotNull(it.name, it.title, it.artist, it.musicAlbum).any { value -> value.contains(word, true) } } } }
        .sortedWith(Comparator<MusicGroup> { a, b -> MediaNames.natural.compare(a.title, b.title) }.thenBy { it.id })
}
