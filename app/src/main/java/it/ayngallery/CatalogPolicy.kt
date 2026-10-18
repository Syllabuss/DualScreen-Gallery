package it.ayngallery

internal fun matchesPreviewCategory(category: String, item: MediaEntity): Boolean =
    if (item.convertedGif) category in setOf("PHOTO", "FAVORITE") else matchesPreviewCategory(category, item.kind)

internal fun matchesPreviewCategory(category: String, kind: String): Boolean = when (category) {
    "PHOTO" -> kind in setOf("IMAGE", "GIF")
    "VIDEO" -> kind == "VIDEO"
    "FAVORITE" -> kind in setOf("IMAGE", "GIF", "VIDEO")
    else -> false
}

/** One visibility decision is shared by UI, queue, artwork, comparison and widgets. */
data class CatalogSnapshot(
    val sources: List<SourceEntity> = emptyList(), val albums: List<AlbumEntity> = emptyList(),
    val media: List<MediaEntity> = emptyList(), val membership: List<MediaSource> = emptyList(),
    val ancestry: List<AlbumAncestor> = emptyList(), val collections: List<CollectionEntity> = emptyList(),
    val collectionItems: List<CollectionItem> = emptyList(), val initialized: Boolean = false,
    val albumMembership: List<AlbumSource> = emptyList(),
    val mutating: Set<String> = emptySet(),
    val artworkChoices: List<ArtworkChoiceEntity> = emptyList(),
    val mediaLinks: List<MediaRelink> = emptyList(),
    val protectedAlbumIds: Set<String> = emptySet(),
    val unlockedAlbumIds: Set<String> = emptySet(),
    val protectionMarkers: Map<String, Boolean> = emptyMap(),
) {
    val byId by lazy { media.associateBy { it.id }.toMutableMap().also { actual ->
        val links = mediaLinks.associate { it.oldId to it.mediaId }
        for (old in links.keys) {
            var id = old; val seen = hashSetOf<String>()
            while (id !in actual && id in links && seen.add(id) && seen.size <= 256) id = links.getValue(id)
            actual[id]?.let { actual.putIfAbsent(old, it) }
        }
    }.toMap() }
    val byDocumentKey by lazy { media.associateBy { it.documentKey } }
    internal val byUri by lazy { media.associateBy { it.uri } }
    internal val byDeviceUri by lazy { media.filter { it.deviceUri != null }.associateBy { it.deviceUri } }
    val artworkByTarget by lazy { artworkChoices.associateBy { it.target } }
    val albumsById by lazy { albums.associateBy { it.id } }
    private val sourcesById by lazy { sources.associateBy { it.id } }
    private val membersByMedia by lazy { membership.groupBy { it.mediaId } }
    private val hidden by lazy { albums.filter { it.hidden || it.systemExcluded }.map { it.id }.toSet() }
    val excludedAlbums by lazy { ancestry.filter { it.ancestorId in hidden }.map { it.albumId }.toSet() + hidden }
    private val protectedParents by lazy { albums.associate { it.id to it.parentId } }
    private val blockedAncestors by lazy { ancestry.filter { it.ancestorId in protectedAlbumIds && it.ancestorId !in unlockedAlbumIds }.associate { it.albumId to it.ancestorId } }
    fun protectionOwner(albumId: String): String? {
        if (protectedAlbumIds.isEmpty() || unlockedAlbumIds.containsAll(protectedAlbumIds)) return null
        var id: String? = albumId
        val seen = hashSetOf<String>()
        while (id != null && seen.add(id)) {
            if (id in protectedAlbumIds && id !in unlockedAlbumIds) return id
            id = protectedParents[id]
        }
        return blockedAncestors[albumId]
    }
    fun albumLocked(id: String) = protectionOwner(id) != null
    fun roleAllows(role: String, kind: String) = role == SourceRole.MIXED.name ||
        if (kind == MediaKind.AUDIO.name) role == SourceRole.MUSIC.name else role == SourceRole.VISUAL.name
    private fun listed(item: MediaEntity): Boolean = !item.trashed && item.albumId !in excludedAlbums &&
        (item.kind != "AUDIO" || MediaNames.kind(item.mime, item.name) == MediaKind.AUDIO) &&
        membersByMedia[item.id].orEmpty().any { link -> sourcesById[link.sourceId]?.let { it.enabled && roleAllows(it.role, item.kind) } == true }
    fun visible(item: MediaEntity): Boolean = listed(item) && !albumLocked(item.albumId)
    fun usable(item: MediaEntity): Boolean = item.available && item.id !in mutating && visible(item) &&
        membersByMedia[item.id].orEmpty().any { sourcesById[it.sourceId]?.let { s -> s.enabled && s.reachable && roleAllows(s.role, item.kind) } == true }
    fun artworkAllowed(item: MediaEntity): Boolean = !albumLocked(item.albumId) && !item.trashed && item.available && item.id !in mutating && item.albumId !in excludedAlbums &&
        membersByMedia[item.id].orEmpty().any { sourcesById[it.sourceId]?.let { s -> s.enabled && s.reachable } == true }
    val visibleMedia by lazy { media.filter(::visible) }
    val visibleByAlbum by lazy { visibleMedia.groupBy { it.albumId } }
    private val listedByAlbum by lazy { media.filter(::listed).groupBy { it.albumId } }
    private val populatedLockedAlbums by lazy { media.asSequence().filter(::listed).mapNotNull { protectionOwner(it.albumId) }.toSet() }
    fun albumBranchIds(id: String): Set<String> = ancestry.filter { it.ancestorId == id }.map { it.albumId }.toSet() + id
    fun albumMediaCount(id: String) = listedByAlbum[id].orEmpty().size
    fun albumHasMedia(id: String) = listedByAlbum[id].orEmpty().isNotEmpty() || id in populatedLockedAlbums
    /** The sole deliberate exception is the first cover, never a playable stream or a widget. */
    private val lockedCovers by lazy {
        media.asSequence().filter { listed(it) && it.available && it.id !in mutating && it.kind in setOf("IMAGE", "GIF", "VIDEO") }
            .mapNotNull { item -> protectionOwner(item.albumId)?.let { it to item } }.groupBy({ it.first }, { it.second })
            .mapValues { (albumId, items) -> items.firstOrNull { it.id == albumsById[albumId]?.coverMediaId }
                ?: items.maxWithOrNull(compareBy<MediaEntity> { it.modified }.thenBy { it.id }) }
    }
    fun lockedCover(album: AlbumEntity): MediaEntity? = lockedCovers[album.id]
    val albumsWithVisibleMedia: Set<String> = buildSet {
        val populated = listedByAlbum.keys
        addAll(populated)
        val parents = albums.associate { it.id to it.parentId }
        for (albumId in populated) {
            var parentId = parents[albumId]
            val seen = mutableSetOf(albumId)
            while (parentId != null && seen.add(parentId)) {
                add(parentId)
                parentId = parents[parentId]
            }
        }
    }
    val rootAlbumIds: Set<String> get() = sources.filter { it.enabled && !it.singleDocument && it.id != DeviceGallery.SOURCE }
        .mapNotNull { source -> runCatching {
            val tree = android.net.Uri.parse(source.treeUri)
            "${tree.authority}:${android.provider.DocumentsContract.getTreeDocumentId(tree)}"
        }.getOrNull() }.toSet().let { keys -> albums.filter { it.documentKey in keys }.map { it.id }.toSet() }
    private val browsableAlbums by lazy { visibleAlbums().distinctBy { it.id } }
    private val browsableChildren by lazy { browsableAlbums.groupBy { it.parentId } }
    private fun expandEmptyFolders(start: List<AlbumEntity>): List<AlbumEntity> {
        val pending = ArrayDeque(start)
        val seen = mutableSetOf<String>()
        val result = mutableListOf<AlbumEntity>()
        while (pending.isNotEmpty()) {
            val album = pending.removeFirst()
            if (!seen.add(album.id)) continue
            if (albumHasMedia(album.id) || browsableChildren[album.id].orEmpty().isEmpty()) result += album
            else pending.addAll(browsableChildren[album.id].orEmpty())
        }
        return result
    }
    fun childAlbums(parent: String, nested: Boolean = false): List<AlbumEntity> = if (nested) browsableChildren[parent].orEmpty() else expandEmptyFolders(browsableChildren[parent].orEmpty())
    fun topAlbums(prefs: GalleryPreferences? = null): List<AlbumEntity> {
        val visible = browsableAlbums
        val roots = rootAlbumIds
        val ids = visible.map { it.id }.toSet()
        val start = visible.filter { album -> album.id in roots ||
            album.parentId in roots || album.parentId == null || album.parentId !in ids }
        return if (prefs?.nestedAlbums == true) start.filter { it.parentId !in ids } else visible.filter { albumHasMedia(it.id) || browsableChildren[it.id].orEmpty().isEmpty() }
    }
    val populatedSubfolderCounts by lazy {
        val counts = mutableMapOf<String, Int>()
        val visible = browsableAlbums.associateBy { it.id }
        for (album in browsableAlbums.filter { visibleByAlbum[it.id].orEmpty().isNotEmpty() }) {
            var parent = album.parentId
            val seen = mutableSetOf(album.id)
            while (parent != null && seen.add(parent)) {
                val ancestor = visible[parent] ?: break
                counts[parent] = (counts[parent] ?: 0) + 1
                if (visibleByAlbum[parent].orEmpty().isNotEmpty()) break
                parent = ancestor.parentId
            }
        }
        counts
    }
    fun visibleAlbums(musical: Boolean = false): List<AlbumEntity> {
        val role = if (musical) "MUSIC" else "VISUAL"
        val ids = albumMembership.filter { link -> link.present && sourcesById[link.sourceId]?.let {
            it.enabled && it.role in setOf(role, "MIXED")
        } == true }.map { it.albumId }.toSet()
        return albums.filter { it.id in ids && it.id !in excludedAlbums &&
            (protectionOwner(it.id) == null || protectionOwner(it.id) == it.id) }
    }
    fun sourceFor(item: MediaEntity, artwork: Boolean = false): SourceEntity? {
        if (if (artwork) !artworkAllowed(item) else !usable(item)) return null
        return membersByMedia[item.id].orEmpty().mapNotNull { sourcesById[it.sourceId] }
            .sortedWith(compareBy<SourceEntity> { it.id == DeviceGallery.SOURCE }
                .thenBy { android.net.Uri.parse(it.treeUri).authority != android.net.Uri.parse(item.uri).authority }.thenBy { it.singleDocument })
            .firstOrNull { it.enabled && it.reachable && (artwork || roleAllows(it.role, item.kind)) }
    }
    fun cover(album: AlbumEntity): MediaEntity? {
        val manual = album.coverMediaId?.let(byId::get)?.takeIf(::artworkAllowed)
        return manual ?: visibleByAlbum[album.id].orEmpty()
            .filter { it.kind in setOf("IMAGE", "GIF", "VIDEO") && usable(it) }
            .maxWithOrNull(compareBy<MediaEntity> { it.modified }.thenBy { it.id })
    }
    fun collectionCover(collection: CollectionEntity): MediaEntity? = collection.coverMediaId?.let(byId::get)?.takeIf(::artworkAllowed)
        ?: collectionItems.asSequence().filter { it.collectionId == collection.id }.mapNotNull { byId[it.mediaId]?.takeIf(::usable) }.firstOrNull()
}

/** Keep the video decoder for GIFs converted to MP4/WebM; their filename preserves the GIF suffix. */
val MediaEntity.convertedGif: Boolean get() = kind == "VIDEO" &&
    name.lowercase().let { it.endsWith(".gif.mp4") || it.endsWith(".gif.webm") }

object MediaNames {
    fun kind(mime: String, name: String): MediaKind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        // Providers may label playlists as audio, but they contain references, not playable samples.
        if (ext in setOf("m3u", "m3u8", "pls", "xspf") || mime.lowercase() in setOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-scpls", "application/xspf+xml")) return null
        if (mime == "image/gif" || ext == "gif") return MediaKind.GIF
        if (mime.startsWith("image/") && mime !in setOf("image/svg+xml", "image/x-adobe-dng")) return MediaKind.IMAGE
        if (mime.startsWith("video/") || ext in setOf("mp4", "webm", "mkv", "mov", "m4v")) return MediaKind.VIDEO
        if (mime.startsWith("audio/") && ext !in setOf("sf2", "sfz", "mid", "midi")) return MediaKind.AUDIO
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "heic", "heif", "avif" -> MediaKind.IMAGE
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav" -> MediaKind.AUDIO
            else -> null
        }
    }

    val natural = Comparator<String> { left, right ->
        val a = left.lowercase(); val b = right.lowercase()
        var ai = 0; var bi = 0
        var result = 0
        while (ai < a.length && bi < b.length) {
            val an = a[ai] in '0'..'9'; val bn = b[bi] in '0'..'9'
            var ae = ai + 1; var be = bi + 1
            while (ae < a.length && (a[ae] in '0'..'9') == an) ae++
            while (be < b.length && (b[be] in '0'..'9') == bn) be++
            if (a[ai].isDigit() && b[bi].isDigit()) {
                while (ai < ae - 1 && a[ai] == '0') ai++
                while (bi < be - 1 && b[bi] == '0') bi++
                result = (ae - ai).compareTo(be - bi)
            }
            if (result == 0) {
                var offset = 0
                while (offset < minOf(ae - ai, be - bi)) {
                    result = a[ai + offset].compareTo(b[bi + offset])
                    if (result != 0) break
                    offset++
                }
                if (result == 0) result = (ae - ai).compareTo(be - bi)
            }
            if (result != 0) break
            ai = ae; bi = be
        }
        if (result != 0) result else (ai < a.length).compareTo(bi < b.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
