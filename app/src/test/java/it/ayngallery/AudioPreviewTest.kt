package it.ayngallery

import android.content.*
import android.content.pm.ProviderInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.UUID

/** Production service, private commands, session/Binder and persistence; decoded state is synthetic, no sound/codec claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class AudioPreviewTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private lateinit var service: org.robolectric.android.controller.ServiceController<MusicService>
    private lateinit var decoded: PreviewDecodedPlayer
    private lateinit var transport: MediaController
    private lateinit var songs: List<MediaEntity>
    private lateinit var incoming: IncomingFixture
    private lateinit var received: IncomingFile
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Before fun connect() {
        val docs = OperationDocuments()
        docs.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.audiopreview"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.audiopreview", docs)
        val first = docs.nodes.getValue("image")
        first.file!!.writeBytes(File("src/test/resources/media/audio-segments.m4a").readBytes())
        docs.nodes["image"] = first.copy(name = "first.m4a", mime = "audio/mp4")
        val second = docs.add("child", "second.m4a", first.file!!.readBytes()); docs.nodes[second.id] = second.copy(mime = "audio/mp4")
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.audiopreview", "root")
        runBlocking {
            val source = SourceEntity("source", tree.toString(), "Preview", "MUSIC")
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            songs = app.catalog.dao.media().sortedBy { it.name }
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.media.size == 2 } }
        }
        songs.forEach { item -> val source = DataSource.toDataSource(app, Uri.parse(item.uri))
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "6000") }
        shadowOf(app).grantPermissions("it.ayngallery.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        org.robolectric.shadows.ShadowProcess.setUid(app.applicationInfo.uid)
        service = Robolectric.buildService(MusicService::class.java).create()
        waitFor { ReflectionHelpers.getField<CompletableDeferred<Unit>>(service.get(), "restored").isCompleted }
        ReflectionHelpers.getField<Player>(service.get(), "player").release(); shadowOf(Looper.getMainLooper()).idle()
        decoded = PreviewDecodedPlayer(songs.map { app.catalog.musicItem(it, app.catalog.snapshot.value) })
        ReflectionHelpers.setField(service.get(), "player", decoded)
        val wrapped = MusicSessionPlayer(decoded, app.preferences, app.music::invalidateVideoResume, service.get()::previewActive)
        ReflectionHelpers.setField(service.get(), "sessionPlayer", wrapped)
        ReflectionHelpers.getField<MediaSession>(service.get(), "mediaSession").setPlayer(wrapped)
        val binding = Intent(app, MusicService::class.java).setAction(MediaSessionService.SERVICE_INTERFACE)
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MusicService::class.java), service.get().onBind(binding))
        app.music.observe(); waitFor { ReflectionHelpers.getField<MediaController?>(app.music, "controller") != null }
        transport = ReflectionHelpers.getField(app.music, "controller")
        waitFor { transport.currentMediaItem?.mediaId == songs[1].id }
        persist()
        incoming = IncomingFixture()
        incoming.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.incoming-fixture"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("ayngallery.incoming-fixture", incoming)
        val external = incoming.add("Received.m4a", "audio/mp4", File("src/test/resources/media/audio-segments.m4a").readBytes())
        received = runBlocking { IncomingMedia.inspect(app, IncomingSelection(UUID.randomUUID().toString(), listOf(external.toString()))).single() }
    }
    @After fun close() { scope.cancel(); app.music.close(); if (::service.isInitialized) service.destroy(); app.onTerminate() }
    private fun persist() { MusicService::class.java.getDeclaredMethod("persist").apply { isAccessible = true }.invoke(service.get()) }
    private fun begin(token: String = UUID.randomUUID().toString()): String {
        val job = scope.async { app.music.preview(ExternalTarget.from(songs[0]), AudioRecipe(6000, 2000, 4000), token) }
        waitFor { job.isCompleted }; runBlocking { job.await() }
        waitFor { transport.currentMediaItem?.mediaId == songs[0].id && !transport.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) }
        return token
    }
    private fun beginReceived(): String {
        val token = UUID.randomUUID().toString()
        val job = scope.async { app.music.previewReceived(received, token) }
        waitFor { job.isCompleted }; runBlocking { job.await() }
        waitFor { transport.currentMediaItem?.mediaId == received.id && app.music.previewToken.value == token }
        return token
    }
    @Test fun receivedAudioUsesCommonPlayerAndNeverPersistsTemporaryUriOrCatalogRows() {
        val token = beginReceived()
        assertTrue(decoded.playWhenReady); assertEquals(1, decoded.mediaItemCount)
        assertEquals(received.uri, decoded.currentMediaItem!!.localConfiguration!!.uri.toString())
        decoded.seekTo(1700); persist()
        val saved = app.getSharedPreferences("music", Context.MODE_PRIVATE)
        assertEquals(org.json.JSONArray(songs.map { it.id }).toString(), saved.getString("queue", ""))
        assertEquals(3000L, saved.getLong("position", -1))
        assertEquals(2, runBlocking { app.catalog.dao.media().size }); assertEquals(1, runBlocking { app.catalog.dao.sources().size })
        assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri.toString() == received.uri })
        app.music.finishPreview(token); waitFor { app.music.previewToken.value == null && decoded.mediaItemCount == 2 }
        assertEquals(songs[1].id, decoded.currentMediaItem!!.mediaId); assertEquals(3000L, decoded.currentPosition)
        assertFalse(decoded.playWhenReady); assertTrue(decoded.shuffleModeEnabled); assertEquals(Player.REPEAT_MODE_ALL, decoded.repeatMode)
    }
    @Test fun staleTemporaryCleanupCannotStopNewMusicAndRevokedFileCannotStart() {
        val token = beginReceived()
        app.music.play(songs[0].id, listOf(songs[0].id))
        waitFor { app.music.previewToken.value == null && decoded.currentMediaItem?.mediaId == songs[0].id }
        app.music.finishPreview(token); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(songs[0].id, decoded.currentMediaItem!!.mediaId); assertTrue(decoded.playWhenReady)
        incoming.revoked = true
        val denied = scope.async { runCatching { app.music.previewReceived(received, UUID.randomUUID().toString()) } }
        waitFor { denied.isCompleted }; assertTrue(runBlocking { denied.await() }.isFailure)
        assertNull(app.music.previewToken.value); assertEquals(songs[0].id, decoded.currentMediaItem!!.mediaId)
    }
    @Test fun revocationWhileReceivedAudioIsOpenRestoresCanonicalQueuePaused() {
        beginReceived(); incoming.revoked = true
        runBlocking { app.preferences.save(app.preferences.state.value.copy(reducedMotion = true)) }
        waitFor { app.music.previewToken.value == null && decoded.mediaItemCount == 2 }
        assertEquals(songs[1].id, decoded.currentMediaItem!!.mediaId); assertFalse(decoded.playWhenReady)
        assertTrue(app.music.state.value.error!!.contains("scaduto")); assertEquals(2, runBlocking { app.catalog.dao.media().size })
    }
    @Test fun receivedAudioServiceRecreationRestoresOnlyCanonicalQueuePaused() {
        beginReceived(); service.destroy()
        service = Robolectric.buildService(MusicService::class.java).create()
        waitFor { ReflectionHelpers.getField<CompletableDeferred<Unit>>(service.get(), "restored").isCompleted }
        val recovered = ReflectionHelpers.getField<Player>(service.get(), "player")
        assertEquals(songs.map { it.id }, (0 until recovered.mediaItemCount).map { recovered.getMediaItemAt(it).mediaId })
        assertFalse(recovered.playWhenReady); assertNull(app.music.previewToken.value)
    }
    @Test fun clippingPreservesCanonicalQueuePositionModesAndRestoresPaused() {
        val token = begin()
        assertEquals(1, decoded.mediaItemCount); assertTrue(decoded.playWhenReady)
        assertEquals(2000L, decoded.currentMediaItem!!.clippingConfiguration.startPositionMs)
        assertEquals(4000L, decoded.currentMediaItem!!.clippingConfiguration.endPositionMs)
        assertFalse(decoded.shuffleModeEnabled); assertEquals(Player.REPEAT_MODE_OFF, decoded.repeatMode)
        assertTrue(transport.mediaMetadata.title.toString().startsWith("Anteprima ·"))
        decoded.seekTo(1200); persist()
        val saved = app.getSharedPreferences("music", Context.MODE_PRIVATE)
        assertEquals(org.json.JSONArray(songs.map { it.id }).toString(), saved.getString("queue", ""))
        assertEquals(1, saved.getInt("index", -1)); assertEquals(3000L, saved.getLong("position", -1))
        assertTrue(saved.getBoolean("shuffle", false)); assertEquals(Player.REPEAT_MODE_ALL, saved.getInt("repeat", -1))
        transport.pause(); waitFor { !decoded.playWhenReady }
        app.music.finishPreview(token)
        waitFor { app.music.previewToken.value == null && decoded.mediaItemCount == 2 }
        assertEquals(songs[1].id, decoded.currentMediaItem!!.mediaId); assertEquals(3000L, decoded.currentPosition)
        assertFalse(decoded.playWhenReady); assertTrue(decoded.shuffleModeEnabled); assertEquals(Player.REPEAT_MODE_ALL, decoded.repeatMode)
        assertEquals(0L, decoded.currentMediaItem!!.clippingConfiguration.startPositionMs)
    }
    @Test fun oldCleanupAndQueueEditsCannotReplaceANewerPreviewOrItsSavedQueue() {
        val first = begin(); val second = begin()
        app.music.finishPreview(first); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(second, app.music.previewToken.value)
        assertFalse(transport.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))
        app.music.remove(0); waitFor { app.music.state.value.error?.contains("Ferma l'anteprima") == true }
        assertEquals(1, decoded.mediaItemCount)
        app.music.finishPreview(second); waitFor { decoded.mediaItemCount == 2 && app.music.previewToken.value == null }
        assertEquals(songs.map { it.id }, (0 until decoded.mediaItemCount).map { decoded.getMediaItemAt(it).mediaId })
    }
    @Test fun explicitNewSongSupersedesPreviewAndItsLaterDismissalCannotRestoreTheOldQueue() {
        val token = begin()
        app.music.play(songs[1].id, listOf(songs[1].id))
        waitFor { app.music.previewToken.value == null && decoded.mediaItemCount == 1 && decoded.currentMediaItem?.mediaId == songs[1].id && decoded.playWhenReady }
        app.music.finishPreview(token); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, decoded.mediaItemCount); assertEquals(songs[1].id, decoded.currentMediaItem!!.mediaId)
    }
    @Test fun recreationPersistsOnlyCanonicalItemsAndNeverAutoplaysThePreview() {
        begin(); service.destroy()
        service = Robolectric.buildService(MusicService::class.java).create()
        waitFor { ReflectionHelpers.getField<CompletableDeferred<Unit>>(service.get(), "restored").isCompleted }
        val recovered = ReflectionHelpers.getField<Player>(service.get(), "player")
        assertEquals(songs.map { it.id }, (0 until recovered.mediaItemCount).map { recovered.getMediaItemAt(it).mediaId })
        assertFalse(recovered.playWhenReady); assertNull(app.music.previewToken.value)
        assertEquals(0L, recovered.currentMediaItem!!.clippingConfiguration.startPositionMs)
    }
    @Test fun hiddenSourceIsFilteredWhenRestoringRatherThanPlayingASavedInaccessibleItem() {
        val token = begin(); runBlocking { app.catalog.hideAlbums(listOf(songs.first().albumId), true) }
        app.music.finishPreview(token); waitFor { app.music.previewToken.value == null && decoded.mediaItemCount == 0 }
        assertFalse(decoded.playWhenReady); assertEquals(2, runBlocking { app.catalog.dao.media().size })
    }
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        do { shadowOf(Looper.getMainLooper()).idle(); if (done()) return; Thread.sleep(10) } while (System.nanoTime() < deadline)
        fail("Audio preview did not reach expected state; ${app.music.state.value.error}")
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class PreviewDecodedPlayer(items: List<MediaItem>) : SimpleBasePlayer(Looper.getMainLooper()) {
    private fun playlist(items: List<MediaItem>) = items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).setMediaMetadata(it.mediaMetadata)
        .setDurationUs((if (it.clippingConfiguration.endPositionMs > 0) it.clippingConfiguration.endPositionMs - it.clippingConfiguration.startPositionMs else 6000) * 1000)
        .setIsSeekable(true).build() }
    private var value = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build()).setPlaylist(playlist(items))
        .setCurrentMediaItemIndex(1).setContentPositionMs(3000).setPlaybackState(Player.STATE_READY).setShuffleModeEnabled(true).setRepeatMode(Player.REPEAT_MODE_ALL)
        .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
    override fun getState() = value
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> { value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build(); return Futures.immediateVoidFuture() }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        value = value.buildUpon().setPlaylist(playlist(mediaItems)).setCurrentMediaItemIndex(if (mediaItems.isEmpty()) C.INDEX_UNSET else startIndex.takeIf { it >= 0 } ?: 0)
            .setContentPositionMs(startPositionMs.takeIf { it >= 0 } ?: 0).setPlaybackState(if (mediaItems.isEmpty()) Player.STATE_IDLE else Player.STATE_READY).build()
        return Futures.immediateVoidFuture()
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = handleSetMediaItems(value.playlist.filterIndexed { index, _ -> index !in fromIndex until toIndex }.map { it.mediaItem }, 0, 0)
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { value = value.buildUpon().setRepeatMode(repeatMode).build(); return Futures.immediateVoidFuture() }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> { value = value.buildUpon().setShuffleModeEnabled(shuffleModeEnabled).build(); return Futures.immediateVoidFuture() }
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> { value = value.buildUpon().setCurrentMediaItemIndex(mediaItemIndex).setContentPositionMs(positionMs).build(); return Futures.immediateVoidFuture() }
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
