package it.ayngallery

import android.content.*
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import android.content.pm.ProviderInfo
import android.media.AudioManager
import android.os.*
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.google.common.util.concurrent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

/** Production service/session/Binder and receiver; decoded readiness is a fixture, not an audio-output proof. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
class MusicVideoTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private lateinit var service: org.robolectric.android.controller.ServiceController<MusicService>
    private lateinit var decoded: DecodedMusicFixture
    private lateinit var transport: MediaController
    @Before fun connect() {
        val documents = OperationDocuments()
        documents.attachInfo(app, ProviderInfo().apply { authority = "fixture"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("fixture", documents)
        val songs = (1..2).map { MediaEntity("song$it", "fixture:song$it", "content://fixture/tree/root/document/song$it", "album", "song$it.wav", "audio/wav", "AUDIO", 100, 1) }
        val video = songs.first().copy(id = "video", documentKey = "fixture:image", uri = "content://fixture/tree/root/document/image", name = "corrupt-video.mp4", mime = "video/mp4", kind = "VIDEO")
        runBlocking {
            val source = SourceEntity("source", "content://fixture/tree/root", "Fixture", "MIXED")
            app.catalog.writer.withLock { app.catalog.db.withTransaction {
            app.contentResolver.takePersistableUriPermission(android.net.Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.catalog.dao.saveSource(source)
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("album", "fixture:root", "content://fixture/tree/root/document/root", "Fixture", "Fixture", null)))
            app.catalog.dao.saveMedia(songs + video)
            app.catalog.dao.saveMembership((songs + video).map { MediaSource(it.id, source.id, 1) })
            } }
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && songs.all(it::usable) && it.usable(video) } }
        }
        shadowOf(app).grantPermissions("it.ayngallery.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        org.robolectric.shadows.ShadowProcess.setUid(app.applicationInfo.uid)
        service = Robolectric.buildService(MusicService::class.java).create()
        val recovered = ReflectionHelpers.getField<CompletableDeferred<Unit>>(service.get(), "restored")
        waitFor { recovered.isCompleted }
        // Replace decoding only; use the production service's player boundary, callback, controller and receiver.
        ReflectionHelpers.getField<Player>(service.get(), "player").release()
        shadowOf(Looper.getMainLooper()).idle()
        decoded = DecodedMusicFixture(songs.map { MediaItem.Builder().setMediaId(it.id).setUri(it.uri).build() })
        ReflectionHelpers.setField(service.get(), "player", decoded)
        app.music.soundSettingsPlayer = decoded
        val wrapped = MusicSessionPlayer(decoded, app.preferences, app.music::invalidateVideoResume)
        ReflectionHelpers.setField(service.get(), "sessionPlayer", wrapped)
        ReflectionHelpers.getField<MediaSession>(service.get(), "mediaSession").setPlayer(wrapped)
        val binding = Intent(app, MusicService::class.java).setAction(MediaSessionService.SERVICE_INTERFACE)
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MusicService::class.java), service.get().onBind(binding))
        app.music.observe()
        waitFor { ReflectionHelpers.getField<MediaController?>(app.music, "controller") != null }
        transport = ReflectionHelpers.getField(app.music, "controller")
        waitFor { transport.playWhenReady && transport.currentMediaItem?.mediaId == "song1" }
    }
    @After fun release() {
        app.video.stop(app.video.owner)
        app.music.close()
        if (::service.isInitialized) service.destroy()
        app.onTerminate()
    }
    @Test fun musicVolumeChangesOnlyItsPlayerAndSurvivesSoundSettingsFade() {
        val system = app.getSystemService(AudioManager::class.java)
        val systemBefore = system.getStreamVolume(AudioManager.STREAM_MUSIC)
        app.music.setVolume(.35f)
        waitFor { kotlin.math.abs(decoded.volume - .35f) < .001f && kotlin.math.abs(app.music.volume.value - .35f) < .001f }
        assertEquals(systemBefore, system.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(decoded.playWhenReady)
        val ticket = app.music.pauseForSoundSettings()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(450))
        waitFor { !transport.playWhenReady }
        app.music.resumeAfterSoundSettings(ticket)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(450))
        assertEquals(.35f, decoded.volume, .001f)
        assertTrue(decoded.playWhenReady)
        app.music.setVolume(2f); waitFor { decoded.volume == 1f }
        app.music.setVolume(-1f); waitFor { decoded.volume == 0f }
        assertEquals(systemBefore, system.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    @Test fun soundSettingsPauseAndResumeOnlyTheirOwnUnchangedTrack() {
        val ticket = app.music.pauseForSoundSettings()
        assertNotNull(ticket); assertTrue(decoded.playWhenReady)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertTrue(decoded.volume in .1f.. .9f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        assertFalse(decoded.playWhenReady)
        waitFor { !transport.playWhenReady }
        app.music.resumeAfterSoundSettings(ticket); waitFor { decoded.playWhenReady }
        assertEquals(0f, decoded.volume, .001f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(450))
        assertEquals(1f, decoded.volume, .001f)
        val second = app.music.pauseForSoundSettings()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(450))
        waitFor { !transport.playWhenReady }
        transport.seekTo(1, 0); waitFor { decoded.currentMediaItem?.mediaId == "song2" }
        app.music.resumeAfterSoundSettings(second)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(decoded.playWhenReady)
    }
    @Test fun automaticPauseResumesOnlyItsOwnUnchangedSongAndIncludesBuffering() {
        val token = app.music.pauseForVideo()
        assertEquals("song1", token); assertFalse(decoded.playWhenReady)
        waitFor { !transport.playWhenReady }
        app.music.finishVideo(token); waitFor { decoded.playWhenReady }
        decoded.readiness(Player.STATE_BUFFERING)
        waitFor { transport.playbackState == Player.STATE_BUFFERING }
        assertFalse(transport.isPlaying); assertTrue(transport.playWhenReady)
        val bufferingToken = app.music.pauseForVideo()
        assertEquals("song1", bufferingToken); assertFalse(decoded.playWhenReady)
        waitFor { !transport.playWhenReady }
        app.music.finishVideo(bufferingToken); waitFor { decoded.playWhenReady }
        assertEquals("song1", decoded.currentMediaItem?.mediaId)
    }
    @Test fun notificationPauseAndSeekWinOverAnAlreadyQueuedResumeRequest() {
        val token = app.music.pauseForVideo()!!
        waitFor { !transport.playWhenReady }
        val revision = app.music.videoRevision
        transport.pause() // Already paused by the video: still an explicit user decision.
        requestResume(token, revision)
        assertFalse(decoded.playWhenReady)
        assertTrue(app.music.videoRevision > revision)
        decoded.play(); waitFor { transport.playWhenReady }
        app.music.pauseForVideo(); waitFor { !transport.playWhenReady }
        val seekRevision = app.music.videoRevision
        transport.seekTo(1, 0)
        requestResume("song1", seekRevision)
        assertEquals("song2", decoded.currentMediaItem?.mediaId); assertFalse(decoded.playWhenReady)
    }
    @Test fun immediateCloseAndReplacementTransferThePauseWithoutResumingUnderTheNewVideo() {
        val first = app.music.pauseForVideo()!!
        val firstRevision = app.music.videoRevision
        app.music.finishVideo(first) // No controller-state pump: close can precede its paused-state update.
        val replacement = app.music.pauseForVideo()
        assertEquals("song1", replacement)
        requestResume(first, firstRevision)
        assertFalse(decoded.playWhenReady)
        app.music.finishVideo(replacement)
        waitFor { decoded.playWhenReady }
        val immediate = app.music.pauseForVideo()!!
        app.music.finishVideo(immediate)
        waitFor { decoded.playWhenReady }
    }
    @Test fun noisyBroadcastAndHiddenSourcePreventAutomaticResume() {
        val token = app.music.pauseForVideo()!!
        val revision = app.music.videoRevision
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        waitFor { app.music.videoRevision > revision }
        requestResume(token, revision); assertFalse(decoded.playWhenReady)
        decoded.play(); waitFor { transport.playWhenReady }
        val hiddenToken = app.music.pauseForVideo()!!
        val hiddenRevision = app.music.videoRevision
        runBlocking { app.catalog.hideAlbums(listOf("album"), true) }
        requestResume(hiddenToken, hiddenRevision); assertFalse(decoded.playWhenReady)
        assertTrue(runBlocking { app.catalog.dao.media("song1") != null })
    }
    @Test fun videoTogglePausesAndResumesAndStopClearsNavigationDimming() {
        val video = app.catalog.snapshot.value.byId.getValue("video")
        app.video.play(video, 0, false)
        assertTrue(app.video.player!!.playWhenReady)
        assertFalse("Video startup stays visible until the optional timer or moon is used", app.video.dimNavigation)
        app.video.toggle()
        assertFalse(app.video.player!!.playWhenReady)
        assertFalse(app.video.dimNavigation)
        app.video.toggle()
        assertTrue(app.video.player!!.playWhenReady)
        app.video.stop(0)
        assertFalse(app.video.dimNavigation)
    }
    @Test fun mutedVideoDoesNotPauseMusicAndAnotherPaneCannotStopIt() {
        val video = app.catalog.snapshot.value.byId.getValue("video")
        app.video.play(video, 0, true, "pane:one")
        assertTrue(decoded.playWhenReady)
        app.video.stop(1, video.id, "pane:one")
        assertEquals(video.id, app.video.mediaId); assertEquals(0, app.video.owner)
        app.video.stop(0, video.id, "pane:other")
        assertEquals(video.id, app.video.mediaId)
        app.video.stop(0, video.id, "pane:one")
        assertNull(app.video.mediaId); assertTrue(decoded.playWhenReady)
    }
    @Test fun sharedAudioIsExplicitAndCanRestoreNormalFocusHandling() {
        runBlocking { app.preferences.update { it.copy(videoKeepPlaying = false) } }
        runBlocking { app.preferences.update { it.copy(videoMixAudio = true) } }
        app.video.play(app.catalog.snapshot.value.byId.getValue("video"), 0, false)
        shadowOf(app.video.player!!.playbackLooper).idle()
        val internal = ReflectionHelpers.getField<Any>(app.video.player, "internalPlayer")
        val focus = ReflectionHelpers.getField<Any>(internal, "audioFocusManager")
        assertNull(ReflectionHelpers.getField<Any?>(focus, "audioAttributes"))
        assertFalse("The gallery music queue still pauses for its own video", decoded.playWhenReady)
        runBlocking { app.preferences.update { it.copy(videoMixAudio = false) } }
        waitFor { ReflectionHelpers.getField<Any?>(focus, "audioAttributes") != null }
    }
    @Test fun videoNotificationCommandsControlTheSameDecoderWithoutReplacingMusicQueue() {
        app.video.play(app.catalog.snapshot.value.byId.getValue("video"), 0, false)
        val decoder = app.video.player!!
        val notification = Robolectric.buildService(VideoService::class.java).create()
        var control: MediaController? = null
        try {
            val session = ReflectionHelpers.getField<MediaSession>(notification.get(), "session")
            assertSame(decoder, session.player)
            val future = MediaController.Builder(app, session.token).buildAsync()
            waitFor { future.isDone }; control = future.get()
            assertFalse(control.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
            control.pause(); waitFor { !decoder.playWhenReady }
            control.play(); waitFor { decoder.playWhenReady }
            assertSame(decoder, app.video.player)
            assertEquals("song1", transport.currentMediaItem?.mediaId)
            assertFalse(decoded.playWhenReady)
            app.video.stop(0)
            assertNull(ReflectionHelpers.getField<MediaSession?>(notification.get(), "session"))
        } finally { control?.release(); notification.destroy() }
    }
    @Test fun videoAndMusicDecoderFailureCannotGrantAutomaticResume() {
        val video = app.catalog.snapshot.value.byId.getValue("video")
        app.video.play(video, 0, false)
        assertFalse(decoded.playWhenReady)
        // Decoder failure is an injected event; Robolectric does not certify native codec behavior.
        app.video.playbackFailed(PlaybackException("Fixture video decoder failure", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertNotNull(app.video.error)
        app.video.stop(0); assertFalse(decoded.playWhenReady)
        decoded.play(); waitFor { transport.playWhenReady }
        val token = app.music.pauseForVideo()!!
        decoded.fail()
        waitFor { transport.playerError != null }
        requestResume(token, app.music.videoRevision); assertFalse(decoded.playWhenReady)
    }
    private fun requestResume(id: String, revision: Long) {
        val request = transport.sendCustomCommand(SessionCommand("it.ayngallery.VIDEO_RESUME", Bundle.EMPTY),
            Bundle().apply { putString("media", id); putLong("revision", revision) })
        waitFor { request.isDone }
        assertEquals(SessionResult.RESULT_SUCCESS, request.get().resultCode)
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            app.video.player?.playbackLooper?.let { shadowOf(it).idle() }
            shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("Audio policy fixture did not reach its expected state: video=${app.video.player?.playbackState}, error=${app.video.player?.playerError}, audio=${if (::decoded.isInitialized) decoded.playbackState else null}")
    }
}

/** Only the decoder/readiness is simulated; never generates sound or claims codec support. */
@androidx.annotation.OptIn(UnstableApi::class)
private class DecodedMusicFixture(items: List<MediaItem>) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var value = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).setDurationUs(60_000_000).setIsSeekable(true).build() })
        .setCurrentMediaItemIndex(0).setContentPositionMs(10_000).setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
    override fun getState() = value
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        value = value.buildUpon().setVolume(volume).build()
        return Futures.immediateVoidFuture()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        return Futures.immediateVoidFuture()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        value = value.buildUpon().setCurrentMediaItemIndex(mediaItemIndex).setContentPositionMs(positionMs).build()
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val kept = value.playlist.filterIndexed { index, _ -> index !in fromIndex until toIndex }
        value = value.buildUpon().setPlaylist(kept).setCurrentMediaItemIndex(if (kept.isEmpty()) C.INDEX_UNSET else 0)
            .setPlaybackState(if (kept.isEmpty()) Player.STATE_IDLE else value.playbackState)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        return Futures.immediateVoidFuture()
    }
    fun readiness(state: Int) { value = value.buildUpon().setPlaybackState(state).build(); invalidateState() }
    fun fail() { value = value.buildUpon().setPlaybackState(Player.STATE_IDLE).setPlayerError(
        PlaybackException("Fixture decoder failure", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)).build(); invalidateState() }
}
