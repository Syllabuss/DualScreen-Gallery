package it.ayngallery

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Shared storage exposed through the same document APIs as explicitly linked folders. */
class SharedStorageDocuments : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "it.ayngallery.sharedstorage"
        const val ROOT = "shared"
        internal fun resolve(root: File, id: String): File {
            require(id == ROOT || id.startsWith("$ROOT/"))
            val base = root.canonicalFile
            val file = if (id == ROOT) base else File(base, id.removePrefix("$ROOT/")).canonicalFile
            require(file == base || file.path.startsWith(base.path + File.separator))
            val relative = file.relativeTo(base).invariantSeparatorsPath
            val parts = relative.split('/')
            require(!(parts.firstOrNull().equals("Android", ignoreCase = true) &&
                parts.getOrNull(1)?.lowercase() in setOf("data", "obb", "sandbox")))
            return file
        }
    }
    override fun onCreate() = true
    private fun allowed() {
        check(android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) { "Storage permission required" }
    }
    private val base get() = Environment.getExternalStorageDirectory().canonicalFile
    private fun file(id: String): File { allowed(); return resolve(base, id) }
    private fun id(file: File) = if (file == base) ROOT else "$ROOT/${file.relativeTo(base).invariantSeparatorsPath}"
    private fun mime(file: File) = if (file.isDirectory) Document.MIME_TYPE_DIR else
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES)
        val cursor = MatrixCursor(columns)
        if (android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            val values = mapOf<String, Any>(Root.COLUMN_ROOT_ID to ROOT, Root.COLUMN_DOCUMENT_ID to ROOT,
                Root.COLUMN_TITLE to context!!.getString(R.string.shared_storage_title), Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD),
                Root.COLUMN_MIME_TYPES to "*/*", Root.COLUMN_AVAILABLE_BYTES to base.usableSpace)
            cursor.addRow(columns.map { values[it] })
        }
        return cursor
    }
    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS))
    private fun add(cursor: MatrixCursor, file: File) {
        var flags = if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (file != base) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        val values = mapOf<String, Any>(Document.COLUMN_DOCUMENT_ID to id(file), Document.COLUMN_DISPLAY_NAME to
            (if (file == base) context!!.getString(R.string.shared_storage_title) else file.name), Document.COLUMN_MIME_TYPE to mime(file),
            Document.COLUMN_SIZE to file.length(), Document.COLUMN_LAST_MODIFIED to file.lastModified(), Document.COLUMN_FLAGS to flags)
        cursor.addRow(cursor.columnNames.map { values[it] })
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection).also {
        val target = file(documentId); if (!target.exists()) throw FileNotFoundException(); add(it, target)
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = cursor(projection).also { result ->
        val parent = file(parentDocumentId)
        val children = parent.listFiles() ?: throw FileNotFoundException()
        children.forEach { child ->
            val safe = runCatching { file(id(child)) }.getOrNull()
            // Do not follow aliases/symlinks into a second tree or outside shared storage.
            if (safe != null && safe == child.absoluteFile) add(result, safe)
        }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = file(parentDocumentId); val child = file(documentId)
        return child != parent && child.path.startsWith(parent.path + File.separator)
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val target = file(documentId)
        if (!target.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(displayName.isNotBlank() && displayName !in setOf(".", "..") && displayName.length <= 255 && displayName.none { it == '/' || it == '\\' || it.code < 32 })
        val parent = file(parentDocumentId)
        check(parent.isDirectory)
        val target = file("${id(parent)}/$displayName")
        check(if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile())
        return id(target)
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        require(displayName.isNotBlank() && displayName !in setOf(".", "..") && displayName.length <= 255 && displayName.none { it == '/' || it == '\\' || it.code < 32 })
        val original = file(documentId); check(original != base)
        val target = file("${id(original.parentFile!!)}/$displayName")
        check(!target.exists() && original.renameTo(target))
        return id(target)
    }
    override fun deleteDocument(documentId: String) {
        val target = file(documentId); check(target != base)
        // Only individual files and empty directories; never recursively delete a folder.
        check(target.delete())
    }
}
