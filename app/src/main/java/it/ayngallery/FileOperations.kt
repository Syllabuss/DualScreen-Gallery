package it.ayngallery

import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.room.withTransaction
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DocumentInfo(val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long, val flags: Int)
data class FileDigest(val bytes: Long, val sha256: String)

class FileDocuments(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
    fun stat(uri: Uri): DocumentInfo {
        require(uri.scheme == "content")
        if (DeviceGallery.isMedia(uri)) return resolver.query(uri,
            arrayOf("_display_name", "mime_type", "_size", "date_modified"), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException(localized(R.string.text_media_unavailable_91dca9, "Media non disponibile"))
            DocumentInfo(uri, cursor.getString(0).orEmpty(), cursor.getString(1) ?: "application/octet-stream",
                if (cursor.isNull(2)) -1L else cursor.getLong(2), if (cursor.isNull(3)) 0L else cursor.getLong(3) * 1000L, 0)
        } ?: throw IOException(localized(R.string.text_device_gallery_unavailable_4a3bc5, "Galleria del dispositivo non disponibile"))
        return resolver.query(uri, columns, Bundle.EMPTY, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException(localized(R.string.text_file_not_found_cf1869, "File non trovato"))
            DocumentInfo(uri, cursor.getString(1) ?: "", cursor.getString(2) ?: "application/octet-stream",
                if (cursor.isNull(3)) -1 else cursor.getLong(3), if (cursor.isNull(4)) 0 else cursor.getLong(4),
                if (cursor.isNull(5)) 0 else cursor.getInt(5))
        } ?: throw IOException(localized(R.string.text_provider_unavailable_f52c42, "Provider non disponibile"))
    }
    fun children(parent: Uri, limit: Int = Int.MAX_VALUE): List<DocumentInfo> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        return resolver.query(uri, columns, Bundle.EMPTY, null)?.use { cursor -> buildList {
            while (cursor.moveToNext()) { require(size < limit) { localized(R.string.text_too_many_documents_in_the_folder_dc4e96, "Troppi documenti nella cartella") }; add(DocumentInfo(DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)),
                cursor.getString(1) ?: "", cursor.getString(2) ?: "application/octet-stream",
                if (cursor.isNull(3)) -1 else cursor.getLong(3), if (cursor.isNull(4)) 0 else cursor.getLong(4),
                if (cursor.isNull(5)) 0 else cursor.getInt(5))) }
        } } ?: throw IOException(localized(R.string.text_folder_unavailable_1f6e37, "Cartella non disponibile"))
    }
    fun create(parent: Uri, mime: String, name: String): Uri =
        DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException(localized(R.string.text_creation_denied_b143ba, "Creazione negata"))
    fun rename(uri: Uri, name: String): Uri =
        DocumentsContract.renameDocument(resolver, uri, name) ?: throw IOException(localized(R.string.text_publishing_denied_76e1f0, "Pubblicazione negata"))
    fun delete(uri: Uri) { check(DocumentsContract.deleteDocument(resolver, uri)) { localized(R.string.text_deletion_denied_e0b43a, "Eliminazione negata") } }
    fun input(uri: Uri): InputStream = resolver.openInputStream(uri) ?: throw IOException(localized(R.string.text_file_unreadable_2af756, "File non leggibile"))
    fun output(uri: Uri): OutputStream = resolver.openOutputStream(uri, "wt") ?: throw IOException(localized(R.string.text_file_not_writable_01d31c, "File non scrivibile"))
}

object FileNames {
    fun validateBase(name: String) {
        require(name.isNotBlank() && name == name.trim() && name.length <= 180 && name !in setOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it.code < 32 } && !name.startsWith(".ayngallery-")) { localized(R.string.text_name_not_allowed_513891, "Nome non ammesso") }
    }
    fun validate(name: String, original: String) {
        validateBase(name)
        require(name.substringAfterLast('.', "").equals(original.substringAfterLast('.', ""), true)) { localized(R.string.text_keep_the_original_extension_renaming_does_not_co_5220af, "Mantieni l'estensione originale: rinominare non converte il file") }
    }
    fun available(name: String, existing: Set<String>, keepBoth: Boolean): String {
        val names = existing.map { it.lowercase() }.toSet()
        if (name.lowercase() !in names) return name
        require(keepBoth) { localized(R.string.text_name_already_taken_choose_another_name_or_keep_b_ec9d46, "Nome già occupato. Scegli un altro nome o Mantieni entrambe.") }
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        for (i in 1..10_000) {
            val candidate = "${name.take(dot)} ($i)${name.drop(dot)}"
            if (candidate.lowercase() !in names) return candidate
        }
        throw IOException(localized(R.string.text_too_many_conflicting_names_76409a, "Troppi nomi in conflitto"))
    }
}

class OperationCancelled : IOException(localized(R.string.text_operation_canceled_original_preserved_e6b590, "Operazione annullata; originale conservato"))
class SourceChanged : IOException(localized(R.string.text_the_source_changed_the_original_will_not_be_dele_1bfd44, "La sorgente è cambiata. L'originale non viene eliminato; rivaluta il file corrente."))

/** Journal precedes every side effect. Provider IO and Room are not one atomic transaction. */
class FileOperations(private val catalog: Catalog, private val releaseMedia: suspend (String) -> Unit = {},
                     private val clock: () -> TrashClockSample = { TrashClockSample.now(catalog.context) },
                     private val deletePrivate: (File) -> Boolean = { it.delete() }, private val gameMode: () -> Boolean = { false }) {
    private val context = catalog.context
    private val dao = catalog.dao
    private val documents = FileDocuments(context)
    val operations = dao.observeOperations().stateIn(catalog.scope, SharingStarted.Eagerly, emptyList())
    private val receivingFtp = mutableSetOf<String>() // Guarded by the catalog writer, including startup recovery.

    internal suspend fun createFtpDirectory(parent: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        FileNames.validateBase(name)
        val id = UUID.randomUUID().toString()
        record(FileOperation(id, id, "FTP_MKDIR", parent.toString(), "ftp-directory:$id", "", name,
            DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, parent.toString(), null, name, false, id))
        execute(id)
        val op = requireNotNull(dao.operation(id))
        check(op.phase == "COMPLETED") { op.message ?: "Folder creation failed" }
        Uri.parse(op.publishedUri)
    }
    private suspend fun publishFtpDirectory(initial: FileOperation) {
        var op = initial
        if (op.publishedUri == null) {
            val parent = Uri.parse(op.destinationParent)
            require(documents.stat(parent).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
            FileNames.available(op.requestedName, documents.children(parent).map { it.name }.toSet(), false)
            op = record(op.copy(phase = "CREATING"))
            val result = documents.create(parent, DocumentsContract.Document.MIME_TYPE_DIR, op.requestedName)
            op = record(op.copy(phase = "PUBLISHED", publishedUri = result.toString()))
        }
        val result = documents.stat(Uri.parse(op.publishedUri))
        require(result.name == op.requestedName && result.mime == DocumentsContract.Document.MIME_TYPE_DIR)
        record(op.copy(phase = "COMPLETED", finalName = result.name))
    }

    /** Network EOF is separate from closing a stream: failed FTP transfers also close their streams. */
    internal suspend fun beginFtpReceive(parent: Uri, name: String): Pair<String, OutputStream> = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            FileNames.validateBase(name)
            require(documents.stat(parent).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
            FileNames.available(name, documents.children(parent).map { it.name }.toSet(), false)
            val id = UUID.randomUUID().toString()
            check(directory.isDirectory || directory.mkdirs())
            val staged = privateFile("$id.part")
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
            record(FileOperation(id, id, "FTP_RECEIVE", staged.toURI().toString(), "ftp:$id", "", name,
                mime, -1, 0, parent.toString(), null, name, false, id, phase = "RECEIVING"))
            try {
                check(StatFs(directory.path).availableBytes >= FtpUploadOutputStream.RESERVED_BYTES) { "Insufficient internal storage for FTP upload" }
                val output = FtpUploadOutputStream(staged.outputStream(), { StatFs(directory.path).availableBytes })
                receivingFtp += id; id to output
            }
            catch (error: Exception) { record(dao.operation(id)!!.copy(phase = "FAILED", message = error.message)); throw error }
        }
    }
    internal suspend fun finishFtpReceive(id: String): Uri = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val op = requireNotNull(dao.operation(id))
            require(op.kind == "FTP_RECEIVE" && op.phase == "RECEIVING")
            val hash = digest(privateFile("$id.part").inputStream(), id = id)
            record(op.copy(phase = "STAGED", digest = hash.sha256, bytes = hash.bytes))
            receivingFtp -= id
        }
        execute(id)
        val op = requireNotNull(dao.operation(id))
        check(op.phase == "COMPLETED") { op.message ?: "FTP transfer was not published" }
        Uri.parse(op.publishedUri)
    }
    internal suspend fun abortFtpReceive(id: String) = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val op = dao.operation(id) ?: return@withLock
            require(op.kind == "FTP_RECEIVE")
            receivingFtp -= id
            // Verified data is retained if publication failed; never erase a recoverable upload.
            if (op.digest == null && op.publishedUri == null) {
                val staged = privateFile("$id.part")
                if (staged.exists()) check(deletePrivate(staged))
                record(op.copy(phase = "CANCELLED"))
            }
        }
    }
    private suspend fun publishFtpReceive(initial: FileOperation) {
        var op = initial
        require(op.digest != null) { "Incomplete FTP transfer cannot be published" }
        val parent = Uri.parse(op.destinationParent)
        val staged = privateFile("${op.id}.part")
        if (op.publishedUri == null) verified(op, staged.toURI().toString())
        if (op.publishedUri == null && op.finalName != null) {
            val temporaryExists = op.temporaryUri?.let { runCatching { documents.stat(Uri.parse(it)) }.isSuccess } == true
            if (!temporaryExists) {
                val result = documents.children(parent).single { it.name == op.finalName }
                verified(op, result.uri.toString())
                op = record(op.copy(publishedUri = result.uri.toString(), phase = "PUBLISHED"))
            }
        }
        if (op.publishedUri == null) {
            if (op.temporaryUri == null) {
                FileNames.available(op.requestedName, documents.children(parent).map { it.name }.toSet(), false)
                val temporaryName = ".ayngallery-${op.id}.part"
                op = record(op.copy(phase = "CREATING"))
                val candidates = documents.children(parent).filter { it.name == temporaryName }
                val temporary = if (candidates.isEmpty()) documents.create(parent, op.mime, temporaryName)
                    else candidates.single().uri
                require(documents.stat(temporary).flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0)
                op = record(op.copy(temporaryUri = temporary.toString(), phase = "COPYING"))
            }
            val temporary = Uri.parse(op.temporaryUri)
            // bytes is the immutable verification length; transfer progress must not replace it on a failed copy.
            val copied = FtpUploadOutputStream(documents.output(temporary), { StatFs(directory.path).availableBytes })
                .use { output -> digest(staged.inputStream(), output, op.id) }
            require(copied == FileDigest(op.bytes, op.digest!!))
            verified(op, temporary.toString())
            FileNames.available(op.requestedName, documents.children(parent).map { it.name }.toSet(), false)
            checkCancel(op.id)
            withContext(NonCancellable) {
                op = record(op.copy(phase = "PUBLISHING", finalName = op.requestedName))
                val published = documents.rename(temporary, op.requestedName)
                op = record(op.copy(publishedUri = published.toString(), phase = "PUBLISHED"))
            }
        }
        verified(op, op.publishedUri!!)
        require(documents.stat(Uri.parse(op.publishedUri)).name == op.requestedName)
        // A failed private cleanup is retryable, with the already verified published URI in the journal.
        if (staged.exists()) check(deletePrivate(staged))
        record(op.copy(phase = "COMPLETED"))
    }
    val trash = dao.observeTrash().stateIn(catalog.scope, SharingStarted.Eagerly, emptyList())
    val directory = File(context.filesDir, "trash")
    companion object {
        const val SEVEN_DAYS = 7 * 24 * 60 * 60 * 1000L
        fun export(kind: String) = kind in setOf("EXPORT_IMAGE", "EXPORT_VIDEO", "EXPORT_AUDIO")
        fun terminal(phase: String) = phase in setOf("COMPLETED", "CANCELLED", "COPY_ONLY", "SKIPPED", "ORIGINAL_ONLY", "RESTORED_WITH_BACKUP")
    }
    suspend fun enqueueImport(target: IncomingFile, albumId: String, name: String = target.proposedName,
        keepBoth: Boolean = true, allowGame: Boolean = false, start: Boolean = true): String = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            FileNames.validate(name, target.proposedName)
            dao.operations().firstOrNull { it.kind == "IMPORT" && it.mediaId == target.id && it.phase != "SKIPPED" }?.let { old ->
                require(old.destinationAlbumId == albumId && old.requestedName == name && ImportRecipe.decode(old.recipe!!).target == target) {
                    localized(R.string.text_the_selection_already_has_a_different_operation_5c4a12, "La selezione ha già un'operazione diversa: risolvila prima in Copie ed esportazioni")
                }
                return@withLock old.id // Rotation/process recovery cannot enqueue the same confirmed file twice.
            }
            catalog.rememberAlbumsLocked(listOf(albumId))
            val parent = importParent(target, albumId, catalog.readSnapshot())
            require(IncomingMedia.unchanged(target, IncomingMedia.stat(context, Uri.parse(target.uri)))) { localized(R.string.text_the_chosen_file_changed_reopen_the_picker_65e401, "Il file scelto è cambiato: riapri il selettore") }
            documents.input(Uri.parse(target.uri)).use { require(it.read() >= 0) { localized(R.string.text_empty_file_7a1d6c, "File vuoto") } }
            require(dao.operations().none { it.kind == "IMPORT" && it.sourceUri == target.uri && !terminal(it.phase) }) {
                localized(R.string.text_this_file_has_an_import_to_resolve_in_file_opera_3498d1, "Questo file ha già un'importazione da risolvere in Copie ed esportazioni")
            }
            val id = UUID.randomUUID().toString()
            withContext(NonCancellable) {
                record(FileOperation(id, target.id, "IMPORT", target.uri, "incoming:${target.id}", "", target.name,
                    target.mime, target.size, target.modified, parent.toString(), albumId, name, keepBoth, UUID.randomUUID().toString(),
                    recipe = ImportRecipe(target, allowGame).encode()))
                if (start) schedule(id)
            }; id
        }
    }
    suspend fun enqueueImports(targets: List<IncomingFile>, albumId: String, names: Map<String, String> = emptyMap(),
        keepBoth: Boolean = true, allowGame: Boolean = false, start: Boolean = true): List<String> {
        require(targets.size in 1..128 && targets.map { it.id }.distinct().size == targets.size)
        return targets.map { target ->
            try { enqueueImport(target, albumId, names[target.id] ?: target.proposedName, keepBoth, allowGame, start) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                record(FileOperation(UUID.randomUUID().toString(), target.id, "IMPORT", target.uri, "incoming:${target.id}", "", target.name,
                    target.mime, target.size, target.modified, null, albumId, names[target.id] ?: target.proposedName, keepBoth, UUID.randomUUID().toString(),
                    phase = "SKIPPED", message = localized(R.string.text_not_started_1_s_4866a0, "Non avviata: %1\$s", error.message ?: localized(R.string.text_file_unavailable_6dcc4f, "file non disponibile")), recipe = ImportRecipe(target, allowGame).encode())).id
            }
        }
    }
    private fun importFile(op: FileOperation, extension: String): File {
        require(op.kind == "IMPORT" && UUID.fromString(op.id).toString() == op.id && extension in setOf("part", "bin"))
        val root = File(context.filesDir, "imports").canonicalFile
        return File(root, "${op.id}.$extension").canonicalFile.also { require(it.parentFile == root) }
    }
    private fun cleanupImport(op: FileOperation) {
        for (extension in listOf("part", "bin")) importFile(op, extension).let { file ->
            if (file.exists()) check(deletePrivate(file)) { localized(R.string.text_copy_imported_or_canceled_private_temporary_file_cc6d03, "Copia importata o annullata; temporaneo privato conservato. Riprendi la pulizia.") }
        }
    }
    private suspend fun stageImport(initial: FileOperation): FileOperation {
        var op = initial
        val recipe = ImportRecipe.decode(op.recipe ?: throw IOException(localized(R.string.text_import_data_missing_774e96, "Dati dell'importazione mancanti")))
        require(recipe.target.uri == op.sourceUri && recipe.target.id == op.mediaId && op.sourceKey == "incoming:${op.mediaId}" && recipe.target.mime == op.mime)
        val part = importFile(op, "part"); val committed = importFile(op, "bin")
        if (committed.exists()) {
            require(recipe.hash != null && digest(committed.inputStream(), id = op.id) == FileDigest(recipe.bytes!!, recipe.hash)) { localized(R.string.text_private_copy_unverified_no_new_changes_a39372, "Copia privata non verificata: nessuna nuova modifica") }
            return record(op.copy(phase = "STAGED", expectedSize = recipe.bytes))
        }
        if (recipe.hash != null && part.exists() && digest(part.inputStream(), id = op.id) == FileDigest(recipe.bytes!!, recipe.hash)) {
            withContext(NonCancellable) { check(part.renameTo(committed)); op = record(op.copy(phase = "STAGED", expectedSize = recipe.bytes)) }
            return op
        }
        val source = Uri.parse(op.sourceUri)
        require(IncomingMedia.unchanged(recipe.target, IncomingMedia.stat(context, source))) { localized(R.string.text_received_file_changed_original_preserved_141a38, "Il file ricevuto è cambiato: originale conservato") }
        check(part.parentFile!!.isDirectory || part.parentFile!!.mkdirs()) { localized(R.string.text_private_storage_unavailable_8e7c33, "Spazio privato non disponibile") }
        if (recipe.target.size >= 0) require(recipe.target.size <= StatFs(part.parent!!).availableBytes - 8L * 1024 * 1024) { localized(R.string.text_insufficient_private_storage_import_stopped_orig_5daa32, "Spazio privato insufficiente: importazione fermata, originale conservato") }
        op = record(op.copy(phase = "STAGING", message = localized(R.string.text_acquiring_the_private_copy_before_the_destinatio_87ef67, "Acquisizione della copia privata prima della destinazione…"), bytes = 0))
        val acquired = FileOutputStream(part).use { native ->
            val bounded = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(StatFs(part.parent!!).availableBytes >= 8L * 1024 * 1024 + length) { localized(R.string.text_private_storage_full_original_preserved_2c14a4, "Spazio privato esaurito: originale conservato") }
                    native.write(bytes, offset, length)
                }
                override fun flush() = native.flush()
            }
            val value = digest(documents.input(source), bounded, op.id, progress = true); native.fd.sync(); value
        }
        require(acquired.bytes > 0 && (recipe.target.size < 0 || acquired.bytes == recipe.target.size)) { localized(R.string.text_received_size_differs_no_file_published_c4dc10, "Dimensione ricevuta diversa: nessun file pubblicato") }
        require(IncomingMedia.unchanged(recipe.target, IncomingMedia.stat(context, source)) && digest(documents.input(source), id = op.id) == acquired) { localized(R.string.text_the_source_changed_during_acquisition_a4b43b, "La sorgente è cambiata durante l'acquisizione") }
        require(digest(part.inputStream(), id = op.id) == acquired && (recipe.hash == null || recipe.hash == acquired.sha256)) { localized(R.string.text_private_copy_damaged_or_source_replaced_ad5ee7, "Copia privata non integra o sorgente sostituita") }
        checkCancel(op.id)
        withContext(NonCancellable) {
            op = record(op.copy(phase = "STAGE_VERIFIED", recipe = recipe.copy(hash = acquired.sha256, bytes = acquired.bytes).encode(), expectedSize = acquired.bytes))
            check(!committed.exists() && part.renameTo(committed)) { localized(R.string.text_could_not_publish_the_private_copy_7f58f5, "Pubblicazione della copia privata non riuscita") }
            op = record(op.copy(phase = "STAGED"))
        }
        return op
    }
    suspend fun enqueueVideoExport(target: ExternalTarget, recipe: VideoRecipe, albumId: String, name: String, start: Boolean = true): String =
        enqueueClipExport(target, "VIDEO", recipe.encode(), recipe.duration, albumId, name, start)
    suspend fun enqueueAudioExport(target: ExternalTarget, recipe: AudioRecipe, albumId: String, name: String, start: Boolean = true): String =
        enqueueClipExport(target, "AUDIO", recipe.encode(), recipe.duration, albumId, name, start)
    private suspend fun enqueueClipExport(target: ExternalTarget, kind: String, recipe: String, duration: Long, albumId: String, name: String, start: Boolean): String = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            catalog.rememberLocked(listOf(target.id))
            catalog.rememberAlbumsLocked(listOf(albumId))
            require(kind in setOf("AUDIO", "VIDEO"))
            val audio = kind == "AUDIO"
            val audioRecipe = if (audio) AudioRecipe.decode(recipe) else null
            val extension = audioRecipe?.extension ?: "mp4"
            FileNames.validateBase(name)
            require(name.substringAfterLast('.', "").equals(extension, true)) { localized(R.string.text_the_name_must_end_in_1_s_e12d3a, "Il nome deve terminare con .%1\$s", extension) }
            val data = catalog.readSnapshot()
            val item = data.byId[target.id]?.takeIf { data.usable(it) && target.matches(it) && it.kind == (audioRecipe?.sourceKind ?: kind) } ?: throw SourceChanged()
            require(dao.operations().none { it.mediaId == item.id && it.kind in setOf("MOVE", "RENAME", "TRASH") && !terminal(it.phase) }) { localized(R.string.text_resolve_the_operation_on_the_original_first_c24f4b, "Risolvi prima l'operazione in corso sull'originale") }
            val album = data.albumsById[albumId]?.takeIf { it.id !in data.excludedAlbums } ?: throw IOException(localized(R.string.text_destination_unavailable_1a3939, "Destinazione non disponibile"))
            require(data.albumMembership.any { link -> link.albumId == album.id && link.present && data.sources.any { it.id == link.sourceId && it.enabled && it.reachable && it.role in setOf("MUSIC", "VISUAL", "MIXED") } }) { localized(R.string.text_choose_a_destination_1_s_2c24d6, "Scegli una destinazione %1\$s", if (audio) localized(R.string.text_for_music_eef927, "musicale") else localized(R.string.text_for_videos_4cebe5, "per video")) }
            val parent = authorizedParent(album, data)
            require(documents.stat(parent).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { localized(R.string.text_destination_is_not_writable_e03949, "Destinazione non scrivibile") }
            val uri = catalog.uri(item, data); val original = documents.stat(uri)
            if (original.name != target.name || original.mime != item.mime || DocumentVersions.changed(target.size, target.modified, original.size, original.modified)) throw SourceChanged()
            require((if (audio) AudioEditing.inspect(context, uri) else VideoEditing.inspect(context, uri).duration) == duration) { localized(R.string.text_the_source_duration_has_changed_reopen_the_edito_be0a12, "La durata della sorgente è cambiata: riapri l'editor") }
            val id = UUID.randomUUID().toString()
            record(FileOperation(id, item.id, "EXPORT_$kind", uri.toString(), item.documentKey, item.albumId, item.name,
                audioRecipe?.mime ?: "video/mp4", original.size, original.modified, parent.toString(), album.id, name, true, UUID.randomUUID().toString(), recipe = recipe))
            if (start) schedule(id); id
        }
    }
    fun trashFile(entry: TrashEntry): File = privateFile(entry.fileName)
    suspend fun enqueueImageExport(target: ExternalTarget, recipe: ImageRecipe, albumId: String, name: String, start: Boolean = true): String = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            catalog.rememberLocked(listOf(target.id))
            catalog.rememberAlbumsLocked(listOf(albumId))
            FileNames.validateBase(name)
            require(name.substringAfterLast('.', "").equals(recipe.extension, true)) { localized(R.string.text_the_name_must_end_in_1_s_e12d3a, "Il nome deve terminare con .%1\$s", recipe.extension) }
            val data = catalog.readSnapshot()
            val item = data.byId[target.id]?.takeIf { data.usable(it) && target.matches(it) && recipe.accepts(it.kind) } ?: throw SourceChanged()
            require(dao.operations().none { it.mediaId == item.id && it.kind in setOf("MOVE", "RENAME", "TRASH") && !terminal(it.phase) }) { localized(R.string.text_resolve_the_operation_on_the_original_first_c24f4b, "Risolvi prima l'operazione in corso sull'originale") }
            val album = data.albumsById[albumId]?.takeIf { it.id !in data.excludedAlbums } ?: throw IOException(localized(R.string.text_destination_unavailable_1a3939, "Destinazione non disponibile"))
            require(data.albumMembership.any { link -> link.albumId == album.id && link.present && data.sources.any { it.id == link.sourceId && it.enabled && it.reachable && it.role in setOf("VISUAL", "MIXED") } }) { localized(R.string.text_choose_an_image_destination_e8911f, "Scegli una destinazione per immagini") }
            val parent = authorizedParent(album, data)
            require(documents.stat(parent).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { localized(R.string.text_destination_is_not_writable_e03949, "Destinazione non scrivibile") }
            val uri = catalog.uri(item, data); val original = documents.stat(uri)
            if (original.name != target.name || original.mime != item.mime || DocumentVersions.changed(target.size, target.modified, original.size, original.modified)) throw SourceChanged()
            val id = UUID.randomUUID().toString()
            val operation = FileOperation(id, item.id, "EXPORT_IMAGE", uri.toString(), item.documentKey, item.albumId, item.name,
                recipe.mime, original.size, original.modified, parent.toString(), album.id, name, true, UUID.randomUUID().toString(), recipe = recipe.encode())
            record(operation); if (start) schedule(id); id
        }
    }
    suspend fun createFolder(parentId: String, name: String, start: Boolean = true, reuseExisting: Boolean = false): String = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            FileNames.validateBase(name)
            val data = catalog.readSnapshot()
            val album = data.albumsById[parentId] ?: throw IOException(localized(R.string.text_folder_unavailable_1f6e37, "Cartella non disponibile"))
            require(parentId !in data.excludedAlbums) { localized(R.string.text_hidden_folder_d2c9a2, "Cartella nascosta") }
            val parent = authorizedParent(album, data)
            require(documents.stat(parent).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { localized(R.string.text_folder_is_not_writable_348e84, "Cartella non scrivibile") }
            val children = documents.children(parent)
            val existing = if (reuseExisting) children.singleOrNull { it.name == name } else null
            require(existing == null || existing.mime == DocumentsContract.Document.MIME_TYPE_DIR) { localized(R.string.text_name_taken_no_folder_overwritten_9f5059, "Nome occupato: nessuna cartella sovrascritta") }
            val finalName = if (reuseExisting) name else FileNames.available(name, children.map { it.name }.toSet(), true)
            val id = UUID.randomUUID().toString()
            val operation = FileOperation(id, parentId, "MKDIR", parent.toString(), album.documentKey, parentId, name,
                DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, parent.toString(), parentId, finalName, true, UUID.randomUUID().toString(), publishedUri = existing?.uri?.toString())
            record(operation)
            if (start) schedule(id)
            id
        }
    }
    private suspend fun executeFolder(initial: FileOperation) {
        var op = initial
        val data = catalog.readSnapshot()
        val parent = data.albumsById[op.sourceAlbumId] ?: throw IOException(localized(R.string.text_folder_needs_relinking_f72a5e, "Cartella da ricollegare"))
        require(parent.id !in data.excludedAlbums) { localized(R.string.text_hidden_folder_d2c9a2, "Cartella nascosta") }
        val uri = authorizedParent(parent, data)
        if (op.publishedUri == null) {
            val existing = documents.children(uri).filter { it.name == op.requestedName }
            if (op.phase in setOf("CREATING", "INTERRUPTED", "FAILED") && existing.size == 1 && existing.single().mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                // Recover by reading an existing folder; never delete or overwrite its contents.
                op = record(op.copy(publishedUri = existing.single().uri.toString(), finalName = existing.single().name))
            } else {
                require(existing.isEmpty()) { localized(R.string.text_name_taken_no_folder_overwritten_9f5059, "Nome occupato: nessuna cartella sovrascritta") }
                require(documents.stat(uri).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { localized(R.string.text_folder_is_not_writable_348e84, "Cartella non scrivibile") }
                op = record(op.copy(phase = "CREATING"))
                val created = documents.create(uri, DocumentsContract.Document.MIME_TYPE_DIR, op.requestedName)
                op = record(op.copy(publishedUri = created.toString(), phase = "PUBLISHED"))
            }
        }
        val folder = documents.stat(Uri.parse(op.publishedUri))
        require(folder.mime == DocumentsContract.Document.MIME_TYPE_DIR) { localized(R.string.text_the_provider_did_not_create_a_folder_fc23fd, "Il provider non ha creato una cartella") }
        val key = "${folder.uri.authority}:${DocumentsContract.getDocumentId(folder.uri)}"
        val old = data.albums.firstOrNull { it.documentKey == key }
        val result = old ?: AlbumEntity(op.resultMediaId, key, folder.uri.toString(), folder.name, "${parent.path} / ${folder.name}", parent.id)
        val ancestors = data.ancestry.filter { it.albumId == parent.id }.map { it.ancestorId } + parent.id + result.id
        val memberships = data.albumMembership.filter { it.albumId == parent.id && it.present }.map { it.copy(albumId = result.id) }
        withContext(NonCancellable) {
            catalog.db.withTransaction {
                dao.saveAlbums(listOf(result)); dao.saveAncestors(ancestors.distinct().map { AlbumAncestor(result.id, it) })
                dao.saveAlbumSources(memberships)
            }
            record(op.copy(phase = "COMPLETED", resultMediaId = result.id, finalName = folder.name, message = localized(R.string.text_folder_available_1_s_ae9bd3, "Cartella disponibile: %1\$s", result.path)))
        }
    }
    private fun privateFile(name: String): File {
        require(Regex("[0-9a-f-]{36}\\.(part|bin)").matches(name)) { localized(R.string.text_invalid_private_locator_9aa0f7, "Locator privato non valido") }
        val file = File(directory, name).canonicalFile
        require(file.parentFile == directory.canonicalFile)
        return file
    }
    fun schedule(id: String) {
        if ((context.applicationContext as? GalleryApp)?.explorer?.transfers?.current?.value == id) return
        // Native serial chain: queued workers do not spend their execution timeout waiting behind other copies.
        WorkManager.getInstance(context).enqueueUniqueWork("file-queue", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<FileOperationWorker>().addTag("file:$id").setInputData(workDataOf("operation" to id)).build())
    }
    fun maintenance() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("trash:expiry", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashMaintenanceWorker>(1, TimeUnit.DAYS).build())
        catalog.scope.launch {
            recoverInterrupted()
            cleanupExpired()
        }
    }
    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val operations = dao.operations()
            // 0.2-dev could finish a restore before noticing that private cleanup was denied.
            for (restore in operations.filter { it.kind == "RESTORE" && it.phase == "COMPLETED" }) {
                val name = restore.sourceUri.substringAfterLast('/')
                val backup = runCatching { privateFile(name) }.getOrNull() ?: continue
                if (!backup.exists() || dao.trash().any { it.fileName == name }) continue
                val original = operations.firstOrNull { it.id == name.removeSuffix(".bin") && it.kind in setOf("TRASH", "ARCHIVE") } ?: continue
                val date = original.sourceDeletedAt ?: original.sourceDeletionStartedAt ?: original.archiveAt ?: continue
                val digest = original.digest ?: continue
                val item = dao.media(restore.mediaId) ?: continue
                withContext(NonCancellable) {
                    catalog.db.withTransaction {
                        val same = !item.trashed && item.uri == restore.publishedUri && dao.trash(item.id) == null
                        val archiveId = if (same) item.id else UUID.randomUUID().toString()
                        if (!same) dao.saveMedia(listOf(item.copy(id = archiveId, documentKey = "trash:$archiveId", name = original.originalName,
                            mime = original.mime, kind = MediaNames.kind(original.mime, original.originalName)?.name ?: item.kind,
                            size = original.bytes, available = false, trashed = true, favorite = false)))
                        dao.saveTrash(TrashEntry(archiveId, original.id, name, original.sourceUri, original.sourceAlbumId,
                            original.originalName, original.mime, original.bytes, digest, date, date + SEVEN_DAYS))
                        record(restore.copy(phase = if (same) "PARTIAL" else "RESTORED_WITH_BACKUP",
                            message = localized(R.string.text_previous_restore_recovered_private_copy_retained_542ed0, "Ripristino precedente recuperato: copia privata conservata con la scadenza originale")))
                    }
                }
            }
            for (upload in dao.operations().filter { it.kind == "FTP_RECEIVE" && it.id !in receivingFtp &&
                    it.digest == null && it.publishedUri == null && !terminal(it.phase) }) {
                try {
                    cleanupTemporary(upload)
                    record(upload.copy(phase = "CANCELLED"))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (dao.operation(upload.id)?.phase != "FAILED") throw error
                }
            }
            dao.operations().filter { it.id !in receivingFtp && !terminal(it.phase) && it.phase !in setOf("FAILED", "PARTIAL", "INTERRUPTED") }.forEach {
                record(it.copy(phase = "INTERRUPTED", message = localized(R.string.text_operation_interrupted_resume_or_cancel_in_file_o_dc8133, "Operazione interrotta. Riprendi o annulla dalla schermata Operazioni.")))
            }
            dao.operations().filter { it.kind == "TRASH" && it.recipe == "DIRECT_DELETE" && it.phase == "COMPLETED" }.forEach { op ->
                dao.trash(op.mediaId)?.takeIf { it.operationId == op.id }?.let { purgeLocked(listOf(it), explicit = true) }
            }
        }
    }
    suspend fun enqueue(item: MediaEntity, kind: String, destinationAlbumId: String? = null,
                        name: String = item.name, keepBoth: Boolean = true, start: Boolean = true,
                        expectedDigest: FileDigest? = null, retainedMediaId: String? = null, expectedTrash: TrashEntry? = null, permanent: Boolean = false): String = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            require(kind in setOf("COPY", "MOVE", "RENAME", "TRASH", "RESTORE", "RESTORE_COPY"))
            require(!permanent || kind == "TRASH")
            if (expectedDigest != null) require(kind == "TRASH" && expectedDigest.bytes >= 0 && expectedDigest.sha256.matches(Regex("[0-9a-f]{64}")))
            if (retainedMediaId != null) require(expectedDigest != null && retainedMediaId != item.id)
            catalog.rememberLocked(listOf(item.id))
            catalog.rememberAlbumsLocked(listOfNotNull(destinationAlbumId))
            val current = dao.media(item.id) ?: throw IOException(localized(R.string.text_media_unavailable_91dca9, "Media non disponibile"))
            require(current.uri == item.uri && current.documentKey == item.documentKey && current.trashed == item.trashed) { localized(R.string.text_the_file_has_already_changed_update_the_selectio_151a60, "Il file è già cambiato; aggiorna la selezione") }
            if (expectedTrash != null) require(dao.trash(item.id) == expectedTrash) {
                localized(R.string.text_trash_item_changed_confirm_the_target_again_cd7047, "Voce del cestino cambiata: conferma di nuovo il bersaglio")
            }
            val pending = dao.operations().firstOrNull { it.mediaId == item.id && !terminal(it.phase) }
            if (pending != null) {
                require(pending.kind == kind && pending.requestedName == name && pending.keepBoth == keepBoth &&
                    pending.destinationAlbumId == (if (kind == "TRASH") null else destinationAlbumId ?: current.albumId) &&
                    pending.retainedMediaId == retainedMediaId && (pending.recipe == "DIRECT_DELETE") == permanent &&
                    (expectedDigest == null || (pending.digest == expectedDigest.sha256 && pending.expectedSize == expectedDigest.bytes))) {
                    localized(R.string.text_resolve_the_other_operation_on_this_file_first_cf0c95, "Un'altra operazione su questo file richiede prima di essere risolta")
                }
                return@withLock pending.id
            }
            val data = catalog.readSnapshot()
            val entry = if (kind in setOf("RESTORE", "RESTORE_COPY")) dao.trash(item.id) ?: throw IOException(localized(R.string.text_item_not_in_trash_1d8ce8, "Voce non presente nel cestino")) else null
            FileNames.validate(name, entry?.originalName ?: current.name)
            if (entry == null) require(data.usable(current)) { localized(R.string.text_media_hidden_disconnected_or_busy_6151f3, "Media nascosto, scollegato o occupato") }
            else require(!entry.purging && (if (kind == "RESTORE_COPY") !current.trashed else current.trashed)) { localized(R.string.text_restore_unavailable_3d38ae, "Ripristino non disponibile") }
            if (entry == null && kind in setOf("MOVE", "RENAME", "TRASH")) require(data.sourceFor(current)?.singleDocument != true) {
                localized(R.string.text_this_single_file_link_is_read_only_import_a_copy_2b4ea6, "Il collegamento al singolo file è in sola lettura: importa una copia o autorizza la sua cartella per modificare l'originale")
            }
            val sourceUri = if (entry != null) trashFile(entry).toURI().toString() else catalog.uri(current, data).toString()
            val source = if (entry == null) documents.stat(Uri.parse(sourceUri)) else null
            if (source != null && DocumentVersions.changed(current.size, current.modified, source.size, source.modified)) throw SourceChanged()
            if (expectedDigest != null && source!!.size >= 0 && source.size != expectedDigest.bytes) throw SourceChanged()
            if (kind in setOf("MOVE", "RENAME", "TRASH")) require(source!!.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) {
                if (DeviceGallery.isMedia(Uri.parse(sourceUri))) localized(R.string.text_to_move_rename_or_use_trash_authorize_the_folder_44ca3d, "Per spostare, rinominare o usare il cestino, autorizza la cartella dalle Sorgenti. Il file rimane intatto.")
                else localized(R.string.text_the_provider_cannot_remove_the_original_c3a48f, "Il provider non consente di rimuovere l'originale")
            }
            val destination = if (kind == "TRASH") null else data.albumsById[destinationAlbumId ?: current.albumId]
                ?: throw IOException(localized(R.string.text_choose_an_authorized_destination_folder_d16813, "Scegli una cartella di destinazione autorizzata"))
            val parent = destination?.let { authorizedParent(it, data) }
            if (parent != null) require(documents.stat(parent).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { localized(R.string.text_the_folder_does_not_allow_new_copies_3ec9ee, "La cartella non consente nuove copie") }
            if (kind == "RENAME") require(destination?.id == current.albumId && name != current.name) { localized(R.string.text_choose_another_name_in_the_same_folder_0121f0, "Scegli un nome diverso nella stessa cartella") }
            if (kind == "MOVE") require(destination?.id != current.albumId || name != current.name) { localized(R.string.text_the_file_is_already_in_the_chosen_folder_3a7291, "Il file è già nella cartella scelta") }
            val id = UUID.randomUUID().toString()
            val op = FileOperation(id, current.id, kind, sourceUri, current.documentKey, current.albumId, entry?.originalName ?: current.name,
                entry?.mime ?: current.mime, expectedDigest?.bytes ?: source?.size ?: entry!!.size, source?.modified ?: 0L, parent?.toString(), destination?.id,
                name, keepBoth, if (kind in setOf("COPY", "RESTORE_COPY")) UUID.randomUUID().toString() else current.id,
                digest = expectedDigest?.sha256 ?: entry?.digest, bytes = expectedDigest?.bytes ?: 0, retainedMediaId = retainedMediaId, recipe = if (permanent) "DIRECT_DELETE" else null)
            dao.saveOperation(op)
            if (start) schedule(id)
            id
        }
    }
    /** Explicit album batch, retaining folder hierarchy through the same recoverable journal. */
    suspend fun enqueueAlbumMedia(targets: List<MediaEntity>, albumFolders: List<AlbumEntity>, kind: String,
        destinationAlbumId: String, keepBoth: Boolean = true, start: Boolean = true) {
        require(kind in setOf("COPY", "MOVE") && targets.isNotEmpty())
        val data = catalog.readSnapshot()
        val folders = albumFolders.associateBy { it.id }
        val destinationId = destinationAlbumId
        var ancestor: String? = destinationId
        val visited = mutableSetOf<String>()
        while (ancestor != null && visited.add(ancestor)) {
            require(ancestor !in folders) { localized(R.string.text_choose_a_destination_outside_the_selected_albums_312088, "Scegli una destinazione esterna agli album selezionati.") }
            ancestor = data.albumsById[ancestor]?.parentId
        }
        val created = mutableMapOf<String, String>()
        val creatingFolders = mutableSetOf<String>()
        suspend fun destinationFor(id: String): String {
            created[id]?.let { return it }
            require(creatingFolders.add(id)) { localized(R.string.text_invalid_folder_structure_637a03, "Struttura delle cartelle non valida.") }
            val folder = folders.getValue(id)
            val parent = folder.parentId?.takeIf { it in folders }?.let { destinationFor(it) } ?: destinationId
            val operationId = createFolder(parent, folder.name, start = false)
            execute(operationId)
            val operation = catalog.dao.operation(operationId)!!
            check(operation.phase == "COMPLETED") { operation.message ?: localized(R.string.text_folder_not_created_c4c5b9, "Cartella non creata") }
            return requireNotNull(operation.resultMediaId).also { created[id] = it; creatingFolders.remove(id) }
        }
        for ((albumId, media) in targets.groupBy { it.albumId }) {
            val parent = destinationFor(albumId)
            enqueueAll(media, kind, parent, keepBoth = keepBoth, start = start)
        }
    }

    suspend fun enqueueAll(items: List<MediaEntity>, kind: String, destinationAlbumId: String? = null,
                           name: String? = null, keepBoth: Boolean = true, start: Boolean = true,
                           expectedDigests: Map<String, FileDigest> = emptyMap(), retainedMediaIds: Map<String, String> = emptyMap(),
                           expectedTrashEntries: Map<String, TrashEntry> = emptyMap(), permanent: Boolean = false): List<String> {
        require(items.isNotEmpty())
        require(kind in setOf("COPY", "MOVE", "RENAME", "TRASH", "RESTORE", "RESTORE_COPY"))
        return items.distinctBy { it.id }.map { item ->
            try { enqueue(item, kind, destinationAlbumId, name ?: item.name, keepBoth, start, expectedDigests[item.id], retainedMediaIds[item.id], expectedTrashEntries[item.id], permanent) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // A rejected element is a terminal result, never a resumable operation against a stale locator.
                val op = FileOperation(UUID.randomUUID().toString(), item.id, kind, item.uri, item.documentKey, item.albumId,
                    item.name, item.mime, item.size, item.modified, null, destinationAlbumId, name ?: item.name, keepBoth, item.id,
                    phase = "SKIPPED", message = localized(R.string.text_not_started_1_s_4866a0, "Non avviata: %1\$s", error.message ?: localized(R.string.text_file_unavailable_6dcc4f, "file non disponibile")))
                record(op).id
            }
        }
    }
    private fun authorizedParent(album: AlbumEntity, data: CatalogSnapshot): Uri {
        val source = data.albumMembership.filter { it.albumId == album.id && it.present }.mapNotNull { link ->
            data.sources.find { it.id == link.sourceId && !it.singleDocument && it.id != DeviceGallery.SOURCE && it.enabled && it.reachable }
        }.firstOrNull() ?: throw SecurityException(localized(R.string.text_destination_disconnected_or_access_needs_restori_06ea20, "Destinazione scollegata o accesso da ripristinare"))
        return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(source.treeUri), DocumentsContract.getDocumentId(Uri.parse(album.documentUri)))
    }
    private fun importParent(target: IncomingFile, albumId: String, data: CatalogSnapshot): Uri {
        val album = data.albumsById[albumId]?.takeIf { it.id !in data.excludedAlbums } ?: throw IOException(localized(R.string.text_destination_hidden_or_unavailable_54ced7, "Destinazione nascosta o non disponibile"))
        val role = if (target.kind == "AUDIO") "MUSIC" else "VISUAL"
        val source = data.albumMembership.filter { it.albumId == album.id && it.present }.mapNotNull { link ->
            data.sources.find { it.id == link.sourceId && !it.singleDocument && it.id != DeviceGallery.SOURCE && it.enabled && it.reachable && it.role in setOf(role, "MIXED") }
        }.firstOrNull() ?: throw IOException(localized(R.string.text_choose_an_active_connected_folder_1_s_e19a6c, "Scegli una cartella %1\$s attiva e collegata", if (role == "MUSIC") localized(R.string.text_for_music_eef927, "musicale") else localized(R.string.text_for_images_and_videos_3a551b, "per immagini e video")))
        val parent = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(source.treeUri), DocumentsContract.getDocumentId(Uri.parse(album.documentUri)))
        require(documents.stat(parent).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { localized(R.string.text_destination_is_not_writable_e03949, "Destinazione non scrivibile") }
        return parent
    }
    private suspend fun record(value: FileOperation): FileOperation = withContext(NonCancellable) {
        var restored = false
        val saved = catalog.db.withTransaction {
            val previous = dao.operation(value.id)
            val next = value.copy(cancelRequested = previous?.cancelRequested ?: value.cancelRequested, updatedAt = clock().wall)
            dao.saveOperation(next)
            restored = previous?.phase != "COMPLETED" && next.phase == "COMPLETED" && next.kind in setOf("RESTORE", "RESTORE_COPY")
            next
        }
        if (restored) (context.applicationContext as? GalleryApp)?.sounds?.play("restore")
        saved
    }
    suspend fun cancel(id: String) {
        dao.cancelOperation(id)
        if (dao.operation(id)?.kind?.startsWith("NETWORK_") == true) (context.applicationContext as GalleryApp).explorer.transfers.cancel()
    }
    suspend fun retry(id: String) {
        val op = dao.operation(id) ?: return
        if (op.kind.startsWith("NETWORK_")) { (context.applicationContext as GalleryApp).explorer.retry(op); return }
        require(!terminal(op.phase)) { localized(R.string.text_operation_cannot_be_resumed_e10db1, "Operazione non riprendibile") }
        dao.resumeOperation(id)
        schedule(id)
    }
    private suspend fun checkCancel(id: String) {
        currentCoroutineContext().ensureActive()
        if (dao.operation(id)?.cancelRequested == true) throw OperationCancelled()
    }
    private suspend fun digest(input: InputStream, output: OutputStream? = null, id: String, progress: Boolean = false,
                               respectCancel: Boolean = true): FileDigest {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var bytes = 0L; var last = 0L
        input.use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output?.write(buffer, 0, count); sha.update(buffer, 0, count); bytes += count
                if (SystemClock.elapsedRealtime() - last >= 250) {
                    if (respectCancel) checkCancel(id)
                    if (progress) dao.operation(id)?.let { record(it.copy(bytes = bytes)) }
                    last = SystemClock.elapsedRealtime()
                }
            }
        }
        output?.flush(); if (respectCancel) checkCancel(id)
        return FileDigest(bytes, sha.digest().joinToString("") { "%02x".format(it) })
    }
    private fun sourceInput(op: FileOperation): InputStream = if (op.kind == "IMPORT") importFile(op, "bin").inputStream()
    else if (op.kind in setOf("RESTORE", "RESTORE_COPY")) {
        // Never trust an arbitrary persisted file:// path.
        val file = privateFile("${op.sourceUri.substringAfterLast('/').substringBefore('?')}")
        require(file.toURI().toString() == op.sourceUri); file.inputStream()
    } else documents.input(Uri.parse(op.sourceUri))
    private suspend fun sourceUnchanged(op: FileOperation, digest: FileDigest) {
        if (op.kind == "IMPORT") {
            val recipe = ImportRecipe.decode(op.recipe!!)
            require(digest == FileDigest(recipe.bytes!!, recipe.hash!!) && this.digest(sourceInput(op), id = op.id) == digest) { localized(R.string.text_private_copy_changed_destination_not_published_b61c4c, "Copia privata cambiata: destinazione non pubblicata") }
            return
        }
        if (op.kind in setOf("RESTORE", "RESTORE_COPY")) {
            require(digest.sha256 == op.digest && digest.bytes == op.expectedSize) { localized(R.string.text_trash_copy_damaged_362903, "Copia nel cestino non integra") }
            return
        }
        val source = documents.stat(Uri.parse(op.sourceUri))
        if (source.name != op.originalName || DocumentVersions.changed(op.expectedSize, op.expectedModified, source.size, source.modified) ||
            this.digest(sourceInput(op), id = op.id) != digest) throw SourceChanged()
    }
    private suspend fun verified(op: FileOperation, uri: String): FileDigest {
        val value = if (uri.startsWith("file:")) digest(privateFile(uri.substringAfterLast('/')).inputStream(), id = op.id)
            else digest(documents.input(Uri.parse(uri)), id = op.id)
        require(value.sha256 == op.digest && value.bytes == op.bytes) { localized(R.string.text_destination_verification_failed_original_preserv_108861, "Verifica della destinazione fallita: originale conservato") }
        return value
    }
    private val execution = Mutex()

    suspend fun execute(id: String, beforeIo: suspend (FileOperation) -> Unit = {}) = withContext(Dispatchers.IO) {
        execution.withLock {
            var op = dao.operation(id) ?: return@withLock
            require(!op.kind.startsWith("NETWORK_")) { localized(R.string.explorer_transfer_error, "Ricollega i dispositivi e verifica la copia prima di riprovare.") }
            if (terminal(op.phase)) {
                if (op.phase == "COMPLETED" && op.kind == "TRASH" && op.recipe == "DIRECT_DELETE")
                    dao.trash(op.mediaId)?.takeIf { it.operationId == op.id }?.let { purgeLocked(listOf(it), explicit = true) }
                return@withLock
            }
            try {
                checkCancel(id)
                if (export(op.kind) && gameMode() && !when (op.kind) {
                    "EXPORT_IMAGE" -> ImageRecipe.decode(op.recipe ?: "").allowGame
                    "EXPORT_AUDIO" -> AudioRecipe.decode(op.recipe ?: "").allowGame
                    else -> VideoRecipe.decode(op.recipe ?: "").allowGame
                }) {
                    record(op.copy(phase = "DEFERRED", message = localized(R.string.text_game_mode_export_deferred_disable_it_and_tap_res_a4b373, "Modalità Gioco: esportazione rimandata. Disattivala e premi Riprendi.")))
                    return@withLock
                }
                if (op.kind == "IMPORT" && gameMode() && !ImportRecipe.decode(op.recipe!!).allowGame) {
                    record(op.copy(phase = "DEFERRED", message = localized(R.string.text_game_mode_import_deferred_disable_it_and_tap_res_450951, "Modalità Gioco: importazione rimandata. Disattivala e premi Riprendi."))); return@withLock
                }
                beforeIo(op)
                if (op.kind == "FTP_MKDIR") { publishFtpDirectory(op); return@withLock }
                if (op.kind == "FTP_RECEIVE") { publishFtpReceive(op); return@withLock }
                if (op.kind in setOf("EXPORT_VIDEO", "EXPORT_AUDIO")) cleanupClipTemporary(op)
                if (op.kind == "MKDIR") { catalog.writer.withLock { executeFolder(op) }; return@withLock }
                val item = if (op.kind == "IMPORT") dao.media(op.resultMediaId)?.takeIf { it.uri == op.publishedUri }
                    ?: ImportRecipe.decode(op.recipe!!).target.item(op.sourceKey)
                    else dao.media(op.mediaId) ?: throw IOException(localized(R.string.text_media_record_missing_c01843, "Record del media mancante"))
                if (op.kind == "DISCARD_TRASH_BACKUP") {
                    require(sourceExists(op)) { localized(R.string.text_source_unavailable_private_copy_retained_23d417, "Origine non disponibile: copia privata conservata") }
                    sourceUnchanged(op, FileDigest(op.bytes, op.digest!!))
                    withContext(NonCancellable) {
                        val backup = privateFile("${op.id}.bin")
                        if (backup.exists()) check(deletePrivate(backup)) { localized(R.string.text_private_copy_not_removed_original_preserved_9a16c1, "Copia privata non rimossa; originale conservato") }
                        record(op.copy(phase = "ORIGINAL_ONLY", message = localized(R.string.text_original_preserved_trash_operation_canceled_5a8833, "Originale conservato; operazione di cestino annullata")))
                    }
                    return@withLock
                }
                if (op.kind == "ARCHIVE") {
                    verified(op, op.publishedUri!!)
                    withContext(NonCancellable) {
                        index(op, item)
                        record(op.copy(phase = "COMPLETED", message = localized(R.string.text_copy_in_trash_no_further_removal_of_the_original_6a1315, "Copia nel cestino; nessun'altra rimozione dell'originale")))
                    }
                    return@withLock
                }
                if (op.kind == "PURGE") {
                    val entry = dao.trash(op.mediaId)
                    if (entry == null) record(op.copy(phase = "COMPLETED", message = localized(R.string.text_no_remaining_copy_in_trash_5e3e21, "Nessuna copia residua nel cestino")))
                    else if (trashFile(entry).toURI().toString() != op.sourceUri) record(op.copy(phase = "SKIPPED", message = localized(R.string.text_the_item_changed_confirm_again_79bd2a, "La voce è cambiata: nuova conferma necessaria")))
                    else purgeLocked(listOf(entry), explicit = true)
                    return@withLock
                }
                val indexed = op.publishedUri != null && item.uri == op.publishedUri && item.id == op.resultMediaId && !item.trashed
                require(item.documentKey == op.sourceKey || op.sourceDeletedAt != null || indexed || (op.kind == "COPY" && op.phase == "INDEXING" && op.publishedUri != null)) { localized(R.string.text_source_identity_changed_289adf, "Identità della sorgente cambiata") }
                if (indexed && op.kind != "COPY") {
                    verified(op, op.publishedUri!!)
                    if (op.kind == "RESTORE") cleanupRestored(op)
                    if (op.kind == "IMPORT") cleanupImport(op)
                    record(op.copy(phase = "COMPLETED", message = localized(R.string.text_result_recovered_2cc841, "Risultato recuperato"))); return@withLock
                }
                if (op.kind == "IMPORT") {
                    val parent = importParent(ImportRecipe.decode(op.recipe!!).target, op.destinationAlbumId!!, catalog.readSnapshot())
                    val frozen = Uri.parse(op.destinationParent)
                    require(parent.authority == frozen.authority && DocumentsContract.getDocumentId(parent) == DocumentsContract.getDocumentId(frozen)) {
                        localized(R.string.text_destination_identity_changed_no_new_changes_2bc461, "Identità della destinazione cambiata: nessuna nuova modifica")
                    }
                }
                if (op.kind !in setOf("RESTORE", "RESTORE_COPY", "IMPORT") && op.sourceDeletedAt == null && !indexed && op.phase != "INDEXING") {
                    require(catalog.readSnapshot().visible(item)) { localized(R.string.text_the_source_is_hidden_or_disabled_no_new_changes_3a5881, "La sorgente è nascosta o disattivata. Nessuna nuova modifica.") }
                }
                if (op.kind in setOf("RESTORE", "RESTORE_COPY")) require(dao.trash(op.mediaId)?.purging == false) { localized(R.string.text_trash_unavailable_474ba5, "Cestino non disponibile") }
                if (op.phase in setOf("PUBLISHING", "INTERRUPTED", "FAILED") && op.publishedUri == null && op.finalName != null && op.kind != "TRASH") {
                    val oldTempExists = op.temporaryUri?.let { runCatching { documents.stat(Uri.parse(it)) }.isSuccess } == true
                    if (!oldTempExists) {
                        val found = documents.children(Uri.parse(op.destinationParent)).filter { it.name == op.finalName }
                        require(found.size == 1) { localized(R.string.text_publishing_interrupted_destination_needs_verific_e95574, "Pubblicazione interrotta: destinazione da verificare") }
                        verified(op, found.single().uri.toString())
                        op = record(op.copy(publishedUri = found.single().uri.toString(), phase = "PUBLISHED"))
                    }
                }
                if (op.kind == "TRASH" && op.publishedUri == null && privateFile("${op.id}.bin").exists()) {
                    val final = privateFile("${op.id}.bin").toURI().toString()
                    verified(op, final); op = record(op.copy(publishedUri = final, finalName = op.originalName, phase = "PUBLISHED"))
                }
                if (op.publishedUri == null) {
                    op = stageVerifiedCopy(op)
                    op = publishVerifiedCopy(op)
                }
                verified(op, op.publishedUri!!)
                if (op.kind != "TRASH") require(documents.stat(Uri.parse(op.publishedUri)).name == op.finalName) { localized(R.string.text_the_destination_name_changed_no_further_deletion_4b4834, "Il nome della destinazione è cambiato. Nessuna ulteriore eliminazione.") }
                if (op.kind in setOf("MOVE", "RENAME", "TRASH") && op.phase !in setOf("DELETED", "INDEXING")) {
                    op = deleteVerifiedOriginal(op)
                }
                withContext(NonCancellable) {
                    op = record(op.copy(phase = "INDEXING"))
                    index(op, item)
                    if (op.kind == "RESTORE") {
                        op = record(op.copy(phase = "CLEANUP"))
                        cleanupRestored(op)
                    }
                    if (op.kind == "IMPORT") { op = record(op.copy(phase = "CLEANUP")); cleanupImport(op) }
                    op = record(op.copy(phase = "COMPLETED", message = when (op.kind) {
                        "TRASH" -> if (op.recipe == "DIRECT_DELETE") localized(R.string.text_operation_complete_9b2712, "Operazione completata") else localized(R.string.text_in_trash_for_seven_days_e05a70, "Nel cestino per sette giorni")
                        "COPY" -> localized(R.string.text_copy_complete_original_preserved_51eb2c, "Copia completata: originale conservato")
                        "IMPORT" -> localized(R.string.text_copy_imported_into_the_chosen_folder_external_or_edcde6, "Copia importata nella cartella scelta: originale esterno conservato")
                        "EXPORT_IMAGE" -> if (ImageRecipe.decode(op.recipe!!).input == "GIF_FIRST_FRAME")
                            localized(R.string.text_first_frame_exported_original_gif_and_favorites_8d9d76, "Primo fotogramma esportato: GIF e Preferiti originali conservati") else localized(R.string.text_image_exported_original_and_favorites_preserved_5e5c41, "Immagine esportata: originale e Preferiti conservati")
                        "EXPORT_VIDEO" -> localized(R.string.text_video_segment_exported_original_favorites_and_co_e1d82f, "Segmento video esportato: originale, Preferiti e raccolte conservati")
                        "EXPORT_AUDIO" -> localized(R.string.text_audio_segment_exported_original_favorites_and_pl_bb24a1, "Segmento audio esportato: originale, Preferiti e playlist conservati")
                        else -> localized(R.string.text_operation_complete_9b2712, "Operazione completata")
                    }))
                }
                if (op.phase == "COMPLETED" && op.kind == "TRASH" && op.recipe == "DIRECT_DELETE")
                    dao.trash(op.mediaId)?.takeIf { it.operationId == op.id }?.let { purgeLocked(listOf(it), explicit = true) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    op = dao.operation(id) ?: op
                    if (!terminal(op.phase)) record(op.copy(phase = "INTERRUPTED", message = localized(R.string.text_interrupted_verify_copy_and_original_before_resu_eb3b9a, "Interruzione: copia e originale da verificare prima di riprendere")))
                }
                throw cancelled
            } catch (cancelled: OperationCancelled) {
                op = dao.operation(id) ?: op
                if (op.kind == "FTP_RECEIVE" && op.digest != null)
                    record(op.copy(phase = "PARTIAL", message = localized(R.string.text_source_unavailable_private_copy_retained_23d417, "Origine non disponibile: copia privata conservata")))
                else if (op.publishedUri != null) record(op.copy(phase = "PARTIAL", message = localized(R.string.text_destination_published_verify_the_result_before_r_c120e7, "Destinazione pubblicata: verifica il risultato prima di risolvere l'operazione. Nessuna ulteriore rimozione.")))
                else {
                    try { cleanupTemporary(op); record((dao.operation(id) ?: op).copy(phase = "CANCELLED", message = cancelled.message)) }
                    catch (stopped: CancellationException) { throw stopped }
                    catch (error: Exception) {
                        // Continue only after the cleanup failure was persisted; database failures must still surface.
                        if (dao.operation(id)?.phase != "FAILED") throw error
                    }
                }
            } catch (error: Exception) {
                op = dao.operation(id) ?: op
                if (error is SourceChanged) dao.media(op.mediaId)?.takeIf { !it.trashed && it.documentKey == op.sourceKey }?.let {
                    dao.saveMedia(listOf(it.copy(documentKey = "unavailable:${it.id}:${it.documentKey}", available = false)))
                }
                record(op.copy(phase = if (op.publishedUri != null) "PARTIAL" else "FAILED",
                    message = error.message ?: localized(R.string.text_operation_failed_original_preserved_4dd031, "Operazione non riuscita: originale conservato")))
            }
        }
    }
    private suspend fun stageVerifiedCopy(initial: FileOperation): FileOperation {
        var op = initial
        if (op.kind == "IMPORT") op = stageImport(op)
        if (op.digest != null && op.temporaryUri != null) {
            // A complete verified temporary can be reused; partial streams restart in the same owned document.
            try { verified(op, op.temporaryUri!!) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cancelled: OperationCancelled) { throw cancelled }
            catch (_: Exception) { op = record(op.copy(digest = if (op.kind in setOf("RESTORE", "RESTORE_COPY")) dao.trash(op.mediaId)?.digest else null, phase = "COPYING")) }
        }
        if (op.temporaryUri == null) {
            op = record(op.copy(phase = "CREATING", bytes = 0, message = null))
            val temporary = if (op.kind == "TRASH") {
                check(directory.isDirectory || directory.mkdirs()) { localized(R.string.text_trash_archive_unavailable_65088e, "Archivio del cestino non disponibile") }
                if (op.expectedSize >= 0) require(op.expectedSize <= StatFs(directory.path).availableBytes - 1024 * 1024) {
                    localized(R.string.text_insufficient_space_to_protect_the_file_original_837367, "Spazio insufficiente per mettere al sicuro il file: originale conservato")
                }
                val file = privateFile("${op.id}.part")
                if (!file.exists()) check(file.createNewFile()) { localized(R.string.text_could_not_reserve_the_private_copy_557a15, "Impossibile riservare la copia privata") }
                file.toURI().toString()
            } else {
                val parent = Uri.parse(op.destinationParent!!)
                val prefix = ".ayngallery-${op.id}."
                val owned = documents.children(parent).filter { it.name.startsWith(prefix) }
                require(owned.size <= 1) { localized(R.string.text_ambiguous_temporary_file_check_the_destination_7fc5f4, "Temporaneo ambiguo: verifica la destinazione") }
                (owned.singleOrNull()?.uri ?: documents.create(parent, op.mime, "$prefix${op.requestedName.substringAfterLast('.', "part")}")).toString()
            }
            op = record(op.copy(temporaryUri = temporary, phase = "COPYING"))
        }
        val temporary = op.temporaryUri!!
        if (op.phase !in setOf("VERIFIED", "PUBLISHING")) {
            op = record(op.copy(phase = "COPYING", bytes = 0, message = null))
            val copied = when (op.kind) {
                "EXPORT_IMAGE" -> exportImage(op, temporary)
                "EXPORT_VIDEO", "EXPORT_AUDIO" -> exportClip(op, temporary)
                "TRASH" -> FileOutputStream(privateFile("${op.id}.part")).use { out ->
                    val value = digest(sourceInput(op), out, op.id, true)
                    out.fd.sync()
                    value
                }
                else -> documents.output(Uri.parse(temporary)).use { out -> digest(sourceInput(op), out, op.id, true) }
            }
            if (!export(op.kind) && op.expectedSize >= 0 && copied.bytes != op.expectedSize) throw SourceChanged()
            if (op.kind == "TRASH" && op.digest != null && copied.sha256 != op.digest) throw SourceChanged()
            if (!export(op.kind)) sourceUnchanged(op, copied)
            op = record(op.copy(digest = copied.sha256, bytes = copied.bytes, phase = "VERIFYING"))
            verified(op, temporary)
            op = record(op.copy(phase = "VERIFIED"))
        }
        return op
    }

    private suspend fun publishVerifiedCopy(initial: FileOperation): FileOperation {
        var op = initial
        checkCancel(op.id)
        if (op.kind == "TRASH") {
            op = record(op.copy(phase = "PUBLISHING"))
            val final = privateFile("${op.id}.bin")
            if (!final.exists()) check(privateFile("${op.id}.part").renameTo(final)) { localized(R.string.text_could_not_publish_the_trash_copy_dd8550, "Pubblicazione del cestino non riuscita") }
            op = record(op.copy(publishedUri = final.toURI().toString(), finalName = op.originalName, phase = "PUBLISHED"))
        } else {
            val parent = Uri.parse(op.destinationParent!!)
            val temp = Uri.parse(op.temporaryUri!!)
            val children = documents.children(parent)
            val existing = children.filter { it.uri != temp }.map { it.name }.toSet()
            val name = op.finalName ?: FileNames.available(op.requestedName, existing, op.keepBoth)
            require(name.lowercase() !in existing.map { it.lowercase() }) { localized(R.string.text_publishing_conflict_original_preserved_2f5ae0, "Conflitto durante la pubblicazione: originale conservato") }
            val stat = documents.stat(temp)
            require(stat.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) { localized(R.string.text_this_provider_cannot_publish_the_temporary_file_949885, "Questo provider non consente la pubblicazione del temporaneo. Scegli una diversa destinazione.") }
            op = record(op.copy(phase = "PUBLISHING", finalName = name))
            val published = documents.rename(temp, name)
            op = record(op.copy(publishedUri = published.toString(), phase = "PUBLISHED"))
            require(documents.stat(published).name == name) { localized(R.string.text_the_provider_changed_the_requested_name_the_orig_b5eddd, "Il provider ha modificato il nome richiesto. L'originale resta conservato.") }
        }
        return op
    }

    private suspend fun deleteVerifiedOriginal(initial: FileOperation): FileOperation {
        var op = initial
        op.retainedMediaId?.let { keptId ->
            require(op.kind == "TRASH" && keptId != op.mediaId && op.digest != null) { localized(R.string.text_invalid_duplicate_protection_011a1a, "Protezione duplicati non valida") }
            val data = catalog.readMediaSnapshot(keptId)
            val kept = data.byId[keptId]?.takeIf(data::usable) ?: throw SourceChanged()
            val retained = digest(documents.input(catalog.uri(kept, data)), id = op.id)
            if (retained.sha256 != op.digest || retained.bytes != op.bytes) throw SourceChanged()
        }
        // Block new playback before releasing open handles, then revalidate the complete source immediately before deletion.
        op = record(op.copy(phase = "DELETING")); releaseMedia(op.mediaId)
        val source = if (sourceExists(op)) documents.stat(Uri.parse(op.sourceUri)) else null
        if (source != null) {
            sourceUnchanged(op, FileDigest(op.bytes, op.digest!!)); checkCancel(op.id)
            withContext(NonCancellable) {
                // Persist the time before the provider effect: it may remove the file and then throw.
                op = record(op.copy(sourceDeletionStartedAt = clock().wall))
                documents.delete(Uri.parse(op.sourceUri))
                require(!sourceExists(op)) { localized(R.string.text_the_provider_did_not_remove_the_original_99c355, "Il provider non ha rimosso l'originale") }
                op = record(op.copy(phase = "DELETED", sourceDeletedAt = clock().wall))
            }
        } else {
            require(!sourceExists(op)) { localized(R.string.text_source_cannot_be_verified_6e3e1e, "Origine non verificabile") }
            op = record(op.copy(phase = "DELETED", sourceDeletedAt = op.sourceDeletedAt ?: op.sourceDeletionStartedAt ?: clock().wall))
        }
        return op
    }

    private suspend fun sourceExists(op: FileOperation): Boolean {
        val source = Uri.parse(op.sourceUri)
        // A removed/unauthorised tree is never proof that a document was deleted.
        val root = DocumentsContract.buildDocumentUriUsingTree(source, DocumentsContract.getTreeDocumentId(source))
        documents.stat(root)
        val album = dao.albums().firstOrNull { it.id == op.sourceAlbumId } ?: throw IOException(localized(R.string.text_source_folder_needs_verification_c7496b, "Cartella di origine da verificare"))
        val parent = DocumentsContract.buildDocumentUriUsingTree(source, DocumentsContract.getDocumentId(Uri.parse(album.documentUri)))
        documents.stat(parent)
        // Some providers reject a tree URI for a deleted document as 'not a descendant'.
        // A successful listing of its still-authorised parent proves absence without confusing it with a revoked grant.
        return documents.children(parent).any { DocumentsContract.getDocumentId(it.uri) == DocumentsContract.getDocumentId(source) }
    }
    private suspend fun exportImage(op: FileOperation, temporary: String): FileDigest {
        val recipe = ImageRecipe.decode(op.recipe ?: throw IOException(localized(R.string.text_recipe_missing_0d4b6e, "Ricetta mancante")))
        require(recipe.mime == op.mime) { localized(R.string.text_invalid_recipe_format_ec8914, "Formato della ricetta non valido") }
        require(dao.media(op.mediaId)?.kind?.let(recipe::accepts) == true) { localized(R.string.text_source_type_changed_d2e607, "Tipo della sorgente cambiato") }
        record(op.copy(phase = "DECODING"))
        val source = digest(sourceInput(op), id = op.id)
        sourceUnchanged(op, source)
        val image = ImageEditing.render(context.contentResolver, Uri.parse(op.sourceUri), recipe)
        try {
            checkCancel(op.id); record(op.copy(phase = "ENCODING"))
            documents.output(Uri.parse(temporary)).use { ImageEditing.write(image, recipe, it) }
            checkCancel(op.id); sourceUnchanged(op, source)
            val data = catalog.readMediaSnapshot(op.mediaId)
            require(data.byId[op.mediaId]?.let(data::usable) == true) { localized(R.string.text_the_source_is_no_longer_available_407cb7, "La sorgente non è più disponibile") }
            return digest(documents.input(Uri.parse(temporary)), id = op.id)
        } finally { image.recycle() }
    }
    private fun clipTemporary(op: FileOperation): File {
        require(UUID.fromString(op.id).toString() == op.id) { localized(R.string.text_invalid_export_identity_2dbcad, "Identità di esportazione non valida") }
        val directory = File(context.filesDir, "exports").canonicalFile
        return File(directory, "${op.id}.${if (op.kind == "EXPORT_AUDIO") AudioRecipe.decode(requireNotNull(op.recipe)).extension else "mp4"}").canonicalFile.also { require(it.parentFile == directory) }
    }
    private fun cleanupClipTemporary(op: FileOperation) {
        val output = clipTemporary(op)
        if (output.exists()) check(deletePrivate(output)) { localized(R.string.text_private_temporary_file_retained_resume_or_cancel_e646b3, "Temporaneo privato conservato: riprendi o annulla la pulizia") }
    }
    private suspend fun exportClip(op: FileOperation, temporary: String): FileDigest {
        val audio = op.kind == "EXPORT_AUDIO"
        val raw = op.recipe ?: throw IOException(localized(R.string.text_recipe_missing_0d4b6e, "Ricetta mancante"))
        val recipe = if (audio) null else VideoRecipe.decode(raw)
        val audioRecipe = if (audio) AudioRecipe.decode(raw) else null
        require(op.mime == (audioRecipe?.mime ?: "video/mp4") && dao.media(op.mediaId)?.kind == (audioRecipe?.sourceKind ?: "VIDEO")) { localized(R.string.text_source_type_or_format_changed_2e65b2, "Tipo della sorgente o formato cambiato") }
        val output = clipTemporary(op)
        val directory = output.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { localized(R.string.text_export_storage_unavailable_5041f2, "Spazio di esportazione non disponibile") }
        cleanupClipTemporary(op)
        try {
            val source = digest(sourceInput(op), id = op.id)
            sourceUnchanged(op, source)
            record(op.copy(phase = "ENCODING", message = localized(R.string.text_encoding_segment_4f4ac8, "Codifica del segmento…")))
            val check: suspend () -> Unit = {
                checkCancel(op.id)
                require(StatFs(directory.path).availableBytes > 8L * 1024 * 1024) { localized(R.string.text_insufficient_private_storage_original_preserved_4f3855, "Spazio privato insufficiente: originale conservato") }
            }
            val progress: suspend (Int?) -> Unit = { percent -> record(op.copy(phase = "ENCODING", message = percent?.let { localized(R.string.text_encoding_segment_1_s_a411b2, "Codifica del segmento: %1\$s%%", it) } ?: localized(R.string.text_encoding_segment_4f4ac8, "Codifica del segmento…"))); Unit }
            if (audio) AudioEditing.render(context, Uri.parse(op.sourceUri), audioRecipe!!, output, check, progress)
            else VideoEditing.render(context, Uri.parse(op.sourceUri), recipe!!, output, check, progress)
            checkCancel(op.id); sourceUnchanged(op, source)
            val data = catalog.readMediaSnapshot(op.mediaId)
            require(data.byId[op.mediaId]?.let(data::usable) == true) { localized(R.string.text_the_source_is_no_longer_available_407cb7, "La sorgente non è più disponibile") }
            record(op.copy(phase = "COPYING", message = localized(R.string.text_saving_the_new_copy_d7e74b, "Salvataggio della nuova copia…")))
            return documents.output(Uri.parse(temporary)).use { out -> digest(output.inputStream(), out, op.id, true) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                cleanupClipTemporary(op)
            }
        }
    }
    private suspend fun cleanupTemporary(op: FileOperation) {
        try {
            if (op.kind == "FTP_RECEIVE" && op.digest == null)
                privateFile("${op.id}.part").let { if (it.exists()) check(deletePrivate(it)) }
            if (op.kind in setOf("EXPORT_VIDEO", "EXPORT_AUDIO")) cleanupClipTemporary(op)
            val temp = op.temporaryUri
            if (temp == null) { if (op.kind == "IMPORT") cleanupImport(op); return }
            if (op.kind == "TRASH") privateFile("${op.id}.part").let { if (it.exists()) check(deletePrivate(it)) }
            else {
                val uri = Uri.parse(temp)
                val info = documents.stat(uri)
                require(info.name.startsWith(".ayngallery-${op.id}."))
                documents.delete(uri)
            }
            if (op.kind == "IMPORT") {
                record(op.copy(temporaryUri = null, digest = null))
                cleanupImport(op)
            }
        } catch (error: Exception) { record((dao.operation(op.id) ?: op).copy(phase = "FAILED", message = localized(R.string.text_temporary_file_retained_1_s_3157a4, "Temporaneo conservato: %1\$s", error.message))); throw error }
    }
    private suspend fun index(op: FileOperation, original: MediaEntity) = catalog.writer.withLock {
        val item = dao.media(original.id) ?: original
        if (op.kind in setOf("TRASH", "ARCHIVE")) {
            val now = (if (op.kind == "ARCHIVE") op.archiveAt else op.sourceDeletedAt) ?: throw IOException(localized(R.string.text_trash_date_unverified_591d7a, "Data del cestino non verificata"))
            val target = item.copy(id = op.resultMediaId, documentKey = "trash:${op.resultMediaId}", available = false, trashed = true,
                favorite = item.favorite && item.id == op.resultMediaId)
            catalog.db.withTransaction {
                if (dao.trash(target.id) == null) dao.saveTrash(TrashEntry(target.id, op.id, "${op.id}.bin", op.sourceUri, op.sourceAlbumId,
                    op.originalName, op.mime, op.bytes, op.digest!!, now, now + SEVEN_DAYS))
                dao.saveMedia(listOf(target))
            }
            return@withLock
        }
        val destination = documents.stat(Uri.parse(op.publishedUri!!))
        val data = catalog.readSnapshot()
        val album = data.albumsById[op.destinationAlbumId] ?: throw IOException(localized(R.string.text_destination_album_unavailable_1d55d6, "Album di destinazione non disponibile"))
        val links = data.albumMembership.filter { it.albumId == album.id && it.present }
            .mapNotNull { link -> data.sources.find { it.id == link.sourceId && !it.singleDocument && it.id != DeviceGallery.SOURCE && it.enabled && it.reachable } }
        require(links.isNotEmpty()) { localized(R.string.text_destination_needs_relinking_file_preserved_6cd661, "Destinazione da ricollegare; file conservato") }
        val key = "${destination.uri.authority}:${DocumentsContract.getDocumentId(destination.uri)}"
        var result = item.copy(id = op.resultMediaId, documentKey = key, uri = destination.uri.toString(), albumId = album.id,
            name = destination.name, size = destination.size.takeIf { it >= 0 } ?: op.bytes, modified = destination.modified,
            mime = op.mime, kind = MediaNames.kind(op.mime, destination.name)?.name ?: item.kind,
            favorite = if (op.kind in setOf("COPY", "RESTORE_COPY", "IMPORT") || export(op.kind)) false else item.favorite, available = true, trashed = false,
            videoPosition = if (op.kind == "EXPORT_VIDEO") 0 else item.videoPosition,
            added = if (export(op.kind)) System.currentTimeMillis() else item.added,
            deviceUri = DeviceGallery.alias(context, destination.uri)?.toString())
        if (op.kind == "EXPORT_VIDEO") result = result.copy(duration = VideoEditing.inspect(context, destination.uri).duration)
        if (op.kind == "IMPORT" && result.kind == "VIDEO") result = SourceScanner(catalog).metadata(result, destination.uri, null)
        if (op.kind == "EXPORT_AUDIO") result = result.copy(duration = AudioEditing.inspect(context, destination.uri), title = null, artist = null,
            musicAlbum = null, artworkUri = null, artworkMediaId = null, videoPosition = 0)
        if (result.kind == "AUDIO" && (result.id != item.id || result.albumId != item.albumId)) {
            val folderCover = data.media.filter { it.albumId == album.id && it.kind == "IMAGE" && data.artworkAllowed(it) }
                .sortedWith(compareBy<MediaEntity> { ArtworkOrders.coverRank(it.name) }.thenBy { it.name }).firstOrNull()
            val coverUri = folderCover?.let { catalog.uri(it, data, artwork = true).toString() }
            result = result.copy(artworkMediaId = folderCover?.id, artworkUri = if (result.id == item.id) item.artworkUri?.takeIf { Uri.parse(it).scheme == "file" } else null)
            if (result.id != item.id) result = SourceScanner(catalog).metadata(result, destination.uri, coverUri)
        }
        catalog.db.withTransaction {
            dao.saveMedia(listOf(result)); dao.clearMembership(result.id)
            dao.saveMembership(links.map { MediaSource(result.id, it.id, it.lastScan) })
        }
        links.forEach { catalog.schedule(it.id) }
    }
    private suspend fun cleanupRestored(op: FileOperation) {
        val old = privateFile(op.sourceUri.substringAfterLast('/'))
        if (old.exists()) check(deletePrivate(old)) { localized(R.string.text_file_restored_private_copy_still_retained_resume_f3fe8f, "File ripristinato; copia privata ancora conservata. Riprendi la pulizia o mantieni la copia di sicurezza.") }
        val entry = dao.trash(op.mediaId)
        if (entry?.fileName == old.name) dao.removeTrash(op.mediaId)
    }
    suspend fun keepRestoredBackup(id: String) = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val op = dao.operation(id) ?: return@withLock
            require(op.kind == "RESTORE" && op.phase == "PARTIAL" && op.publishedUri != null)
            val item = dao.media(op.mediaId) ?: throw IOException(localized(R.string.text_file_unavailable_dad522, "File non disponibile"))
            require(!item.trashed && item.uri == op.publishedUri) { localized(R.string.text_restore_not_yet_indexed_use_resume_6e4516, "Ripristino non ancora indicizzato: usa Riprendi") }
            dao.resumeOperation(id); verified(op, op.publishedUri)
            record(op.copy(phase = "RESTORED_WITH_BACKUP", message = localized(R.string.text_restored_backup_copy_retained_in_trash_until_the_634382, "Ripristinato; copia di sicurezza conservata nel cestino fino alla scadenza originale")))
        }
    }
    suspend fun resolveTrash(id: String, keepArchive: Boolean) = withContext(Dispatchers.IO) {
        execution.withLock {
            var op = dao.operation(id) ?: return@withLock
            require((op.kind == "TRASH" || (keepArchive && op.kind == "DISCARD_TRASH_BACKUP")) && op.phase == "PARTIAL" && op.publishedUri != null)
            require(op.sourceDeletedAt == null && sourceExists(op)) { localized(R.string.text_source_already_removed_or_unverifiable_use_resum_3e26c2, "Origine già rimossa o non verificabile: usa Riprendi; la copia privata resta protetta") }
            val item = dao.media(op.mediaId) ?: throw IOException(localized(R.string.text_media_unavailable_91dca9, "Media non disponibile"))
            withContext(NonCancellable) {
                catalog.db.withTransaction {
                    val archiveId = if (keepArchive && item.available && item.documentKey == op.sourceKey) UUID.randomUUID().toString() else item.id
                    if (archiveId != item.id) dao.saveMedia(listOf(item.copy(id = archiveId, documentKey = "pending-archive:$archiveId",
                        available = false, trashed = true, favorite = false)))
                    dao.resumeOperation(id)
                    op = record(op.copy(kind = if (keepArchive) "ARCHIVE" else "DISCARD_TRASH_BACKUP", phase = "INDEXING",
                        mediaId = archiveId, resultMediaId = archiveId, archiveAt = if (keepArchive) clock().wall else null))
                }
            }
        }
        execute(id) // Decision is durable before any cleanup/indexing and can never resume an original deletion.
    }
    suspend fun keepCopy(id: String) = withContext(Dispatchers.IO) {
        execution.withLock {
            val op = dao.operation(id) ?: return@withLock
            require(op.phase == "PARTIAL" && op.kind in setOf("COPY", "MOVE", "RENAME") && op.publishedUri != null)
            require(op.kind == "COPY" || (op.sourceDeletedAt == null && sourceExists(op))) { localized(R.string.text_source_already_removed_use_resume_to_recover_ref_4c3a87, "Origine già rimossa: usa Riprendi per recuperare i riferimenti sulla destinazione") }
            val item = dao.media(op.mediaId) ?: throw IOException(localized(R.string.text_media_unavailable_91dca9, "Media non disponibile"))
            // This explicit resolution never removes the original or inherits its personal references.
            val digest = digest(documents.input(Uri.parse(op.publishedUri)), id = op.id, respectCancel = false)
            require(digest.sha256 == op.digest && digest.bytes == op.bytes) { localized(R.string.text_destination_changed_85142f, "Destinazione cambiata") }
            withContext(NonCancellable) {
                dao.resumeOperation(id)
                val copy = record(op.copy(kind = "COPY", resultMediaId = UUID.randomUUID().toString(), phase = "INDEXING",
                    finalName = documents.stat(Uri.parse(op.publishedUri)).name, message = localized(R.string.text_copy_retained_move_incomplete_no_further_deletio_f4c49b, "Copia conservata: spostamento non completato, nessuna ulteriore eliminazione")))
                try {
                    index(copy, item)
                    record(copy.copy(phase = "COPY_ONLY"))
                } catch (error: Exception) {
                    record(copy.copy(phase = "PARTIAL", message = localized(R.string.text_copy_retained_references_need_restoring_1_s_e7e976, "Copia conservata: riferimenti da ripristinare. %1\$s", error.message)))
                    throw error
                }
            }
        }
    }
    suspend fun purge(entries: List<TrashEntry>, explicit: Boolean = false) = withContext(Dispatchers.IO) {
        catalog.writer.withLock { purgeLocked(entries, explicit) }
    }
    private suspend fun purgeLocked(entries: List<TrashEntry>, explicit: Boolean) {
            entries.distinctBy { it.mediaId }.forEach { expected ->
                val id = expected.mediaId
                val entry = dao.trash(id) ?: return@forEach
                val active = dao.operations().filter { it.mediaId == id && !terminal(it.phase) }
                val own = active.firstOrNull { it.kind == "PURGE" && it.sourceUri == trashFile(expected).toURI().toString() }
                val operation = own ?: FileOperation(UUID.randomUUID().toString(), id, "PURGE", trashFile(expected).toURI().toString(),
                    "trash:$id", expected.originalAlbumId, expected.originalName, expected.mime, expected.size, 0,
                    null, null, expected.originalName, false, id, phase = "PURGING", digest = expected.digest, bytes = expected.size)
                if (entry.operationId != expected.operationId || entry.fileName != expected.fileName) {
                    if (explicit) record(operation.copy(phase = "SKIPPED", message = localized(R.string.text_trash_item_changed_confirm_the_target_again_cd7047, "Voce del cestino cambiata: conferma di nuovo il bersaglio")))
                    return@forEach
                }
                if (active.any { it.kind != "PURGE" }) {
                    if (explicit) record(operation.copy(phase = "SKIPPED", message = localized(R.string.text_restore_or_another_operation_in_progress_6bf31c, "Ripristino o altra operazione in corso")))
                    return@forEach
                }
                require(explicit || entry.expiresAt <= clock().wall) { localized(R.string.text_the_item_has_not_expired_148c30, "La voce non è scaduta") }
                try { withContext(NonCancellable) {
                    record(operation.copy(phase = "PURGING"))
                    dao.saveTrash(entry.copy(purging = true))
                    val file = trashFile(entry)
                    if (file.exists()) check(deletePrivate(file)) { localized(R.string.text_private_deletion_failed_208163, "Cancellazione privata non riuscita") }
                    dao.removeTrash(id)
                    record(operation.copy(phase = "COMPLETED", message = localized(R.string.text_private_copy_permanently_deleted_cb214b, "Copia privata eliminata definitivamente")))
                } } catch (error: Exception) { record(operation.copy(phase = "FAILED", message = localized(R.string.text_cleanup_interrupted_1_s_3f18c1, "Pulizia interrotta: %1\$s", error.message))) }
            }
    }
    suspend fun cleanupExpired() {
        if (!TrashClockGuard(context).accept(clock())) return
        val entries = dao.trash().filter { it.expiresAt <= clock().wall }
        try { purge(entries) } catch (_: IllegalArgumentException) { /* A pending restore owns its file until explicitly resolved. */ }
    }
}

data class TrashClockSample(val wall: Long, val elapsed: Long, val boot: Int) {
    companion object { fun now(context: Context) = TrashClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)) }
}
class TrashClockGuard(context: Context) {
    private val prefs = context.getSharedPreferences("trash_clock", Context.MODE_PRIVATE)
    fun accept(sample: TrashClockSample): Boolean = synchronized(prefs) {
        val oldWall = prefs.getLong("wall", -1); val oldElapsed = prefs.getLong("elapsed", -1); val oldBoot = prefs.getInt("boot", -2)
        val stable = oldWall >= 0 && sample.boot == oldBoot && sample.elapsed >= oldElapsed &&
            kotlin.math.abs((sample.wall - oldWall) - (sample.elapsed - oldElapsed)) <= 10 * 60_000L
        val alarm = prefs.getBoolean("alarm", false) || (oldWall >= 0 && !stable && sample.boot == oldBoot)
        check(prefs.edit().putLong("wall", sample.wall).putLong("elapsed", sample.elapsed).putInt("boot", sample.boot).putBoolean("alarm", alarm).commit())
        stable && !alarm
    }
    fun blocked() = prefs.getBoolean("alarm", false)
    fun acknowledge(sample: TrashClockSample) = synchronized(prefs) {
        check(prefs.edit().putLong("wall", sample.wall).putLong("elapsed", sample.elapsed).putInt("boot", sample.boot).putBoolean("alarm", false).commit())
    }
}
class FileOperationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("operation") ?: return Result.failure()
        (applicationContext as GalleryApp).files.execute(id) { setForeground(fileForegroundInfo(applicationContext, it)) }
        // The journal reports per-file failures/partial results. A handled rejection must not cancel the rest of a batch.
        return Result.success()
    }
}
internal fun fileForegroundInfo(context: Context, op: FileOperation): ForegroundInfo {
    val channel = "file-operations"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(channel, localized(R.string.text_file_operations_7333a0, "Operazioni sui file"), NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(context, 20, Intent(context, LauncherActivity::class.java).setAction("it.ayngallery.OPEN_OPERATIONS"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ph_folders)
        .setContentTitle(fileActionName(op.kind, op.recipe)).setContentText(op.originalName)
        .setSubText(localized(R.string.text_progress_and_cancellation_in_file_operations_86c746, "Progresso e annullamento nella schermata Operazioni"))
        .setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
        .setOngoing(true).setProgress(0, 0, true).build()
    return if (Build.VERSION.SDK_INT >= 35 && op.kind in setOf("EXPORT_VIDEO", "EXPORT_AUDIO")) ForegroundInfo(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(20, notification)
}
class TrashMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as GalleryApp).files.cleanupExpired(); Result.success()
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { Result.retry() }
}
