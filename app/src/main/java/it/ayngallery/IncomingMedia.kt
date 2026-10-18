package it.ayngallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Exact user-selected files, never a provider root or a network/file path. No grant is persisted for a copy. */
data class IncomingSelection(val id: String, val uris: List<String>) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(uris.size in 1..128 && uris.distinct().size == uris.size)
        uris.forEach { IncomingMedia.checkUri(it.toUri()) }
    }
    fun encode() = JSONObject().put("v", 1).put("id", id).put("uris", JSONArray(uris)).toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { localized(R.string.text_selection_too_large_choose_fewer_files_23f153, "Selezione troppo grande: scegli meno file") }
    }
    companion object {
        fun from(intent: Intent): IncomingSelection {
            val values = buildList {
                intent.data?.let { add(it.toString()) }
                intent.clipData?.let { clip ->
                    require(clip.itemCount <= 128) { localized(R.string.text_choose_up_to_128_files_per_import_2cea07, "Scegli al massimo 128 file per importazione") }
                    for (index in 0 until clip.itemCount) add(requireNotNull(clip.getItemAt(index).uri).toString())
                }
            }.distinct()
            return IncomingSelection(UUID.randomUUID().toString(), values)
        }
        fun decode(raw: String): IncomingSelection {
            require(raw.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
            val root = JSONObject(raw); require(root.getInt("v") == 1)
            val uris = root.getJSONArray("uris"); require(uris.length() in 1..128)
            return IncomingSelection(root.getString("id"), (0 until uris.length()).map { uris.getString(it) })
        }
    }
}

data class IncomingFile(val id: String, val uri: String, val name: String, val mime: String,
    val kind: String, val size: Long, val modified: Long) {
    init {
        require(UUID.fromString(id).toString() == id); IncomingMedia.checkUri(uri.toUri())
        require(name.isNotBlank() && name.length <= 1024 && mime.length <= 128)
        require(MediaNames.kind(mime, name)?.name == kind && size >= -1 && modified >= 0)
    }
    val proposedName: String get() {
        val cleaned = name.map { if (it == '/' || it == '\\' || it.code < 32) '_' else it }.joinToString("").trim()
        val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..12 && it.all(Char::isLetterOrDigit) }
        val base = if (extension != null) cleaned.substringBeforeLast('.') else cleaned
        val stem = base.removePrefix(".ayngallery-").trim('.').ifBlank { localized(R.string.text_imported_media_53118d, "Media importato") }
        return stem.take(minOf(170, 180 - (extension?.length?.plus(1) ?: 0))) + (extension?.let { ".$it" } ?: "")
    }
    fun encode() = JSONObject().put("id", id).put("uri", uri).put("name", name).put("mime", mime).put("kind", kind)
        .put("size", size).put("modified", modified).toString()
    fun item(key: String) = MediaEntity(id, key, uri, "", name, mime, kind, size, modified)
    companion object {
        fun decode(raw: String): IncomingFile {
            require(raw.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
            val root = JSONObject(raw)
            return IncomingFile(root.getString("id"), root.getString("uri"), root.getString("name"), root.getString("mime"),
                root.getString("kind"), root.getLong("size"), root.getLong("modified"))
        }
    }
}

/** The verified private stage is durable before SAF destination IO; no external deletion is an import action. */
data class ImportRecipe(val target: IncomingFile, val allowGame: Boolean = false, val hash: String? = null, val bytes: Long? = null) {
    init { require((hash == null) == (bytes == null)); if (hash != null) require(hash.matches(Regex("[a-f0-9]{64}")) && bytes!! > 0) }
    fun encode() = JSONObject().put("v", 1).put("target", JSONObject(target.encode())).put("allowGame", allowGame)
        .put("hash", hash).put("bytes", bytes).toString()
    companion object {
        fun decode(raw: String): ImportRecipe {
            require(raw.toByteArray(Charsets.UTF_8).size <= 40 * 1024)
            val root = JSONObject(raw); require(root.getInt("v") == 1)
            return ImportRecipe(IncomingFile.decode(root.getJSONObject("target").toString()), root.getBoolean("allowGame"),
                if (root.has("hash")) root.getString("hash") else null, if (root.has("bytes")) root.getLong("bytes") else null)
        }
    }
}

object IncomingMedia {
    suspend fun inspected(context: Context, selection: IncomingSelection, progress: (Int) -> Unit = {}): String {
        val files = JSONArray(); val errors = JSONArray()
        for ((index, uri) in selection.uris.withIndex()) {
            try { files.put(JSONObject(inspect(context, IncomingSelection(selection.id, listOf(uri))).single().encode())) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { errors.put(localized(R.string.text_file_1_s_2_s_348193, "File %1\$s: %2\$s", index + 1, failure.message?.take(512) ?: localized(R.string.text_unavailable_1d80f0, "non disponibile"))) }
            progress(index + 1)
        }
        return JSONObject().put("files", files).put("errors", errors).toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { localized(R.string.text_metadata_too_large_choose_fewer_files_2a4c6e, "Metadati troppo grandi: scegli meno file") }
        }
    }
    suspend fun readable(context: Context, target: IncomingFile) = withContext(Dispatchers.IO) {
        require(networkPreviewReadable(context, target) || unchanged(target, stat(context, target.uri.toUri()))) { localized(R.string.text_the_received_file_changed_open_it_again_8c87d1, "Il file ricevuto è cambiato: aprilo nuovamente") }
        context.contentResolver.openInputStream(target.uri.toUri())?.use { require(it.read() >= 0) { localized(R.string.text_empty_file_7a1d6c, "File vuoto") } }
            ?: throw IOException(localized(R.string.text_received_file_unreadable_f23c3d, "File ricevuto non leggibile"))
    }
    internal fun checkUri(uri: Uri) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.toString().length <= 8192 && uri.fragment == null && !DocumentsContract.isTreeUri(uri)) { localized(R.string.text_choose_readable_individual_files_not_a_folder_or_bd3427, "Scegli singoli file leggibili, non una cartella o un indirizzo") }
    }
    fun stat(context: Context, uri: Uri): DocumentInfo {
        checkUri(uri)
        require(uri.authority != context.packageName && !uri.authority!!.startsWith("${context.packageName}.")) { localized(R.string.text_this_private_app_file_is_not_an_import_source_f90db3, "Questo file privato dell'app non è una sorgente d'importazione") }
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase() ?: "application/octet-stream"
        val columns = if (DocumentsContract.isDocumentUri(context, uri))
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            else arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return resolver.query(uri, columns, android.os.Bundle.EMPTY, null)?.use { cursor ->
            require(cursor.moveToFirst()) { localized(R.string.text_file_unavailable_dad522, "File non disponibile") }
            fun string(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
            fun number(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            val name = string(OpenableColumns.DISPLAY_NAME)?.takeIf(String::isNotBlank) ?: localized(R.string.text_unnamed_media_24a1bb, "Media senza nome")
            require(name.length <= 1024) { localized(R.string.text_file_name_too_long_576415, "Nome del file troppo lungo") }
            DocumentInfo(uri, name, mime, number(OpenableColumns.SIZE)?.coerceAtLeast(-1) ?: -1,
                number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.coerceAtLeast(0) ?: 0, 0)
        } ?: throw IOException(localized(R.string.text_the_provider_did_not_return_file_data_17e4e1, "Il provider non restituisce i dati del file"))
    }
    fun unchanged(target: IncomingFile, fresh: DocumentInfo) = target.name == fresh.name && target.mime == fresh.mime &&
        !DocumentVersions.changed(target.size, target.modified, fresh.size, fresh.modified)
    suspend fun inspect(context: Context, selection: IncomingSelection): List<IncomingFile> = withContext(Dispatchers.IO) {
        selection.uris.map { raw ->
            currentCoroutineContext().ensureActive()
            val uri = raw.toUri(); val before = stat(context, uri)
            val kind = MediaNames.kind(before.mime, before.name) ?: throw IOException(localized(R.string.text_1_s_unsupported_format_025f92, "%1\$s: formato non gestito", before.name))
            context.contentResolver.openInputStream(uri)?.use { require(it.read() >= 0) { localized(R.string.text_1_s_empty_file_80be53, "%1\$s: file vuoto", before.name) } } ?: throw IOException(localized(R.string.text_1_s_unreadable_file_2b9af6, "%1\$s: file non leggibile", before.name))
            val id = UUID.nameUUIDFromBytes("${selection.id}:$raw".toByteArray(Charsets.UTF_8)).toString()
            IncomingFile(id, raw, before.name, before.mime, kind.name, before.size, before.modified).also {
                require(unchanged(it, stat(context, uri))) { localized(R.string.text_1_s_file_changed_while_reading_9910f3, "%1\$s: file cambiato durante la lettura", before.name) }
            }
        }
    }
}
