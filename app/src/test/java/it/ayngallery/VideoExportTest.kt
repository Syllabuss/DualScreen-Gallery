package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Metadata is a decoder fixture. Recovery tests exercise the journal, not native video encoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
@androidx.annotation.OptIn(UnstableApi::class)
class VideoExportTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private lateinit var files: FileOperations
    private lateinit var original: MediaEntity
    private lateinit var destination: AlbumEntity
    private var game = false
    @Before fun start() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.videoexport"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.videoexport", provider)
        val node = provider.nodes.getValue("image")
        node.file!!.writeBytes(File("src/test/resources/media/video-segments.mp4").readBytes())
        provider.nodes["image"] = node.copy(name = "video.mp4", mime = "video/mp4")
        catalog = Catalog(app); files = FileOperations(catalog, gameMode = { game })
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.videoexport", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("source", tree.toString(), "Fixture", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        original = catalog.dao.media().single().copy(favorite = true, duration = 6000, videoPosition = 4000)
        catalog.dao.saveMedia(listOf(original)); catalog.collection("Keep", "MEDIA", listOf(original.id))
        destination = catalog.dao.albums().single { it.name == "Destination" }
        metadata(original.uri, 6000)
    }
    @After fun close() = runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close() }
    private fun metadata(uri: String, duration: Long) {
        val source = DataSource.toDataSource(catalog.context, Uri.parse(uri))
        mapOf(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO to "yes", MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO to "yes",
            MediaMetadataRetriever.METADATA_KEY_DURATION to "$duration", MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH to "160",
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT to "120").forEach { (key, value) -> ShadowMediaMetadataRetriever.addMetadata(source, key, value) }
    }
    private suspend fun enqueue(recipe: VideoRecipe = VideoRecipe(6000, 2000, 4000), name: String = "segment.mp4") =
        files.enqueueVideoExport(ExternalTarget.from(original), recipe, destination.id, name, start = false)
    @Test fun recipeRoundTripPlanAndParsingRequirePhysicalEncodingAndValidTimes() {
        val recipe = VideoRecipe(6000, 1250, 4500, left = .25f, right = .75f, height = 1080, mute = true, allowGame = true)
        assertEquals(recipe, VideoRecipe.decode(recipe.encode()))
        assertEquals(3250L, recipe.length); assertEquals("0:03,250", segmentTime(recipe.length))
        assertEquals(1250L, VideoRecipe.millis("1,250", 6000))
        listOf("NaN", "Infinity", "-1", "6.001", "none").forEach { raw -> assertThrows(IllegalArgumentException::class.java) { VideoRecipe.millis(raw, 6000) } }
        assertThrows(IllegalArgumentException::class.java) { VideoRecipe(6000, 1000, 1249) }
        assertThrows(IllegalArgumentException::class.java) { VideoRecipe(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { VideoRecipe(6000, start = Long.MAX_VALUE, end = Long.MIN_VALUE + 999) }
        assertThrows(IllegalArgumentException::class.java) { VideoRecipe(6000, right = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { VideoRecipe.decode(recipe.encode().replace("VIDEO", "AUDIO")) }
        val plan = VideoEditing.editedItem(Uri.parse(original.uri), recipe)
        assertTrue(plan.removeAudio)
        assertEquals(1250L, plan.mediaItem.clippingConfiguration.startPositionMs); assertEquals(4500L, plan.mediaItem.clippingConfiguration.endPositionMs)
        val crop = plan.effects.videoEffects.first() as Crop
        assertEquals(androidx.media3.common.util.Size(80, 120), crop.configure(160, 120))
        val encoder = VideoEditing.fullEncoder(catalog.context)
        assertTrue(encoder.videoNeedsEncoding()); assertTrue(encoder.audioNeedsEncoding())
    }
    @Test fun enqueueFreezesRecipeButRejectsWrongNamesKindsDurationsAndMusicDestinations() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { enqueue(name = "wrong.mkv") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { enqueue(VideoRecipe(7000)) } }
        assertThrows(SourceChanged::class.java) { runBlocking { files.enqueueVideoExport(ExternalTarget.from(original.copy(kind = "AUDIO")), VideoRecipe(6000), destination.id, "bad.mp4", false) } }
        val source = catalog.dao.sources().single(); catalog.dao.saveSource(source.copy(role = "MUSIC"))
        assertThrows(SourceChanged::class.java) { runBlocking { enqueue() } }
        catalog.dao.saveSource(source)
        val id = enqueue(); val op = catalog.dao.operation(id)!!
        assertEquals("EXPORT_VIDEO", op.kind); assertEquals(VideoRecipe(6000, 2000, 4000), VideoRecipe.decode(op.recipe!!))
        assertNotEquals(original.id, op.resultMediaId); assertEquals("QUEUED", op.phase)
        assertEquals(0, provider.creations); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun videoAudioExtractionFreezesMp3BoundsWithoutTouchingOriginalOrItsReferences() = runBlocking {
        val recipe = AudioRecipe(6000, 1250, 4500, format = "MP3", sourceKind = "VIDEO")
        val id = files.enqueueAudioExport(ExternalTarget.from(original), recipe, destination.id, "sound.mp3", start = false)
        val op = catalog.dao.operation(id)!!
        assertEquals("EXPORT_AUDIO", op.kind); assertEquals("audio/mpeg", op.mime)
        assertEquals(recipe, AudioRecipe.decode(op.recipe!!)); assertNotEquals(original.id, op.resultMediaId)
        assertEquals(original, catalog.dao.media(original.id)); assertEquals(0, provider.creations)
        assertTrue(provider.deletions.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { AudioRecipe(6000, format = "MP4") }
        assertThrows(SourceChanged::class.java) { runBlocking {
            files.enqueueAudioExport(ExternalTarget.from(original), recipe.copy(sourceKind = "AUDIO"), destination.id, "wrong.mp3", start = false)
        } }; Unit
    }
    @Test fun gameModeDefersBeforeAnyIoAndExplicitOverrideReachesTheWorker() = runBlocking {
        game = true; val id = enqueue(); files.execute(id) { fail("No IO in game mode") }
        assertEquals("DEFERRED", catalog.dao.operation(id)!!.phase); assertEquals(0, provider.creations)
        val allowed = enqueue(VideoRecipe(6000, allowGame = true))
        files.execute(allowed) { throw IOException("Fixture: foreground unavailable") }
        assertEquals("FAILED", catalog.dao.operation(allowed)!!.phase)
        assertTrue(catalog.dao.operation(allowed)!!.message!!.contains("foreground unavailable")); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun cancellationCleansOnlyOwnedPrivateAndProviderTempsAndReportsDeniedCleanup() = runBlocking {
        val id = enqueue(); val op = catalog.dao.operation(id)!!
        val temp = provider.createDocument("destination", "video/mp4", ".ayngallery-$id.mp4")
        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(op.destinationParent), temp)
        val private = File(catalog.context.filesDir, "exports/$id.mp4").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2, 3)) }
        catalog.dao.saveOperation(op.copy(temporaryUri = uri.toString())); files.cancel(id)
        files = FileOperations(catalog, deletePrivate = { false }); files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase); assertTrue(private.exists()); assertTrue(provider.deletions.isEmpty())
        files = FileOperations(catalog); files.execute(id)
        assertEquals("CANCELLED", catalog.dao.operation(id)!!.phase); assertFalse(private.exists())
        assertEquals(listOf(temp), provider.deletions); assertTrue(provider.nodes.containsKey("image")); assertTrue(catalog.dao.media(original.id)!!.favorite)
    }
    @Test fun verifiedJournalRecoveryPublishesOnceAndCreatesIndependentReferences() = runBlocking {
        val id = enqueue(); val op = catalog.dao.operation(id)!!
        val temp = provider.createDocument("destination", "video/mp4", ".ayngallery-$id.mp4")
        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(op.destinationParent), temp)
        val bytes = File("src/test/resources/media/video-segments.mp4").readBytes()
        provider.nodes.getValue(temp).file!!.writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        metadata(uri.toString(), 2000) // This fixture models an already verified encoded output, not a codec test.
        catalog.dao.saveOperation(op.copy(temporaryUri = uri.toString(), bytes = bytes.size.toLong(), digest = hash, phase = "VERIFIED"))
        provider.failAfterRename = true; files.execute(id); assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        // OperationDocuments deliberately changes the document ID on rename, as SAF providers may do.
        val published = provider.nodes.values.single { it.name == "segment.mp4" }
        metadata(DocumentsContract.buildDocumentUriUsingTree(Uri.parse(op.destinationParent), published.id).toString(), 2000)
        provider.failAfterRename = false; files = FileOperations(catalog); files.execute(id)
        val completed = catalog.dao.operation(id)!!; assertEquals(completed.message, "COMPLETED", completed.phase)
        val result = catalog.dao.media(completed.resultMediaId)!!
        assertEquals("VIDEO", result.kind); assertEquals(2000L, result.duration); assertEquals(0L, result.videoPosition); assertFalse(result.favorite)
        assertEquals(1, provider.nodes.values.count { it.name == "segment.mp4" })
        assertEquals(original.id, catalog.dao.collectionItems().single().mediaId); assertTrue(catalog.dao.media(original.id)!!.favorite)
        assertArrayEquals(bytes, provider.nodes.getValue("image").file!!.readBytes()); assertTrue(provider.deletions.isEmpty())
    }
    @Test @Config(sdk = [35], qualifiers = "it") fun android15UsesMediaProcessingOnlyForVideoExport() = runBlocking {
        val op = catalog.dao.operation(enqueue())!!
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, fileForegroundInfo(catalog.context, op).foregroundServiceType)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, fileForegroundInfo(catalog.context, op.copy(kind = "COPY")).foregroundServiceType)
    }
    @Test fun exportMuxerDropsSourceMetadataButKeepsTechnicalRotationAndCodecFormat() {
        var saved: androidx.media3.common.Format? = null; var entries = 0; var closed = false
        val sink = object : androidx.media3.transformer.Muxer {
            override fun addTrack(format: androidx.media3.common.Format): Int { saved = format; return 12 }
            override fun writeSampleData(trackId: Int, byteBuffer: java.nio.ByteBuffer, bufferInfo: android.media.MediaCodec.BufferInfo) = Unit
            override fun addMetadataEntry(metadataEntry: androidx.media3.common.Metadata.Entry) { entries++ }
            override fun close() { closed = true }
        }
        val factory = object : androidx.media3.transformer.Muxer.Factory {
            override fun create(path: String) = sink
            override fun getSupportedSampleMimeTypes(trackType: Int) = com.google.common.collect.ImmutableList.of("video/avc", "audio/mp4a-latm")
        }
        val safe = VideoEditing.withoutPersonalMetadata(factory).create("fixture.mp4")
        val location = androidx.media3.container.Mp4LocationData(12.34f, 56.78f)
        val format = androidx.media3.common.Format.Builder().setSampleMimeType("video/avc").setWidth(160).setHeight(120)
            .setRotationDegrees(90).setMetadata(androidx.media3.common.Metadata(location)).build()
        assertEquals(12, safe.addTrack(format)); safe.addMetadataEntry(location); safe.close()
        assertNull(saved!!.metadata); assertEquals(90, saved!!.rotationDegrees); assertEquals("video/avc", saved!!.sampleMimeType)
        assertEquals(0, entries); assertTrue(closed)
    }
}
