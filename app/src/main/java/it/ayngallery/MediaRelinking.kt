package it.ayngallery

import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class RelinkCandidate(val oldId: String, val target: IncomingFile, val baseline: String,
    val oldName: String, val oldSize: Long, val oldModified: Long, val oldPath: String) {
    fun encode() = JSONObject().put("v", 1).put("old", oldId).put("target", JSONObject(target.encode())).put("baseline", baseline)
        .put("name", oldName).put("size", oldSize).put("modified", oldModified).put("path", oldPath).toString()
    companion object {
        fun decode(raw: String): RelinkCandidate {
            require(raw.toByteArray(Charsets.UTF_8).size <= 40 * 1024)
            val root = JSONObject(raw); require(root.getInt("v") == 1)
            val id = root.getString("old"); require(id.length in 1..256)
            val baseline = root.getString("baseline"); require(baseline.matches(Regex("[a-f0-9]{64}")))
            val name = root.getString("name"); val path = root.getString("path")
            require(name.length in 1..1024 && path.length <= 8192 && !name.contains('\u0000') && !path.contains('\u0000'))
            val size = root.getLong("size"); val modified = root.getLong("modified"); require(size >= -1 && modified >= 0)
            return RelinkCandidate(id, IncomingFile.decode(root.getJSONObject("target").toString()), baseline, name, size, modified, path)
        }
    }
}

/** User-selected correspondence, never inferred from equal names or a foreign URI. No file writes. */
class MediaRelinking(private val catalog: Catalog) {
    private val context get() = catalog.context
    companion object {
        fun missing(data: CatalogSnapshot, source: String? = null): List<MediaEntity> {
            val readableSources = data.sources.filter { it.reachable }.map { it.id }.toSet()
            val readable = data.membership.filter { it.sourceId in readableSources }.map { it.mediaId }.toSet()
            val included = source?.let { id -> data.membership.filter { it.sourceId == id }.map { it.mediaId }.toSet() }
            return data.media.filter { !it.trashed && (!it.available || it.id !in readable) && (included == null || it.id in included) }
        }
        fun missing(item: MediaEntity, data: CatalogSnapshot): Boolean = !item.trashed && (!item.available ||
            data.membership.none { link -> link.mediaId == item.id && data.sources.any { it.id == link.sourceId && it.reachable } })
    }
    private fun original(id: String, data: CatalogSnapshot): MediaEntity = data.media.firstOrNull { it.id == id && missing(it, data) }
        ?: error(localized(R.string.text_the_reference_is_already_available_or_relinked_r_a58a41, "Il riferimento è già disponibile o è stato ricollegato. Aggiorna la lista."))
    suspend fun preview(id: String, target: IncomingFile, canPersist: Boolean): RelinkCandidate = withContext(Dispatchers.IO) {
        require(canPersist && DocumentsContract.isDocumentUri(context, target.uri.toUri())) { localized(R.string.text_choose_a_linkable_document_in_the_android_picker_96d1f0, "Scegli un documento collegabile dal selettore Android") }
        val data = catalog.readSnapshot(); val old = original(id, data)
        require(old.kind == target.kind) { localized(R.string.text_choose_the_same_content_type_as_the_original_ref_53b10c, "Scegli lo stesso tipo di contenuto del riferimento originale") }
        IncomingMedia.readable(context, target)
        RelinkCandidate(id, target, LibraryBackup.fingerprint(data), old.name, old.size, old.modified, data.albumsById[old.albumId]?.path.orEmpty())
    }
    suspend fun confirm(candidate: RelinkCandidate): String = withContext(Dispatchers.IO) { catalog.writer.withLock {
        val before = catalog.readSnapshot()
        require(LibraryBackup.fingerprint(before) == candidate.baseline) { localized(R.string.text_the_library_changed_choose_the_file_again_to_ref_81ccf1, "La libreria è cambiata: scegli nuovamente il file per aggiornare l'anteprima") }
        require(catalog.dao.operations().none { !FileOperations.terminal(it.phase) }) { localized(R.string.text_finish_file_operations_before_relinking_544ffb, "Concludi le operazioni sui file prima del ricollegamento") }
        val old = original(candidate.oldId, before)
        require(old.kind == candidate.target.kind) { localized(R.string.text_different_file_type_75990a, "Tipo del file differente") }
        DocumentLinks(catalog).linkLocked(candidate.target, true) { canonical -> mergeLocked(listOf(old to canonical)) }
    } }

    /** Caller owns writer + transaction. One snapshot and one metadata pass for an entire batch. */
    internal suspend fun mergeLocked(pairs: List<Pair<MediaEntity, String>>) {
        val moved = pairs.filter { it.first.id != it.second }
        if (moved.isEmpty()) return
        val map = moved.associate { it.first.id to it.second }; require(map.size == moved.size)
        require(map.values.none { it in map }) { localized(R.string.text_circular_matches_choose_one_reference_per_file_e1f63c, "Corrispondenze circolari: scegli un solo riferimento per file") }
        val data = catalog.readSnapshot(); val dao = catalog.dao
        val prior = moved.associate { it.second to it.first }; require(prior.size == moved.size) { localized(R.string.text_multiple_references_chosen_for_the_same_file_64dc75, "Più riferimenti scelti per lo stesso file") }
        moved.forEach { (old, id) -> val current = data.byId.getValue(id); require(current.kind == old.kind && current.available && !current.trashed) }
        fun remap(id: String?) = map[id] ?: id
        val media = data.media.filter { it.id !in map }.map { current ->
            val old = prior[current.id]
            current.copy(favorite = current.favorite || old?.favorite == true,
                favoriteAddedAt = if (current.favorite) current.favoriteAddedAt else old?.favoriteAddedAt ?: 0,
                videoPosition = if (current.videoPosition == 0L) old?.videoPosition ?: 0L else current.videoPosition,
                videoPositionAt = if (current.videoPosition == 0L) old?.videoPositionAt ?: current.videoPositionAt else current.videoPositionAt,
                artworkMediaId = remap(current.artworkMediaId))
        }
        dao.saveMedia(media.filter { it != data.byId[it.id] })
        val oldByDestination = moved.groupBy { data.byId.getValue(it.second).albumId }
        dao.saveAlbums(data.albums.map { album ->
            val oldAlbums = oldByDestination[album.id].orEmpty().map { data.albumsById.getValue(it.first.albumId) }
            album.copy(coverMediaId = remap(album.coverMediaId), hidden = album.hidden || oldAlbums.any { it.hidden },
                systemExcluded = album.systemExcluded || oldAlbums.any { it.systemExcluded })
        }.filter { it != data.albumsById[it.id] })
        val ancestors = data.ancestry.groupBy { it.albumId }
        dao.saveAncestors(moved.flatMap { (old, id) -> ancestors[old.albumId].orEmpty().map { it.copy(albumId = data.byId.getValue(id).albumId) } }.distinct())
        val affected = data.collectionItems.filter { it.mediaId in map }.map { it.collectionId }.toSet()
        val groups = data.collectionItems.groupBy { it.collectionId }
        data.collections.filter { it.coverMediaId in map }.forEach { dao.saveCollection(it.copy(coverMediaId = remap(it.coverMediaId))) }
        for (id in affected) {
            val next = groups[id].orEmpty().sortedBy { it.position }.map { it.copy(mediaId = remap(it.mediaId)!!) }
                .distinctBy { it.mediaId }.mapIndexed { i, link -> link.copy(position = i) }
            dao.clearCollection(id); dao.saveCollectionItems(next)
        }
        val choices = data.artworkChoices.associate { it.target to it.copy(coverMediaId = remap(it.coverMediaId)) }.toMutableMap()
        val liveAudioTargets = media.filter { it.kind == "AUDIO" }.map(::musicAlbumTarget).toSet()
        for ((old, id) in moved) {
            val current = data.byId.getValue(id)
            fun inherit(from: String, to: String) { choices[from]?.takeIf { from != to && to !in choices }?.let { choices[to] = it.copy(target = to) } }
            val oldTrack = ArtworkOrders.track(old.id); val newTrack = ArtworkOrders.track(id)
            inherit(oldTrack, newTrack); choices.remove(oldTrack)
            inherit(ArtworkOrders.folder(old.albumId), ArtworkOrders.folder(current.albumId))
            if (old.kind == "AUDIO") {
                val from = musicAlbumTarget(old); val to = musicAlbumTarget(current)
                inherit(from, to); if (from != to && from !in liveAudioTargets) choices.remove(from)
            }
        }
        data.artworkChoices.filter { it.target !in choices }.forEach { dao.deleteArtworkChoice(it.target) }
        choices.values.filter { it != data.artworkByTarget[it.target] }.forEach { dao.saveArtworkChoice(it) }
        map.keys.toList().chunked(500).forEach { dao.clearMemberships(it); dao.deleteMediaBatch(it) }
        dao.saveRelinks(map.map { MediaRelink(it.key, it.value) } + data.mediaLinks.filter { it.mediaId in map }.map { it.copy(mediaId = map.getValue(it.mediaId)) })
        val remaining = dao.membership().map { it.sourceId }.toSet()
        data.sources.filter { it.restoreUri != null && it.id !in remaining }.forEach {
            dao.saveSource(it.copy(enabled = false, error = localized(R.string.text_references_relinked_backup_source_retained_923cab, "Riferimenti ricollegati; sorgente del backup conservata")))
        }
    }
}
