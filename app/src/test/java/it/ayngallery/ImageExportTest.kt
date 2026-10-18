package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.*
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageExportTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private lateinit var files: FileOperations
    private lateinit var media: MediaEntity
    private var game = false
    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.imageexport"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.imageexport", provider)
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 12) bitmap.setPixel(x, y,
            when { x < 6 && y < 4 -> Color.RED; y < 4 -> Color.GREEN; x < 6 -> Color.BLUE; else -> Color.TRANSPARENT })
        provider.nodes.getValue("image").file!!.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        catalog = Catalog(app); files = FileOperations(catalog, gameMode = { game })
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.imageexport", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("source", tree.toString(), "Fixture", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source); media = catalog.dao.media().single()
        catalog.favoriteAll(listOf(media.id), true); media = catalog.dao.media(media.id)!!
        catalog.collection("Keep", "MEDIA", listOf(media.id))
        Unit
    }
    @After fun close() = runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close() }
    private fun original() = provider.nodes.getValue("image").file!!.readBytes()
    private suspend fun enqueue(recipe: ImageRecipe = ImageRecipe(), name: String = "edited.${recipe.extension}") =
        files.enqueueImageExport(ExternalTarget.from(media), recipe, media.albumId, name, start = false)
    private fun result(op: FileOperation): Bitmap = BitmapFactory.decodeByteArray(provider.node(op.publishedUri!!).file!!.readBytes(), 0, op.bytes.toInt())!!
    private suspend fun gifSource(bytes: ByteArray = PhotoGifProvider().bytes): ByteArray {
        val node = provider.nodes.getValue("image")
        node.file!!.writeBytes(bytes)
        provider.nodes["image"] = node.copy(name = "animation.gif", mime = "image/gif")
        media = media.copy(name = "animation.gif", mime = "image/gif", kind = "GIF", size = bytes.size.toLong())
        catalog.dao.saveMedia(listOf(media))
        return bytes
    }
    @Test fun transparentFirstGifFrameKeepsAlphaInPngAndUsesWhiteOnlyForExplicitJpeg() = runBlocking {
        val bytes = PhotoGifProvider().bytes.copyOf()
        val control = (0 until bytes.size - 7).first { bytes[it] == 0x21.toByte() && bytes[it + 1] == 0xf9.toByte() }
        bytes[control + 3] = 1 // Transparency flag; palette index zero is the first frame's red pixel.
        bytes[control + 6] = 0
        gifSource(bytes)
        for (format in listOf("PNG", "JPEG")) {
            val recipe = ImageRecipe(input = "GIF_FIRST_FRAME", format = format, quality = 100)
            val id = enqueue(recipe); files.execute(id)
            val op = catalog.dao.operation(id)!!; assertEquals(op.message, "COMPLETED", op.phase)
            val image = result(op); val pixel = image.getPixel(0, 0)
            if (format == "PNG") assertEquals(0, Color.alpha(pixel))
            else { assertEquals(255, Color.alpha(pixel)); assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240) }
            image.recycle()
        }
        assertArrayEquals(bytes, original()); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun explicitFirstGifFrameCreatesStaticPngAndJpegWithNewIdentityAndPreservesAnimation() = runBlocking {
        val bytes = gifSource()
        val preview = ImageEditing.preview(catalog.context.contentResolver, android.net.Uri.parse(media.uri), "GIF_FIRST_FRAME")
        assertEquals(Color.RED, preview.bitmap.getPixel(0, 0)); preview.bitmap.recycle()
        for (format in listOf("PNG", "JPEG")) {
            val recipe = ImageRecipe(input = "GIF_FIRST_FRAME", format = format, quality = 100)
            val id = enqueue(recipe)
            files.execute(id); val op = catalog.dao.operation(id)!!
            assertEquals(op.message, "COMPLETED", op.phase)
            assertEquals("GIF_FIRST_FRAME", ImageRecipe.decode(op.recipe!!).input)
            assertEquals("Esporta fotogramma GIF", fileActionName(op.kind, op.recipe))
            val output = catalog.dao.media(op.resultMediaId)!!
            assertEquals("IMAGE", output.kind); assertEquals(recipe.mime, output.mime); assertFalse(output.favorite)
            assertNotEquals(media.id, output.id)
            val image = result(op)
            assertEquals(1, image.width); assertEquals(1, image.height)
            val color = image.getPixel(0, 0)
            assertTrue(Color.red(color) > 240); assertTrue(Color.blue(color) < 15); image.recycle()
            val outputPreview = ImageEditing.preview(catalog.context.contentResolver, android.net.Uri.parse(output.uri))
            outputPreview.bitmap.recycle() // The static editor can read the actual output without allowing animation.
        }
        assertArrayEquals(bytes, original()); assertEquals("GIF", catalog.dao.media(media.id)!!.kind)
        assertTrue(catalog.dao.media(media.id)!!.favorite)
        assertEquals(listOf(media.id), catalog.dao.collectionItems().map { it.mediaId })
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun gifRecipeIsExplicitBackwardsCompatibleAndCannotRelaxTheStaticEditorOrDecodeOtherFormats() = runBlocking {
        val legacy = org.json.JSONObject(ImageRecipe().encode()).apply { remove("input") }.toString()
        assertEquals(ImageRecipe(), ImageRecipe.decode(legacy))
        assertThrows(IllegalArgumentException::class.java) { ImageRecipe(input = "ANY_ANIMATION") }
        assertThrows(IllegalArgumentException::class.java) { ImageRecipe.decode(org.json.JSONObject(legacy).put("input", org.json.JSONObject.NULL).toString()) }
        assertFalse(ImageRecipe(input = "GIF_FIRST_FRAME").accepts("IMAGE"))
        assertThrows(SourceChanged::class.java) { runBlocking { enqueue(ImageRecipe(input = "GIF_FIRST_FRAME")) } }
        val uri = android.net.Uri.parse(media.uri)
        assertThrows(IllegalArgumentException::class.java) { ImageEditing.preview(catalog.context.contentResolver, uri, "GIF_FIRST_FRAME") }
        gifSource()
        assertThrows(IllegalArgumentException::class.java) { ImageEditing.preview(catalog.context.contentResolver, uri) }
        assertThrows(SourceChanged::class.java) { runBlocking { enqueue() } }
        assertEquals(0, catalog.dao.operations().size); assertTrue(provider.deletions.isEmpty())
    }
    @Test @Config(sdk = [33], qualifiers = "it") fun gifRecipeAndPublishedFrameRecoverWithoutDuplicatingTheOutput() = runBlocking {
        val bytes = gifSource()
        val id = enqueue(ImageRecipe(input = "GIF_FIRST_FRAME", turns = 1))
        provider.failAfterRename = true; files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        provider.failAfterRename = false; files = FileOperations(catalog)
        files.execute(id); val op = catalog.dao.operation(id)!!
        assertEquals(op.message, "COMPLETED", op.phase)
        assertEquals("GIF_FIRST_FRAME", ImageRecipe.decode(op.recipe!!).input)
        assertEquals(1, provider.nodes.values.count { it.name == "edited.png" })
        assertEquals("IMAGE", catalog.dao.media(op.resultMediaId)!!.kind)
        assertArrayEquals(bytes, original()); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun nativeCropRotationMirrorAlphaAndNewIdentityPreserveOriginalReferences() = runBlocking {
        val original = original()
        val id = enqueue(ImageRecipe(right = 0.5f, turns = 1))
        files.execute(id); val op = catalog.dao.operation(id)!!
        assertEquals("COMPLETED", op.phase); assertNotEquals(media.id, op.resultMediaId)
        val image = result(op)
        assertEquals(8, image.width); assertEquals(6, image.height)
        assertEquals(Color.BLUE, image.getPixel(1, 1)); assertEquals(Color.RED, image.getPixel(6, 1)); image.recycle()
        assertArrayEquals(original, original()); assertTrue(catalog.dao.media(media.id)!!.favorite)
        assertFalse(catalog.dao.media(op.resultMediaId)!!.favorite); assertEquals(listOf(media.id), catalog.dao.collectionItems().map { it.mediaId })
        assertTrue(provider.deletions.isEmpty())
        val mirrorId = enqueue(ImageRecipe(mirror = true), "mirror.png"); files.execute(mirrorId)
        val mirrored = result(catalog.dao.operation(mirrorId)!!)
        assertEquals(Color.GREEN, mirrored.getPixel(1, 1)); assertEquals(Color.RED, mirrored.getPixel(10, 1))
        assertEquals(0, Color.alpha(mirrored.getPixel(1, 6))); mirrored.recycle()
        val verticalId = enqueue(ImageRecipe(mirrorVertical = true), "vertical.png"); files.execute(verticalId)
        val vertical = result(catalog.dao.operation(verticalId)!!)
        assertEquals(Color.BLUE, vertical.getPixel(1, 1)); vertical.recycle()
        val monoId = enqueue(ImageRecipe(filter = "MONO"), "mono.png"); files.execute(monoId)
        val mono = result(catalog.dao.operation(monoId)!!); val gray = mono.getPixel(1, 1)
        assertEquals(Color.red(gray), Color.green(gray)); assertEquals(Color.green(gray), Color.blue(gray)); mono.recycle()
    }
    // Robolectric 4.14.1's API 35 ImageDecoder nativeBinding cannot create a decoder from an AssetFileDescriptor.
    // Native URI/codec coverage uses API 28 and 33; Android 35 decoding remains a device test.
    @Test @Config(sdk = [33], qualifiers = "it") fun nativeJpegHasRealWhiteFlatteningAndConflictingNamesNeverOverwrite() = runBlocking {
        val original = original()
        val id = enqueue(ImageRecipe(format = "JPEG", quality = 100), "white.jpg")
        files.execute(id); val op = catalog.dao.operation(id)!!; assertEquals(op.message, "COMPLETED", op.phase)
        val image = result(op); val pixel = image.getPixel(10, 6)
        assertEquals(255, Color.alpha(pixel)); assertTrue(Color.red(pixel) > 220 && Color.green(pixel) > 220 && Color.blue(pixel) > 220); image.recycle()
        assertEquals("image/jpeg", catalog.dao.media(op.resultMediaId)!!.mime)
        val conflict = enqueue(name = media.name); files.execute(conflict)
        assertEquals("image (1).png", catalog.dao.operation(conflict)!!.finalName); assertArrayEquals(original, original())
    }
    @Test fun gameModeDefersBeforeProviderIoConsentIsPersistedAndCancellationCleansOnlyTemporary() = runBlocking {
        game = true
        val id = enqueue(); var reads = 0; provider.beforeRead = { reads++ }
        files.execute(id); assertEquals("DEFERRED", catalog.dao.operation(id)!!.phase); assertEquals(0, reads); assertEquals(0, provider.creations)
        val consent = enqueue(ImageRecipe(allowGame = true), "consent.png")
        files.execute(consent); assertEquals("COMPLETED", catalog.dao.operation(consent)!!.phase)
        game = false; files.execute(id); assertEquals("COMPLETED", catalog.dao.operation(id)!!.phase)
        val cancel = enqueue(name = "cancelled.png")
        files.cancel(cancel); files.execute(cancel); assertEquals("CANCELLED", catalog.dao.operation(cancel)!!.phase)
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun publishedResultAndRecipeRecoverAfterProviderThrowsWithoutMakingTwoCopies() = runBlocking {
        val id = enqueue(ImageRecipe(turns = 3)); val expected = catalog.dao.operation(id)!!.recipe
        provider.failAfterRename = true; files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        provider.failAfterRename = false
        files = FileOperations(catalog); files.execute(id)
        val op = catalog.dao.operation(id)!!; assertEquals("COMPLETED", op.phase); assertEquals(expected, op.recipe)
        assertEquals(1, catalog.dao.media().count { it.id == op.resultMediaId }); assertEquals(1, provider.nodes.values.count { it.name == "edited.png" })
        val image = result(op); assertEquals(8, image.width); assertEquals(12, image.height); image.recycle()
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun invalidRecipesLowMemoryWriteFailureAndSourceChangeNeverPublishOrRewriteOriginal() = runBlocking {
        val original = original()
        assertThrows(IllegalArgumentException::class.java) { ImageRecipe(left = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ImageRecipe(left = 0.8f, right = 0.2f) }
        assertThrows(IllegalArgumentException::class.java) { ImageRecipe(turns = 4) }
        val uri = catalog.uri(media, catalog.readSnapshot())
        assertThrows(IllegalArgumentException::class.java) { ImageEditing.render(catalog.context.contentResolver, uri, ImageRecipe(), budget = 1) }
        val id = enqueue(); provider.denyWrite = true; files.execute(id); assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        assertEquals(1, catalog.dao.media().size); assertArrayEquals(original, original())
        provider.denyWrite = false
        val changed = enqueue(name = "changed.png")
        provider.nodes.getValue("image").name = "renamed.png"
        files.execute(changed); assertEquals("FAILED", catalog.dao.operation(changed)!!.phase)
        assertNull(catalog.dao.operation(changed)!!.publishedUri); assertTrue(provider.nodes.containsKey("image"))
    }
    @Test fun exifOrientationIsBakedIntoPixelsAndRecipeCoordinatesUseUprightDimensions() {
        val input = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888); input.eraseColor(Color.RED)
        val stream = ByteArrayOutputStream(); input.compress(Bitmap.CompressFormat.JPEG, 100, stream); input.recycle()
        val jpeg = stream.toByteArray()
        val exif = byteArrayOf(69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0)
        provider.nodes.getValue("image").file!!.writeBytes(jpeg.take(2).toByteArray() + byteArrayOf(-1, -31, 0, 34) + exif + jpeg.drop(2).toByteArray())
        val uri = android.net.Uri.parse(media.uri)
        val preview = ImageEditing.preview(catalog.context.contentResolver, uri)
        assertEquals(8, preview.original.width); assertEquals(12, preview.original.height); preview.bitmap.recycle()
        val output = ImageEditing.render(catalog.context.contentResolver, uri, ImageRecipe(right = 0.5f))
        assertEquals(4, output.width); assertEquals(12, output.height); output.recycle()
    }
    @Test fun staticEditorRejectsAnimationEvenWhenProviderCallsItPng() {
        val header = byteArrayOf(71,73,70,56,57,97,1,0,1,0,-128,0,0,0,0,0,-1,-1,-1)
        val frame = byteArrayOf(33,-7,4,0,10,0,0,0,44,0,0,0,0,1,0,1,0,0,2,2,68,1,0)
        provider.nodes.getValue("image").file!!.writeBytes(header + frame + frame + byteArrayOf(59))
        val uri = android.net.Uri.parse(media.uri)
        assertThrows(IllegalArgumentException::class.java) { ImageEditing.preview(catalog.context.contentResolver, uri) }
        assertThrows(IllegalArgumentException::class.java) { ImageEditing.render(catalog.context.contentResolver, uri, ImageRecipe()) }
        assertEquals(ImageRecipe(right = 0.5f, turns = 1, allowGame = true), ImageRecipe.decode(ImageRecipe(right = 0.5f, turns = 1, allowGame = true).encode()))
    }
}
