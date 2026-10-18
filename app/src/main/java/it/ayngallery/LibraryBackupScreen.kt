package it.ayngallery

import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** A rotation keeps a small private draft ID, never megabytes of catalog JSON in an Activity bundle. */
class LibraryDrafts(private val directory: File) {
    private fun file(id: String): File { require(UUID.fromString(id).toString() == id); return File(directory, "$id.json") }
    fun read(id: String): String = file(id).let { require(it.length() in 1..LibraryBackup.MAX_BYTES.toLong()); it.readText(Charsets.UTF_8).also(LibraryBackup::decode) }
    fun create(raw: String): String {
        LibraryBackup.decode(raw)
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val id = UUID.randomUUID().toString(); val atomic = AtomicFile(file(id)); val out = atomic.startWrite()
        try { out.write(raw.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (failure: Exception) { atomic.failWrite(out); throw failure }
        check(read(id) == raw) { localized(R.string.text_backup_draft_not_verified_fd30b7, "Bozza del backup non verificata") }; return id
    }
    fun discard(id: String?) { id?.let { file(it).delete() } }
}
