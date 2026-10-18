package it.ayngallery

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/** Resolve FTP names by enumerating the granted tree, never by constructing document IDs from input. */
internal class FtpSharedFolder(context: Context, tree: Uri) {
    val documents = FileDocuments(context)
    val root: Uri
    init {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
        require(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
        root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        require(documents.stat(root).mime == DocumentsContract.Document.MIME_TYPE_DIR)
    }
    fun children(parent: Uri) = documents.children(parent, 10_000).filter { !it.name.startsWith('.') }
    fun resolve(path: String): DocumentInfo {
        var current = documents.stat(root)
        for (name in components(path)) {
            require(current.mime == DocumentsContract.Document.MIME_TYPE_DIR)
            current = children(current.uri).singleOrNull { it.name == name } ?: throw FileNotFoundException(name)
        }
        return current
    }
    fun destination(path: String): Pair<Uri, String> {
        val parts = components(path)
        require(parts.isNotEmpty())
        val name = parts.last()
        FileNames.validateBase(name)
        val parent = resolve("/" + parts.dropLast(1).joinToString("/"))
        require(parent.mime == DocumentsContract.Document.MIME_TYPE_DIR)
        return parent.uri to name
    }
    companion object {
        fun components(path: String, working: String = "/"): List<String> {
            require(path.length <= 4096 && path.none { it == '\\' || it.code < 32 || it.code == 127 })
            val result = if (path.startsWith('/')) mutableListOf() else components(working).toMutableList()
            for (name in path.split('/')) when (name) {
                "", "." -> Unit
                ".." -> { require(result.isNotEmpty()); result.removeAt(result.lastIndex) }
                else -> { require(!name.startsWith('.')); FileNames.validateBase(name); result += name }
            }
            require(result.size <= 64)
            return result
        }
    }
}
