package it.ayngallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class ScanProgress(val source: String? = null, val count: Int = 0, val running: Boolean = false, val error: String? = null)

class Catalog(val context: Context) {
    val protection = AlbumProtection(context) { ids, enabled -> AlbumMarkers(this).write(ids, enabled) }
    val db = Room.databaseBuilder(context, CatalogDatabase::class.java, "catalog.db")
        .addMigrations(CatalogDatabase.MIGRATION_1_2, CatalogDatabase.MIGRATION_2_3, CatalogDatabase.MIGRATION_3_4, CatalogDatabase.MIGRATION_4_5, CatalogDatabase.MIGRATION_5_6, CatalogDatabase.MIGRATION_6_7, CatalogDatabase.MIGRATION_7_8, CatalogDatabase.MIGRATION_8_9, CatalogDatabase.MIGRATION_9_10, CatalogDatabase.comparisonMigration(context), CatalogDatabase.MIGRATION_11_12, CatalogDatabase.MIGRATION_12_13, CatalogDatabase.MIGRATION_13_14).build()
    val dao = db.catalog()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // serialize writers in the first beta; split by document if measured contention demands it.
    val writer = Mutex()
    val progress = MutableStateFlow(ScanProgress())
    val cacheRevision = MutableStateFlow(0L)
    internal val browsed = MutableStateFlow<Map<String, CatalogSnapshot>>(emptyMap())
    // A combine of independently observed tables can expose half a committed transaction. Read one complete snapshot per invalidation.
    val snapshot: StateFlow<CatalogSnapshot> = dao.observeCatalogChanges(androidx.sqlite.db.SimpleSQLiteQuery("SELECT 1"))
        .combine(dao.observeMutatingMedia().distinctUntilChanged()) { _, _ -> Unit }
        .conflate().map { readStoredSnapshot() }
        .combine(browsed) { stored, current -> DirectAlbums.merge(stored, current) }
        .combine(protection.state) { data, state -> if (data.protectedAlbumIds == state.protected && data.unlockedAlbumIds == state.unlocked) data else data.copy(protectedAlbumIds = state.protected, unlockedAlbumIds = state.unlocked) }
        .distinctUntilChanged().flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, CatalogSnapshot())

    private fun readableTrees(): Set<String> = context.contentResolver.persistedUriPermissions.toTypedArray().filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    private fun accessible(source: SourceEntity, trees: Set<String>): SourceEntity {
        if (source.id == DeviceGallery.SOURCE) return source.copy(enabled = false, reachable = false)
        if (source.restoreUri != null) return source.copy(reachable = false, error = localized(R.string.text_backup_source_needs_relinking_fb5836, "Backup: sorgente da ricollegare"))
        val allowed = if (source.id == DeviceGallery.SOURCE) DeviceGallery.hasAccess(context) else source.treeUri in trees
        return if (allowed) source else source.copy(reachable = false, error = localized(R.string.text_access_needs_restoring_6a31e2, "Accesso da ripristinare"))
    }

    suspend fun readSnapshot(): CatalogSnapshot = DirectAlbums.merge(readStoredSnapshot(), browsed.value)
    internal suspend fun readStoredSnapshot(): CatalogSnapshot {
        val readable = readableTrees()
        return db.withTransaction {
            CatalogSnapshot(dao.sources().map { accessible(it, readable) }, dao.albums(), dao.media(), dao.membership(), dao.ancestors(), dao.collections(), dao.collectionItems(), initialized = true,
                albumMembership = dao.albumSources(), mutating = dao.mutatingMedia().toSet(), artworkChoices = dao.artworkChoices(), mediaLinks = dao.relinks(),
                protectedAlbumIds = protection.state.value.protected, unlockedAlbumIds = protection.state.value.unlocked)
        }
    }
    /** Fresh access for one stream; reuse visibility policy without rereading the whole catalog per file. */
    suspend fun readMediaSnapshot(id: String): CatalogSnapshot {
        val readable = readableTrees()
        return db.withTransaction {
            var canonical = id; val seen = hashSetOf<String>()
            while (dao.media(canonical) == null) {
                require(seen.add(canonical) && seen.size <= 256) { localized(R.string.text_circular_reference_6918cc, "Riferimento circolare") }
                canonical = dao.relink(canonical) ?: break
            }
            val item = dao.media(canonical)
            // Persisted documents are authoritative here: the observed flow can lag a committed write.
            if (item == null) {
                // Unpersisted directory results still use their overlay, with fresh access/visibility from Room.
                val current = readSnapshot()
                val fresh = current.byId[id] ?: return@withTransaction CatalogSnapshot(initialized = true)
                val allowed = current.sourceFor(fresh)?.id != DeviceGallery.SOURCE ||
                    fresh.deviceUri?.let { DeviceGallery.visibleNow(context, Uri.parse(it), fresh.kind) } != false
                return@withTransaction current.copy(media = listOf(fresh.copy(available = fresh.available && allowed)))
            }
            val deviceReadable = item?.deviceUri?.let { DeviceGallery.visibleNow(context, Uri.parse(it), item.kind) } == true
            CatalogSnapshot(sources = dao.sourcesFor(canonical).map { source -> accessible(source, readable).let {
                if (it.id == DeviceGallery.SOURCE && !deviceReadable) it.copy(reachable = false) else it
            } },
                albums = item?.let { dao.visibilityAlbumsFor(it.albumId) }.orEmpty(), media = listOfNotNull(item),
                membership = dao.membershipFor(canonical), ancestry = item?.let { dao.ancestryFor(it.albumId) }.orEmpty(),
                mutating = if (dao.mutating(canonical)) setOf(canonical) else emptySet(), initialized = true,
                mediaLinks = if (canonical != id && item != null) listOf(MediaRelink(id, canonical)) else emptyList(),
                protectedAlbumIds = protection.state.value.protected, unlockedAlbumIds = protection.state.value.unlocked)
        }
    }

    /** Probe roots, never walk folders on resume. Losing access does not remove media, memberships or Favorites. */
    suspend fun refreshAccess() = writer.withLock {
        val readable = readableTrees()
        for (source in dao.sources().filter { it.enabled }) {
            currentCoroutineContext().ensureActive()
            if (source.restoreUri != null) continue
            if (source.id == DeviceGallery.SOURCE) {
                val reachable = DeviceGallery.hasAccess(context)
                val error = if (reachable) null else localized(R.string.text_allow_photos_and_videos_0e8d89, "Consenti foto e video")
                if (source.reachable != reachable || source.error != error)
                    dao.saveSource(source.copy(reachable = reachable, error = error))
                continue
            }
            val error = if (source.treeUri !in readable) localized(R.string.text_access_needs_restoring_6a31e2, "Accesso da ripristinare") else try {
                val tree = Uri.parse(source.treeUri)
                if (source.singleDocument) IncomingMedia.stat(context, tree)
                else {
                    val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val info = FileDocuments(context).stat(root)
                    require(info.mime == DocumentsContract.Document.MIME_TYPE_DIR)
                }
                null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { localized(R.string.text_access_needs_restoring_6a31e2, "Accesso da ripristinare") }
            catch (_: Exception) { localized(R.string.text_source_disconnected_or_reading_failed_2c1ac4, "Sorgente scollegata o lettura non riuscita") }
            if (source.reachable != (error == null) || source.error != error)
                dao.saveSource(source.copy(reachable = error == null, error = error))
        }
    }

    suspend fun addSource(uri: Uri, role: SourceRole, grantFlags: Int) = writer.withLock {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { localized(R.string.text_choose_a_folder_56d054, "Scegli una cartella") }
        val flags = grantFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { localized(R.string.text_the_folder_does_not_grant_read_access_c5c5f5, "La cartella non concede accesso in lettura.") }
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val existing = dao.sources().firstOrNull { DocumentIdentity.key(Uri.parse(it.treeUri), true) == DocumentIdentity.key(uri, true) }
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        var title = localized(R.string.text_selected_folder_aae82a, "Cartella selezionata")
        context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), android.os.Bundle.EMPTY, null)?.use {
            if (it.moveToFirst()) title = it.getString(0) ?: title
        }
        val source = existing?.copy(title = title, role = if (existing.role == role.name) role.name else SourceRole.MIXED.name,
            treeUri = uri.toString(), enabled = true, reachable = true, error = null)
            ?: SourceEntity(UUID.randomUUID().toString(), uri.toString(), title, role.name)
        dao.saveSource(source)
        schedule(source.id)
    }

    suspend fun authorizeDestination(uri: Uri, role: SourceRole, grantFlags: Int): String = writer.withLock {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { localized(R.string.text_choose_a_folder_56d054, "Scegli una cartella") }
        val flags = grantFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) { localized(R.string.text_the_destination_must_grant_read_and_write_access_64dff6, "La destinazione deve concedere lettura e scrittura") }
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val existing = dao.sources().firstOrNull { DocumentIdentity.key(Uri.parse(it.treeUri), true) == DocumentIdentity.key(uri, true) }
        require(existing?.enabled != false) { localized(R.string.text_this_source_is_disabled_enable_it_in_sources_42528d, "Questa sorgente è disattivata: abilitala dalla schermata Sorgenti") }
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val documents = FileDocuments(context)
        val info = documents.stat(root)
        require(info.mime == DocumentsContract.Document.MIME_TYPE_DIR && info.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { localized(R.string.text_folder_is_not_writable_348e84, "Cartella non scrivibile") }
        val key = "${uri.authority}:${DocumentsContract.getDocumentId(root)}"
        val known = dao.albums().firstOrNull { it.documentKey == key || DocumentIdentity.key(Uri.parse(it.documentUri)) == DocumentIdentity.key(root) }
        val source = existing?.copy(treeUri = uri.toString(), reachable = true, error = null)
            ?: SourceEntity(UUID.randomUUID().toString(), uri.toString(), info.name, role.name)
        val album = known ?: AlbumEntity(UUID.randomUUID().toString(), key, root.toString(), info.name, info.name, null,
            systemExcluded = documents.children(root).any { it.name == ".nomedia" })
        db.withTransaction {
            dao.saveSource(source); dao.saveAlbums(listOf(album))
            dao.saveAncestors(listOf(AlbumAncestor(album.id, album.id)))
            dao.saveAlbumSources(listOf(AlbumSource(album.id, source.id, source.lastScan)))
        }
        if (existing == null) schedule(source.id)
        album.id
    }

    fun schedule(sourceId: String) {
        (context as? GalleryApp)?.albums?.refreshSource(sourceId)
    }

    fun rescan() { snapshot.value.sources.filter { it.enabled && it.restoreUri == null }.forEach { schedule(it.id) } }

    internal suspend fun rememberAlbumsLocked(ids: List<String>) {
        val current = readSnapshot()
        val known = dao.albums().map { it.id }.toSet()
        val albums = ids.distinct().mapNotNull(current.albumsById::get).filter { it.id !in known }
        if (albums.isEmpty()) return
        val targets = albums.map { it.id }.toSet()
        db.withTransaction {
            current.sources.filter { source -> current.albumMembership.any { it.albumId in targets && it.sourceId == source.id } }
                .forEach { dao.saveSource(it) }
            dao.saveAlbums(albums)
            dao.saveAncestors(current.ancestry.filter { it.albumId in targets })
            dao.saveAlbumSources(current.albumMembership.filter { it.albumId in targets })
        }
    }
    suspend fun hideAlbums(ids: List<String>, hidden: Boolean) = writer.withLock {
        rememberAlbumsLocked(ids); dao.hideAlbums(ids, hidden)
    }
    internal suspend fun rememberLocked(ids: List<String>) {
        val current = readSnapshot()
        val rows = ids.distinct().mapNotNull { current.byId[it] }.filter { item ->
            dao.media(item.id)?.let { it.uri != item.uri || it.documentKey != item.documentKey || it.albumId != item.albumId } ?: true
        }
        if (rows.isEmpty()) return
        db.withTransaction {
            val existing = dao.media()
            for (item in rows) {
                existing.firstOrNull { it.documentKey == item.documentKey && it.id != item.id }?.let {
                    dao.saveMedia(listOf(it.copy(documentKey = "unavailable:${it.id}:${it.documentKey}", available = false)))
                }
                val album = current.albumsById[item.albumId] ?: continue
                val links = current.membership.filter { it.mediaId == item.id }
                current.sources.filter { source -> links.any { it.sourceId == source.id } }.forEach { dao.saveSource(it) }
                dao.saveAlbums(listOf(album)); dao.saveAncestors(current.ancestry.filter { it.albumId == album.id })
                dao.saveAlbumSources(current.albumMembership.filter { it.albumId == album.id })
                dao.saveMedia(listOf(item)); dao.saveMembership(links)
            }
        }
    }
    suspend fun saveVideoPosition(id: String, position: Long) = writer.withLock {
        rememberLocked(listOf(id)); dao.videoPosition(id, position, System.currentTimeMillis())
    }
    suspend fun favorite(id: String) = writer.withLock { rememberLocked(listOf(id)); dao.media(id)?.let { dao.favorite(id, !it.favorite); if (!it.favorite) (context.applicationContext as? GalleryApp)?.sounds?.play("favorite") } }
    suspend fun favoriteAll(ids: List<String>, value: Boolean) = writer.withLock {
        rememberLocked(ids)
        val addedAt = System.currentTimeMillis()
        db.withTransaction { ids.distinct().forEach { dao.favorite(it, value, addedAt) } }
        if (value && ids.isNotEmpty()) (context.applicationContext as? GalleryApp)?.sounds?.play("favorite")
    }
    suspend fun setAlbumCover(id: String, mediaId: String?) = writer.withLock {
        rememberLocked(listOfNotNull(mediaId))
        val data = readSnapshot()
        require(data.albumsById.containsKey(id) && !data.albumLocked(id) && (mediaId == null || data.byId[mediaId]?.let {
            it.albumId == id && it.kind in setOf("IMAGE", "GIF", "VIDEO") && data.artworkAllowed(it)
        } == true)) { localized(R.string.text_artwork_unavailable_56b21e, "Copertina non disponibile") }
        dao.albumCover(id, mediaId)
    }
    suspend fun enableSource(id: String, enabled: Boolean) = writer.withLock {
        dao.source(id)?.let { dao.saveSource(it.copy(enabled = enabled)); if (enabled && it.restoreUri == null) schedule(id) }
    }

    private val thumbnailDecode = Mutex()
    private val videoCovers get() = context.getSharedPreferences("video_covers", Context.MODE_PRIVATE)
    internal fun videoCoverFrame(id: String) = videoCovers.getLong(id, 0).coerceAtLeast(0)
    internal fun setVideoCoverFrame(item: MediaEntity, timeMs: Long) {
        require(snapshot.value.byId[item.id]?.let { it.kind == "VIDEO" && snapshot.value.usable(it) } == true)
        require(timeMs >= 0 && (item.duration == null || timeMs <= item.duration))
        videoCovers.edit().putLong(item.id, timeMs).apply()
        cacheRevision.value++
    }
    internal suspend fun videoThumbnail(item: MediaEntity, source: String, frameMs: Long = videoCoverFrame(item.id), transient: Boolean = false): Pair<String, String>? = withContext(Dispatchers.IO) {
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${item.id}/${item.documentKey}/${item.modified}/${item.size}/$frameMs/video-frame-v1".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = java.io.File(context.cacheDir, if (transient) "video-preview" else "artwork")
        val cached = java.io.File(directory, "video-$key.art")
        if (cached.isFile && cached.length() > 0) return@withContext cached.toURI().toString() to key
        thumbnailDecode.withLock {
        if (cached.isFile && cached.length() > 0) return@withContext cached.toURI().toString() to key
        val reader = android.media.MediaMetadataRetriever()
        try {
            reader.setDataSource(context, Uri.parse(source))
            val image = reader.getScaledFrameAtTime(frameMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST, 640, 640) ?: return@withContext null
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                try { if (!image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)) return@withContext null; output.toByteArray() }
                finally { image.recycle() }
            }
            writer.withLock {
                currentCoroutineContext().ensureActive()
                val stored = CacheMaintenance.storeArtwork(directory, "video-$key", bytes, if (transient) 4L * 1024 * 1024 else CacheMaintenance.ARTWORK_LIMIT)
                stored to key
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        finally { runCatching { reader.release() } }
    } }

    fun uri(item: MediaEntity, data: CatalogSnapshot = snapshot.value, artwork: Boolean = false): Uri {
        val state = protection.state.value
        val access = if (data.protectedAlbumIds == state.protected && data.unlockedAlbumIds == state.unlocked) data
            else if (data.protectedAlbumIds == state.protected && data.unlockedAlbumIds == state.unlocked) data else data.copy(protectedAlbumIds = state.protected, unlockedAlbumIds = state.unlocked)
        if (access.albumLocked(item.albumId)) throw SecurityException(localized(R.string.protected_album, "Album protetto"))
        val source = data.sourceFor(item, artwork) ?: throw SecurityException(localized(R.string.text_source_unavailable_or_media_hidden_2075ae, "Sorgente non disponibile o media nascosto"))
        if (source.id == DeviceGallery.SOURCE) {
            if (!DeviceGallery.canRead(context, item.kind)) throw SecurityException(localized(R.string.text_allow_access_to_this_media_36f18a, "Consenti l'accesso a questo media"))
            return Uri.parse(item.deviceUri ?: item.uri).also { require(DeviceGallery.isMedia(it)) }
        }
        if (source.singleDocument) return Uri.parse(source.treeUri).also {
            require(item.documentKey == "${it.authority}:${DocumentsContract.getDocumentId(it)}" ||
                DocumentIdentity.key(Uri.parse(item.uri)) == DocumentIdentity.key(it)) { localized(R.string.text_document_link_changed_66df8b, "Collegamento al documento cambiato") }
        }
        return DocumentIdentity.inTree(Uri.parse(item.uri), Uri.parse(source.treeUri))
    }

    internal fun lockedCoverUri(albumId: String, item: MediaEntity, data: CatalogSnapshot): Uri {
        val album = data.albumsById[albumId] ?: throw SecurityException("Album missing")
        require(data.albumLocked(albumId) && data.lockedCover(album)?.id == item.id)
        val open = data.copy(protectedAlbumIds = emptySet(), unlockedAlbumIds = emptySet())
        val source = open.sourceFor(item, artwork = true) ?: throw SecurityException("Cover unavailable")
        require(!source.singleDocument && source.id != DeviceGallery.SOURCE)
        return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(source.treeUri), DocumentsContract.getDocumentId(Uri.parse(item.uri)))
    }

    fun artwork(item: MediaEntity, data: CatalogSnapshot = snapshot.value): String? = artworkReference(item, data)?.first

    private val artworkReads = android.util.LruCache<String, Boolean>(512)
    private val artworkReader = Mutex()
    suspend fun loadArtworkReference(item: MediaEntity, data: CatalogSnapshot): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (item.kind == "AUDIO" && data.usable(item)) artworkReader.withLock {
            val key = "${item.id}/${item.modified}/${item.size}/${cacheRevision.value}"
            if (embeddedArtworkUri(context, item) == null && artworkReads.get(key) == null) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri(item, data))
                    retriever.embeddedPicture?.takeIf { it.size <= 8 * 1024 * 1024 }?.let {
                        CacheMaintenance.storeArtwork(java.io.File(context.cacheDir, "artwork"), item.id, it)
                    }
                    artworkReads.put(key, true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { artworkReads.put(key, true) }
                finally { retriever.release() }
            }
        }
        artworkReference(item, data)
    }

    /** URI and actual cover version, rather than the unchanged song's timestamp. */
    fun artworkReference(item: MediaEntity, data: CatalogSnapshot = snapshot.value): Pair<String, String>? {
        if (!data.usable(item)) return null
        val choices = listOfNotNull(data.artworkByTarget[ArtworkOrders.track(item.id)], data.artworkByTarget[musicAlbumTarget(item)], data.artworkByTarget[ArtworkOrders.folder(item.albumId)])
        fun picture(id: String?) = id?.let(data.byId::get)?.takeIf { it.kind in setOf("IMAGE", "GIF") && data.artworkAllowed(it) }
        val manual = choices.firstNotNullOfOrNull { picture(it.coverMediaId) } ?: picture(data.albumsById[item.albumId]?.coverMediaId)
        val raw = item.artworkUri?.let(Uri::parse)
        val embedded = embeddedArtworkUri(context, item)
        val legacyFolder = raw?.takeIf { it.scheme == "content" }?.let { runCatching { data.byDocumentKey["${it.authority}:${DocumentsContract.getDocumentId(it)}"] }.getOrNull() }
        val folder = picture(item.artworkMediaId)?.takeIf { it.albumId == item.albumId } ?: legacyFolder?.let { picture(it.id) }?.takeIf { it.albumId == item.albumId }
        val savedPriority = context.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE).getString("artwork_order", ArtworkOrders.DEFAULT)
        val priority = choices.firstNotNullOfOrNull { it.priority?.takeIf(ArtworkOrders::valid) } ?: savedPriority?.takeIf(ArtworkOrders::valid) ?: ArtworkOrders.DEFAULT
        fun reference(picture: MediaEntity) = uri(picture, data, artwork = true).toString() to "${picture.id}/${picture.modified}/${picture.size}"
        for (source in priority.split(',')) {
            val value = when (source) { "MANUAL" -> manual?.let(::reference); "EMBEDDED" -> embedded?.let { it to "${item.id}/${item.modified}/${item.size}" }; else -> folder?.let(::reference) }
            if (value != null) return value
        }
        return null
    }

    suspend fun setMusicArtwork(target: String, mediaId: String?, priority: String?) = writer.withLock {
        rememberLocked(listOfNotNull(mediaId))
        require(priority == null || ArtworkOrders.valid(priority)) { localized(R.string.text_invalid_artwork_priority_c1e3fd, "Priorità delle copertine non valida") }
        val data = readSnapshot()
        val audio = data.visibleMedia.filter { it.kind == "AUDIO" }
        require(audio.any { ArtworkOrders.track(it.id) == target || musicAlbumTarget(it) == target } || data.visibleAlbums(musical = true).any { ArtworkOrders.folder(it.id) == target }) { localized(R.string.text_album_or_track_no_longer_available_41bdb5, "Album o brano non più disponibile") }
        require(mediaId == null || data.byId[mediaId]?.let { it.kind in setOf("IMAGE", "GIF") && data.artworkAllowed(it) } == true) { localized(R.string.text_artwork_unavailable_56b21e, "Copertina non disponibile") }
        if (mediaId == null && priority == null) dao.deleteArtworkChoice(target)
        else dao.saveArtworkChoice(ArtworkChoiceEntity(target, mediaId, priority))
    }

    suspend fun collection(name: String, kind: String, ids: List<String>): String = writer.withLock {
        rememberLocked(ids)
        require(kind in setOf("MEDIA", "PLAYLIST")) { localized(R.string.text_invalid_collection_type_802a12, "Tipo di raccolta non valido") }
        val cleanName = collectionName(name)
        val data = readSnapshot()
        val targets = ids.distinct()
        require(targets.all { data.byId[it]?.let { item -> data.visible(item) && (kind != "PLAYLIST" || item.kind == "AUDIO") } == true }) { localized(R.string.text_an_item_is_no_longer_available_for_this_collecti_eae1b5, "Uno degli elementi non è più disponibile per questa raccolta") }
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            dao.saveCollection(CollectionEntity(id, cleanName, kind))
            dao.saveCollectionItems(targets.mapIndexed { index, media -> CollectionItem(id, media, index) })
        }
        id
    }

    private fun collectionName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 120 && it.none(Char::isISOControl)) { localized(R.string.text_use_a_name_of_1120_characters_without_control_ch_d4d026, "Usa un nome da 1 a 120 caratteri, senza caratteri di controllo") }
    }

    suspend fun renameCollection(id: String, name: String) = writer.withLock {
        val cleanName = collectionName(name)
        val current = dao.collections().firstOrNull { it.id == id } ?: error(localized(R.string.text_the_collection_was_removed_7b276a, "La raccolta è stata rimossa"))
        dao.saveCollection(current.copy(name = cleanName))
    }

    suspend fun addCollectionItems(id: String, ids: List<String>) = writer.withLock {
        rememberLocked(ids)
        val data = readSnapshot()
        val current = data.collections.firstOrNull { it.id == id } ?: error(localized(R.string.text_the_collection_was_removed_7b276a, "La raccolta è stata rimossa"))
        require(ids.isNotEmpty() && ids.all { data.byId[it]?.let { item -> data.visible(item) && (current.kind != "PLAYLIST" || item.kind == "AUDIO") } == true }) { localized(R.string.text_an_item_is_no_longer_available_for_this_collecti_eae1b5, "Uno degli elementi non è più disponibile per questa raccolta") }
        changeCollectionItems(id) { existing -> (existing + ids).distinct() }
    }

    suspend fun removeCollectionItem(id: String, mediaId: String) = writer.withLock {
        changeCollectionItems(id) { existing -> require(mediaId in existing) { localized(R.string.text_item_already_removed_47c0c9, "Elemento già rimosso") }; existing - mediaId }
    }

    suspend fun moveCollectionItem(id: String, mediaId: String, beforeId: String?) = writer.withLock {
        changeCollectionItems(id) { existing ->
            require(mediaId in existing && beforeId != mediaId && (beforeId == null || beforeId in existing)) { localized(R.string.text_the_collection_order_has_changed_try_again_938c15, "L'ordine della raccolta è cambiato: riprova") }
            existing.toMutableList().apply { remove(mediaId); add(if (beforeId == null) size else indexOf(beforeId), mediaId) }
        }
    }

    private suspend fun changeCollectionItems(id: String, change: (List<String>) -> List<String>) = db.withTransaction {
        require(dao.collections().any { it.id == id }) { localized(R.string.text_the_collection_was_removed_7b276a, "La raccolta è stata rimossa") }
        val next = change(dao.collectionItems().filter { it.collectionId == id }.map { it.mediaId })
        dao.clearCollection(id)
        dao.saveCollectionItems(next.mapIndexed { index, media -> CollectionItem(id, media, index) })
    }

    suspend fun setCollectionCover(id: String, mediaId: String?) = writer.withLock {
        rememberLocked(listOfNotNull(mediaId))
        val data = readSnapshot()
        val current = data.collections.firstOrNull { it.id == id } ?: error(localized(R.string.text_the_collection_was_removed_7b276a, "La raccolta è stata rimossa"))
        require(mediaId == null || data.byId[mediaId]?.let { it.kind in setOf("IMAGE", "GIF") && data.artworkAllowed(it) } == true) { localized(R.string.text_artwork_unavailable_56b21e, "Copertina non disponibile") }
        dao.saveCollection(current.copy(coverMediaId = mediaId))
    }

    suspend fun deleteCollection(id: String) = writer.withLock {
        db.withTransaction { dao.clearCollection(id); dao.deleteCollection(id) }
    }
}

class SourceScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Consume work left by older APKs without restarting the removed indexer.
    override suspend fun doWork(): Result = Result.success()
}
