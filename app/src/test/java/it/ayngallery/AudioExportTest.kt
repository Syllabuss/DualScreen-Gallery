package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
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

/** Metadata and already encoded output are fixtures; journal/provider are real, native AAC encoding is not tested. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
@androidx.annotation.OptIn(UnstableApi::class)
class AudioExportTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private lateinit var files: FileOperations
    private lateinit var original: MediaEntity
    private lateinit var destination: AlbumEntity
    private var game = false
    @Before fun start() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.audioexport"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.audioexport", provider)
        val node = provider.nodes.getValue("image")
        node.file!!.writeBytes(File("src/test/resources/media/audio-segments.m4a").readBytes())
        provider.nodes["image"] = node.copy(name = "song.m4a", mime = "audio/mp4")
        catalog = Catalog(app); files = FileOperations(catalog, gameMode = { game })
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.audioexport", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val source = SourceEntity("source", tree.toString(), "Fixture", "MUSIC")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        original = catalog.dao.media().single().copy(favorite = true, duration = 6000, title = "Original title", artist = "Original artist",
            musicAlbum = "Original album", artworkUri = "file:///original-artwork", artworkMediaId = "original-cover")
        catalog.dao.saveMedia(listOf(original)); catalog.collection("Keep", "PLAYLIST", listOf(original.id))
        destination = catalog.dao.albums().single { it.name == "Destination" }
        metadata(original.uri, 6000)
    }
    @After fun close() = runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close() }
    private fun metadata(uri: String, duration: Long) {
        val source = DataSource.toDataSource(catalog.context, Uri.parse(uri))
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "$duration")
    }
    private suspend fun enqueue(recipe: AudioRecipe = AudioRecipe(6000, 2125, 3875), name: String = "segment.m4a") =
        files.enqueueAudioExport(ExternalTarget.from(original), recipe, destination.id, name, start = false)
    @Test fun recipeRetainsPreciseBoundsAndRequiresPhysicalAacEncodingWithoutVideo() {
        val recipe = AudioRecipe(6000, 2125, 3875, bitrate = 256, allowGame = true)
        assertEquals(recipe, AudioRecipe.decode(recipe.encode())); assertEquals(1750L, recipe.length)
        assertThrows(IllegalArgumentException::class.java) { AudioRecipe(6000, 1000, 1249) }
        assertThrows(IllegalArgumentException::class.java) { AudioRecipe(6000, end = 6001) }
        assertThrows(IllegalArgumentException::class.java) { AudioRecipe(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { AudioRecipe(6000, start = Long.MAX_VALUE, end = Long.MIN_VALUE + 999) }
        assertThrows(IllegalArgumentException::class.java) { AudioRecipe(6000, bitrate = 500) }
        assertThrows(IllegalArgumentException::class.java) { AudioRecipe.decode(recipe.encode().replace("AUDIO", "UNKNOWN")) }
        val plan = AudioEditing.editedItem(Uri.parse(original.uri), recipe)
        assertTrue(plan.removeVideo); assertFalse(plan.removeAudio)
        assertEquals(2125L, plan.mediaItem.clippingConfiguration.startPositionMs); assertEquals(3875L, plan.mediaItem.clippingConfiguration.endPositionMs)
        assertTrue(VideoEditing.fullEncoder(catalog.context, 256000).audioNeedsEncoding())
    }
    @Test fun mp3RecipeAndLegacyAacRecipeKeepPreciseJournalBounds() = runBlocking {
        val recipe = AudioRecipe(6000, 2125, 3875, format = "MP3")
        assertEquals(recipe, AudioRecipe.decode(recipe.encode()))
        assertEquals("mp3", recipe.extension); assertEquals("audio/mpeg", recipe.mime)
        val legacy = org.json.JSONObject(AudioRecipe(6000).encode()).apply { remove("format"); remove("sourceKind") }
        assertEquals(AudioRecipe(6000), AudioRecipe.decode(legacy.toString()))
        val id = enqueue(recipe, "segment.mp3"); val op = catalog.dao.operation(id)!!
        assertEquals("audio/mpeg", op.mime); assertEquals(recipe, AudioRecipe.decode(op.recipe!!))
        assertEquals(0, provider.creations); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun queueValidatesFreshSourceDurationExtensionAndDestinationBeforeAnyWrite() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { enqueue(name = "wrong.mp3") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { enqueue(AudioRecipe(7000)) } }
        assertThrows(SourceChanged::class.java) { runBlocking { files.enqueueAudioExport(ExternalTarget.from(original.copy(kind = "VIDEO")), AudioRecipe(6000), destination.id, "bad.m4a", false) } }
        val source = catalog.dao.sources().single(); catalog.dao.saveSource(source.copy(role = "VISUAL"))
        assertThrows(SourceChanged::class.java) { runBlocking { enqueue() } }; catalog.dao.saveSource(source)
        val id = enqueue(); val op = catalog.dao.operation(id)!!
        assertEquals("EXPORT_AUDIO", op.kind); assertEquals("audio/mp4", op.mime); assertEquals(AudioRecipe(6000, 2125, 3875), AudioRecipe.decode(op.recipe!!))
        assertNotEquals(original.id, op.resultMediaId); assertEquals("QUEUED", op.phase)
        assertEquals(0, provider.creations); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun gameDeferralOccursBeforeIoAndExplicitConsentReachesWorker() = runBlocking {
        game = true; val id = enqueue(); files.execute(id) { fail("No IO in game mode") }
        assertEquals("DEFERRED", catalog.dao.operation(id)!!.phase); assertEquals(0, provider.creations)
        val allowed = enqueue(AudioRecipe(6000, allowGame = true))
        files.execute(allowed) { throw IOException("Fixture: foreground unavailable") }
        assertEquals("FAILED", catalog.dao.operation(allowed)!!.phase)
        assertTrue(catalog.dao.operation(allowed)!!.message!!.contains("foreground unavailable")); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun cancellationReportsDeniedPrivateCleanupAndThenRemovesOnlyOwnedTemps() = runBlocking {
        val id = enqueue(); val op = catalog.dao.operation(id)!!
        val temp = provider.createDocument("destination", "audio/mp4", ".ayngallery-$id.m4a")
        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(op.destinationParent), temp)
        val private = File(catalog.context.filesDir, "exports/$id.m4a").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2, 3)) }
        catalog.dao.saveOperation(op.copy(temporaryUri = uri.toString())); files.cancel(id)
        files = FileOperations(catalog, deletePrivate = { false }); files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase); assertTrue(private.exists()); assertTrue(provider.deletions.isEmpty())
        files = FileOperations(catalog); files.execute(id)
        assertEquals("CANCELLED", catalog.dao.operation(id)!!.phase); assertFalse(private.exists())
        assertEquals(listOf(temp), provider.deletions); assertTrue(provider.nodes.containsKey("image")); assertTrue(catalog.dao.media(original.id)!!.favorite)
    }
    @Test fun verifiedJournalRecoveryPublishesOneIndependentSongAndDoesNotInheritOriginalTagsOrPlaylist() = runBlocking {
        val id = enqueue(); val op = catalog.dao.operation(id)!!
        val temp = provider.createDocument("destination", "audio/mp4", ".ayngallery-$id.m4a")
        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(op.destinationParent), temp)
        val bytes = File("src/test/resources/media/audio-segments.m4a").readBytes()
        provider.nodes.getValue(temp).file!!.writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        metadata(uri.toString(), 1750) // Models an already verified export; not an AAC codec test.
        catalog.dao.saveOperation(op.copy(temporaryUri = uri.toString(), bytes = bytes.size.toLong(), digest = hash, phase = "VERIFIED"))
        provider.failAfterRename = true; files.execute(id); assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        val published = provider.nodes.values.single { it.name == "segment.m4a" }
        metadata(DocumentsContract.buildDocumentUriUsingTree(Uri.parse(op.destinationParent), published.id).toString(), 1750)
        provider.failAfterRename = false; files = FileOperations(catalog); files.execute(id)
        val completed = catalog.dao.operation(id)!!; assertEquals(completed.message, "COMPLETED", completed.phase)
        val result = catalog.dao.media(completed.resultMediaId)!!
        assertEquals("AUDIO", result.kind); assertEquals(1750L, result.duration); assertFalse(result.favorite)
        assertNull(result.title); assertNull(result.artist); assertNull(result.musicAlbum); assertNull(result.artworkUri); assertNull(result.artworkMediaId)
        assertEquals(1, provider.nodes.values.count { it.name == "segment.m4a" })
        assertEquals(original.id, catalog.dao.collectionItems().single().mediaId); assertEquals(original, catalog.dao.media(original.id))
        assertArrayEquals(bytes, provider.nodes.getValue("image").file!!.readBytes()); assertTrue(provider.deletions.isEmpty())
    }
    @Test @Config(sdk = [35], qualifiers = "it") fun android15UsesMediaProcessingForAudioButCopyUsesDataSync() = runBlocking {
        val op = catalog.dao.operation(enqueue())!!
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, fileForegroundInfo(catalog.context, op).foregroundServiceType)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, fileForegroundInfo(catalog.context, op.copy(kind = "COPY")).foregroundServiceType)
    }
}
