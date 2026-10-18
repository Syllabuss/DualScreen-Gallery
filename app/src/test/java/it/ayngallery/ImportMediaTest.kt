package it.ayngallery

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

/** File-only provider: selected content has no tree, no writable API and is not a catalog source. */
class IncomingFixture : ContentProvider() {
    data class Entry(val name: String, val mime: String, val file: File)
    val entries = linkedMapOf<String, Entry>()
    val queries = mutableListOf<String>()
    val modes = mutableListOf<String>()
    var revoked = false; var unknownSize = false; var changedTime = 0L
    var onRead: (String) -> Unit = {}
    override fun onCreate() = true
    fun add(name: String = "selected.png", mime: String = "image/png", bytes: ByteArray): Uri {
        val key = UUID.randomUUID().toString(); val file = File(context!!.filesDir, "incoming-fixture-$key").apply { writeBytes(bytes) }
        entries[key] = Entry(name, mime, file)
        return Uri.parse("content://ayngallery.incoming-fixture/media/$key")
    }
    private fun entry(uri: Uri): Entry { if (revoked) throw SecurityException("Fixture revoked"); return entries.getValue(uri.lastPathSegment!!) }
    override fun getType(uri: Uri) = entry(uri).mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
        require(projection == null || projection.all { it in setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) }) { "Only OpenableColumns are supported" }
        val entry = entry(uri); queries += uri.toString()
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> { when (it) {
            OpenableColumns.DISPLAY_NAME -> entry.name
            OpenableColumns.SIZE -> if (unknownSize) null else entry.file.length()
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> changedTime
            else -> null
        } }.toTypedArray()) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        modes += mode; require(mode == "r"); onRead(uri.toString())
        return ParcelFileDescriptor.open(entry(uri).file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?) = throw AssertionError("No external creation")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = throw AssertionError("No external writes")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = throw AssertionError("No external deletion")
    companion object {
        fun png(): ByteArray {
            val image = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888); image.eraseColor(Color.MAGENTA); image.setPixel(7, 3, Color.TRANSPARENT)
            return ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); image.recycle(); it.toByteArray() }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportMediaTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var catalog: Catalog
    private lateinit var destination: OperationDocuments
    private lateinit var incoming: IncomingFixture
    private lateinit var files: FileOperations
    private lateinit var target: IncomingFile
    private lateinit var album: AlbumEntity
    private lateinit var original: MediaEntity
    private lateinit var sourceBytes: ByteArray
    @Before fun start(): Unit = runBlocking {
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        destination = OperationDocuments(); destination.attachInfo(app, ProviderInfo().apply {
            authority = "ayngallery.import-destination"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("ayngallery.import-destination", destination)
        incoming = IncomingFixture(); incoming.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.incoming-fixture"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("ayngallery.incoming-fixture", incoming)
        catalog = Catalog(app); files = FileOperations(catalog)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.import-destination", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("destination", tree.toString(), "Destination fixture", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        album = catalog.dao.albums().single { it.name == "Destination" }; original = catalog.dao.media().single()
        catalog.favorite(original.id); original = catalog.dao.media(original.id)!!; catalog.collection("Original refs", "MEDIA", listOf(original.id))
        sourceBytes = IncomingFixture.png(); val uri = incoming.add(bytes = sourceBytes)
        target = IncomingMedia.inspect(app, IncomingSelection(UUID.randomUUID().toString(), listOf(uri.toString()))).single()
    }
    @After fun close() = runBlocking { if (::catalog.isInitialized) { catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close() } }
    private suspend fun enqueue(keepBoth: Boolean = true) = files.enqueueImport(target, album.id, keepBoth = keepBoth, start = false)
    private fun stage(id: String, extension: String = "bin") = File(app.filesDir, "imports/$id.$extension")
    private suspend fun assertReferences() {
        assertEquals(original, catalog.dao.media(original.id)); assertEquals(original.id, catalog.dao.collectionItems().single().mediaId)
        assertTrue(catalog.dao.trash().isEmpty()); assertTrue(incoming.modes.all { it == "r" })
        assertArrayEquals(destination.originalBytes, destination.nodes.getValue("image").file!!.readBytes())
    }
    @Test fun plainSelectedFileIsCopiedWithVerifiedBytesAndNewReferencesButNoPersistentExternalAccess() = runBlocking {
        val grants = app.contentResolver.persistedUriPermissions.map { it.uri }
        val id = enqueue(); files.execute(id); val op = catalog.dao.operation(id)!!
        assertEquals(op.message, "COMPLETED", op.phase); assertEquals("IMPORT", op.kind)
        val copy = catalog.dao.media(op.resultMediaId)!!
        assertArrayEquals(sourceBytes, destination.node(copy.uri).file!!.readBytes()); assertFalse(copy.favorite)
        assertEquals("IMAGE", copy.kind); assertEquals(album.id, copy.albumId); assertNotEquals(original.id, copy.id)
        assertFalse(stage(id).exists()); assertFalse(stage(id, "part").exists())
        assertEquals(2, catalog.dao.media().size); assertEquals(1, catalog.dao.sources().size)
        assertEquals(grants, app.contentResolver.persistedUriPermissions.map { it.uri }); assertReferences()
        incoming.revoked = true
        assertEquals(id, enqueue()); files.execute(id)
        assertEquals(2, catalog.dao.media().size) // Stable selection/rotation does not import twice.
    }
    @Test fun unknownSizeAndMissingModifiedMetadataStillRequireRealVerifiedAcquisition() = runBlocking {
        incoming.unknownSize = true
        target = IncomingMedia.inspect(app, IncomingSelection(UUID.randomUUID().toString(), listOf(target.uri))).single()
        assertEquals(-1, target.size); assertEquals(0, target.modified)
        val id = enqueue(); files.execute(id); val op = catalog.dao.operation(id)!!
        assertEquals(op.message, "COMPLETED", op.phase); assertEquals(sourceBytes.size.toLong(), op.bytes)
        assertEquals(sourceBytes.size.toLong(), op.expectedSize); assertEquals(sourceBytes.size.toLong(), ImportRecipe.decode(op.recipe!!).bytes)
        assertReferences()
    }
    @Test fun keepBothResolvesOccupiedNameWhileAnUnapprovedConflictCanBeCancelledSafely() = runBlocking {
        val existing = destination.add("destination", target.proposedName, byteArrayOf(1, 2, 3))
        val id = enqueue(); files.execute(id); val success = catalog.dao.operation(id)!!
        assertEquals("COMPLETED", success.phase); assertEquals("selected (1).png", success.finalName)
        assertArrayEquals(byteArrayOf(1, 2, 3), existing.file!!.readBytes())
        target = target.copy(id = UUID.randomUUID().toString())
        val rejected = enqueue(keepBoth = false); files.execute(rejected)
        val conflict = catalog.dao.operation(rejected)!!; assertEquals("FAILED", conflict.phase); assertNull(conflict.publishedUri)
        assertTrue(stage(rejected).exists()); assertTrue(conflict.message!!.contains("Nome già occupato"))
        files.cancel(rejected); files.execute(rejected)
        assertEquals("CANCELLED", catalog.dao.operation(rejected)!!.phase); assertFalse(stage(rejected).exists())
        assertFalse(destination.nodes.values.any { it.name.startsWith(".ayngallery-$rejected.") }); assertReferences()
    }
    @Test fun aVerifiedPrivateStageSurvivesExternalRevocationAndRecoversWithoutAnotherAcquisition() = runBlocking {
        destination.canRename = false
        val id = enqueue(); files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase); assertTrue(stage(id).exists())
        val reads = incoming.modes.size; incoming.revoked = true; destination.canRename = true
        files = FileOperations(catalog); files.execute(id)
        val op = catalog.dao.operation(id)!!; assertEquals(op.message, "COMPLETED", op.phase)
        assertEquals(reads, incoming.modes.size); assertArrayEquals(sourceBytes, destination.node(op.publishedUri!!).file!!.readBytes())
        assertFalse(stage(id).exists()); assertReferences()
    }
    @Test fun deniedStageCleanupLeavesThePublishedCopyRecoverableWithoutDuplicatingOrResettingFavorites() = runBlocking {
        var deny = true; files = FileOperations(catalog, deletePrivate = { if (deny) false else it.delete() })
        val id = enqueue(); files.execute(id); val first = catalog.dao.operation(id)!!
        assertEquals("PARTIAL", first.phase); assertTrue(stage(id).exists()); assertNotNull(catalog.dao.media(first.resultMediaId))
        catalog.favorite(first.resultMediaId); incoming.revoked = true; val creations = destination.creations
        deny = false; files.execute(id)
        assertEquals("COMPLETED", catalog.dao.operation(id)!!.phase); assertFalse(stage(id).exists())
        assertEquals(creations, destination.creations); assertTrue(catalog.dao.media(first.resultMediaId)!!.favorite)
        assertEquals(2, catalog.dao.media().size); assertReferences()
    }
    @Test fun cancellationDuringAcquisitionRemovesOnlyItsPrivatePartAndNeverCreatesADestination() = runBlocking {
        val id = enqueue(); incoming.onRead = { runBlocking { if (catalog.dao.operation(id)!!.phase == "STAGING") files.cancel(id) } }
        files.execute(id); val op = catalog.dao.operation(id)!!
        assertEquals("CANCELLED", op.phase); assertEquals(0, destination.creations)
        assertFalse(stage(id).exists()); assertFalse(stage(id, "part").exists()); assertReferences()
    }
    @Test fun insufficientPrivateSpaceAndRevokedAccessNeverFallBackToSourceRemoval() = runBlocking {
        val id = enqueue(); ShadowStatFs.registerStats(app.filesDir, 100, 0, 0); files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase); assertEquals(0, destination.creations)
        assertFalse(stage(id).exists()); assertFalse(stage(id, "part").exists())
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        incoming.revoked = true; files.execute(id); assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        files.cancel(id); files.execute(id); assertEquals("CANCELLED", catalog.dao.operation(id)!!.phase); assertReferences()
    }
    @Test fun sourceChangedBetweenStreamsWithIdenticalStatCannotPublishAPrivateOrSafCopy() = runBlocking {
        val id = enqueue(); var reads = 0
        incoming.onRead = { if (++reads == 2) incoming.entries.getValue(Uri.parse(target.uri).lastPathSegment!!).file.writeBytes(sourceBytes.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }) }
        files.execute(id); val op = catalog.dao.operation(id)!!
        assertEquals("FAILED", op.phase); assertTrue(op.message!!.contains("sorgente è cambiata"))
        assertNull(op.publishedUri); assertFalse(stage(id).exists()); assertEquals(0, destination.creations); assertReferences()
    }
    @Test fun gameModeDefersUnapprovedAcquisitionAndExplicitConsentAllowsANewSelection() = runBlocking {
        files = FileOperations(catalog, gameMode = { true })
        val id = enqueue(); val reads = incoming.modes.size; files.execute(id)
        assertEquals("DEFERRED", catalog.dao.operation(id)!!.phase); assertEquals(reads, incoming.modes.size)
        files.cancel(id); files.execute(id); assertEquals("CANCELLED", catalog.dao.operation(id)!!.phase)
        target = target.copy(id = UUID.randomUUID().toString())
        val allowed = files.enqueueImport(target, album.id, allowGame = true, start = false); files.execute(allowed)
        assertEquals("COMPLETED", catalog.dao.operation(allowed)!!.phase); assertReferences()
    }
    @Test fun destinationHiddenDisabledOrChangedToMusicBeforeExecutionNeverAcquiresOrWrites() = runBlocking {
        val id = enqueue(); val reads = incoming.modes.size
        catalog.hideAlbums(listOf(album.id), true); files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase); assertFalse(stage(id).exists())
        catalog.hideAlbums(listOf(album.id), false)
        val source = catalog.dao.sources().single()
        catalog.dao.saveSource(source.copy(enabled = false)); files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        catalog.dao.saveSource(source.copy(role = "MUSIC")); files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase); assertEquals(reads, incoming.modes.size); assertEquals(0, destination.creations)
        catalog.dao.saveSource(source); files.execute(id)
        assertEquals("COMPLETED", catalog.dao.operation(id)!!.phase); assertReferences()
    }
    @Test fun cancellationWithDeniedPrivateCleanupDoesNotRestoreAnAlreadyDeletedSafTemporary() = runBlocking {
        var deny = true; files = FileOperations(catalog, deletePrivate = { if (deny) false else it.delete() })
        destination.canRename = false
        val id = enqueue(); files.execute(id)
        val temp = catalog.dao.operation(id)!!.temporaryUri!!; assertTrue(stage(id).exists())
        files.cancel(id); files.execute(id)
        val failed = catalog.dao.operation(id)!!
        assertEquals("FAILED", failed.phase); assertNull(failed.temporaryUri); assertNull(failed.digest)
        assertFalse(runCatching { destination.node(temp) }.isSuccess); assertTrue(stage(id).exists())
        deny = false; files.execute(id)
        assertEquals("CANCELLED", catalog.dao.operation(id)!!.phase); assertFalse(stage(id).exists()); assertReferences()
    }
    @Test fun invalidAndMusicTargetsHaveExplicitPerFileRejectionsWithoutBlockingVisualImports() = runBlocking {
        val audio = incoming.add("track.mp3", "audio/mpeg", byteArrayOf(1, 2, 3, 4))
        val music = IncomingMedia.inspect(app, IncomingSelection(UUID.randomUUID().toString(), listOf(audio.toString()))).single()
        val operations = files.enqueueImports(listOf(target, music), album.id, start = false)
        operations.forEach { files.execute(it) }
        assertEquals(listOf("COMPLETED", "SKIPPED"), operations.map { catalog.dao.operation(it)!!.phase })
        assertTrue(catalog.dao.operation(operations.last())!!.message!!.contains("musicale")); assertReferences()
    }
    @Test fun selectionAndRecipesAreBoundedAndNeverAcceptFolderFileNetworkOrPrivateAppUris(): Unit = runBlocking {
        val selection = IncomingSelection(UUID.randomUUID().toString(), listOf(target.uri))
        assertEquals(selection, IncomingSelection.decode(selection.encode()))
        val recipe = ImportRecipe(target, true, "a".repeat(64), sourceBytes.size.toLong())
        assertEquals(recipe, ImportRecipe.decode(recipe.encode()))
        assertThrows(IllegalArgumentException::class.java) { IncomingSelection(selection.id, List(129) { "content://fixture/$it" }) }
        assertThrows(IllegalArgumentException::class.java) { IncomingSelection(selection.id, listOf("file:///private/data")) }
        assertThrows(IllegalArgumentException::class.java) { IncomingSelection(selection.id, listOf("https://example.com/photo.png")) }
        assertThrows(IllegalArgumentException::class.java) { IncomingSelection(selection.id, listOf("content://fixture/tree/root")) }
        assertThrows(IllegalArgumentException::class.java) { IncomingMedia.stat(app, Uri.parse("content://${app.packageName}.share/copies/secret")) }
        val intent = Intent().setData(Uri.parse(target.uri)).apply { clipData = ClipData.newRawUri("chosen", Uri.parse(target.uri)) }
        assertEquals(listOf(target.uri), IncomingSelection.from(intent).uris)
        val unsupported = incoming.add("text.txt", "text/plain", "text".toByteArray())
        assertTrue(runCatching { IncomingMedia.inspect(app, IncomingSelection(selection.id, listOf(unsupported.toString()))) }.exceptionOrNull() is java.io.IOException)
    }
}
