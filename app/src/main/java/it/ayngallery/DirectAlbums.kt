package it.ayngallery

import android.provider.DocumentsContract
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Browse only chosen roots; Room retains the last complete listing for a quick next launch. */
class DirectAlbums(private val app: GalleryApp) {
    val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val catalog get() = app.catalog
    private val reader = Mutex()
    private val windows = mutableMapOf<Int, GalleryRoute>()
    private val dirtySources = mutableSetOf<String>()
    private var pending: Job? = null
    private var revision = 0L
    private var pendingRevision = -1L

    fun show(slot: Int, route: GalleryRoute) {
        windows[slot] = route
        request()
    }
    fun leave(slot: Int) { windows.remove(slot); if (windows.isEmpty()) pending?.cancel() }
    fun refreshDevice(force: Boolean) { catalog.scope.launch(Dispatchers.Main.immediate) {
        if (force) { dirtySources += catalog.snapshot.value.sources.filter { it.enabled && it.id != DeviceGallery.SOURCE }.map { it.id }; revision++ }
        request()
    } }
    fun refreshSource(id: String) { catalog.scope.launch(Dispatchers.Main.immediate) { if (id != DeviceGallery.SOURCE) { dirtySources += id; revision++ }; request() } }
    suspend fun refreshAndWait() {
        val scan = withContext(Dispatchers.Main.immediate) {
            dirtySources += catalog.snapshot.value.sources.filter { it.enabled && it.reachable && it.id != DeviceGallery.SOURCE && !it.singleDocument }.map { it.id }
            revision++
            request()
            pending
        }
        try { withTimeout(60_000) { scan?.join() } }
        catch (timeout: TimeoutCancellationException) { scan?.cancel(); android.util.Log.w("AynGallery", "Folder refresh exceeded 60 seconds"); throw timeout }
    }
    private fun request() {
        if (windows.isEmpty()) return
        if (pending?.isActive == true && pendingRevision == revision) return
        pending?.cancel()
        val routes = windows.values.toList()
        if (routes.none { it.tab in setOf("ALBUM", "PHOTO", "VIDEO", "MUSIC", "FAVORITE") }) return
        pendingRevision = revision
        pending = catalog.scope.launch(Dispatchers.Main.immediate) {
            delay(150)
            val dirty = dirtySources.toSet()
            val readSources = mutableSetOf<String>()
            try {
                val parts = withContext(Dispatchers.IO) { reader.withLock {
                    val stored = catalog.readStoredSnapshot()
                    val enabled = stored.sources.filter { it.enabled && it.reachable && it.restoreUri == null && it.id != DeviceGallery.SOURCE && !it.singleDocument }
                    if (enabled.isNotEmpty() && enabled.all { it.lastScan > 0 }) ready.value = true
                    val parts = catalog.browsed.value.filterKeys { key -> enabled.any { it.id == key } }.toMutableMap()
                    parts["roots"] = roots(stored)
                    for (source in enabled) {
                        if (source.id in parts && source.id !in dirty) continue
                        val root = parts.getValue("roots").albums.firstOrNull { album ->
                            parts.getValue("roots").albumMembership.any { it.albumId == album.id && it.sourceId == source.id }
                        } ?: continue
                        val markerRevision = catalog.protection.markerRevision
                        val fresh = browseRoot(source, root)
                        // Apply locks before a completed listing becomes observable in Room or on a panel.
                        catalog.protection.observeMarkers(fresh.protectionMarkers, markerRevision)
                        cacheRoot(source, fresh)?.let { parts[source.id] = it }
                        readSources += source.id
                    }
                    parts
                } }
                ensureActive()
                catalog.browsed.value = parts
                ready.value = true
                dirtySources.removeAll(readSources)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("AynGallery", localized(R.string.text_could_not_read_albums_585db4, "Lettura album non riuscita"), error)
                    withContext(Dispatchers.Main.immediate) { app.session.noticeSource(localized(R.string.text_album_unavailable_check_permission_or_reopen_the_70b1c5, "Album non disponibile. Controlla il permesso o riapri la cartella.")) }
                }
        }
    }
    /** Commit a completed listing atomically, keeping favorites, edits and operation journals intact. */
    private suspend fun cacheRoot(source: SourceEntity, fresh: CatalogSnapshot): CatalogSnapshot? = catalog.writer.withLock {
        catalog.db.withTransaction {
            val stored = catalog.readStoredSnapshot()
            val current = stored.sources.firstOrNull { it.id == source.id && it.treeUri == source.treeUri && it.role == source.role && it.enabled && it.reachable }
                ?: return@withTransaction null
            val protected = catalog.dao.operations().filter { !FileOperations.terminal(it.phase) }
                .flatMap { listOf(it.mediaId, it.resultMediaId) }.toSet()
            val valid = fresh.media.filter { item ->
                item.id !in protected && stored.byDocumentKey[item.documentKey]?.id !in protected &&
                    stored.byId[item.id]?.let { saved -> saved.uri == item.uri || saved.documentKey == item.documentKey || saved.documentKey.startsWith("device:") } != false
            }
            val ids = valid.map { it.id }.toSet()
            val oldIds = stored.membership.filter { it.sourceId == source.id }.map { it.mediaId }.toSet()
            val oldAlbums = stored.albumMembership.filter { it.sourceId == source.id && it.present }.map { it.albumId }.toSet()
            if (current.lastScan > 0 && ids == oldIds && valid.all { it == stored.byId[it.id] } &&
                fresh.albums.map { it.id }.toSet() == oldAlbums && fresh.albums.all { it == stored.albumsById[it.id] })
                return@withTransaction fresh.copy(sources = listOf(current))
            val replacements = valid.mapNotNull { item -> stored.byDocumentKey[item.documentKey]?.takeIf { it.id != item.id }
                ?.let { saved -> saved.copy(documentKey = "unavailable:${saved.id}:${saved.documentKey}", available = false) } }.associateBy { it.id }
            val epoch = maxOf(System.currentTimeMillis(), current.lastScan + 1)
            val refreshedSource = current.copy(lastScan = epoch, error = null)
            val part = fresh.copy(sources = listOf(refreshedSource), media = valid,
                membership = fresh.membership.filter { it.mediaId in ids }.map { it.copy(scan = epoch) },
                albumMembership = fresh.albumMembership.map { it.copy(scan = epoch) })
            val merged = merge(stored.copy(media = stored.media.map { replacements[it.id] ?: it }), mapOf(source.id to part))
            val cachedMedia = valid.mapNotNull { merged.byId[it.id] }
            val cachedAlbums = part.albums.mapNotNull { merged.albumsById[it.id] }
            val stillLinked = stored.membership.filter { it.sourceId != source.id }.map { it.mediaId }.toSet()
            val missing = stored.membership.filter { it.sourceId == source.id && it.mediaId !in ids && it.mediaId !in protected && it.mediaId !in stillLinked }
                .mapNotNull { merged.byId[it.mediaId]?.takeUnless { it.trashed }?.copy(available = false) }
            catalog.dao.saveAlbums(cachedAlbums)
            catalog.dao.saveMedia((replacements.values + missing + cachedMedia).distinctBy { it.id })
            catalog.dao.clearSourceMembership(source.id)
            val protectedLinks = stored.membership.filter { it.sourceId == source.id && it.mediaId in protected }
            catalog.dao.saveMembership(part.membership + protectedLinks)
            catalog.dao.saveAncestors(part.ancestry)
            catalog.dao.saveAlbumSources(part.albumMembership)
            catalog.dao.missingAlbums(source.id, epoch)
            catalog.dao.saveSource(refreshedSource)
            part.copy(media = (cachedMedia + protectedLinks.mapNotNull { stored.byId[it.mediaId] }).distinctBy { it.id },
                albums = cachedAlbums, membership = part.membership + protectedLinks)
        }
    }
    /** Read only explicitly chosen trees; missing audio tags are cached with the complete listing. */
    private suspend fun browseRoot(source: SourceEntity, root: AlbumEntity): CatalogSnapshot {
        val queue = ArrayDeque<AlbumEntity>(); queue.add(root)
        val visited = mutableSetOf<String>()
        val pages = mutableListOf<CatalogSnapshot>()
        val known = catalog.readStoredSnapshot()
        val reserved = catalog.dao.operations().filter { !FileOperations.terminal(it.phase) }
            .flatMap { listOfNotNull(it.temporaryUri, it.publishedUri) }.toSet()
        val ancestry = mutableMapOf(root.id to listOf(AlbumAncestor(root.id, root.id)))
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = queue.removeFirst()
            if (!visited.add(folder.documentKey)) continue
            val page = SourceScanner(catalog).browse(source, folder, known, ancestry[folder.id], reserved)
            pages += page
            page.ancestry.groupBy { it.albumId }.forEach { (id, links) -> ancestry[id] = links }
            page.albums.filter { it.parentId == folder.id && !it.systemExcluded }.forEach(queue::add)
        }
        return CatalogSnapshot(sources = listOf(source), albums = pages.flatMap { it.albums }.distinctBy { it.id },
            media = pages.flatMap { it.media }.distinctBy { it.id }, membership = pages.flatMap { it.membership }.distinct(),
            albumMembership = pages.flatMap { it.albumMembership }.distinct(), ancestry = pages.flatMap { it.ancestry }.distinct(), initialized = true,
            protectionMarkers = pages.flatMap { it.protectionMarkers.entries }.associate { it.toPair() })
    }
    /** Explicit selection only: read the selected subtree without starting an index or writing metadata. */
    suspend fun albumContents(ids: Set<String>): List<MediaEntity> = reader.withLock {
        val current = catalog.readSnapshot()
        val parts = catalog.browsed.value.toMutableMap()
        val selected = mutableSetOf<String>()
        val queue = ArrayDeque<AlbumEntity>()
        ids.forEach { queue.add(current.albumsById[it] ?: error(localized(R.string.text_album_unavailable_fe6944, "Album non disponibile"))) }
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val album = queue.removeFirst()
            require(!current.albumLocked(album.id)) { localized(R.string.protected_album, "Album protetto") }
            if (!selected.add(album.id)) continue
            val source = current.albumMembership.filter { it.albumId == album.id && it.present }
                .mapNotNull { link -> current.sources.find { it.id == link.sourceId } }.firstOrNull()
                ?: parts.values.flatMap { it.sources }.firstOrNull { candidate -> parts.values.any { part -> part.albumMembership.any { it.albumId == album.id && it.sourceId == candidate.id } } }
                ?: error(localized(R.string.text_album_source_unavailable_d29ab8, "Sorgente dell'album non disponibile"))
            if (source.id != DeviceGallery.SOURCE) {
                val markerRevision = catalog.protection.markerRevision
                val fresh = SourceScanner(catalog).browse(source, album)
                catalog.protection.observeMarkers(fresh.protectionMarkers, markerRevision)
                val protection = catalog.protection.state.value
                val access = if (current.protectedAlbumIds == protection.protected && current.unlockedAlbumIds == protection.unlocked) current
                    else current.copy(protectedAlbumIds = protection.protected, unlockedAlbumIds = protection.unlocked)
                require(!access.albumLocked(album.id)) { localized(R.string.protected_album, "Album protetto") }
                parts[album.id] = fresh
                fresh.albums.filter { it.parentId == album.id && !it.hidden && !it.systemExcluded }.forEach(queue::add)
            } else current.albums.filter { it.parentId == album.id && it.id !in current.excludedAlbums }.forEach(queue::add)
        }
        catalog.browsed.value = parts
        val fresh = catalog.readSnapshot()
        fresh.visibleMedia.filter { it.albumId in selected && it.available }.distinctBy { it.id }
    }

    private fun roots(stored: CatalogSnapshot): CatalogSnapshot {
        val albums = mutableListOf<AlbumEntity>(); val links = mutableListOf<AlbumSource>()
        for (source in stored.sources.filter { it.enabled && it.restoreUri == null && it.id != DeviceGallery.SOURCE && !it.singleDocument }) {
            val tree = Uri.parse(source.treeUri)
            if (!DocumentsContract.isTreeUri(tree)) continue
            val key = "${tree.authority}:${DocumentsContract.getTreeDocumentId(tree)}"
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()
            val album = stored.albums.find { it.documentKey == key || (it.documentUri == rootUri && it.parentId == null) } ?: AlbumEntity(stableId(key), key,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString(), source.title, source.title, null)
            albums += album; links += AlbumSource(album.id, source.id, source.lastScan)
        }
        return CatalogSnapshot(albums = albums, albumMembership = links, ancestry = albums.map { AlbumAncestor(it.id, it.id) }, initialized = true)
    }
    companion object {
        internal fun stableId(key: String) = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        internal fun merge(stored: CatalogSnapshot, parts: Map<String, CatalogSnapshot>): CatalogSnapshot {
            if (parts.isEmpty()) return stored
            val fresh = parts.values.flatMap { it.media }.associateBy { it.id }
            val device = parts[DeviceGallery.SOURCE]
            val readFolders = parts.values.flatMap { it.albums }.map { it.id }.toSet()
            val old = stored.media.map { item ->
                if (item.id !in fresh && item.albumId in readFolders && !item.trashed) item.copy(available = false) else item
            }.associateBy { it.id }.toMutableMap()
            for ((id, item) in fresh) {
                val saved = stored.byId[id]
                old[id] = if (saved != null && (saved.modified > item.modified ||
                    (saved.uri != item.uri && saved.documentKey != item.documentKey && !saved.documentKey.startsWith("device:")))) saved
                else if (saved == null) item else item.copy(favorite = saved.favorite, favoriteAddedAt = saved.favoriteAddedAt, videoPosition = saved.videoPosition, videoPositionAt = saved.videoPositionAt,
                    trashed = saved.trashed, added = saved.added)
            }
            val readSources = parts.values.flatMap { it.sources }.map { it.id }.toSet()
            val membership = stored.membership.filter { it.sourceId !in readSources } +
                parts.values.flatMap { it.membership }
            return stored.copy(
                sources = (parts.values.flatMap { it.sources } + stored.sources).associateBy { it.id }.values.map {
                    if (it.id == DeviceGallery.SOURCE) it.copy(enabled = false, reachable = false) else it
                },
                albums = (stored.albums + parts.values.flatMap { it.albums }.map { album ->
                    stored.albumsById[album.id]?.let { album.copy(hidden = it.hidden, coverMediaId = it.coverMediaId) } ?: album
                }).associateBy { it.id }.values.toList(),
                media = old.values.toList(), membership = membership.distinctBy { it.mediaId to it.sourceId },
                ancestry = (stored.ancestry + parts.values.flatMap { it.ancestry }).distinct(),
                albumMembership = (stored.albumMembership.filter { device == null || it.sourceId != DeviceGallery.SOURCE } +
                    parts.values.flatMap { it.albumMembership }).distinctBy { it.albumId to it.sourceId },
                initialized = stored.initialized)
        }
    }
}
