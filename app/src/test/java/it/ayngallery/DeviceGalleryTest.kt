package it.ayngallery

import android.Manifest
import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class DeviceGalleryTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: DeviceMediaFixture
    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        provider = DeviceMediaFixture()
        provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        catalog = Catalog(app)
        catalog.dao.saveSource(SourceEntity(DeviceGallery.SOURCE, DeviceGallery.ROOT, "Device", "VISUAL"))
        Unit
    }
    @After fun close() = runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close() }
    private fun grant() = shadowOf(catalog.context as Application).grantPermissions(*DeviceGallery.permissions())
    private suspend fun scan() = DeviceGallery(catalog).scan(catalog.dao.source(DeviceGallery.SOURCE)!!)
    private suspend fun seedLegacyCollection(id: String) {
        catalog.dao.saveCollection(CollectionEntity("keep", "Keep", "MEDIA"))
        catalog.dao.saveCollectionItems(listOf(CollectionItem("keep", id, 0)))
    }
    @Test @Config(sdk = [34], qualifiers = "it") fun singleDocumentSelectionAdoptsDeviceGalleryIdentityInsteadOfDuplicatingThePhysicalFile() = runBlocking {
        grant()
        val documents = OperationDocuments()
        val info = ProviderInfo().apply { authority = "ayngallery.single-devicealias"; exported = true; grantUriPermissions = true
            packageName = catalog.context.packageName; name = OperationDocuments::class.java.name
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission }
        documents.attachInfo(catalog.context, info)
        shadowOf(catalog.context.packageManager).addOrUpdateProvider(info)
        shadowOf(catalog.context.packageManager).addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        ShadowContentResolver.registerProviderInternal(info.authority, documents)
        provider.documentAlias = Uri.parse("content://media/external/file/1")
        documents.nodes.getValue("image").file!!.writeBytes(provider.file.readBytes()); documents.nodes.getValue("image").name = "photo.png"
        provider.entries.first { it.id == 1L }.modified = 0
        scan()
        val physical = "content://media/external_primary/images/media/1"
        val before = catalog.dao.media().single { it.deviceUri == physical }
        catalog.favorite(before.id)
        val uri = DocumentsContract.buildDocumentUri(info.authority, "image")
        val target = IncomingMedia.inspect(catalog.context, IncomingSelection(java.util.UUID.randomUUID().toString(), listOf(uri.toString()))).single()
        assertEquals(before.id, DocumentLinks(catalog).link(target, true))
        assertEquals(3, catalog.dao.media().size); assertTrue(catalog.dao.media(before.id)!!.favorite)
        // Losing visual permission must leave the explicitly linked, read-only document usable.
        shadowOf(catalog.context as Application).denyPermissions(*DeviceGallery.permissions())
        val data = catalog.readMediaSnapshot(before.id); val actual = data.byId.getValue(before.id)
        assertTrue(data.usable(actual)); assertEquals(uri, catalog.uri(actual, data))
        assertEquals(0, documents.childQueries); assertTrue(documents.deletions.isEmpty())
    }
    @Test @Config(sdk = [34], qualifiers = "it") fun safFileAndAggregateUrisResolveToOnePhysicalImageAndKeepItsFavorite() = runBlocking {
        grant()
        val documents = OperationDocuments()
        val info = ProviderInfo().apply { authority = "ayngallery.devicealias"; exported = true; grantUriPermissions = true
            packageName = catalog.context.packageName; name = OperationDocuments::class.java.name
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission }
        documents.attachInfo(catalog.context, info)
        shadowOf(catalog.context.packageManager).addOrUpdateProvider(info)
        shadowOf(catalog.context.packageManager).addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        ShadowContentResolver.registerProviderInternal("ayngallery.devicealias", documents)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.devicealias", "root")
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, "image")
        provider.documentAlias = Uri.parse("content://media/external/file/1")
        val physical = Uri.parse("content://media/external_primary/images/media/1")
        assertEquals(physical, DeviceGallery.alias(catalog.context, doc))
        assertEquals(physical, DeviceGallery.alias(catalog.context, Uri.parse("content://media/external/images/media/1")))
        val source = SourceEntity("saf", tree.toString(), "SAF", "VISUAL")
        catalog.context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        catalog.dao.saveSource(source)
        documents.nodes.getValue("image").file!!.writeBytes(provider.file.readBytes())
        documents.nodes.getValue("image").name = "photo.png"
        provider.entries.first { it.id == 1L }.modified = 0 // fixture document metadata uses milliseconds, zero means unknown
        scan()
        val media = catalog.dao.media().single { it.deviceUri == physical.toString() }
        catalog.favoriteAll(listOf(media.id), true)
        catalog.hideAlbums(listOf(media.albumId), true)
        SourceScanner(catalog).scan(source)
        assertEquals(3, catalog.dao.media().size); assertTrue(catalog.dao.media(media.id)!!.favorite)
        assertEquals(media.id, catalog.dao.media().single { it.deviceUri == physical.toString() }.id)
        assertFalse(catalog.readSnapshot().visible(catalog.dao.media(media.id)!!))
        catalog.hideAlbums(listOf(media.albumId), false)
        assertEquals(source.id, catalog.readSnapshot().sourceFor(catalog.dao.media(media.id)!!)!!.id)
        assertEquals(doc, catalog.uri(catalog.dao.media(media.id)!!, catalog.readSnapshot()))
        scan(); assertEquals(3, catalog.dao.media().size); assertTrue(catalog.dao.media(media.id)!!.favorite)
    }

    @Test fun deviceImagesGifAndVideoHaveStableAlbumsAndFavoritesAndNeverScanAudio() = runBlocking {
        grant(); scan()
        val initial = catalog.dao.media()
        assertEquals(setOf("IMAGE", "GIF", "VIDEO"), initial.map { it.kind }.toSet())
        assertEquals(3, initial.size); assertEquals(2, catalog.dao.albums().size)
        val image = initial.single { it.kind == "IMAGE" }
        catalog.favoriteAll(listOf(image.id), true); seedLegacyCollection(image.id)
        assertEquals(42_000L, initial.single { it.kind == "VIDEO" }.duration)
        scan()
        assertEquals(initial.map { it.id }.toSet(), catalog.dao.media().map { it.id }.toSet())
        assertTrue(catalog.dao.media(image.id)!!.favorite)
        assertEquals(image.id, catalog.dao.collectionItems().single().mediaId)
        assertTrue(provider.queried.none { it.contains("audio") })
        assertFalse(catalog.readSnapshot().usable(catalog.dao.media(image.id)!!))
    }
    @Test fun revokedPermissionPerformsNoMediaQueryAndConservesReferences() = runBlocking {
        grant(); scan(); val image = catalog.dao.media().first()
        val cached = catalog.readSnapshot()
        catalog.favoriteAll(listOf(image.id), true); seedLegacyCollection(image.id)
        shadowOf(catalog.context as Application).denyPermissions(*DeviceGallery.permissions())
        assertThrows(SecurityException::class.java) { catalog.uri(image, cached) }
        provider.queried.clear(); scan()
        assertTrue(provider.queried.isEmpty()); assertFalse(catalog.dao.source(DeviceGallery.SOURCE)!!.reachable)
        assertFalse(catalog.readMediaSnapshot(image.id).usable(catalog.dao.media(image.id)!!))
        assertTrue(catalog.dao.media(image.id)!!.favorite); assertEquals(image.id, catalog.dao.collectionItems().single().mediaId)
        grant(); scan(); assertFalse(catalog.readMediaSnapshot(image.id).usable(catalog.dao.media(image.id)!!))
    }
    @Test @Config(sdk = [34], qualifiers = "it") fun partialPhotoAccessRechecksSelectionAndRejectsSharingARevokedItem() = runBlocking {
        shadowOf(catalog.context as Application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        provider.selected = setOf("images:1"); scan()
        val image = catalog.dao.media().single(); catalog.favoriteAll(listOf(image.id), true)
        assertFalse(catalog.readMediaSnapshot(image.id).usable(image))
        provider.selected = setOf("video:3")
        assertFalse(catalog.readMediaSnapshot(image.id).usable(image))
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            ExternalMedia.prepare(catalog, ExternalRequest(ExternalAction.SHARE, listOf(ExternalTarget.from(image))))
        } }
        scan(); assertFalse(catalog.dao.media(image.id)!!.available); assertTrue(catalog.dao.media(image.id)!!.favorite)
        assertEquals(1, catalog.dao.media().count { it.available }); assertEquals("VIDEO", catalog.dao.media().single { it.available }.kind)
    }
    @Test fun musicFolderCoverIsExcludedAndChangedFileCannotInheritOldFavorite() = runBlocking {
        grant()
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
        catalog.dao.saveSource(SourceEntity("music", tree.toString(), "Music", "MUSIC"))
        provider.entries += DeviceMediaFixture.Entry(4, "images", "cover.jpg", "image/jpeg", "Music/Record/", "record")
        scan(); assertFalse(catalog.dao.media().any { it.name == "cover.jpg" })
        val old = catalog.dao.media().single { it.name == "photo.png" }
        catalog.favoriteAll(listOf(old.id), true)
        provider.entries.first { it.id == 1L }.modified = 2
        scan()
        assertFalse(catalog.dao.media(old.id)!!.available); assertTrue(catalog.dao.media(old.id)!!.favorite)
        val replacement = catalog.dao.media().single { it.name == old.name && it.available }
        assertNotEquals(old.id, replacement.id); assertFalse(replacement.favorite)
    }
    @Test fun existingSafAliasIsReusedAndNativeCopyNeverDeletesOriginal() = runBlocking {
        grant()
        val app = catalog.context
        val destination = OperationDocuments()
        destination.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.devicecopy"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.devicecopy", destination)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.devicecopy", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("destination", tree.toString(), "Destination", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        val existing = catalog.dao.media().single()
        // Fixture aliases model the public MediaStore.getMediaUri mapping; that platform mapping requires a device check.
        val gif = provider.entries.single { it.id == 2L }
        catalog.dao.saveMedia(listOf(existing.copy(deviceUri = "content://media/external/images/media/2", name = gif.name,
            mime = gif.mime, kind = "GIF", size = provider.file.length(), modified = gif.modified * 1000, favorite = true)))
        scan(); assertEquals(existing.id, catalog.dao.media().single { it.deviceUri?.endsWith("/2") == true }.id)
        assertTrue(catalog.dao.media(existing.id)!!.favorite)
        assertEquals(source.id, catalog.readSnapshot().sourceFor(catalog.dao.media(existing.id)!!)!!.id)
        val image = catalog.dao.media().single { it.name == "photo.png" }
        val files = FileOperations(catalog)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { files.enqueue(image, "COPY", destinationAlbumId = existing.albumId, name = "device-copy.png", start = false) } }
        val selected = catalog.dao.media(existing.id)!!.copy(size = destination.nodes.getValue("image").file!!.length(), modified = 0)
        destination.nodes.getValue("image").name = selected.name
        catalog.dao.saveMedia(listOf(selected))
        val id = files.enqueue(selected, "COPY", destinationAlbumId = existing.albumId, name = "selected-copy.gif", start = false)
        files.execute(id); val operation = catalog.dao.operation(id)!!
        assertEquals(operation.message, "COMPLETED", operation.phase)
        assertArrayEquals(destination.nodes.getValue("image").file!!.readBytes(), destination.node(operation.publishedUri!!).file!!.readBytes())
        assertEquals(0, provider.deletes); assertTrue(catalog.dao.media(image.id)!!.available)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { files.enqueue(image, "TRASH", start = false) } }
        assertEquals(0, provider.deletes)
    }
}

/** ContentProvider fixture for Android's image/video query and stream contract, not a Thor media index. */
class DeviceMediaFixture : ContentProvider() {
    data class Entry(val id: Long, val type: String, val name: String, val mime: String, val path: String, val bucket: String, var modified: Long = 1)
    val entries = mutableListOf(Entry(1, "images", "photo.png", "image/png", "DCIM/Camera/", "camera"),
        Entry(2, "images", "animation.gif", "image/gif", "DCIM/Camera/", "camera"),
        Entry(3, "video", "movie.mp4", "video/mp4", "Movies/", "movies"))
    var selected: Set<String>? = null
    var documentAlias: Uri? = null
    val queried = mutableListOf<String>()
    lateinit var file: File
    var deletes = 0
    override fun onCreate(): Boolean { file = File(context!!.cacheDir, "device-fixture").apply { writeBytes("native media bytes".toByteArray()) }; return true }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        queried += uri.toString()
        val columns = projection ?: arrayOf("_id")
        val type = uri.pathSegments.getOrNull(1)
        val id = uri.lastPathSegment?.toLongOrNull()
        return MatrixCursor(columns).apply {
            entries.filter { (it.type == type || type == "file") && (id == null || it.id == id) && (selected == null || "${it.type}:${it.id}" in selected!!) }.forEach { item ->
                addRow(columns.map<String, Any?> { column -> when (column) {
                    "_id" -> item.id; "_display_name" -> item.name; "mime_type" -> item.mime; "_size" -> file.length()
                    "date_modified" -> item.modified; "bucket_id" -> item.bucket; "bucket_display_name" -> item.bucket
                    "relative_path" -> item.path; "_data" -> "/storage/emulated/0/${item.path}${item.name}"; "duration" -> 42_000L
                    "volume_name" -> "external_primary"
                    else -> null
                } }.toTypedArray())
            }
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode == "r"); return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        if (method == "get_media_uri") Bundle().apply { putParcelable("uri", documentAlias) } else super.call(method, arg, extras)
    override fun getType(uri: Uri) = entries.firstOrNull { it.id == uri.lastPathSegment?.toLongOrNull() }?.mime
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Read only")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { deletes++; error("Read only") }
}
