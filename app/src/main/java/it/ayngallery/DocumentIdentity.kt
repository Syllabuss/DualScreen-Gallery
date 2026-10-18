package it.ayngallery

import android.net.Uri
import android.provider.DocumentsContract

/** Only documented local providers share identities; arbitrary providers remain independent. */
internal object DocumentIdentity {
    fun inTree(document: Uri, tree: Uri): Uri {
        val id = DocumentsContract.getDocumentId(document)
        val translated = when {
            document.authority == tree.authority -> id
            document.authority == SharedStorageDocuments.AUTHORITY && tree.authority == "com.android.externalstorage.documents" -> {
                require(id == SharedStorageDocuments.ROOT || id.startsWith("${SharedStorageDocuments.ROOT}/"))
                "primary:${id.removePrefix(SharedStorageDocuments.ROOT).trimStart('/')}"
            }
            document.authority == "com.android.externalstorage.documents" && tree.authority == SharedStorageDocuments.AUTHORITY -> {
                require(id.startsWith("primary:"))
                SharedStorageDocuments.ROOT + id.removePrefix("primary:").let { if (it.isEmpty()) "" else "/$it" }
            }
            else -> error("Unrelated document providers")
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, translated)
    }
    fun key(uri: Uri, tree: Boolean = false): String = runCatching {
        val id = if (tree) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
        when (uri.authority) {
            SharedStorageDocuments.AUTHORITY -> {
                require(id == SharedStorageDocuments.ROOT || id.startsWith("${SharedStorageDocuments.ROOT}/"))
                "local:primary:${id.removePrefix(SharedStorageDocuments.ROOT).trimStart('/')}"
            }
            "com.android.externalstorage.documents" -> {
                require(':' in id)
                "local:${id.substringBefore(':').lowercase()}:${id.substringAfter(':')}"
            }
            else -> "${uri.authority}:$id"
        }
    }.getOrElse { uri.toString() }
}
