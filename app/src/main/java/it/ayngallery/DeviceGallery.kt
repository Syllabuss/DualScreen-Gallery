package it.ayngallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Android's visual library; music continues to use explicitly selected SAF folders. */
class DeviceGallery(private val catalog: Catalog) {
    private val context get() = catalog.context
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }
    fun observe() {
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
    }
    fun close() { context.contentResolver.unregisterContentObserver(observer) }
    fun refresh(force: Boolean = true) {
        (context as? GalleryApp)?.albums?.refreshDevice(force)
    }
    suspend fun scan(source: SourceEntity) { read(source, persist = true) }
    suspend fun browse(source: SourceEntity): CatalogSnapshot = read(source, persist = false)
    private suspend fun read(source: SourceEntity, persist: Boolean): CatalogSnapshot {
        val dao = catalog.dao
        if (!hasAccess(context)) {
            val unavailable = source.copy(reachable = false, error = localized(R.string.text_allow_access_to_photos_and_videos_d1906e, "Consenti l'accesso a foto e video"))
            if (persist) dao.saveSource(unavailable)
            return CatalogSnapshot(sources = listOf(unavailable), initialized = true)
        }
        val epoch = maxOf(System.currentTimeMillis(), source.lastScan + 1)
        val existing = dao.media().toMutableList()
        val byUri = mutableMapOf<String, MediaEntity>()
        for (item in existing.filter { it.kind != "AUDIO" }) {
            val alias = item.deviceUri ?: alias(context, Uri.parse(item.uri))?.toString()
            if (alias != null && !item.trashed && !item.documentKey.startsWith("unavailable:")) byUri[alias] = item
        }
        val albums = dao.albums().associateBy { it.documentKey }.toMutableMap()
        val sources = dao.sources()
        val musicFolders = sources.filter { it.role == "MUSIC" }.mapNotNull { physicalFolder(Uri.parse(it.treeUri)) }
        val oldLinks = dao.membership()
        val seen = mutableSetOf<String>()
        val writtenAlbums = mutableSetOf<String>()
        val rows = mutableListOf<MediaEntity>(); val links = mutableListOf<MediaSource>()
        val readRows = mutableListOf<MediaEntity>(); val readLinks = mutableListOf<MediaSource>()
        var count = 0
        suspend fun flush() {
            if (rows.isEmpty()) return
            if (persist) catalog.db.withTransaction { dao.saveMedia(rows); dao.saveMembership(links) }
            else { readRows.addAll(rows); readLinks.addAll(links) }
            rows.clear(); links.clear()
            if (persist) catalog.progress.value = ScanProgress(source.title, count, true)
        }
        val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context).ifEmpty { setOf("external") } else setOf("external")
        for (volume in volumes.sorted()) for (kind in listOf("IMAGE", "VIDEO")) {
            currentCoroutineContext().ensureActive()
            if (!canRead(context, kind)) continue
            val table = if (kind == "IMAGE") {
                if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val columns = mutableListOf("_id", "_display_name", "mime_type", "_size", "date_modified", "bucket_id", "bucket_display_name")
            columns += if (Build.VERSION.SDK_INT >= 29) "relative_path" else "_data"
            if (kind == "VIDEO") columns += "duration"
            if (Build.VERSION.SDK_INT >= 29) columns += "volume_name"
            val selection = when { Build.VERSION.SDK_INT >= 30 -> "is_pending=0 AND is_trashed=0"; Build.VERSION.SDK_INT >= 29 -> "is_pending=0"; else -> null }
            val cursor = context.contentResolver.query(table, columns.toTypedArray(), selection, null, "_id ASC")
                ?: throw java.io.IOException(localized(R.string.text_android_gallery_did_not_respond_52ab35, "La galleria Android non ha risposto"))
            cursor.use {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val actualVolume = if (Build.VERSION.SDK_INT >= 29) it.getString(if (kind == "VIDEO") 9 else 8)?.takeIf(String::isNotBlank) ?: volume else volume
                    val actualTable = if (Build.VERSION.SDK_INT >= 29) {
                        if (kind == "IMAGE") MediaStore.Images.Media.getContentUri(actualVolume) else MediaStore.Video.Media.getContentUri(actualVolume)
                    } else table
                    val uri = ContentUris.withAppendedId(actualTable, it.getLong(0))
                    val name = it.getString(1) ?: localized(R.string.text_unnamed_034324, "Senza nome")
                    if (name.startsWith(".ayngallery-")) continue
                    val mime = it.getString(2) ?: if (kind == "IMAGE") "image/jpeg" else "video/mp4"
                    val mediaKind = MediaNames.kind(mime, name) ?: continue
                    val size = if (it.isNull(3)) -1L else it.getLong(3)
                    val modified = if (it.isNull(4)) 0L else it.getLong(4) * 1000L
                    val location = if (Build.VERSION.SDK_INT >= 29) actualVolume to it.getString(7).orEmpty()
                        else legacyFolder(it.getString(7).orEmpty())
                    val path = location.second
                    if (musicFolders.any { folder -> (location.first == folder.first || location.first == "external") &&
                            (folder.second.isEmpty() || path.trimEnd('/') == folder.second || path.startsWith(folder.second + "/")) }) continue
                    val duration = if (kind == "VIDEO" && !it.isNull(8)) it.getLong(8) else null
                    val bucket = it.getString(5) ?: path
                    val albumKey = "device:$actualVolume:$bucket"
                    var previous = byUri[uri.toString()]
                    if (previous != null && DocumentVersions.changed(previous.size, previous.modified, size, modified)) {
                        if (persist) rows += previous.copy(documentKey = "unavailable:${previous.id}:${previous.documentKey}", available = false)
                        previous = null
                    }
                    val existingAlbum = previous?.takeIf { !it.documentKey.startsWith("device:") }?.albumId?.let { id -> albums.values.find { album -> album.id == id } }
                    val oldAlbum = existingAlbum ?: albums[albumKey]
                    val album = oldAlbum ?: AlbumEntity(if (persist) UUID.randomUUID().toString() else UUID.nameUUIDFromBytes(albumKey.toByteArray(Charsets.UTF_8)).toString(), albumKey, table.toString(),
                        it.getString(6)?.takeIf(String::isNotBlank) ?: localized(R.string.text_images_a9f46a, "Immagini"), path.ifBlank { localized(R.string.text_device_gallery_1354db, "Galleria del dispositivo") }, null)
                    albums[album.documentKey] = album
                    if (writtenAlbums.add(album.id) && persist) catalog.db.withTransaction {
                        dao.saveAlbums(listOf(album)); dao.saveAlbumSources(listOf(AlbumSource(album.id, SOURCE, epoch)))
                        dao.saveAncestors(listOf(AlbumAncestor(album.id, album.id)))
                    }
                    val item = previous?.copy(albumId = album.id, name = name, mime = mime, kind = mediaKind.name,
                        size = size, modified = modified, duration = duration, deviceUri = uri.toString(), available = true)
                        ?: MediaEntity(if (persist) UUID.randomUUID().toString() else UUID.nameUUIDFromBytes("$uri|$size|$modified".toByteArray(Charsets.UTF_8)).toString(),
                            "device:$uri", uri.toString(), album.id, name, mime, mediaKind.name, size, modified, duration = duration, deviceUri = uri.toString())
                    rows += item; links += MediaSource(item.id, SOURCE, if (persist) epoch else source.lastScan); seen += item.id; count++
                    if (rows.size >= 150) flush()
                }
            }
        }
        flush()
        if (!persist) return CatalogSnapshot(sources = listOf(source.copy(reachable = true, error = null)),
            albums = albums.values.filter { it.id in writtenAlbums }, media = readRows, membership = readLinks,
            ancestry = albums.values.filter { it.id in writtenAlbums }.map { AlbumAncestor(it.id, it.id) },
            albumMembership = writtenAlbums.map { AlbumSource(it, source.id, source.lastScan) }, initialized = true)
        val stale = oldLinks.filter { it.sourceId == SOURCE && it.mediaId !in seen }.map { it.mediaId }.toSet()
        val other = sources.associateBy { it.id }
        val retained = oldLinks.filter { it.sourceId != SOURCE && it.mediaId in stale && other[it.sourceId]?.let { s -> s.enabled && s.reachable && s.lastScan == it.scan } == true }.map { it.mediaId }.toSet()
        catalog.db.withTransaction {
            dao.missingAlbums(SOURCE, epoch)
            dao.saveMedia(dao.media().filter { it.id in stale && it.id !in retained && !it.trashed }.map { it.copy(available = false) })
            dao.saveSource(source.copy(reachable = true, error = null, lastScan = epoch))
        }
        catalog.progress.value = ScanProgress(source.title, count, false)
        return CatalogSnapshot(initialized = true)
    }
    companion object {
        const val SOURCE = "device-visual"
        const val ROOT = "content://media/"
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        private fun granted(context: Context, name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        fun canRead(context: Context, kind: String) = if (Build.VERSION.SDK_INT < 33) granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            else granted(context, if (kind == "VIDEO") Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES) ||
                (Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        fun hasAccess(context: Context) = canRead(context, "IMAGE") || canRead(context, "VIDEO")
        fun fullAccess(context: Context) = if (Build.VERSION.SDK_INT < 33) granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            else granted(context, Manifest.permission.READ_MEDIA_IMAGES) && granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        fun visibleNow(context: Context, uri: Uri, kind: String): Boolean {
            if (!isMedia(uri) || !canRead(context, kind)) return false
            return try { context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } == true }
            catch (_: Exception) { false }
        }
        fun isMedia(uri: Uri) = uri.scheme == "content" && uri.authority == "media" && uri.pathSegments.size == 4 &&
            uri.pathSegments[1] in setOf("images", "video") && uri.pathSegments[2] == "media" && uri.lastPathSegment?.toLongOrNull() != null
        fun alias(context: Context, uri: Uri): Uri? {
            val media = if (isMedia(uri)) uri else if (Build.VERSION.SDK_INT >= 29 && DocumentsContract.isDocumentUri(context, uri))
                runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() else null
            if (media == null || media.authority != "media" || media.scheme != "content") return null
            if (Build.VERSION.SDK_INT < 29 || (isMedia(media) && media.pathSegments.first() != "external")) return media.takeIf(::isMedia)
            // SAF can return external/file/ID or an aggregate external URI. Resolve its actual volume and visual table.
            return runCatching {
                context.contentResolver.query(media, arrayOf("_id", "mime_type", "volume_name"), null, null, null)?.use { row ->
                    if (!row.moveToFirst()) return@use null
                    val mime = row.getString(1).orEmpty(); val volume = row.getString(2)?.takeIf(String::isNotBlank) ?: media.pathSegments.first()
                    val table = when { mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume)
                        mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume); else -> return@use null }
                    ContentUris.withAppendedId(table, row.getLong(0))
                }
            }.getOrNull() ?: media.takeIf(::isMedia)
        }
        private fun physicalFolder(uri: Uri): Pair<String, String>? = runCatching {
            if (uri.authority != "com.android.externalstorage.documents" || !DocumentsContract.isTreeUri(uri)) return@runCatching null
            val id = DocumentsContract.getTreeDocumentId(uri)
            val volume = id.substringBefore(':').let { if (it == "primary") "external_primary" else it.lowercase() }
            volume to id.substringAfter(':', "").trim('/')
        }.getOrNull()
        private fun legacyFolder(path: String): Pair<String, String> {
            val folder = path.substringBeforeLast('/', "")
            val primary = listOf("/storage/emulated/0/", "/sdcard/").firstOrNull { folder.startsWith(it) }
            if (primary != null) return "external_primary" to folder.removePrefix(primary)
            if (folder.startsWith("/storage/")) {
                val local = folder.removePrefix("/storage/")
                return local.substringBefore('/').lowercase() to local.substringAfter('/', "")
            }
            return "external" to folder
        }
    }
}
