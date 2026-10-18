package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.*
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native raster codecs and provider with synthetic personal metadata. Android recipient grant enforcement remains a device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [WindowsFileProviderPaths::class], qualifiers = "it")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCopiesTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private lateinit var copies: ShareCopies
    private lateinit var original: MediaEntity
    private var time = System.currentTimeMillis()
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun start(): Unit = runBlocking {
        WindowsFileProviderPaths.reset()
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("png", "image/png"); addExtensionMimeTypeMapping("jpg", "image/jpeg")
        }
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.sharetest"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.sharetest", provider)
        png(12, 8)
        catalog = Catalog(app); copies = ShareCopies(catalog, now = { time })
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.sharetest", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("source", tree.toString(), "Fixture", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        original = catalog.dao.media().single(); catalog.favorite(original.id); original = catalog.dao.media(original.id)!!
        catalog.collection("Keep", "MEDIA", listOf(original.id))
        Unit
    }
    @After fun close() = runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close() }
    private fun png(width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED); for (y in 0 until height) for (x in width / 2 until width) bitmap.setPixel(x, y, Color.TRANSPARENT)
        provider.nodes.getValue("image").file!!.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun request(options: ShareCopyOptions = ShareCopyOptions()) = ExternalRequest(ExternalAction.SHARE, listOf(ExternalTarget.from(original)), options)
    private fun output(batch: PreparedShare) = File(ShareCopies.directory(app, batch.id), batch.images.single().name)
    private suspend fun assertOriginal(bytes: ByteArray) {
        assertArrayEquals(bytes, provider.nodes.getValue("image").file!!.readBytes())
        assertEquals(original, catalog.dao.media(original.id)); assertTrue(provider.deletions.isEmpty())
        assertEquals(original.id, catalog.dao.collectionItems().single().mediaId); assertTrue(catalog.dao.operations().isEmpty())
    }
    @Test fun nativePngKeepsAlphaAndOriginalReferencesWithExplicitReadOnlyPublishedUris() = runBlocking {
        val bytes = provider.nodes.getValue("image").file!!.readBytes(); val frozen = request()
        val batch = copies.prepare(frozen); assertEquals("image-001.png", batch.images.single().name)
        assertEquals(12, batch.images.single().width); assertEquals(8, batch.images.single().height)
        val uri = Uri.parse(batch.images.single().uri)
        ShareCopies.allowed(app, uri, own = true)
        assertThrows(IllegalArgumentException::class.java) { ShareCopies.allowed(app, uri, own = false) }
        val decoded = BitmapFactory.decodeFile(output(batch).path)!!; assertEquals(0, Color.alpha(decoded.getPixel(8, 4))); decoded.recycle()
        val intent = copies.publish(batch.id, frozen)
        @Suppress("DEPRECATION") val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags); assertEquals("image/png", send.type)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri); ShareCopies.allowed(app, uri, own = false)
        assertArrayEquals(output(batch).readBytes(), app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        val private = File(app.filesDir, "trash/sentinel").apply { parentFile!!.mkdirs(); writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, "${app.packageName}.share", private) }
        assertThrows(IllegalArgumentException::class.java) { ShareCopies.allowed(app, uri.buildUpon().appendPath("../secret").build(), own = false) }
        assertThrows(SecurityException::class.java) { app.contentResolver.openOutputStream(uri, "w") }
        assertOriginal(bytes); assertEquals(1, catalog.dao.media().size)
    }
    @Test fun insufficientSpaceStopsPreparationAndRemovesItsLeaseWithoutTouchingOriginals() = runBlocking {
        val bytes = provider.nodes.getValue("image").file!!.readBytes()
        ShadowStatFs.registerStats(app.filesDir, 100, 0, 0)
        val error = runCatching { copies.prepare(request()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Spazio insufficiente"))
        assertTrue(File(app.filesDir, "share-copies").listFiles().orEmpty().isEmpty())
        assertTrue(File(app.filesDir, "share-copy-ledgers").listFiles().orEmpty().isEmpty())
        assertOriginal(bytes)
    }
    @Test fun jpegResizeDoesNotEnlargeAndOnlyExplicitJpegReplacesTransparencyWithWhite() = runBlocking {
        png(2000, 1000); original = original.copy(size = provider.nodes.getValue("image").file!!.length()); catalog.dao.saveMedia(listOf(original))
        val bytes = provider.nodes.getValue("image").file!!.readBytes()
        val batch = copies.prepare(request(ShareCopyOptions(longest = 1024, format = "JPEG", quality = 100)))
        assertEquals(1024, batch.images.single().width); assertEquals(512, batch.images.single().height)
        val decoded = BitmapFactory.decodeFile(output(batch).path)!!; val pixel = decoded.getPixel(800, 100)
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240); decoded.recycle()
        copies.discard(batch.id); assertFalse(output(batch).exists())
        val larger = copies.prepare(request(ShareCopyOptions(longest = 4096)))
        assertEquals(2000, larger.images.single().width); assertEquals(1000, larger.images.single().height)
        assertOriginal(bytes)
    }
    @Test fun exifGpsOrientationAndCommentAreExcludedFromAnUprightRasterCopy() = runBlocking {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.RED)
        val compressed = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 100, compressed); bitmap.recycle()
        val tiff = ByteBuffer.allocate(140).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put(byteArrayOf(73, 73)); tiff.putShort(42); tiff.putInt(8); tiff.putShort(2)
        fun entry(tag: Int, type: Int, count: Int, value: Int) { tiff.putShort(tag.toShort()); tiff.putShort(type.toShort()); tiff.putInt(count); tiff.putInt(value) }
        entry(0x112, 3, 1, 6); entry(0x8825, 4, 1, 38); tiff.putInt(0); tiff.putShort(4)
        entry(1, 2, 2, 'N'.code); entry(2, 5, 3, 92); entry(3, 2, 2, 'E'.code); entry(4, 5, 3, 116); tiff.putInt(0)
        listOf(12, 34, 56, 56, 12, 34).forEach { tiff.putInt(it); tiff.putInt(1) }
        val exif = byteArrayOf(69, 120, 105, 102, 0, 0) + tiff.array()
        val marker = "fixture-private-location-marker".toByteArray()
        fun app(markerCode: Int, data: ByteArray) = byteArrayOf(-1, markerCode.toByte(), ((data.size + 2) ushr 8).toByte(), (data.size + 2).toByte()) + data
        val jpeg = compressed.toByteArray()
        val bytes = jpeg.take(2).toByteArray() + app(0xe1, exif) + app(0xfe, marker) + jpeg.drop(2).toByteArray()
        val node = provider.nodes.getValue("image"); node.file!!.writeBytes(bytes)
        provider.nodes["image"] = node.copy(name = "gps.jpg", mime = "image/jpeg")
        original = original.copy(name = "gps.jpg", mime = "image/jpeg", size = bytes.size.toLong()); catalog.dao.saveMedia(listOf(original))
        val batch = copies.prepare(request())
        assertEquals(8, batch.images.single().width); assertEquals(12, batch.images.single().height)
        val png = output(batch).readBytes(); val chunks = mutableListOf<String>(); var offset = 8
        while (offset < png.size) { val length = ByteBuffer.wrap(png, offset, 4).int; chunks += String(png, offset + 4, 4, Charsets.US_ASCII); offset += 12 + length }
        assertFalse(chunks.any { it in setOf("eXIf", "tEXt", "iTXt", "zTXt") })
        assertFalse(png.toString(Charsets.ISO_8859_1).contains(marker.toString(Charsets.US_ASCII)))
        assertOriginal(bytes)
    }
    @Test fun expiredAndCancelledCopiesHaveASeparateCleanupWithDeniedRemovalPersisted() = runBlocking {
        val bytes = provider.nodes.getValue("image").file!!.readBytes()
        val trash = File(app.filesDir, "trash/keep.bin").apply { parentFile!!.mkdirs(); writeText("trash remains") }
        val frozen = request(); val batch = copies.prepare(frozen); copies.publish(batch.id, frozen)
        copies.discard(batch.id); assertTrue(output(batch).exists())
        time += ShareCopies.RETENTION; copies.cleanup(); assertTrue(output(batch).exists())
        time++; copies.cleanup(); assertFalse(output(batch).exists()); assertEquals("trash remains", trash.readText())
        time = System.currentTimeMillis(); val next = copies.prepare(request())
        val denied = ShareCopies(catalog, now = { time }, delete = { false })
        assertThrows(IllegalStateException::class.java) { runBlocking { denied.discard(next.id) } }
        assertTrue(output(next).exists()); assertThrows(IllegalArgumentException::class.java) { ShareCopies.allowed(app, Uri.parse(next.images.single().uri), own = true) }
        copies.cleanup(); assertFalse(output(next).exists()); assertOriginal(bytes)
    }
    @Test fun changedSourceEvenWithSameSizeAndTimeAndHiddenAlbumsPreventPublication() = runBlocking {
        val frozen = request(); val batch = copies.prepare(frozen)
        val bytes = provider.nodes.getValue("image").file!!.readBytes(); val changed = bytes.copyOf(); changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        provider.nodes.getValue("image").file!!.writeBytes(changed)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { copies.publish(batch.id, frozen) } }
        assertThrows(IllegalArgumentException::class.java) { ShareCopies.allowed(app, Uri.parse(batch.images.single().uri), own = false) }
        provider.nodes.getValue("image").file!!.writeBytes(bytes); catalog.hideAlbums(listOf(original.albumId), true)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { copies.publish(batch.id, frozen) } }
        copies.discard(batch.id); assertFalse(output(batch).exists()); assertOriginal(bytes)
    }
    @Test fun animationCannotBeFlattenedSilentlyAndCancellationRemovesPartiallyPreparedBatch() = runBlocking {
        val frozen = request()
        assertThrows(IllegalArgumentException::class.java) { frozen.copy(targets = listOf(frozen.targets.single().copy(kind = "GIF"))) }
        val node = provider.nodes.getValue("image"); val bytes = node.file!!.readBytes()
        node.file!!.writeBytes(PhotoGifProvider().bytes); original = original.copy(size = node.file!!.length()); catalog.dao.saveMedia(listOf(original))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { copies.prepare(request()) } }
        assertTrue(File(app.filesDir, "share-copies").listFiles().orEmpty().isEmpty())
        node.file!!.writeBytes(bytes); original = original.copy(size = bytes.size.toLong()); catalog.dao.saveMedia(listOf(original))
        val second = provider.add("destination", "second.png", bytes); val source = catalog.dao.sources().single(); SourceScanner(catalog).scan(source)
        val other = catalog.dao.media().single { it.name == second.name }
        val entered = CompletableDeferred<Unit>()
        val job = async { copies.prepare(ExternalRequest(ExternalAction.SHARE, listOf(ExternalTarget.from(original), ExternalTarget.from(other)), ShareCopyOptions())) { done, _ ->
            if (done == 1) { entered.complete(Unit); awaitCancellation() } } }
        withTimeout(5000) { entered.await() }; job.cancelAndJoin()
        assertTrue(File(app.filesDir, "share-copies").listFiles().orEmpty().isEmpty())
        assertTrue(File(app.filesDir, "share-copy-ledgers").listFiles().orEmpty().isEmpty()); assertOriginal(bytes)
    }
    @Test fun frozenRequestAndPreparedLeaseRestoreButWrongDraftCannotReadOrShareIt(): Unit = runBlocking {
        val frozen = request(ShareCopyOptions(longest = 2048, format = "JPEG"))
        assertEquals(frozen, ExternalRequest.decode(frozen.encode()))
        val originalRequest = frozen.copy(copy = null); assertEquals(originalRequest, ExternalRequest.decode(originalRequest.encode()))
        val batch = copies.prepare(frozen); copies = ShareCopies(catalog, now = { time })
        assertEquals(batch, copies.read(batch.id, ExternalRequest.decode(frozen.encode())))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { copies.read(batch.id, frozen.copy(copy = ShareCopyOptions())) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ExternalMedia.prepare(catalog, frozen) } }
        assertThrows(IllegalArgumentException::class.java) { ExternalRequest(ExternalAction.OPEN, frozen.targets, frozen.copy) }
        val info = app.packageManager.resolveContentProvider("${app.packageName}.share", android.content.pm.PackageManager.GET_META_DATA)!!
        assertFalse(info.exported); assertTrue(info.grantUriPermissions)
        copies.publish(batch.id, frozen)
        val ledger = File(app.filesDir, "share-copy-ledgers/${batch.id}.json")
        val expired = System.currentTimeMillis() - 1
        val manifest = org.json.JSONObject(ledger.readText()).put("created", expired - ShareCopies.RETENTION).put("expires", expired)
        ledger.writeText(manifest.toString())
        assertTrue(output(batch).exists()) // The provider must enforce expiry even before a cleanup run.
        assertThrows(IllegalArgumentException::class.java) { app.contentResolver.openInputStream(Uri.parse(batch.images.single().uri)) }
    }
    @Test fun thumbnailProviderReadsCanRunWhileReviewedBatchesArePublishedWithoutLosingTheirLeases() = runBlocking {
        val frozen = request(); val batches = List(4) { copies.prepare(frozen) }
        val gate = CompletableDeferred<Unit>()
        val reads = async(Dispatchers.IO) {
            gate.await()
            repeat(100) { batches.forEach { batch ->
                assertEquals("image/png", app.contentResolver.getType(Uri.parse(batch.images.single().uri)))
            } }
        }
        val writes = async(Dispatchers.IO) { gate.await(); batches.forEach { copies.publish(it.id, frozen) } }
        gate.complete(Unit); reads.await(); writes.await()
        batches.forEach { ShareCopies.allowed(app, Uri.parse(it.images.single().uri), own = false) }
    }
    @Test fun gameModeNeedsExplicitConsentAndStopsAnUnapprovedBatchBetweenImages() = runBlocking {
        var playing = true; copies = ShareCopies(catalog, gameMode = { playing })
        assertTrue(runCatching { copies.prepare(request()) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(app.filesDir, "share-copies").exists())
        val allowed = request(ShareCopyOptions(allowGame = true))
        assertEquals(allowed, ExternalRequest.decode(allowed.encode()))
        val batch = copies.prepare(allowed); copies.discard(batch.id)
        playing = false
        val bytes = provider.nodes.getValue("image").file!!.readBytes()
        provider.add("destination", "second.png", bytes); SourceScanner(catalog).scan(catalog.dao.sources().single())
        val other = catalog.dao.media().single { it.name == "second.png" }
        val pending = ExternalRequest(ExternalAction.SHARE, listOf(ExternalTarget.from(original), ExternalTarget.from(other)), ShareCopyOptions())
        val failure = runCatching { copies.prepare(pending) { done, _ -> if (done == 1) playing = true } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(File(app.filesDir, "share-copies").listFiles().orEmpty().isEmpty()); assertOriginal(bytes)
    }
}
