package it.ayngallery

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class FolderFile(val key: String, val uri: String, val path: String, val name: String, val mime: String,
    val kind: String, val size: Long, val modified: Long)
data class FolderReference(val id: String, val name: String, val path: String, val kind: String, val mime: String,
    val size: Long, val modified: Long, val excluded: Boolean)
data class FolderRelinkReview(val source: String, val originalRoot: String, val originalPath: String, val tree: String,
    val title: String, val role: String, val baseline: String, val files: List<FolderFile>, val references: List<FolderReference>) {
    fun encode(): String = JSONObject().put("v", 1).put("source", source).put("root", originalRoot).put("oldPath", originalPath)
        .put("tree", tree).put("title", title).put("role", role).put("baseline", baseline)
        .put("files", JSONArray(files.map { JSONArray(listOf(it.key, it.uri, it.path, it.name, it.mime, it.kind, it.size, it.modified)) }))
        .put("refs", JSONArray(references.map { JSONArray(listOf(it.id, it.name, it.path, it.kind, it.mime, it.size, it.modified, it.excluded)) }))
        .toString().also { require(it.toByteArray(Charsets.UTF_8).size <= LibraryBackup.MAX_BYTES) { localized(R.string.text_preview_too_large_choose_an_original_subfolder_c1fdcf, "Anteprima troppo grande: scegli una sottocartella originale") } }
    companion object {
        fun decode(raw: String): FolderRelinkReview {
            require(raw.toByteArray(Charsets.UTF_8).size <= LibraryBackup.MAX_BYTES)
            val j = JSONObject(raw); require(j.getInt("v") == 1)
            val tree = j.getString("tree"); FolderRelinking.tree(tree)
            val baseline = j.getString("baseline"); require(baseline.matches(Regex("[a-f0-9]{64}")))
            val files = j.getJSONArray("files"); val refs = j.getJSONArray("refs")
            require(files.length() <= 200_000 && refs.length() <= 200_000)
            fun JSONArray.text(i: Int, max: Int = 8192) = getString(i).also { require(it.length in 1..max && !it.contains('\u0000')) }
            val f = (0 until files.length()).map { files.getJSONArray(it).let { a ->
                require(a.length() == 8); val uri = Uri.parse(a.text(1)); val root = Uri.parse(tree)
                require(uri.authority == root.authority && DocumentsContract.isTreeUri(uri) && DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(root))
                FolderFile(a.text(0), uri.toString(), a.text(2), a.text(3, 1024), a.text(4, 256), a.text(5, 20), a.getLong(6), a.getLong(7)).also {
                    require(it.kind == MediaNames.kind(it.mime, it.name)?.name && it.size >= -1 && it.modified >= 0)
                    require(it.key == "${uri.authority}:${DocumentsContract.getDocumentId(uri)}")
                }
            } }
            val r = (0 until refs.length()).map { refs.getJSONArray(it).let { a ->
                require(a.length() == 8)
                FolderReference(a.text(0, 256), a.text(1, 1024), a.text(2), a.text(3, 20), a.text(4, 256), a.getLong(5), a.getLong(6), a.getBoolean(7)).also {
                    require(it.kind in setOf("IMAGE", "GIF", "VIDEO", "AUDIO") && it.size >= -1 && it.modified >= 0)
                }
            } }
            require(f.map { it.key }.distinct().size == f.size && r.map { it.id }.distinct().size == r.size)
            val role = j.getString("role"); require(role in setOf("VISUAL", "MUSIC", "MIXED"))
            return FolderRelinkReview(j.getString("source"), j.getString("root"), j.getString("oldPath"), tree, j.getString("title"), role, baseline, f, r)
        }
    }
}

/** Shared indexes avoid duplicating a potentially large same-name candidate list for every old reference. */
class FolderRelinkIndex(val review: FolderRelinkReview) {
    val files = review.files.associateBy { it.key }
    private val exact = review.files.groupBy { it.path to it.kind }
    private val named = review.files.groupBy { it.name to it.kind }
    fun candidates(old: FolderReference): List<FolderFile> = exact[old.path to old.kind].orEmpty().ifEmpty { named[old.name to old.kind].orEmpty() }
    fun unique(old: FolderReference): FolderFile? = exact[old.path to old.kind]?.singleOrNull()?.takeIf {
        old.size > 0 && it.size == old.size && old.mime == it.mime && !DocumentVersions.changed(old.size, old.modified, it.size, it.modified)
    }
    fun uniqueChoices(): Map<String, String> {
        val matches = review.references.mapNotNull { old -> unique(old)?.let { old.id to it.key } }
        val used = matches.groupingBy { it.second }.eachCount()
        return matches.filter { used[it.second] == 1 }.toMap()
    }
    companion object { fun label(path: String) = path.split('/').joinToString(" / ") { Uri.decode(it) } }
}

class FolderRelinking(private val catalog: Catalog,
    private val gameMode: () -> Boolean = { (catalog.context.applicationContext as? GalleryApp)?.preferences?.state?.value?.gameMode == true }) {
    private val context get() = catalog.context
    private fun allowed(consent: Boolean) { require(!gameMode() || consent) { localized(R.string.text_allow_relinking_in_game_mode_670757, "Consenti il ricollegamento durante la modalità Gioco") } }
    companion object {
        fun tree(raw: String): Uri = Uri.parse(raw).also {
            require(it.scheme == "content" && !it.authority.isNullOrBlank() && it.fragment == null && raw.length <= 8192 &&
                DocumentsContract.isTreeUri(it) && it == DocumentsContract.buildTreeDocumentUri(it.authority, DocumentsContract.getTreeDocumentId(it))) { localized(R.string.text_choose_a_folder_in_the_android_picker_354f26, "Scegli una cartella dal selettore Android") }
        }
        fun roots(data: CatalogSnapshot, source: String): List<AlbumEntity> {
            val sourceRow = data.sources.find { it.id == source } ?: return emptyList()
            val ids = data.albumMembership.filter { it.sourceId == source }.map { it.albumId }.toSet() +
                data.membership.filter { it.sourceId == source }.mapNotNull { data.byId[it.mediaId]?.albumId }
            val uri = Uri.parse(sourceRow.restoreUri ?: sourceRow.treeUri)
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val exact = data.albums.filter { it.id in ids && rootId != null && runCatching { DocumentsContract.getDocumentId(Uri.parse(it.documentUri)) }.getOrNull() == rootId }
            return exact.ifEmpty { data.albums.filter { it.id in ids && it.parentId !in ids } }.sortedWith(compareBy(MediaNames.natural) { it.path })
        }
    }
    suspend fun preview(sourceId: String, rootId: String, rawTree: String, canPersist: Boolean, consent: Boolean = false,
        progress: (Int) -> Unit = {}): FolderRelinkReview = withContext(Dispatchers.IO) {
        allowed(consent); require(canPersist) { localized(R.string.text_the_folder_must_allow_persistent_read_access_402191, "La cartella deve consentire accesso persistente in lettura") }
        val tree = tree(rawTree); require(tree.authority != context.packageName && !tree.authority!!.startsWith("${context.packageName}."))
        val initial = catalog.writer.withLock { val data = catalog.readSnapshot(); data to LibraryBackup.fingerprint(data, catalog.dao.comparisonSessions()) }
        val data = initial.first; val source = data.sources.find { it.id == sourceId && !it.singleDocument } ?: error(localized(R.string.text_source_unavailable_ea3980, "Sorgente non disponibile"))
        val root = data.albumsById[rootId] ?: error(localized(R.string.text_choose_the_original_folder_ab2296, "Scegli la cartella originale"))
        require(root.id in data.albumMembership.filter { it.sourceId == sourceId }.map { it.albumId }.toSet() ||
            data.membership.any { it.sourceId == sourceId && data.byId[it.mediaId]?.albumId == rootId })
        fun path(item: MediaEntity): String? {
            val parts = arrayListOf(Uri.encode(item.name)); var album: String? = item.albumId; val seen = hashSetOf<String>()
            while (album != rootId) {
                if (album == null || !seen.add(album) || seen.size > 512) return null
                val folder = data.albumsById[album] ?: return null; parts.add(Uri.encode(folder.name)); album = folder.parentId
            }
            return parts.asReversed().joinToString("/")
        }
        val references = MediaRelinking.missing(data, sourceId).mapNotNull { old -> path(old)?.let {
            FolderReference(old.id, old.name, it, old.kind, old.mime, old.size, old.modified, old.albumId in data.excludedAlbums)
        } }
        require(references.isNotEmpty()) { localized(R.string.text_no_missing_references_in_this_original_folder_749906, "Nessun riferimento mancante in questa cartella originale") }
        val documents = FileDocuments(context)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val title = documents.stat(rootUri).also { require(it.mime == DocumentsContract.Document.MIME_TYPE_DIR) }.name
        val files = arrayListOf<FolderFile>(); val visited = hashSetOf<String>(); val fileKeys = hashSetOf<String>()
        val queue = ArrayDeque<Pair<Uri, String>>(); queue.add(rootUri to ""); var examined = 0
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive(); allowed(consent)
            val (parent, prefix) = queue.removeFirst(); val key = DocumentsContract.getDocumentId(parent)
            if (!visited.add(key)) continue
            require(visited.size <= 50_000) { localized(R.string.text_too_many_folders_choose_an_original_subfolder_922de2, "Troppe cartelle: scegli una sottocartella originale") }
            val children = documents.children(parent, 200_000)
            examined += children.size; require(examined <= 200_000) { localized(R.string.text_too_many_documents_choose_an_original_subfolder_495209, "Troppi documenti: scegli una sottocartella originale") }
            progress(examined)
            if (children.any { it.name == ".nomedia" }) continue
            for (file in children) {
                currentCoroutineContext().ensureActive()
                if (file.name.startsWith(".ayngallery-")) continue
                require(file.name.isNotBlank() && file.name.length <= 1024)
                val path = prefix + Uri.encode(file.name)
                require(path.length <= 8192)
                if (file.mime == DocumentsContract.Document.MIME_TYPE_DIR) { queue.add(file.uri to "$path/"); continue }
                val kind = MediaNames.kind(file.mime, file.name) ?: continue
                val fileKey = "${file.uri.authority}:${DocumentsContract.getDocumentId(file.uri)}"
                if (fileKeys.add(fileKey)) files.add(FolderFile(fileKey, file.uri.toString(), path, file.name, file.mime, kind.name, file.size, file.modified))
            }
        }
        val existing = data.sources.find { it.treeUri == rawTree }
        require(existing?.enabled != false) { localized(R.string.text_re_enable_this_folder_in_sources_first_70d532, "Riattiva prima questa cartella nelle sorgenti") }
        val role = if (existing != null && existing.role != source.role) "MIXED" else source.role
        FolderRelinkReview(sourceId, rootId, root.path, rawTree, title, role, initial.second, files, references).also { FolderRelinkReview.decode(it.encode()) }
    }
    suspend fun confirm(review: FolderRelinkReview, selected: Map<String, String>, verified: Boolean, consent: Boolean = false): Map<String, String> = withContext(Dispatchers.IO) {
        allowed(consent); require(verified && selected.isNotEmpty()) { localized(R.string.text_check_matches_before_relinking_48b12f, "Verifica le corrispondenze prima di ricollegare") }
        require(selected.values.distinct().size == selected.size) { localized(R.string.text_choose_only_one_reference_for_each_file_659de7, "Scegli un solo riferimento per ciascun file") }
        val index = FolderRelinkIndex(review); val refs = review.references.associateBy { it.id }
        val targets = selected.map { (id, key) -> val old = refs[id] ?: error(localized(R.string.text_reference_not_in_preview_a3e66e, "Riferimento non presente nell'anteprima"))
            old to (index.candidates(old).find { it.key == key } ?: error(localized(R.string.text_match_not_in_preview_b32852, "Corrispondenza non presente nell'anteprima"))) }
        val tree = tree(review.tree); val documents = FileDocuments(context)
        fun check(file: FolderFile) {
            val fresh = documents.stat(Uri.parse(file.uri))
            require(fresh.name == file.name && fresh.mime == file.mime && fresh.size == file.size && fresh.modified == file.modified) { localized(R.string.text_a_file_changed_choose_the_folder_again_e4947d, "Un file è cambiato: scegli nuovamente la cartella") }
            documents.input(Uri.parse(file.uri)).use { require(it.read() >= 0) { localized(R.string.text_the_chosen_file_is_empty_or_unreadable_4ea2f0, "Il file scelto è vuoto o non leggibile") } }
        }
        catalog.writer.withLock {
            val before = catalog.readSnapshot()
            require(LibraryBackup.fingerprint(before, catalog.dao.comparisonSessions()) == review.baseline) { localized(R.string.text_the_library_changed_refresh_the_preview_by_choos_cc2201, "La libreria è cambiata: aggiorna l'anteprima scegliendo nuovamente la cartella") }
            require(catalog.dao.operations().none { !FileOperations.terminal(it.phase) }) { localized(R.string.text_finish_file_operations_before_relinking_544ffb, "Concludi le operazioni sui file prima del ricollegamento") }
            val missing = MediaRelinking.missing(before, review.source).associateBy { it.id }
            targets.forEach { (old, file) -> require(missing[old.id]?.kind == file.kind); check(file) }
            val priorRead = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
            val known = before.sources.find { it.treeUri == review.tree }
            require(known?.enabled != false) { localized(R.string.text_re_enable_this_folder_in_sources_first_70d532, "Riattiva prima questa cartella nelle sorgenti") }
            val source = known?.copy(role = review.role, reachable = true, error = null)
                ?: SourceEntity(UUID.randomUUID().toString(), review.tree, review.title, review.role)
            var acquired = false
            try {
                if (!priorRead) { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); acquired = true }
                require(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { localized(R.string.text_persistent_access_not_granted_17c407, "Accesso persistente non concesso") }
                catalog.db.withTransaction {
                    allowed(consent); catalog.dao.saveSource(source); SourceScanner(catalog).scan(source, maxEntries = 200_000, maxFolders = 50_000)
                    val data = catalog.readSnapshot(); val pairs = targets.map { (old, file) ->
                        check(file); val canonical = data.byDocumentKey[file.key]?.takeIf { it.available && !it.trashed && it.kind == old.kind } ?: error(localized(R.string.text_file_not_indexed_check_exclusions_and_access_e8ce8a, "File non indicizzato: verifica esclusioni e accesso"))
                        missing.getValue(old.id) to canonical.id
                    }
                    require(pairs.map { it.second }.distinct().size == pairs.size) { localized(R.string.text_several_matches_point_to_the_same_file_select_on_c691b2, "Più corrispondenze indicano lo stesso file: selezionane una sola") }
                    allowed(consent); MediaRelinking(catalog).mergeLocked(pairs)
                    pairs.associate { it.first.id to it.second }
                }
            } catch (failure: Exception) {
                if (acquired) runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catalog.progress.value = ScanProgress(error = localized(R.string.text_relinking_not_applied_choose_the_folder_again_184529, "Ricollegamento non applicato; scegli nuovamente la cartella"))
                throw failure
            }
        }
    }
}

/** Large review + choices stay private; an Activity saves only a UUID. */
class FolderRelinkDrafts(private val directory: File) {
    private fun file(id: String, choices: Boolean = false): File { require(UUID.fromString(id).toString() == id); return File(directory, "$id${if (choices) ".choices" else ""}.json") }
    private fun write(file: File, raw: String) { directory.mkdirs(); val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(raw.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) } catch (failure: Exception) { atomic.failWrite(output); throw failure } }
    fun create(review: FolderRelinkReview): String {
        val raw = review.encode(); FolderRelinkReview.decode(raw)
        directory.mkdirs(); directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]{36}(\\.choices)?\\.json")) && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val id = UUID.randomUUID().toString(); write(file(id), raw); check(file(id).readText() == raw); return id
    }
    fun read(id: String): FolderRelinkReview = file(id).let { require(it.length() in 1..LibraryBackup.MAX_BYTES.toLong()); FolderRelinkReview.decode(it.readText()) }
    fun choices(id: String): Map<String, String> = file(id, true).let { if (!it.exists()) emptyMap() else {
        require(it.length() <= 16 * 1024 * 1024); val json = JSONObject(it.readText()); require(json.length() <= 200_000)
        json.keys().asSequence().associateWith { key -> json.getString(key).also { require(key.length in 1..256 && it.length in 1..8192) } }
    } }
    fun choices(id: String, value: Map<String, String>) { val raw = JSONObject(value).toString(); require(raw.toByteArray().size <= 16 * 1024 * 1024); write(file(id, true), raw) }
    fun discard(id: String) { file(id).delete(); file(id, true).delete() }
}
