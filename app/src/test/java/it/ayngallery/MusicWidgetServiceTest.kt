package it.ayngallery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual service/session/controller binder; verifies command and queue identity, not physical audio output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
class MusicWidgetServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var service: org.robolectric.android.controller.ServiceController<MusicService>? = null
    @After fun release() { app.music.close(); service?.destroy(); app.onTerminate() }
    @Test fun repeatedTapsDuringColdConnectionDoNotQueueAnotherToggleAndCancellationUnlocks() {
        val connection = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        org.robolectric.util.ReflectionHelpers.setField(app.music, "future", connection)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = scope.async { app.music.widgetCommand("TOGGLE", null) }
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!org.robolectric.util.ReflectionHelpers.getField<Boolean>(app.music, "widgetStarting") && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            assertTrue(org.robolectric.util.ReflectionHelpers.getField<Boolean>(app.music, "widgetStarting"))
            val repeated = scope.async { app.music.widgetCommand("TOGGLE", null) }
            await(repeated)
            assertTrue(first.isActive)
            first.cancel()
            while (!first.isCompleted && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            val retry = scope.async { app.music.widgetCommand("TOGGLE", null) }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(retry.isActive)
            retry.cancel()
        } finally { scope.cancel() }
    }
    @Test fun widgetCommandsUseRecoveredQueueAndPreserveTheFavoriteTarget() {
        val documents = OperationDocuments()
        documents.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "fixture"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("fixture", documents)
        runBlocking {
            val source = SourceEntity("music", "content://fixture/tree/root", "Music", "MUSIC")
            val album = AlbumEntity("album", "fixture:root", "content://fixture/tree/root/document/root", "Music", "Music", null)
            val songs = (1..2).map { MediaEntity("song$it", "fixture:song$it", "content://fixture/tree/root/document/song$it", album.id, "song$it.wav", "audio/wav", "AUDIO", 100, 1, title = "Song $it") }
            app.contentResolver.takePersistableUriPermission(android.net.Uri.parse(source.treeUri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.catalog.dao.saveSource(source); app.catalog.dao.saveAlbums(listOf(album)); app.catalog.dao.saveMedia(songs)
            app.catalog.dao.saveMembership(songs.map { MediaSource(it.id, source.id, 1) })
        }
        app.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putString("queue", "[\"song1\",\"song2\"]").putInt("index", 0).commit()
        // Android grants the app's own signature permission; Robolectric's service fixture needs that grant explicitly.
        shadowOf(app).grantPermissions("it.ayngallery.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        // Binder identity in the Robolectric process must match the installed application identity.
        org.robolectric.shadows.ShadowProcess.setUid(app.applicationInfo.uid)
        service = Robolectric.buildService(MusicService::class.java).create()
        val binding = Intent(app, MusicService::class.java).setAction(MediaSessionService.SERVICE_INTERFACE)
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MusicService::class.java), service!!.get().onBind(binding))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val next = scope.async { app.music.widgetCommand("NEXT", "song1") }
            try { await(next) } catch (error: Exception) {
                val player = org.robolectric.util.ReflectionHelpers.getField<androidx.media3.exoplayer.ExoPlayer>(service!!.get(), "player")
                val saved = app.getSharedPreferences("music", Context.MODE_PRIVATE).getString("queue", "[]")
                throw AssertionError("Widget failed: queue=${(0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }}, current=${player.currentMediaItem?.mediaId}, prefs=$saved, visible=${runBlocking { app.catalog.readSnapshot() }.media.map { it.id }}", error)
            }
            assertEquals("song2", app.music.state.value.id)
            assertFalse(app.music.state.value.playing) // Queue navigation never autoplays.
            val cover = MediaEntity("cover", "fixture:image", "content://fixture/tree/root/document/image", "album", "cover.png", "image/png", "IMAGE", 100, 1)
            val embedded = java.io.File(app.cacheDir, "artwork/song2.art").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            runBlocking {
                app.catalog.dao.saveMedia(listOf(cover, app.catalog.dao.media("song2")!!.copy(artworkUri = embedded.toURI().toString())))
                app.catalog.dao.saveMembership(listOf(MediaSource(cover.id, "music", 1)))
                app.catalog.setMusicArtwork(ArtworkOrders.track("song2"), cover.id, null)
            }
            fun waitArtwork(uri: String) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (app.music.state.value.artwork != uri && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
                assertEquals(uri, app.music.state.value.artwork)
                assertEquals("song2", app.music.state.value.id); assertEquals(listOf("song1", "song2"), app.music.state.value.queue)
                assertFalse(app.music.state.value.playing)
            }
            waitArtwork(cover.uri)
            runBlocking { app.preferences.save(app.preferences.state.value.copy(artworkOrder = "EMBEDDED,MANUAL,FOLDER")) }
            waitArtwork(embedded.toURI().toString()) // Preferences alone update the actual session metadata.
            val beforeCleanup = app.music.state.value
            await(scope.async { app.cache.clear() })
            assertFalse(embedded.exists())
            waitArtwork(cover.uri) // Cleanup invalidates session artwork while preserving the same player and queue.
            assertEquals(beforeCleanup.queue, app.music.state.value.queue)
            assertEquals(beforeCleanup.position, app.music.state.value.position)
            assertEquals(beforeCleanup.id, app.music.state.value.id)
            assertEquals(beforeCleanup.playing, app.music.state.value.playing)
            val staleFavorite = scope.async { runCatching { app.music.widgetCommand("FAVORITE", "song1") }.isFailure }
            await(staleFavorite); assertTrue(runBlocking { staleFavorite.await() })
            assertFalse(runBlocking { app.catalog.dao.media("song1")!!.favorite })
            for ((shuffle, repeat) in listOf(true to 0, false to 1, false to 0)) {
                await(scope.async { app.music.widgetCommand("MODE", "song2") })
                assertEquals(shuffle, app.music.state.value.shuffle)
                assertEquals(repeat, app.music.state.value.repeat)
            }
            val favorite = scope.async { app.music.widgetCommand("FAVORITE", "song2") }
            await(favorite)
            assertTrue(runBlocking { app.catalog.dao.media("song2")!!.favorite })
            await(scope.async { app.music.widgetCommand("SHUFFLE", "song2") })
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(app.music.state.value.shuffle)
            await(scope.async { app.music.widgetCommand("SHUFFLE", "song2") })
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(app.music.state.value.shuffle)
            assertEquals(listOf("song1", "song2"), app.music.state.value.queue)
            assertNull(shadowOf(app).nextStartedActivity)
            val delegate = org.robolectric.util.ReflectionHelpers.getField<androidx.media3.exoplayer.ExoPlayer>(service!!.get(), "player")
            val transport = org.robolectric.util.ReflectionHelpers.getField<androidx.media3.session.MediaController>(app.music, "controller")
            fun waitTrack(id: String, position: Long = 0) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while ((app.music.state.value.id != id || app.music.state.value.position != position) && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
                }
                assertEquals(id, app.music.state.value.id); assertEquals(position, app.music.state.value.position)
                assertFalse(app.music.state.value.playing)
            }
            delegate.seekTo(5000); shadowOf(Looper.getMainLooper()).idle()
            await(scope.async { app.music.widgetCommand("PREVIOUS", "song2") })
            waitTrack("song2") // Default restarts after three seconds, including a widget transport.
            app.music.previous(); waitTrack("song1") // A second press from the app shares the same service policy.
            delegate.seekTo(1, 5000); shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.preferences.save(app.preferences.state.value.copy(alwaysPrevious = true)) }
            val thresholdDeadline = System.nanoTime() + 5_000_000_000L
            while (transport.maxSeekToPreviousPosition != Long.MAX_VALUE && System.nanoTime() < thresholdDeadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
            }
            assertEquals(Long.MAX_VALUE, transport.maxSeekToPreviousPosition)
            transport.seekToPrevious(); waitTrack("song1") // Standard MediaSession command, also used by system controls.
            delegate.seekTo(1, 5000); shadowOf(Looper.getMainLooper()).idle()
            transport.seekToPreviousMediaItem(); waitTrack("song1")
            delegate.seekTo(1, 5000); shadowOf(Looper.getMainLooper()).idle()
            await(scope.async { app.music.widgetCommand("PREVIOUS", "song2") }); waitTrack("song1")
            assertEquals(listOf("song1", "song2"), app.music.state.value.queue)
            await(scope.async { app.music.widgetCommand("NEXT", "song1") }); waitTrack("song2")
            val host = shadowOf(android.appwidget.AppWidgetManager.getInstance(app))
            val ids = listOf(MusicFullWidget::class.java to R.layout.music_widget_full, MusicMinimalWidget::class.java to R.layout.music_widget_minimal).map { (provider, layout) ->
                val id = host.createWidget(provider, layout)
                host.putWidgetInfo(id, android.appwidget.AppWidgetProviderInfo().apply { this.provider = ComponentName(app, provider); initialLayout = layout })
                id
            }
            runBlocking { app.widgets.update(ids) }
            val frame = host.getViewFor(ids.first())
            // Robolectric does not dispatch this manifest-only explicit receiver; register the same receiver for the fixture.
            val receiver = MusicWidgetControlReceiver()
            androidx.core.content.ContextCompat.registerReceiver(app, receiver, android.content.IntentFilter(MusicWidgetControlReceiver.ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                assertTrue(frame.findViewById<android.view.View>(R.id.widget_previous).performClick())
                val deadline = System.nanoTime() + 5_000_000_000L
                while (app.music.state.value.id != "song1" && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
                assertEquals("song1", app.music.state.value.id)
            } finally { app.unregisterReceiver(receiver) }
            runBlocking { app.widgets.update(ids) }
            for (id in ids) assertTrue(host.getViewFor(id).findViewById<android.widget.TextView>(R.id.widget_title).text.contains("Song 1"))
            assertNull(shadowOf(app).nextStartedActivity) // A real RemoteViews tap still never opens the app.
            service!!.destroy(); service = null
            shadowOf(Looper.getMainLooper()).idle()
            service = Robolectric.buildService(MusicService::class.java).create()
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MusicService::class.java), service!!.get().onBind(binding))
            await(scope.async { app.music.widgetCommand("FAVORITE", "song1") })
            assertTrue(runBlocking { app.catalog.dao.media("song1")!!.favorite })
            assertEquals(listOf("song1", "song2"), app.music.state.value.queue)
            assertEquals("song1", app.music.state.value.id)
            assertFalse(app.music.state.value.playing)
        } finally { scope.cancel() }
    }
    private fun await(job: Deferred<*>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (job.isCompleted) { runBlocking { job.await() }; return }
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        fail("MediaSession command did not complete")
    }
}
