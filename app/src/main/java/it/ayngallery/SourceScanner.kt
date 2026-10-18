package it.ayngallery

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

object DocumentVersions {
    fun changed(oldSize: Long, oldModified: Long, size: Long, modified: Long): Boolean =
        (oldSize > 0 && size > 0 && oldSize != size) ||
            (oldModified > 0 && modified > 0 && oldModified != modified)
}

class SourceScanner(private val catalog: Catalog) {
    private data class Entry(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)
    private data class Folder(val documentId: String, val name: String, val path: String,
        val parent: String?, val ancestors: List<String>)

    /** Only this directory's children; read missing audio tags once, without artwork copies or database writes. */
    suspend fun browse(source: SourceEntity, folder: AlbumEntity, known: CatalogSnapshot? = null,
                       ancestors: List<AlbumAncestor>? = null, reserved: Set<String> = emptySet()): CatalogSnapshot {
        require(source.restoreUri == null && source.enabled && source.reachable)
        val tree = Uri.parse(source.treeUri)
        require(catalog.context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { localized(R.string.text_folder_access_needs_restoring_b913f4, "Accesso alla cartella da ripristinare") }
        val id = DocumentsContract.getDocumentId(DocumentIdentity.inTree(Uri.parse(folder.documentUri), tree))
        val children = entries(tree, id, Int.MAX_VALUE)
        val existing = known ?: catalog.readStoredSnapshot()
        val byKey = existing.byDocumentKey
        val byUri = existing.byUri
        val byDevice = existing.byDeviceUri
        val byIdentity = existing.media.associateBy { DocumentIdentity.key(Uri.parse(it.uri)) }
        val reservedIdentities = reserved.map { DocumentIdentity.key(Uri.parse(it)) }.toSet()
        val albums = mutableListOf(folder.copy(systemExcluded = children.any { it.name == ".nomedia" }))
        val media = mutableListOf<MediaEntity>()
        val ancestry = (ancestors ?: existing.ancestry.filter { it.albumId == folder.id })
            .ifEmpty { listOf(AlbumAncestor(folder.id, folder.id)) }.toMutableList()
        if (!albums.first().systemExcluded) for (entry in children) {
            currentCoroutineContext().ensureActive()
            if (entry.name.startsWith(".ayngallery-")) continue
            val key = "${tree.authority}:${entry.id}"
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id).toString()
            if (uri in reserved || DocumentIdentity.key(Uri.parse(uri)) in reservedIdentities) continue
            if (entry.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val child = existing.albums.find { it.documentKey == key || it.documentUri == uri ||
                    DocumentIdentity.key(Uri.parse(it.documentUri)) == DocumentIdentity.key(Uri.parse(uri)) } ?: AlbumEntity(DirectAlbums.stableId(key), key, uri,
                    entry.name, "${folder.path} / ${entry.name}", folder.id)
                albums += child.copy(name = entry.name, path = "${folder.path} / ${entry.name}", documentUri = uri, parentId = folder.id)
                ancestry += ancestry.filter { it.albumId == folder.id }.map { AlbumAncestor(child.id, it.ancestorId) }
                ancestry += AlbumAncestor(child.id, child.id)
                continue
            }
            val kind = MediaNames.kind(entry.mime, entry.name) ?: continue
            var saved = byKey[key] ?: byUri[uri] ?: byIdentity[DocumentIdentity.key(Uri.parse(uri))]
            if (saved == null && byDevice.isNotEmpty() && kind != MediaKind.AUDIO) {
                saved = DeviceGallery.alias(catalog.context, Uri.parse(uri))?.toString()?.let(byDevice::get)
            }
            if (saved?.trashed == true) continue
            if (saved != null && DocumentVersions.changed(saved.size, saved.modified, entry.size, entry.modified)) saved = null
            val item = saved?.copy(documentKey = key, uri = uri, albumId = folder.id, name = entry.name, mime = entry.mime, kind = kind.name,
                size = entry.size, modified = entry.modified, available = true)
                ?: MediaEntity(DirectAlbums.stableId("$key|${entry.size}|${entry.modified}"), key, uri, folder.id, entry.name,
                    entry.mime, kind.name, entry.size, entry.modified)
            media += if (kind == MediaKind.AUDIO && !item.audioMetadataRead) {
                metadata(item, Uri.parse(uri), item.artworkUri, includeArtwork = false)
            } else item
        }
        return CatalogSnapshot(protectionMarkers = mapOf(folder.id to children.any { it.name == AlbumProtection.MARKER }), sources = listOf(source), albums = albums, media = media,
            membership = media.map { MediaSource(it.id, source.id, source.lastScan) }, ancestry = ancestry,
            albumMembership = albums.map { AlbumSource(it.id, source.id, source.lastScan) }, initialized = true)
    }

    suspend fun scan(source: SourceEntity, maxEntries: Int = Int.MAX_VALUE, maxFolders: Int = Int.MAX_VALUE) {
        require(source.restoreUri == null) { localized(R.string.text_explicitly_relink_the_backup_source_179521, "Ricollega esplicitamente la sorgente del backup") }
        val tree = Uri.parse(source.treeUri)
        require(tree.scheme == "content" && !tree.authority.isNullOrBlank())
        val dao = catalog.dao
        val albums = dao.albums().associateBy { it.documentKey }.toMutableMap()
        val existing = dao.media().associateBy { it.documentKey }.toMutableMap()
        val byIdentity = existing.values.associateBy { DocumentIdentity.key(Uri.parse(it.uri)) }.toMutableMap()
        val byDevice = existing.values.mapNotNull { item -> item.deviceUri?.let { it to item } }.toMap().toMutableMap()
        val albumsByIdentity = albums.values.associateBy { DocumentIdentity.key(Uri.parse(it.documentUri)) }.toMutableMap()
        val oldMembership = dao.membership()
        val pending = dao.operations().filter { !FileOperations.terminal(it.phase) }
        val reserved = pending.flatMap { listOfNotNull(it.temporaryUri, it.publishedUri) }
            .map { DocumentIdentity.key(Uri.parse(it)) }.toSet()
        val seen = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val epoch = System.currentTimeMillis()
        val queue = ArrayDeque<Folder>()
        queue.add(Folder(DocumentsContract.getTreeDocumentId(tree), source.title, source.title, null, emptyList()))
        var count = 0
        var walked = 0
        catalog.progress.value = ScanProgress(source.title, running = true)
        val pendingMedia = mutableListOf<MediaEntity>()
        val pendingLinks = mutableListOf<MediaSource>()

        suspend fun flush() {
            if (pendingMedia.isEmpty()) return
            catalog.db.withTransaction {
                dao.saveMedia(pendingMedia)
                dao.saveMembership(pendingLinks)
            }
            pendingMedia.clear(); pendingLinks.clear()
            catalog.progress.value = ScanProgress(source.title, count, true)
        }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = queue.removeFirst()
            val key = "${tree.authority}:${folder.documentId}"
            if (!visited.add(key)) continue
            require(visited.size <= maxFolders) { localized(R.string.text_too_many_folders_choose_a_subfolder_9faacd, "Troppe cartelle: scegli una sottocartella") }
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, folder.documentId)
            val children = entries(tree, folder.documentId, maxEntries - walked)
            walked += children.size
            val oldAlbum = albums[key] ?: albumsByIdentity[DocumentIdentity.key(uri)]
            val album = AlbumEntity(oldAlbum?.id ?: UUID.randomUUID().toString(), key, uri.toString(),
                folder.name, folder.path, folder.parent ?: oldAlbum?.parentId, oldAlbum?.hidden ?: false,
                oldAlbum?.coverMediaId, children.any { it.name == ".nomedia" })
            albums[key] = album
            albumsByIdentity[DocumentIdentity.key(uri)] = album
            val ancestors = (folder.ancestors + album.id).distinct()
            catalog.db.withTransaction {
                dao.saveAlbums(listOf(album))
                dao.saveAncestors(ancestors.map { AlbumAncestor(album.id, it) })
                dao.saveAlbumSources(listOf(AlbumSource(album.id, source.id, epoch)))
            }
            if (album.systemExcluded) continue
            val cover = children.filter { MediaNames.kind(it.mime, it.name) == MediaKind.IMAGE }
                .sortedWith(compareBy<Entry> { ArtworkOrders.coverRank(it.name) }
                    .thenBy { it.name }).firstOrNull()
            val coverUri = cover?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id).toString() }
            val folderAudio = mutableListOf<MediaEntity>()
            for (entry in children) {
                currentCoroutineContext().ensureActive()
                if (entry.name.startsWith(".ayngallery-") || DocumentIdentity.key(DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)) in reserved) continue
                if (entry.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    queue.add(Folder(entry.id, entry.name, "${folder.path} / ${entry.name}", album.id, ancestors))
                    continue
                }
                val kind = MediaNames.kind(entry.mime, entry.name) ?: continue
                val documentKey = "${tree.authority}:${entry.id}"
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                val deviceUri = DeviceGallery.alias(catalog.context, documentUri)?.toString()
                var previous = existing[documentKey] ?: byIdentity[DocumentIdentity.key(documentUri)] ?: deviceUri?.let(byDevice::get)
                if (previous?.trashed == true) continue
                if (previous != null && DocumentVersions.changed(previous.size, previous.modified, entry.size, entry.modified)) {
                    // A provider/path may now name a different file. Preserve old links as unavailable;
                    // never silently attach Favorites to a same-name replacement.
                    pendingMedia.add(previous.copy(documentKey = "unavailable:${previous.id}:$documentKey", available = false))
                    previous = null
                }
                if (previous?.documentKey?.startsWith("device:") == true && previous.albumId != album.id) {
                    // Adopting a SAF locator must retain exclusions from the original device album.
                    dao.saveAncestors(listOf(AlbumAncestor(album.id, previous.albumId)))
                }
                val item = previous?.copy(documentKey = documentKey, uri = documentUri.toString(), albumId = album.id, deviceUri = deviceUri ?: previous.deviceUri,
                    name = entry.name, mime = entry.mime, kind = kind.name, size = entry.size,
                    modified = entry.modified, available = true)
                    ?: MediaEntity(UUID.randomUUID().toString(), documentKey, documentUri.toString(), album.id,
                        entry.name, entry.mime, kind.name, entry.size, entry.modified, deviceUri = deviceUri)
                val missingArtwork = item.artworkUri?.let(Uri::parse)?.scheme == "file" && embeddedArtworkUri(catalog.context, item) == null
                val enriched = if (kind in setOf(MediaKind.AUDIO, MediaKind.VIDEO) && (previous == null || (kind == MediaKind.AUDIO && !item.audioMetadataRead) || (kind == MediaKind.VIDEO && item.duration == null) || missingArtwork))
                    metadata(item, documentUri, coverUri) else item
                existing[documentKey] = enriched
                byIdentity[DocumentIdentity.key(documentUri)] = enriched
                enriched.deviceUri?.let { byDevice[it] = enriched }
                pendingMedia.add(enriched)
                pendingLinks.add(MediaSource(enriched.id, source.id, epoch))
                seen.add(enriched.id)
                if (kind == MediaKind.AUDIO) folderAudio.add(enriched)
                count++
                if (pendingMedia.size >= 150) flush()
            }
            val folderCover = cover?.let { existing["${tree.authority}:${it.id}"] }
            folderAudio.forEach { audio ->
                val updated = audio.copy(artworkMediaId = folderCover?.id)
                existing[audio.documentKey] = updated
                pendingMedia.add(updated)
            }
            flush()
        }
        val otherSources = dao.sources().associateBy { it.id }
        val stale = oldMembership.filter { it.sourceId == source.id && it.mediaId !in seen }.map { it.mediaId }.toSet()
        val stillReachable = oldMembership.filter { it.sourceId != source.id && it.mediaId in stale &&
            otherSources[it.sourceId]?.let { s -> s.enabled && s.reachable && s.lastScan == it.scan } == true }.map { it.mediaId }.toSet()
        catalog.db.withTransaction {
            dao.missingAlbums(source.id, epoch)
            dao.saveMedia(dao.media().filter { it.id in stale && it.id !in stillReachable && !it.trashed }.map { it.copy(available = false) })
            dao.saveSource(source.copy(reachable = true, error = null, lastScan = epoch))
            // Repair aliases saved by older versions, retaining links and user metadata. No file writes.
            if (dao.operations().none { !FileOperations.terminal(it.phase) }) {
                val duplicates = dao.media().filter { it.available && !it.trashed && it.size > 0 && it.modified > 0 }
                    .groupBy { DocumentIdentity.key(Uri.parse(it.uri)) }
                    .filter { (key, items) -> key.startsWith("local:") && items.size > 1 }
                for (items in duplicates.values) {
                    val current = items.first()
                    for (old in items.drop(1).filter { it.kind == current.kind && it.size == current.size && it.modified == current.modified }) {
                        dao.saveMembership((dao.membershipFor(old.id) + dao.membershipFor(current.id))
                            .groupBy { it.sourceId }.values.map { links -> links.maxBy { it.scan }.copy(mediaId = current.id) })
                        MediaRelinking(catalog).mergeLocked(listOf(old to current.id))
                    }
                }
            }
        }
        catalog.progress.value = ScanProgress(source.title, count, false)
    }

    private suspend fun entries(tree: Uri, id: String, limit: Int): List<Entry> {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val signal = android.os.CancellationSignal()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val deadline = Runnable { android.util.Log.w("AynGallery", "Folder provider query exceeded 15 seconds"); signal.cancel() }
        handler.postDelayed(deadline, 15_000)
        try {
        val cursor = catalog.context.contentResolver.query(uri, columns, android.os.Bundle.EMPTY, signal)
            ?: throw java.io.IOException(localized(R.string.text_the_provider_did_not_return_folder_contents_8852fa, "Il provider non ha restituito il contenuto della cartella"))
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    require(size < limit) { localized(R.string.text_too_many_documents_choose_a_subfolder_42bf11, "Troppi documenti: scegli una sottocartella") }
                    val documentId = it.getString(0) ?: continue
                    add(Entry(documentId, it.getString(1) ?: localized(R.string.text_unnamed_034324, "Senza nome"), it.getString(2) ?: "application/octet-stream",
                        if (it.isNull(3)) 0 else it.getLong(3).coerceAtLeast(0), if (it.isNull(4)) 0 else it.getLong(4).coerceAtLeast(0)))
                }
            }
        }
        } finally { handler.removeCallbacks(deadline) }
    }

    internal fun metadata(item: MediaEntity, uri: Uri, fallbackCover: String?, includeArtwork: Boolean = true): MediaEntity {
        val retriever = MediaMetadataRetriever()
        return try {
                retriever.setDataSource(catalog.context, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (item.kind != MediaKind.AUDIO.name) return item.copy(duration = duration)
                val embedded = if (includeArtwork) retriever.embeddedPicture else null
                val artwork = if (embedded != null && embedded.size <= 8 * 1024 * 1024) {
                    CacheMaintenance.storeArtwork(File(catalog.context.cacheDir, "artwork"), item.id, embedded)
                } else fallbackCover
                item.copy(audioMetadataRead = true, duration = duration,
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: item.title,
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: item.artist,
                    musicAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() } ?: item.musicAlbum, artworkUri = artwork)
        } catch (_: RuntimeException) { item.copy(audioMetadataRead = item.kind == "AUDIO", artworkUri = fallbackCover) }
        catch (_: java.io.IOException) { item.copy(audioMetadataRead = item.kind == "AUDIO", artworkUri = fallbackCover) }
        finally { retriever.release() }
    }
}
