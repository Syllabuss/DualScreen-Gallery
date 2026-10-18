package it.ayngallery

import android.content.Intent
import androidx.core.net.toUri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** A linked file is not a folder: query/read only that document, never children or sibling artwork. */
class DocumentLinks(private val catalog: Catalog) {
    private val context get() = catalog.context
    private val dao get() = catalog.dao
    suspend fun link(target: IncomingFile, canPersist: Boolean): String = withContext(Dispatchers.IO) { catalog.writer.withLock {
        linkLocked(target, canPersist)
    } }
    /** Caller owns catalog.writer; the callback is part of the same metadata transaction as the link. */
    internal suspend fun linkLocked(target: IncomingFile, canPersist: Boolean, afterLink: suspend (String) -> Unit = {}): String {
        val uri = target.uri.toUri()
        require(canPersist && DocumentsContract.isDocumentUri(context, uri)) { localized(R.string.text_this_access_cannot_be_retained_choose_the_file_a_2472a4, "Questo accesso non può essere conservato: scegli nuovamente il file dal selettore Android o importa una copia") }
        IncomingMedia.readable(context, target)
        val previousGrant = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) { localized(R.string.text_persistent_access_not_granted_17c407, "Accesso persistente non concesso") }
        val known = dao.sources().firstOrNull { it.treeUri == target.uri && it.singleDocument }
        val source = known?.copy(reachable = true, error = null)
            ?: SourceEntity("document:" + UUID.nameUUIDFromBytes(target.uri.toByteArray(Charsets.UTF_8)), target.uri,
                localized(R.string.text_linked_file_1_s_c1b652, "File collegato · %1\$s", target.name), if (target.kind == "AUDIO") "MUSIC" else "VISUAL")
        try { return catalog.db.withTransaction { val id = scan(source); afterLink(id); id } }
        catch (error: Exception) {
            if (!previousGrant) runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            throw error
        }
    }
    /** Caller holds the catalog writer, as for folder scans. Return the observed logical media ID. */
    suspend fun scan(source: SourceEntity): String {
        require(source.restoreUri == null) { localized(R.string.text_explicitly_relink_the_backup_document_d45acb, "Ricollega esplicitamente il documento del backup") }
        require(source.singleDocument)
        val uri = source.treeUri.toUri(); IncomingMedia.checkUri(uri)
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) { localized(R.string.text_file_access_needs_restoring_5e2b63, "Accesso al file da ripristinare") }
        val fresh = IncomingMedia.inspect(context, IncomingSelection(UUID.randomUUID().toString(), listOf(source.treeUri))).single()
        val key = "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
        val alias = DeviceGallery.alias(context, uri)?.toString()
        val items = dao.media()
        var previous = items.firstOrNull { it.documentKey == key } ?:
            items.firstOrNull { DocumentIdentity.key(it.uri.toUri()) == DocumentIdentity.key(uri) } ?:
            alias?.let { value -> items.firstOrNull { it.deviceUri == value } }
        require(previous?.trashed != true) { localized(R.string.text_the_reference_is_in_the_trash_resolve_it_before_4482d2, "Il riferimento è nel cestino: risolvilo prima di collegarlo") }
        require(dao.operations().none { it.mediaId == previous?.id && !FileOperations.terminal(it.phase) && it.kind in setOf("MOVE", "RENAME", "TRASH") }) { localized(R.string.text_resolve_the_file_operation_first_6c358c, "Risolvi prima l'operazione sul file") }
        val replaced = previous?.takeIf { DocumentVersions.changed(it.size, it.modified, fresh.size, fresh.modified) }
        if (replaced != null) previous = null
        val oldAlbum = previous?.albumId?.let { id -> dao.albums().find { it.id == id } }
        val album = oldAlbum ?: dao.albums().find { it.documentKey == "linked:${source.id}" }
            ?: AlbumEntity(UUID.randomUUID().toString(), "linked:${source.id}", source.treeUri, fresh.name,
                localized(R.string.text_linked_file_1_s_2ebd23, "File collegato / %1\$s", fresh.name), null)
        val item = previous?.copy(documentKey = key, uri = source.treeUri, deviceUri = alias ?: previous.deviceUri,
            name = fresh.name, mime = fresh.mime, kind = fresh.kind, size = fresh.size, modified = fresh.modified, available = true)
            ?: MediaEntity(UUID.randomUUID().toString(), key, source.treeUri, album.id, fresh.name, fresh.mime, fresh.kind, fresh.size, fresh.modified)
        val enriched = if (item.kind in setOf("AUDIO", "VIDEO")) SourceScanner(catalog).metadata(item, uri, null) else item
        val epoch = System.currentTimeMillis()
        catalog.db.withTransaction {
            replaced?.let { dao.saveMedia(listOf(it.copy(documentKey = "unavailable:${it.id}:$key", available = false))) }
            dao.saveSource(source.copy(reachable = true, error = null, lastScan = epoch))
            dao.saveAlbums(listOf(album)); dao.saveAncestors(listOf(AlbumAncestor(album.id, album.id)))
            if (replaced != null && replaced.albumId != album.id) {
                dao.saveAncestors(listOf(AlbumAncestor(album.id, replaced.albumId)) +
                    dao.ancestors().filter { it.albumId == replaced.albumId }.map { it.copy(albumId = album.id) })
            }
            dao.saveAlbumSources(listOf(AlbumSource(album.id, source.id, epoch)))
            dao.saveMedia(listOf(enriched)); dao.saveMembership(listOf(MediaSource(item.id, source.id, epoch)))
        }
        return item.id
    }
}
