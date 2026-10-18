package it.ayngallery

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only enumeration within one explicit persisted SAF tree; no catalog import or mutation. */
internal class ExplorerSafFolder(private val context: Context, private val tree: Uri) {
    private val documents = FileDocuments(context)
    private val providerId = "saf:$tree"
    private val known = mutableSetOf<String>()
    private val files = mutableSetOf<String>()
    private val names = mutableMapOf<String, String>()
    fun name(location: ExplorerLocation): String = synchronized(known) { names[location.directoryId].orEmpty() }
    private fun checkGrant() {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
        check(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) {
            localized(R.string.text_media_unavailable_91dca9, "Media non disponibile")
        }
    }

    suspend fun root(): ExplorerLocation = withContext(Dispatchers.IO) {
        checkGrant()
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = documents.stat(root)
        check(document.mime == DocumentsContract.Document.MIME_TYPE_DIR)
        synchronized(known) { known += root.toString(); names[root.toString()] = document.name }
        ExplorerLocation(providerId, root.toString())
    }

    suspend fun list(location: ExplorerLocation): List<ExplorerEntry> = withContext(Dispatchers.IO) {
        checkGrant()
        require(location.providerId == providerId && synchronized(known) { location.directoryId in known })
        val parent = Uri.parse(location.directoryId)
        check(documents.stat(parent).mime == DocumentsContract.Document.MIME_TYPE_DIR)
        val canWrite = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        documents.children(parent, 10_000).filter { !it.name.startsWith(".ayngallery-") }.map { document ->
            val directory = document.mime == DocumentsContract.Document.MIME_TYPE_DIR
            if (directory) synchronized(known) { known += document.uri.toString(); names[document.uri.toString()] = document.name }
            if (!directory) synchronized(known) { files += document.uri.toString() }
            ExplorerEntry(location, document.uri.toString(), document.name, directory,
                document.size.takeIf { it >= 0 }, document.modified.takeIf { it > 0 }, readable = true,
                writable = canWrite && document.flags and (if (directory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0)
        }
    }
    fun registerDirectory(parent: ExplorerLocation, id: String): ExplorerLocation {
        checkGrant(); require(parent.providerId == providerId && synchronized(known) { parent.directoryId in known })
        val created = documents.children(Uri.parse(parent.directoryId), 10_000).single { it.uri.toString() == id && it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
        synchronized(known) { known += id; names[id] = created.name }
        return ExplorerLocation(providerId, id)
    }
    suspend fun resolveDirectory(id: String): ExplorerLocation {
        val start = root()
        if (synchronized(known) { id in known }) return ExplorerLocation(providerId, id)
        val queue = java.util.ArrayDeque<Pair<ExplorerLocation, Int>>().apply { add(start to 0) }
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val (at, depth) = queue.removeFirst()
            check(seen.size < 10_000 && depth <= 32)
            if (!seen.add(at.directoryId)) continue
            if (at.directoryId == id) return at
            list(at).filter { it.directory }.forEach { queue.add(ExplorerLocation(at.providerId, it.id) to depth + 1) }
        }
        throw SecurityException("Folder is outside the authorized tree or unavailable")
    }
    fun stat(entry: ExplorerEntry): ExplorerEntry {
        checkGrant(); require(entry.location.providerId == providerId && synchronized(known) { entry.id in files })
        val info = documents.stat(Uri.parse(entry.id))
        return entry.copy(size = info.size.takeIf { it >= 0 }, modified = info.modified.takeIf { it > 0 })
    }
    fun <T> read(entry: ExplorerEntry, action: (java.io.InputStream) -> T): T { stat(entry); return documents.input(Uri.parse(entry.id)).use(action) }
    fun delete(entry: ExplorerEntry, expected: FileDigest) {
        val before = stat(entry)
        check(before.size == entry.size && before.modified == entry.modified)
        check(read(entry) { networkDigest(it) } == expected)
        val after = stat(entry)
        check(after.size == before.size && after.modified == before.modified)
        check(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission })
        documents.delete(Uri.parse(entry.id))
    }
    fun writable(at: ExplorerLocation): Boolean {
        checkGrant(); require(at.providerId == providerId && synchronized(known) { at.directoryId in known })
        return context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } &&
            documents.stat(Uri.parse(at.directoryId)).flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
    }

}
