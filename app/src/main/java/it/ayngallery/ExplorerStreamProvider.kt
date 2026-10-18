package it.ayngallery

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.*
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.util.UUID

internal data class ExplorerStream(val entry: ExplorerEntry, val folder: ExplorerSmbFolder)
/** Session-only, own-process, read-only handles. No server path is accepted from a caller. */
class ExplorerStreamProvider : ContentProvider() {
    private val app get() = context!!.applicationContext as GalleryApp
    override fun onCreate() = true
    private fun source(uri: Uri): ExplorerStream {
        check(android.os.Binder.getCallingUid() == android.os.Process.myUid())
        require(uri.authority == "${app.packageName}.network" && uri.pathSegments.size == 1)
        return app.explorer.streams[uri.lastPathSegment] ?: throw java.io.FileNotFoundException()
    }
    override fun getType(uri: Uri): String = source(uri).entry.name.substringAfterLast('.', "").lowercase().let {
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) ?: "application/octet-stream"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val entry = source(uri).entry
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "last_modified")
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> entry.name; OpenableColumns.SIZE -> entry.size; "last_modified" -> entry.modified; else -> null } }) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val stream = source(uri)
        val file = stream.folder.openRead(stream.entry)
        val thread = HandlerThread("network-preview").apply { start() }
        try {
            return app.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = stream.entry.size ?: throw ErrnoException("size", OsConstants.EIO)
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                        if (offset < 0) throw java.io.IOException()
                        file.read(data, offset, 0, minOf(size, data.size)).coerceAtLeast(0)
                    } catch (_: Exception) { throw ErrnoException("read", OsConstants.EIO) }
                    override fun onRelease() { try { file.close() } finally { thread.quitSafely() } }
                }, Handler(thread.looper))
        } catch (failure: Exception) { file.close(); thread.quitSafely(); throw failure }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw SecurityException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException()
}
