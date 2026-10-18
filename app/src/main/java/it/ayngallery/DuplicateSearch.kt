package it.ayngallery

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

data class DuplicateScope(val albums: Set<String> = emptySet(), val media: Set<String> = emptySet())
data class DuplicateFile(val media: MediaEntity, val path: String, val digest: FileDigest) {
    fun current(data: CatalogSnapshot): MediaEntity? = data.byId[media.id]?.takeIf {
        data.usable(it) && it.uri == media.uri && it.documentKey == media.documentKey && it.name == media.name &&
            !DocumentVersions.changed(media.size, media.modified, it.size, it.modified)
    }
}
data class DuplicateGroup(val digest: FileDigest, val files: List<DuplicateFile>) {
    val id get() = "${digest.bytes}:${digest.sha256}"
    val potentialBytes get() = if (digest.bytes > Long.MAX_VALUE / (files.size - 1).coerceAtLeast(1)) Long.MAX_VALUE else digest.bytes * (files.size - 1)
}
data class DuplicateProgress(val phase: String = "IDLE", val checked: Int = 0, val total: Int = 0, val bytes: Long = 0,
    val skipped: Int = 0, val groups: List<DuplicateGroup> = emptyList(), val notice: String = "", val startedAt: Long = 0,
    val scope: DuplicateScope = DuplicateScope()) {
    val active get() = phase in setOf("PREPARING", "RUNNING", "PAUSED", "CANCELLING")
}

/** One user-started, read-only search shared by both panels. Results are observations, never deletion authority. */
class DuplicateSearch(private val catalog: Catalog, private val preferences: PreferenceStore, private val session: GallerySession) {
    private val marker = catalog.context.getSharedPreferences("duplicate_search_v1", Context.MODE_PRIVATE)
    private data class Control(val paused: Boolean = false, val gameConsent: Boolean = false)
    private val control = MutableStateFlow(Control())
    private var job: Job? = null
    val state = MutableStateFlow(DuplicateProgress(phase = if (marker.getString("phase", "IDLE") in setOf("PREPARING", "RUNNING", "PAUSED", "CANCELLING")) "INTERRUPTED" else "IDLE",
        notice = if (marker.getString("phase", "IDLE") != "IDLE") localized(R.string.text_the_previous_search_is_not_resumed_automatically_7b96aa, "La ricerca precedente non viene ripresa automaticamente. Avvia una nuova verifica dei file.") else ""))
    init {
        catalog.scope.launch { catalog.snapshot.collect { data ->
            if (data.initialized) state.update { report -> report.copy(groups = liveGroups(report.groups, data)) }
        } }
    }
    fun pause() { control.update { it.copy(paused = true) } }
    fun resume(allowGame: Boolean = false) { control.update { it.copy(paused = false, gameConsent = it.gameConsent || allowGame) } }
    @Synchronized fun cancel() { if (job?.isActive == true) { state.update { it.copy(phase = "CANCELLING", notice = localized(R.string.text_cancellation_requested_a58dd2, "Annullamento richiesto…")) }; job?.cancel() } }
    @Synchronized private fun finished(phase: String, notice: String) {
        job = null
        state.update { it.copy(phase = phase, notice = notice) }
    }
    @Synchronized fun start(scope: DuplicateScope = DuplicateScope(), allowGame: Boolean = false): Boolean {
        if (job?.isActive == true) return false
        require(scope.albums.isEmpty() || scope.media.isEmpty())
        control.value = Control(gameConsent = allowGame)
        state.value = DuplicateProgress("PREPARING", notice = localized(R.string.text_reading_file_sizes_1fccaa, "Lettura delle dimensioni…"), startedAt = System.currentTimeMillis(), scope = scope)
        job = catalog.scope.launch(start = CoroutineStart.LAZY) {
            var phase = "COMPLETED"
            var notice = localized(R.string.text_search_finished_no_files_were_changed_006f53, "Ricerca conclusa. Nessun file è stato modificato.")
            try {
                check(marker.edit().putString("phase", "PREPARING").commit()) { localized(R.string.text_search_state_not_saved_ca2a23, "Stato ricerca non salvato") }
                search(scope)
            } catch (cancelled: CancellationException) {
                phase = "CANCELLED"; notice = localized(R.string.text_search_canceled_results_are_partial_files_remain_3c838c, "Ricerca annullata. I risultati sono parziali; i file restano intatti.")
                throw cancelled
            } catch (_: Exception) {
                phase = "FAILED"; notice = localized(R.string.text_search_interrupted_access_or_provider_unavailabl_020715, "Ricerca interrotta. Accesso o provider non disponibili; nessun file è stato modificato.")
            } finally { withContext(NonCancellable + Dispatchers.IO) {
                if (!marker.edit().putString("phase", phase).commit()) notice += localized(R.string.text_search_state_not_retained_e21aac, " Stato della ricerca non conservato.")
                finished(phase, notice)
            } }
        }
        job!!.start()
        return true
    }
    private suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        fun reason() = when {
            control.value.paused -> localized(R.string.text_paused_by_you_b54dd5, "Sospesa da te")
            preferences.state.value.gameMode && !control.value.gameConsent -> localized(R.string.text_paused_for_game_mode_resume_only_if_you_want_to_e0c9cb, "Sospesa per modalità Gioco · riprendi solo se vuoi avviare la lettura")
            session.visiblePanels.value == 0 -> localized(R.string.text_paused_while_the_gallery_is_not_visible_3b5e35, "Sospesa mentre la galleria non è visibile")
            else -> ""
        }
        val blocked = reason()
        if (blocked.isEmpty()) return
        state.update { it.copy(phase = "PAUSED", notice = blocked) }
        combine(control, preferences.state, session.visiblePanels) { c, p, visible -> when {
            c.paused -> localized(R.string.text_paused_by_you_b54dd5, "Sospesa da te")
            p.gameMode && !c.gameConsent -> localized(R.string.text_paused_for_game_mode_resume_only_if_you_want_to_e0c9cb, "Sospesa per modalità Gioco · riprendi solo se vuoi avviare la lettura")
            visible == 0 -> localized(R.string.text_paused_while_the_gallery_is_not_visible_3b5e35, "Sospesa mentre la galleria non è visibile")
            else -> ""
        } }.first { reason ->
            if (reason.isNotEmpty()) state.update { it.copy(phase = "PAUSED", notice = reason) }
            reason.isEmpty()
        }
        state.update { if (it.phase == "PAUSED") it.copy(phase = "RUNNING", notice = localized(R.string.text_reading_contents_b436b3, "Lettura del contenuto…")) else it }
    }
    private fun liveGroups(groups: List<DuplicateGroup>, data: CatalogSnapshot) = groups.mapNotNull { group ->
        group.files.filter { it.current(data) != null }.takeIf { it.size > 1 }?.let { group.copy(files = it) }
    }
    private suspend fun search(scope: DuplicateScope) {
        val initial = catalog.readSnapshot()
        val includedAlbums = scope.albums + initial.ancestry.filter { it.ancestorId in scope.albums }.map { it.albumId }
        val selected = initial.media.filter { initial.usable(it) && when {
            scope.media.isNotEmpty() -> it.id in scope.media
            scope.albums.isNotEmpty() -> it.albumId in includedAlbums
            else -> true
        } }.distinctBy { it.documentKey }
        val documents = FileDocuments(catalog.context)
        val candidates = mutableListOf<Pair<MediaEntity, DocumentInfo>>()
        state.update { it.copy(total = selected.size) }
        for (item in selected) {
            checkpoint()
            try {
                val data = catalog.readMediaSnapshot(item.id)
                val current = data.byId[item.id]?.takeIf { data.usable(it) && it.uri == item.uri && it.documentKey == item.documentKey } ?: error(localized(R.string.text_unavailable_ce1391, "Non disponibile"))
                val info = documents.stat(catalog.uri(current, data))
                require(!DocumentVersions.changed(current.size, current.modified, info.size, info.modified) && current.name == info.name)
                candidates.add(current to info)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(skipped = it.skipped + 1) } }
        }
        // Unknown provider sizes can match any bucket; hash all known sizes when an unknown is present.
        val unknown = candidates.any { it.second.size < 0 }
        val sizes = candidates.groupingBy { it.second.size }.eachCount()
        val hashCandidates = candidates.filter { unknown || sizes.getValue(it.second.size) > 1 }
        state.update { it.copy(phase = "RUNNING", checked = 0, total = hashCandidates.size, notice = localized(R.string.text_reading_contents_b436b3, "Lettura del contenuto…")) }
        val groups = linkedMapOf<FileDigest, MutableList<DuplicateFile>>()
        var last = 0L
        var readBytes = 0L
        var progressAt = 0L
        suspend fun publish() {
            val data = catalog.readSnapshot()
            val found = groups.filterValues { it.size > 1 }.map { (hash, files) ->
                DuplicateGroup(hash, files.sortedWith(compareBy(MediaNames.natural) { it.media.name }).toList())
            }.sortedWith(compareByDescending<DuplicateGroup> { it.potentialBytes }.thenBy { it.id })
            state.update { it.copy(groups = liveGroups(found, data)) }
        }
        for ((item, info) in hashCandidates) {
            checkpoint()
            try {
                val file = fingerprint(item, info) { count ->
                    checkpoint()
                    readBytes = Math.addExact(readBytes, count.toLong())
                    if (SystemClock.elapsedRealtime() - progressAt >= 250) {
                        state.update { it.copy(bytes = readBytes) }; progressAt = SystemClock.elapsedRealtime()
                    }
                }
                groups.getOrPut(file.digest) { mutableListOf() }.add(file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(skipped = it.skipped + 1) } }
            state.update { it.copy(checked = it.checked + 1, bytes = readBytes) }
            if (SystemClock.elapsedRealtime() - last >= 250) { publish(); last = SystemClock.elapsedRealtime() }
        }
        publish()
    }
    private suspend fun fingerprint(item: MediaEntity, expected: DocumentInfo? = null, progress: suspend (Int) -> Unit = {}): DuplicateFile {
        val data = catalog.readMediaSnapshot(item.id)
        val current = data.byId[item.id]?.takeIf { data.usable(it) && it.uri == item.uri && it.documentKey == item.documentKey && it.name == item.name }
            ?: throw SourceChanged()
        val documents = FileDocuments(catalog.context)
        val uri = catalog.uri(current, data)
        val before = documents.stat(uri)
        require(before.name == current.name && !DocumentVersions.changed(current.size, current.modified, before.size, before.modified))
        if (expected != null && (before.name != expected.name || DocumentVersions.changed(expected.size, expected.modified, before.size, before.modified))) throw SourceChanged()
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        documents.input(uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                progress(0)
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytes = Math.addExact(bytes, count.toLong()); digest.update(buffer, 0, count); progress(count)
            }
        }
        val after = documents.stat(uri)
        if (after.name != before.name || DocumentVersions.changed(before.size, before.modified, after.size, after.modified) ||
            (before.size >= 0 && before.size != bytes)) throw SourceChanged()
        val fresh = catalog.readMediaSnapshot(item.id)
        val observed = current.copy(size = bytes, modified = before.modified)
        return DuplicateFile(observed, data.albumsById[current.albumId]?.path ?: localized(R.string.text_authorized_folder_739100, "Cartella autorizzata"),
            FileDigest(bytes, digest.digest().joinToString("") { "%02x".format(it) })).also { require(it.current(fresh) != null) }
    }
    /** Explicit metadata transfer only; file deletion is always a separate, hash-guarded trash request. */
    suspend fun transferReferences(keep: DuplicateFile, discard: List<DuplicateFile>) = withContext(Dispatchers.IO) {
        require(discard.isNotEmpty() && discard.none { it.media.id == keep.media.id } && discard.all { it.digest == keep.digest })
        catalog.writer.withLock {
            val all = listOf(keep) + discard.distinctBy { it.media.id }
            for (file in all) require(fingerprint(file.media).digest == file.digest) { localized(R.string.text_content_changed_no_references_transferred_4ec39b, "Il contenuto è cambiato: nessun riferimento trasferito") }
            val data = catalog.readSnapshot()
            require(all.all { it.current(data) != null }) { localized(R.string.text_visibility_or_access_changed_06dec4, "Visibilità o accesso cambiati") }
            val ids = discard.map { it.media.id }.toSet()
            val affected = data.collectionItems.filter { it.mediaId in ids }.map { it.collectionId }.toSet()
            require(data.collections.none { it.id in affected && it.kind == "PLAYLIST" && keep.media.kind != "AUDIO" }) { localized(R.string.text_a_playlist_requires_an_audio_copy_77dd5c, "Una playlist richiede una copia audio") }
            catalog.db.withTransaction {
                if (data.byId.getValue(keep.media.id).favorite || ids.any { data.byId.getValue(it).favorite }) catalog.dao.favorite(keep.media.id, true)
                ids.forEach { catalog.dao.favorite(it, false) }
                affected.forEach { id ->
                    val next = data.collectionItems.filter { it.collectionId == id }.sortedBy { it.position }
                        .map { if (it.mediaId in ids) it.copy(mediaId = keep.media.id) else it }.distinctBy { it.mediaId }
                        .mapIndexed { index, link -> link.copy(position = index) }
                    catalog.dao.clearCollection(id); catalog.dao.saveCollectionItems(next)
                }
            }
        }
    }
}
