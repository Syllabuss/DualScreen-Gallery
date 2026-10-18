package it.ayngallery

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.*
import androidx.media3.extractor.mp3.Mp3Extractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
class PlaybackPolicyTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp

    @org.junit.After fun close() { app.onTerminate() }

    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun liveStartupDeadlineReleasesChannelNavigationAndCancellationPreventsAnObsoleteFailure() {
        runBlocking { app.preferences.update { it.copy(tvEnabled = true) } }
        val entry = TvCatalogEntry("https://example.com/live.m3u8", "News", null, null, null)
        val loading = org.robolectric.util.ReflectionHelpers.getField<androidx.compose.runtime.MutableState<TvCatalogEntry?>>(app.video, "liveLoadingChannel\$delegate")
        val request = kotlinx.coroutines.Job()
        loading.value = entry
        org.robolectric.util.ReflectionHelpers.setField(app.video, "liveRequest", request)
        fun watch() = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(app.video, "watchLiveStartup",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(TvCatalogEntry::class.java, entry))
        watch()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(19))
        assertNotNull(app.video.liveLoadingChannel)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertNull(app.video.liveLoadingChannel); assertTrue(request.isCancelled); assertNotNull(app.tv.failure)
        app.tv.failure = null; loading.value = entry; watch(); app.video.cancelLiveRequest()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21))
        assertNull(app.tv.failure)
    }

    @Test fun sleepPausesCurrentPlaybackAndRevokesThePreviousMusicResumeByDefault() {
        var videoPlaying = true
        var musicPlaying = true
        val player = java.lang.reflect.Proxy.newProxyInstance(androidx.media3.exoplayer.ExoPlayer::class.java.classLoader,
            arrayOf(androidx.media3.exoplayer.ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "getPlayWhenReady" -> videoPlaying
                "pause" -> { videoPlaying = false; null }
                else -> null
            }
        } as androidx.media3.exoplayer.ExoPlayer
        val holder = org.robolectric.util.ReflectionHelpers.getField<androidx.compose.runtime.MutableState<androidx.media3.exoplayer.ExoPlayer?>>(app.video, "player\$delegate")
        holder.value = player
        app.music.pauseAutomaticallyForVideo = { musicPlaying = false; "music" }
        try {
            val ticket = app.music.pauseForVideo()
            app.playbackSleep()
            app.music.finishVideo(ticket)
            assertFalse(videoPlaying); assertFalse(musicPlaying)
            assertTrue(app.playbackSleeping)
            assertNull(org.robolectric.util.ReflectionHelpers.getField<Any?>(app.music, "pendingVideoResume"))
            app.video.panelStopped(-1)
            assertSame(player, app.video.player)
        } finally { holder.value = null; app.playbackWake() }
    }

    @Test fun optingIntoSleepOnlyPreservesPlaybackThatIsAlreadyRunningAndPreferencesRoundTrip() = runBlocking {
        val enabled = GalleryPreferences(musicDuringSleep = true, videoDuringSleep = true)
        assertEquals(enabled, PreferencesTransfer.decode(PreferencesTransfer.encode(enabled)))
        app.preferences.save(enabled)
        val stored = PreferenceStore(app).state.value
        assertTrue(stored.musicDuringSleep && stored.videoDuringSleep)
        val old = org.json.JSONObject(PreferencesTransfer.encode(enabled)).put("version", 31)
        old.getJSONObject("preferences").remove("musicDuringSleep")
        old.getJSONObject("preferences").remove("videoDuringSleep")
        assertFalse(PreferencesTransfer.decode(old.toString()).musicDuringSleep)
        var pauses = 0
        app.music.pauseAutomaticallyForVideo = { pauses++; null }
        app.playbackSleep()
        assertEquals(0, pauses)
        assertNull(app.video.player)
        app.playbackWake()
    }

    @Test fun backClosesOnlyAPausedViewerAndConsumesTheCommandWithoutPausingAPlayingVideo() {
        var playing = true
        var released = false
        val player = java.lang.reflect.Proxy.newProxyInstance(androidx.media3.exoplayer.ExoPlayer::class.java.classLoader,
            arrayOf(androidx.media3.exoplayer.ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "getPlayWhenReady" -> playing
                "release" -> { released = true; null }
                else -> null
            }
        } as androidx.media3.exoplayer.ExoPlayer
        val holder = org.robolectric.util.ReflectionHelpers.getField<androidx.compose.runtime.MutableState<androidx.media3.exoplayer.ExoPlayer?>>(app.video, "player\$delegate")
        holder.value = player
        try {
            assertFalse(app.video.closePausedViewer())
            assertFalse(released)
            playing = false
            assertTrue(app.video.closePausedViewer())
            assertTrue(released)
            assertNull(app.video.player)
            assertFalse(app.video.closePausedViewer())
        } finally { holder.value = null }
    }

    @Test fun playingAgainAfterVideoEndSeeksToZeroAndPlaysEvenWhenPlayWhenReadyStayedTrue() {
        val commands = mutableListOf<String>()
        val player = java.lang.reflect.Proxy.newProxyInstance(androidx.media3.exoplayer.ExoPlayer::class.java.classLoader,
            arrayOf(androidx.media3.exoplayer.ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "getPlaybackState" -> androidx.media3.common.Player.STATE_ENDED
                "getPlayWhenReady" -> true
                "seekTo" -> { commands += "seek:${args!!.last()}"; null }
                "play", "pause" -> { commands += method.name; null }
                else -> null
            }
        } as androidx.media3.exoplayer.ExoPlayer
        val holder = org.robolectric.util.ReflectionHelpers.getField<androidx.compose.runtime.MutableState<androidx.media3.exoplayer.ExoPlayer?>>(app.video, "player\$delegate")
        holder.value = player
        try {
            app.video.toggle()
            assertEquals(listOf("seek:0", "play"), commands)
        } finally { holder.value = null }
    }

    @Test fun videoResumeExpiresAfterAWeekAndDoesNotOfferTheStartOrEnd() {
        val now = 2_000_000_000L
        val video = MediaEntity("video", "video", "content://test/video", "album", "video.mp4", "video/mp4", "VIDEO", 100, 1,
            duration = 180_000, videoPosition = 30_000, videoPositionAt = now - 1000)
        assertTrue(app.video.resumableVideo(video, now))
        assertFalse(app.video.resumableVideo(video.copy(videoPositionAt = now - 7 * 86_400_000L - 1), now))
        assertFalse(app.video.resumableVideo(video.copy(videoPositionAt = 0), now))
        assertFalse(app.video.resumableVideo(video.copy(videoPosition = 500), now))
        assertFalse(app.video.resumableVideo(video.copy(videoPosition = 179_000), now))
        assertFalse(app.video.resumableVideo(video.copy(videoPositionAt = now + 1), now))
    }

    @Test fun backdropColorsPersistAndTransferAndRejectInvalidValues() = runBlocking {
        val value = GalleryPreferences(imageBackdrop = "RADIAL", imageBackdropColor = "#EE44AA", imageBackdropEnd = "#112233", imageBackdropOpacity = 72)
        app.preferences.save(value)
        assertEquals(value, PreferenceStore(app).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        assertThrows(IllegalArgumentException::class.java) { value.copy(imageBackdropColor = "not a color").validate() }
        assertThrows(IllegalArgumentException::class.java) { value.copy(imageBackdropOpacity = 101).validate() }
        Unit
    }

    @Test fun mp3IndexUsesActualFramesWhenXingHeaderOverstatesDurationAndFileLength() {
        // Synthetic MPEG1 Layer III frames; the stale Xing header claims 200 frames, but only 100 exist.
        val frameSize = 417
        val bytes = ByteArray(frameSize * 101)
        for (frame in 0..100) ByteBuffer.wrap(bytes, frame * frameSize, 4).putInt(0xFFFB9000.toInt())
        ByteBuffer.wrap(bytes, 36, 16).put("Xing".toByteArray()).putInt(3).putInt(200).putInt(frameSize * 201)
        val source = ByteArrayDataSource(bytes)
        source.open(DataSpec(android.net.Uri.parse("memory:test.mp3")))
        val input = DefaultExtractorInput(source, 0, bytes.size.toLong())
        var map: SeekMap? = null
        val extractor = musicExtractors().createExtractors().filterIsInstance<Mp3Extractor>().single()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DummyTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        })
        try {
            val position = PositionHolder()
            var reads = 0
            while (extractor.read(input, position) != Extractor.RESULT_END_OF_INPUT) assertTrue(++reads < 1000)
            assertNotNull(map)
            assertTrue("Duration must reflect readable audio, not the oversized Xing declaration", map!!.durationUs in 2_500_000L..2_700_000L)
            assertTrue(map!!.getSeekPoints(map!!.durationUs).first.position <= bytes.size)
        } finally { extractor.release(); source.close() }
    }
}
