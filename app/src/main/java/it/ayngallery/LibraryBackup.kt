package it.ayngallery

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

data class LibraryDocument(val origin: String, val data: CatalogSnapshot, val comparisons: List<ComparisonSessionEntity> = emptyList())
data class LibraryPreview(val baseline: String, val collections: Int, val references: Int, val unavailable: Int, val sourcesToLink: Int, val comparisons: Int = 0)
private data class LibraryPlan(val data: CatalogSnapshot, val comparisons: List<ComparisonSessionEntity>)

/** Metadata only; never import access, current players, operations, trash bytes or device/display state. */
class LibraryBackup(private val catalog: Catalog) {
    private val context get() = catalog.context
    val origin: String get() = synchronized(ORIGIN_LOCK) {
        val prefs = context.getSharedPreferences("library_backup_v1", Context.MODE_PRIVATE)
        prefs.getString("origin", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("origin", it).commit()) { localized(R.string.text_library_identity_not_saved_a70dd7, "Identità della libreria non salvata") }
        }
    }
    suspend fun export(): String = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val data = catalog.readSnapshot()
            val sessions = catalog.dao.comparisonSessions().map { session ->
                val state = ComparisonStore.decode(session.state) ?: error(localized(R.string.text_comparison_session_unreadable_1_s_895563, "Sessione di confronto non leggibile: %1\$s", session.name))
                session.copy(state = ComparisonStore.encode(state.copy(panes = state.panes.map { pane ->
                    pane.copy(mediaId = pane.mediaId?.let { data.byId[it]?.id ?: it }, context = pane.context.map { data.byId[it]?.id ?: it }.distinct())
                })).toString())
            }
            encode(LibraryDocument(origin, data, sessions))
        }
    }
    suspend fun preview(document: LibraryDocument): LibraryPreview = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val current = catalog.readSnapshot(); val sessions = catalog.dao.comparisonSessions()
            report(current, plan(document, current, sessions, false), sessions)
        }
    }
    suspend fun restore(document: LibraryDocument, baseline: String, replaceCollections: Boolean = false): LibraryPreview = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val current = catalog.readSnapshot()
            val sessions = catalog.dao.comparisonSessions()
            require(fingerprint(current, sessions) == baseline) { localized(R.string.text_the_library_changed_refresh_the_preview_a4e11a, "La libreria è cambiata: aggiorna l'anteprima") }
            require(catalog.dao.operations().none { !FileOperations.terminal(it.phase) }) { localized(R.string.text_finish_or_cancel_file_operations_before_restorin_e489ae, "Concludi o annulla le operazioni sui file prima del ripristino") }
            val next = plan(document, current, sessions, replaceCollections)
            catalog.db.withTransaction {
                val data = next.data
                data.sources.forEach { catalog.dao.saveSource(it) }
                catalog.dao.saveAlbums(data.albums); catalog.dao.saveAncestors(data.ancestry)
                catalog.dao.saveAlbumSources(data.albumMembership)
                catalog.dao.saveMedia(data.media); catalog.dao.saveMembership(data.membership)
                data.collections.forEach { item -> catalog.dao.saveCollection(item); catalog.dao.clearCollection(item.id) }
                catalog.dao.saveCollectionItems(data.collectionItems)
                data.artworkChoices.forEach { catalog.dao.saveArtworkChoice(it) }
                catalog.dao.saveComparisonSessions(next.comparisons)
            }
            report(catalog.readSnapshot(), next, catalog.dao.comparisonSessions())
        }
    }
    private fun report(current: CatalogSnapshot, plan: LibraryPlan, sessions: List<ComparisonSessionEntity>): LibraryPreview {
        val planned = plan.data
        val combined = planned.copy(sources = (current.sources + planned.sources).associateBy { it.id }.values.toList(),
            albums = (current.albums + planned.albums).associateBy { it.id }.values.toList(),
            membership = (current.membership + planned.membership).distinctBy { it.mediaId to it.sourceId },
            ancestry = (current.ancestry + planned.ancestry).distinct())
        val missing = MediaRelinking.missing(combined).map { it.id }.toSet()
        val sources = combined.membership.filter { it.mediaId in missing }.map { it.sourceId }.toSet()
        return LibraryPreview(fingerprint(current, sessions), planned.collections.size, planned.media.size,
            planned.media.count { !combined.usable(it) }, planned.sources.count { it.restoreUri != null && it.id in sources }, plan.comparisons.size)
    }
    private fun plan(document: LibraryDocument, current: CatalogSnapshot, savedSessions: List<ComparisonSessionEntity>, replace: Boolean): LibraryPlan {
        val incoming = document.data
        val sameOrigin = document.origin == origin
        val currentSources = current.sources.associateBy { it.id }
        val currentCollections = current.collections.associateBy { it.id }
        val redirected = current.mediaLinks.map { it.oldId }.toSet()
        val linkedMedia = hashSetOf<String>()
        fun stable(kind: String, id: String) = UUID.nameUUIDFromBytes("${document.origin}:$kind:$id".toByteArray(Charsets.UTF_8)).toString()
        fun sourceId(old: SourceEntity) = stable("source", row(old.id, old.restoreUri ?: old.treeUri, old.role).toString())
        fun albumId(old: AlbumEntity) = stable("album", row(old.id, old.documentKey, old.documentUri).toString())
        fun mediaId(old: MediaEntity) = stable("media", row(old.id, old.documentKey, old.uri, old.kind, old.mime, old.size, old.modified).toString())
        val sourceMap = incoming.sources.associate { old ->
            val known = if (sameOrigin) currentSources[old.id]?.takeIf { it.treeUri == old.treeUri && it.restoreUri == old.restoreUri } else null
            old.id to (known?.id ?: "backup:" + sourceId(old))
        }
        val sources = incoming.sources.map { old ->
            val id = sourceMap.getValue(old.id)
            val known = currentSources[id]
            if (known != null && known.restoreUri != null) require(known.restoreUri == (old.restoreUri ?: old.treeUri) && known.role == old.role) { localized(R.string.text_incompatible_source_identity_748a30, "Identità della sorgente incompatibile") }
            known ?: old.copy(id = id,
                treeUri = "content://${context.packageName}.pending-backup/tree/${sourceId(old)}",
                restoreUri = old.restoreUri ?: old.treeUri, reachable = false, error = localized(R.string.text_backup_source_needs_relinking_fb5836, "Backup: sorgente da ricollegare"), lastScan = 0)
        }
        val albumMap = incoming.albums.associate { old ->
            val known = if (sameOrigin) current.albumsById[old.id]?.takeIf { it.documentKey == old.documentKey } else null
            old.id to (known?.id ?: albumId(old))
        }
        val mediaMap = incoming.media.associate { old ->
            val fallback = mediaId(old)
            val linked = (listOfNotNull(old.id.takeIf { sameOrigin }, fallback).firstOrNull { it in redirected })?.let(current.byId::get)?.takeIf { it.kind == old.kind }
            val known = if (sameOrigin) current.byId[old.id]?.takeIf { it.documentKey == old.documentKey && it.kind == old.kind &&
                it.mime == old.mime && !DocumentVersions.changed(it.size, it.modified, old.size, old.modified) } else null
            if (linked != null) linkedMedia.add(old.id)
            old.id to (linked?.id ?: known?.id ?: fallback)
        }
        val media = incoming.media.map { old ->
            val id = mediaMap.getValue(old.id)
            val known = current.byId[id]
            if (known != null && id != old.id && old.id !in linkedMedia) require(known.documentKey == "backup:${document.origin}:${mediaId(old)}" &&
                known.uri == old.uri && known.kind == old.kind && known.mime == old.mime && known.size == old.size && known.modified == old.modified) { localized(R.string.text_incompatible_reference_identity_effad2, "Identità del riferimento incompatibile") }
            if (known != null) known.copy(favorite = known.favorite || old.favorite,
                videoPosition = if (known.videoPosition == 0L) old.videoPosition else known.videoPosition)
            else old.copy(id = id, albumId = albumMap.getValue(old.albumId),
                documentKey = "backup:${document.origin}:${mediaId(old)}", available = false, trashed = false,
                artworkUri = null, artworkMediaId = old.artworkMediaId?.let(mediaMap::getValue), deviceUri = null)
        }
        val albums = incoming.albums.map { old ->
            val id = albumMap.getValue(old.id); val known = current.albumsById[id]
            if (known != null && id != old.id) require(known.documentKey == "backup:${document.origin}:${albumId(old)}" && known.documentUri == old.documentUri) { localized(R.string.text_incompatible_album_identity_b1009c, "Identità dell'album incompatibile") }
            if (known != null) known.copy(hidden = known.hidden || old.hidden, systemExcluded = known.systemExcluded || old.systemExcluded,
                coverMediaId = known.coverMediaId ?: old.coverMediaId?.let(mediaMap::getValue))
            else old.copy(id = id, documentKey = "backup:${document.origin}:${albumId(old)}",
                parentId = old.parentId?.let(albumMap::getValue), coverMediaId = old.coverMediaId?.let(mediaMap::getValue))
        }
        val collectionMap = incoming.collections.associate { old -> old.id to
            (if (sameOrigin && currentCollections[old.id]?.kind == old.kind) old.id else stable("collection", row(old.id, old.kind).toString())) }
        val collections = incoming.collections.map { old ->
            val id = collectionMap.getValue(old.id); val known = currentCollections[id]
            if (known != null && !replace) known.copy(coverMediaId = known.coverMediaId ?: old.coverMediaId?.let(mediaMap::getValue))
            else old.copy(id = id, coverMediaId = old.coverMediaId?.let(mediaMap::getValue))
        }
        val incomingCollections = incoming.collections.associateBy { collectionMap.getValue(it.id) }
        val incomingItems = incoming.collectionItems.groupBy { it.collectionId }
        val currentItems = current.collectionItems.groupBy { it.collectionId }
        val items = collections.flatMap { collection ->
            val old = incomingCollections.getValue(collection.id)
            val restored = incomingItems[old.id].orEmpty().sortedBy { it.position }.map { mediaMap.getValue(it.mediaId) }
            val kept = if (!replace) currentItems[collection.id].orEmpty().sortedBy { it.position }.map { it.mediaId } else emptyList()
            (kept + restored).distinct().mapIndexed { position, id -> CollectionItem(collection.id, id, position) }
        }
        val nextMedia = media.associateBy { it.id }
        val oldAlbumTargets = incoming.media.filter { it.kind == "AUDIO" }.associate { musicAlbumTarget(it) to musicAlbumTarget(nextMedia.getValue(mediaMap.getValue(it.id))) }
        val choices = incoming.artworkChoices.map { old ->
            val target = when {
                old.target.startsWith("TRACK:") -> ArtworkOrders.track(mediaMap.getValue(old.target.removePrefix("TRACK:")))
                old.target.startsWith("FOLDER:") -> ArtworkOrders.folder(albumMap.getValue(old.target.removePrefix("FOLDER:")))
                else -> oldAlbumTargets.getValue(old.target)
            }
            current.artworkByTarget[target] ?: old.copy(target = target, coverMediaId = old.coverMediaId?.let(mediaMap::getValue))
        }
        val memberByPair = current.membership.associateBy { it.mediaId to it.sourceId }
        val albumMemberByPair = current.albumMembership.associateBy { it.albumId to it.sourceId }
        val sessionById = savedSessions.associateBy { it.id }
        val names = savedSessions.associate { it.name to it.id }.toMutableMap()
        val sessions = document.comparisons.map { old ->
            val state = transferState(JSONObject(old.state))
            val id = if (sameOrigin && old.id in sessionById) old.id else stable("comparison", old.id)
            val known = sessionById[id]
            if (known != null && !replace) known else {
                if (known != null && names[known.name] == id) names.remove(known.name)
                var title = old.name; var suffix = 2
                while (names[title] != null && names[title] != id) {
                    val tail = " ($suffix)"; title = old.name.take(100 - tail.length) + tail; suffix++
                }
                names[title] = id
                fun mapped(oldId: String) = mediaMap[oldId] ?: stable("comparison-orphan", oldId)
                old.copy(id = id, name = title, state = ComparisonStore.encode(state.copy(panes = state.panes.map { pane ->
                    pane.copy(mediaId = pane.mediaId?.let(::mapped), context = pane.context.map(::mapped).distinct())
                })).toString())
            }
        }
        return LibraryPlan(CatalogSnapshot(sources, albums, media,
            incoming.membership.map { link -> val mapped = link.copy(mediaId = mediaMap.getValue(link.mediaId), sourceId = sourceMap.getValue(link.sourceId), scan = 0)
                memberByPair[mapped.mediaId to mapped.sourceId] ?: mapped },
            incoming.ancestry.map { it.copy(albumId = albumMap.getValue(it.albumId), ancestorId = albumMap.getValue(it.ancestorId)) },
            collections, items, true,
            incoming.albumMembership.map { link -> val mapped = link.copy(albumId = albumMap.getValue(link.albumId), sourceId = sourceMap.getValue(link.sourceId), scan = 0)
                albumMemberByPair[mapped.albumId to mapped.sourceId] ?: mapped },
            artworkChoices = choices, mediaLinks = current.mediaLinks), sessions)
    }
    suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { localized(R.string.text_choose_a_document_from_the_system_a069ca, "Scegli un documento dal sistema") }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(chunk); if (n < 0) break
                require(output.size() <= MAX_BYTES - n) { localized(R.string.text_backup_too_large_de2bee, "Backup troppo grande") }; output.write(chunk, 0, n)
            }; output.toByteArray()
        } ?: error(localized(R.string.text_document_unavailable_2d9594, "Documento non disponibile"))
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().also { decode(it) }
    }
    suspend fun write(uri: Uri, raw: String) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { localized(R.string.text_choose_a_destination_from_the_system_3bc989, "Scegli una destinazione dal sistema") }
        decode(raw)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(raw.toByteArray(Charsets.UTF_8)); it.flush() } ?: error(localized(R.string.text_destination_unavailable_1a3939, "Destinazione non disponibile"))
        check(read(uri) == raw) { localized(R.string.text_backup_unverified_the_document_may_be_incomplete_510b4b, "Backup non verificato; il documento potrebbe essere incompleto") }
    }
    companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
        private val ORIGIN_LOCK = Any()
        private fun row(vararg values: Any?) = JSONArray(values.map { it ?: JSONObject.NULL })
        fun encode(document: LibraryDocument): String {
            val d = document.data
            val root = JSONObject().put("kind", "ayngallery-library").put("version", 2).put("origin", document.origin)
                .put("sources", JSONArray(d.sources.map { row(it.id, it.treeUri, it.title, it.role, it.enabled, it.restoreUri) }))
                .put("albums", JSONArray(d.albums.map { row(it.id, it.documentKey, it.documentUri, it.name, it.path, it.parentId, it.hidden, it.systemExcluded, it.coverMediaId) }))
                .put("media", JSONArray(d.media.map { row(it.id, it.documentKey, it.uri, it.albumId, it.name, it.mime, it.kind, it.size, it.modified,
                    it.duration, it.title, it.artist, it.musicAlbum, it.artworkMediaId, it.favorite, it.videoPosition, it.added, it.trashed) }))
                .put("membership", JSONArray(d.membership.map { row(it.mediaId, it.sourceId) }))
                .put("ancestry", JSONArray(d.ancestry.map { row(it.albumId, it.ancestorId) }))
                .put("albumMembership", JSONArray(d.albumMembership.map { row(it.albumId, it.sourceId, it.present) }))
                .put("collections", JSONArray(d.collections.map { row(it.id, it.name, it.kind, it.coverMediaId) }))
                .put("items", JSONArray(d.collectionItems.map { row(it.collectionId, it.mediaId, it.position) }))
                .put("artwork", JSONArray(d.artworkChoices.map { row(it.target, it.coverMediaId, it.priority) }))
                .put("comparisons", JSONArray(document.comparisons.map { row(it.id, it.name, JSONObject(it.state)) }))
            return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { localized(R.string.text_backup_too_large_de2bee, "Backup troppo grande") }; decode(it) }
        }
        fun decode(raw: String): LibraryDocument {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { localized(R.string.text_backup_too_large_de2bee, "Backup troppo grande") }
            val parser = JSONTokener(raw); val root = parser.nextValue() as? JSONObject ?: error(localized(R.string.text_unrecognized_format_a07e3b, "Formato non riconosciuto"))
            val version = root.get("version")
            require(root.get("kind") == "ayngallery-library" && version in setOf(1, 2)) { localized(R.string.text_unsupported_backup_version_a9bfd8, "Versione di backup non supportata") }
            val keys = setOf("kind", "version", "origin", "sources", "albums", "media", "membership", "ancestry", "albumMembership", "collections", "items", "artwork")
            require(parser.nextClean() == '\u0000' && root.keys().asSequence().toSet() == keys + if (version == 2) setOf("comparisons") else emptySet())
            val origin = root.get("origin") as? String ?: error(localized(R.string.text_missing_identity_6fc395, "Identità mancante")); require(UUID.fromString(origin).toString() == origin)
            fun rows(key: String, width: Int, limit: Int) = root.getJSONArray(key).let { array ->
                require(array.length() <= limit) { localized(R.string.text_too_many_items_in_backup_7b0ac6, "Troppi elementi nel backup") }
                (0 until array.length()).map { array.getJSONArray(it).also { r -> require(r.length() == width) } }
            }
            fun JSONArray.text(i: Int, max: Int = 8192) = (get(i) as? String)?.also { require(it.length in 1..max && !it.contains('\u0000')) } ?: error(localized(R.string.text_invalid_text_c9999f, "Testo non valido"))
            fun JSONArray.nullable(i: Int, max: Int = 8192) = if (isNull(i)) null else (get(i) as? String)?.also {
                require(it.length <= max && !it.contains('\u0000'))
            } ?: error(localized(R.string.text_invalid_text_c9999f, "Testo non valido"))
            fun JSONArray.bool(i: Int) = get(i) as? Boolean ?: error(localized(R.string.text_invalid_value_23d031, "Valore non valido"))
            fun JSONArray.number(i: Int, min: Long = 0): Long {
                val n = get(i); require(n is Int || n is Long); return (n as Number).toLong().also { require(it >= min) }
            }
            fun JSONArray.optionalNumber(i: Int) = if (isNull(i)) null else number(i)
            fun uri(rawUri: String): String = rawUri.also { val value = Uri.parse(it); require(value.scheme == "content" && !value.authority.isNullOrBlank() && value.fragment == null) }
            val sources = rows("sources", 6, 4096).map { r -> SourceEntity(r.text(0, 256), uri(r.text(1)), r.text(2, 1024), r.text(3, 20).also { require(it in setOf("VISUAL", "MUSIC", "MIXED")) }, r.bool(4), false, restoreUri = r.nullable(5)?.let(::uri)) }
            val albums = rows("albums", 9, 50_000).map { r -> AlbumEntity(r.text(0, 256), r.text(1), uri(r.text(2)), r.text(3, 1024), r.text(4), r.nullable(5, 256), r.bool(6), r.nullable(8, 256), r.bool(7)) }
            val media = rows("media", 18, 200_000).map { r -> MediaEntity(r.text(0, 256), r.text(1), uri(r.text(2)), r.text(3, 256), r.text(4, 1024), r.text(5, 256), r.text(6, 20).also { require(it in setOf("IMAGE", "GIF", "VIDEO", "AUDIO")) },
                r.number(7, -1), r.number(8), r.optionalNumber(9), r.nullable(10), r.nullable(11), r.nullable(12), artworkMediaId = r.nullable(13, 256),
                favorite = r.bool(14), available = false, videoPosition = r.number(15), added = r.number(16), trashed = r.bool(17)) }
            val members = rows("membership", 2, 400_000).map { MediaSource(it.text(0, 256), it.text(1, 256), 0) }
            val ancestry = rows("ancestry", 2, 400_000).map { AlbumAncestor(it.text(0, 256), it.text(1, 256)) }
            val albumMembers = rows("albumMembership", 3, 100_000).map { AlbumSource(it.text(0, 256), it.text(1, 256), 0, it.bool(2)) }
            val collections = rows("collections", 4, 10_000).map { r -> CollectionEntity(r.text(0, 256), r.text(1, 120).also { require(it.none(Char::isISOControl)) }, r.text(2, 20).also { require(it in setOf("MEDIA", "PLAYLIST")) }, r.nullable(3, 256)) }
            val items = rows("items", 3, 400_000).map { CollectionItem(it.text(0, 256), it.text(1, 256), it.number(2).also { n -> require(n <= Int.MAX_VALUE) }.toInt()) }
            val artwork = rows("artwork", 3, 200_000).map { r -> ArtworkChoiceEntity(r.text(0), r.nullable(1, 256), r.nullable(2, 100).also { require(it == null || ArtworkOrders.valid(it)) }) }
            var contextCount = 0
            val comparisons = if (version == 1) emptyList() else rows("comparisons", 3, 1000).map { r ->
                val id = r.text(0, 36); require(UUID.fromString(id).toString() == id)
                val name = r.text(1, 100).also { require(it == it.trim() && it.none(Char::isISOControl)) }
                val state = transferState(r.getJSONObject(2))
                contextCount += state.panes.sumOf { it.context.size }; require(contextCount <= 400_000) { localized(R.string.text_too_many_comparison_references_79dd27, "Troppi riferimenti nei confronti") }
                ComparisonSessionEntity(id, name, ComparisonStore.encode(state).toString())
            }
            require(comparisons.map { it.id }.distinct().size == comparisons.size && comparisons.map { it.name }.distinct().size == comparisons.size)
            val s = sources.associateBy { it.id }; val a = albums.associateBy { it.id }; val m = media.associateBy { it.id }; val c = collections.associateBy { it.id }
            require(s.size == sources.size && a.size == albums.size && m.size == media.size && c.size == collections.size)
            require(sources.map { it.treeUri }.distinct().size == sources.size && albums.map { it.documentKey }.distinct().size == albums.size && media.map { it.documentKey }.distinct().size == media.size)
            require(albums.all { (it.parentId == null || it.parentId in a) && (it.coverMediaId == null || it.coverMediaId in m) })
            require(media.all { it.albumId in a && (it.artworkMediaId == null || it.artworkMediaId in m) })
            require(members.distinct().size == members.size && members.all { it.mediaId in m && it.sourceId in s })
            require(ancestry.distinct().size == ancestry.size && ancestry.all { it.albumId in a && it.ancestorId in a })
            require(albumMembers.distinct().size == albumMembers.size && albumMembers.all { it.albumId in a && it.sourceId in s })
            require(items.map { it.collectionId to it.mediaId }.distinct().size == items.size && items.all { it.collectionId in c && it.mediaId in m && (c.getValue(it.collectionId).kind != "PLAYLIST" || m.getValue(it.mediaId).kind == "AUDIO") })
            require(collections.all { it.coverMediaId == null || it.coverMediaId in m })
            require(artwork.map { it.target }.distinct().size == artwork.size && artwork.all { it.coverMediaId == null || it.coverMediaId in m })
            val targets = media.filter { it.kind == "AUDIO" }.flatMap { listOf(ArtworkOrders.track(it.id), musicAlbumTarget(it)) }.toSet() + albums.map { ArtworkOrders.folder(it.id) }
            require(artwork.all { it.target in targets })
            val covers = albums.mapNotNull { it.coverMediaId } + collections.mapNotNull { it.coverMediaId } + media.mapNotNull { it.artworkMediaId } + artwork.mapNotNull { it.coverMediaId }
            require(covers.all { m.getValue(it).kind in setOf("IMAGE", "GIF") }) { localized(R.string.text_invalid_cover_e32130, "Copertina non valida") }
            // Parent cycles cannot become an unbounded traversal during later source relinking.
            for (album in albums) { var id: String? = album.id; val seen = hashSetOf<String>(); while (id != null) { require(seen.add(id)); require(seen.size <= 512); id = a.getValue(id).parentId } }
            return LibraryDocument(origin, CatalogSnapshot(sources, albums, media, members, ancestry, collections, items, true, albumMembers, artworkChoices = artwork), comparisons)
        }
        /** Strict transfer boundary; the forgiving local decoder must not silently repair an import. */
        private fun transferState(json: JSONObject): ComparisonState {
            require(json.keys().asSequence().toSet() == setOf("version", "active", "linked", "enabled", "panes") && json.get("version") == 1)
            require(json.get("active") is String && json.get("linked") is Boolean && json.get("enabled") is Boolean)
            val panes = json.getJSONArray("panes"); require(panes.length() == 2 || panes.length() == 4)
            fun id(value: Any): String = (value as? String)?.also { require(it.length in 1..256 && it.none(Char::isISOControl)) } ?: error(localized(R.string.text_invalid_comparison_reference_2ba4e7, "Riferimento del confronto non valido"))
            val decoded = (0 until panes.length()).map { i ->
                val pane = panes.getJSONObject(i)
                require(pane.keys().asSequence().toSet() == setOf("id", "media", "pinned", "context", "zoom", "x", "y"))
                require(pane.get("pinned") is Boolean)
                val context = pane.getJSONArray("context"); require(context.length() <= 100_000)
                val refs = (0 until context.length()).map { id(context.get(it)) }; require(refs.distinct().size == refs.size)
                fun number(key: String, min: Double, max: Double): Float {
                    val n = pane.get(key) as? Number ?: error(localized(R.string.text_invalid_transformation_ae3aba, "Trasformazione non valida"))
                    val value = n.toDouble(); require(value.isFinite() && value in min..max); return value.toFloat()
                }
                ComparisonPane(id(pane.get("id")), if (pane.isNull("media")) null else id(pane.get("media")), pane.getBoolean("pinned"), refs,
                    ViewTransform(number("zoom", 1.0, 8.0), number("x", -0.5, 0.5), number("y", -0.5, 0.5)))
            }
            require(decoded.map { it.id }.distinct().size == decoded.size && decoded.any { it.id == json.getString("active") })
            return ComparisonState(decoded, json.getString("active"), json.getBoolean("linked"), json.getBoolean("enabled"))
        }
        fun fingerprint(data: CatalogSnapshot, comparisons: List<ComparisonSessionEntity> = emptyList()): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(vararg values: Any?) { digest.update(row(*values).toString().toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
            data.sources.sortedBy { it.id }.forEach { add("s", it.id, it.treeUri, it.role, it.enabled, it.restoreUri) }
            data.albums.sortedBy { it.id }.forEach { add("a", it.id, it.documentKey, it.name, it.path, it.parentId, it.hidden, it.systemExcluded, it.coverMediaId) }
            data.media.sortedBy { it.id }.forEach { add("m", it.id, it.documentKey, it.albumId, it.name, it.mime, it.kind, it.size, it.modified, it.favorite, it.trashed, it.title, it.artist, it.musicAlbum, it.artworkMediaId, it.videoPosition) }
            data.collections.sortedBy { it.id }.forEach { add("c", it.id, it.name, it.kind, it.coverMediaId) }
            data.collectionItems.sortedWith(compareBy<CollectionItem> { it.collectionId }.thenBy { it.position }).forEach { add("i", it.collectionId, it.mediaId, it.position) }
            data.artworkChoices.sortedBy { it.target }.forEach { add("w", it.target, it.coverMediaId, it.priority) }
            data.membership.sortedWith(compareBy<MediaSource> { it.sourceId }.thenBy { it.mediaId }).forEach { add("l", it.sourceId, it.mediaId) }
            data.ancestry.sortedWith(compareBy<AlbumAncestor> { it.albumId }.thenBy { it.ancestorId }).forEach { add("p", it.albumId, it.ancestorId) }
            data.albumMembership.sortedWith(compareBy<AlbumSource> { it.sourceId }.thenBy { it.albumId }).forEach { add("r", it.sourceId, it.albumId, it.present) }
            data.mediaLinks.sortedBy { it.oldId }.forEach { add("l", it.oldId, it.mediaId) }
            comparisons.sortedBy { it.id }.forEach { add("compare", it.id, it.name, it.state) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
