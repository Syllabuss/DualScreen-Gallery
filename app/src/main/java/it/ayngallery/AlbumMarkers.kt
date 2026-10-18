package it.ayngallery

import android.net.Uri
import android.provider.DocumentsContract

/** A tiny removable sidecar, never a media file or a copy of the common PIN. */
internal class AlbumMarkers(private val catalog: Catalog) {
    suspend fun write(ids: Set<String>, enabled: Boolean): Set<String> {
        val data = catalog.readSnapshot()
        val documents = FileDocuments(catalog.context)
        val written = mutableSetOf<String>()
        for (id in ids) {
            val album = data.albumsById[id] ?: error(localized(R.string.text_album_unavailable_fe6944, "Album non disponibile"))
            val source = data.albumMembership.asSequence().filter { it.albumId == id && it.present }.mapNotNull { link -> data.sources.find { it.id == link.sourceId } }
                .firstOrNull { it.enabled && it.reachable && !it.singleDocument && it.id != DeviceGallery.SOURCE }
                ?: error(localized(R.string.text_album_source_unavailable_d29ab8, "Sorgente dell'album non disponibile"))
            val tree = Uri.parse(source.treeUri)
            val writable = catalog.context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
            if (enabled && !writable || !enabled && id !in catalog.protection.markerIds) continue
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(Uri.parse(album.documentUri)))
            val existing = documents.children(folder).filter { it.name == AlbumProtection.MARKER }
            if (!enabled) { existing.forEach { documents.delete(it.uri) }; continue }
            if (existing.isNotEmpty()) { written += id; continue }
            if (documents.stat(folder).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) continue
            val marker = documents.create(folder, "application/json", AlbumProtection.MARKER)
            try {
                require(documents.stat(marker).name == AlbumProtection.MARKER)
                documents.output(marker).use { it.write("{\"thorGalleryAlbumLock\":1}\n".toByteArray(Charsets.UTF_8)) }
                written += id
            } catch (failure: Exception) {
                runCatching { documents.delete(marker) }
                throw failure
            }
        }
        return written
    }
}
