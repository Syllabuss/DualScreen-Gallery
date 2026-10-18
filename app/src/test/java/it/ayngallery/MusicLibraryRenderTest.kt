package it.ayngallery

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MusicLibraryRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun release() { app.onTerminate() }
    @Test fun previewFollowsPlayingTrackUntilPausedIncludingBufferingAndTrackChanges() {
        val first = MediaEntity("first", "first", "content://preview/first", "album", "Execution Clap.mp3", "audio/mpeg", "AUDIO", 100, 1)
        val second = first.copy(id = "second", uri = "content://preview/second", name = "Shut Me Up.mp3")
        val data = CatalogSnapshot(sources = listOf(SourceEntity("music", "content://preview/root", "Music", "MUSIC")),
            media = listOf(first, second), membership = listOf(MediaSource(first.id, "music", 1), MediaSource(second.id, "music", 1)), initialized = true)
        app.session.selectMedia(second.id)
        val playback = mutableStateOf(PlaybackState(id = first.id, playing = true))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
                PreviewMusicPanel(app, playback.value, data, GalleryPreferences(reducedMotion = true), fullPlayer = true)
            } }
            compose.onNodeWithTag("preview-music-title").assertTextEquals("Execution Clap.mp3")
            compose.onNodeWithTag("preview-music-seek").assertIsEnabled()
            playback.value = playback.value.copy(playing = false, playRequested = true)
            compose.onNodeWithTag("preview-music-title").assertTextEquals("Execution Clap.mp3")
            playback.value = playback.value.copy(playRequested = false)
            compose.onNodeWithTag("preview-music-title").assertTextEquals("Shut Me Up.mp3")
            compose.onNodeWithTag("preview-music-seek").assertIsNotEnabled()
            playback.value = playback.value.copy(id = second.id, playing = true, playRequested = true)
            compose.onNodeWithTag("preview-music-title").assertTextEquals("Shut Me Up.mp3")
            compose.onNodeWithTag("preview-music-seek").assertIsEnabled()
            app.session.selectMedia(first.id)
            compose.onNodeWithTag("preview-music-title").assertTextEquals("Shut Me Up.mp3")
            playback.value = playback.value.copy(id = first.id)
            compose.onNodeWithTag("preview-music-title").assertTextEquals("Execution Clap.mp3")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun playerDisablesTransportAtBothEndsOfTheQueue() {
        val track = MediaEntity("track", "track", "content://preview/track", "album", "Song.mp3", "audio/mpeg", "AUDIO", 100, 1)
        val data = CatalogSnapshot(sources = listOf(SourceEntity("music", "content://preview/root", "Music", "MUSIC")),
            media = listOf(track), membership = listOf(MediaSource(track.id, "music", 1)), initialized = true)
        val playback = mutableStateOf(PlaybackState(id = track.id, queue = listOf("track", "other"), index = 0, hasPrevious = false, hasNext = true))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
                PreviewMusicPanel(app, playback.value, data, GalleryPreferences(reducedMotion = true), fullPlayer = true)
            } }
            compose.onNodeWithContentDescription("Brano precedente").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Brano successivo").assertIsEnabled()
            compose.runOnIdle { playback.value = playback.value.copy(index = 1, hasPrevious = true, hasNext = false) }
            compose.onNodeWithContentDescription("Brano precedente").assertIsEnabled()
            compose.onNodeWithContentDescription("Brano successivo").assertIsNotEnabled()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun rapidCoverEffectChangesAlwaysReturnToAnUprightUnscaledCover() {
        val track = MediaEntity("cover-track", "cover-track", "content://preview/track", "album", "Song.mp3", "audio/mpeg", "AUDIO", 100, 1)
        val data = CatalogSnapshot(sources = listOf(SourceEntity("music", "content://preview/root", "Music", "MUSIC")),
            media = listOf(track), membership = listOf(MediaSource(track.id, "music", 1)), initialized = true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        try {
            controller.get().setContent { GalleryDesign(GalleryPreferences()) {
                PreviewMusicPanel(app, PlaybackState(id = track.id, title = "Song", playing = true), data,
                    GalleryPreferences(), fullPlayer = true)
            } }
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
            fun cover() = compose.onNodeWithTag("music-cover-transform", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val rest = cover()
            fun advance(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
            repeat(3) {
                compose.onNodeWithTag("music-cover-effect:0").performClick()
                advance(900)
                assertTrue(cover().width < rest.width - 1)
                compose.onNodeWithTag("music-cover-effect:2").performClick()
                advance(100)
                compose.onNodeWithTag("music-cover-effect:1").performClick()
                compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
                compose.onNodeWithTag("music-cover-effect:3").performClick()
                advance(80)
                // Interrupt the return to rest and cycle again before it finishes.
                compose.onNodeWithTag("music-cover-effect:0").performClick()
                advance(90)
                compose.onNodeWithTag("music-cover-effect:2").performClick()
                advance(90)
                compose.onNodeWithTag("music-cover-effect:1").performClick()
                compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
                compose.onNodeWithTag("music-cover-effect:3").performClick()
                advance(700)
                val settled = cover()
                assertEquals(rest.left, settled.left, .5f)
                assertEquals(rest.top, settled.top, .5f)
                assertEquals(rest.width, settled.width, .5f)
                assertEquals(rest.height, settled.height, .5f)
            }
        } finally { controller.pause().stop().destroy(); compose.mainClock.autoAdvance = true }
    }
    @Test fun compactPlayerCanBeDraggedWithinThePreviewAndKeepsPositionWhileHidden() {
        val visible = mutableStateOf(true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
                PreviewMusicPanel(app, PlaybackState(id = "song", title = "Playing song", playing = true), CatalogSnapshot(),
                    GalleryPreferences(reducedMotion = true), fullPlayer = false, controlsVisible = visible.value)
            } }
            compose.waitForIdle()
            val player = compose.onNodeWithTag("preview-mini-player")
            val original = player.fetchSemanticsNode().boundsInRoot
            player.performTouchInput { swipe(center, center + Offset(180f, -160f)) }
            compose.waitForIdle()
            val moved = player.fetchSemanticsNode().boundsInRoot
            assertTrue(moved.left > original.left + 80)
            assertTrue(moved.top < original.top - 80)
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.onNodeWithTag("preview-mini-player").assertDoesNotExist()
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            val restored = player.fetchSemanticsNode().boundsInRoot
            assertEquals(moved.left, restored.left, .5f)
            assertEquals(moved.top, restored.top, .5f)
            player.performTouchInput { swipe(center, center + Offset(4000f, -4000f)) }
            compose.waitForIdle()
            compose.onNodeWithTag("preview-mini-player").assertDoesNotExist()
            compose.onNodeWithTag("preview-music-dock").assertIsDisplayed().performClick()
            compose.waitForIdle()
            val clamped = player.fetchSemanticsNode().boundsInRoot
            val preview = compose.onNodeWithTag("preview-music-panel").fetchSemanticsNode().boundsInRoot
            assertTrue(clamped.left >= preview.left && clamped.top >= preview.top)
            assertTrue(clamped.right <= preview.right && clamped.bottom <= preview.bottom)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun fullPreviewShowsTrackAndEveryPlaybackControl() {
        val track = MediaEntity("preview-track", "preview-track", "content://preview/track", "album", "Song.mp3", "audio/mpeg", "AUDIO", 100, 1,
            title = "Preview song", artist = "Preview artist")
        val data = CatalogSnapshot(sources = listOf(SourceEntity("music", "content://preview/root", "Music", "MUSIC")),
            media = listOf(track), membership = listOf(MediaSource(track.id, "music", 1)), initialized = true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
                PreviewMusicPanel(app, PlaybackState(id = track.id, title = track.title!!, duration = 120000), data,
                    GalleryPreferences(reducedMotion = true), fullPlayer = true)
            } }
            compose.onNodeWithText("Preview song").assertIsDisplayed()
            compose.onNodeWithText("Preview artist").assertIsDisplayed()
            listOf("Brano precedente", "Brano successivo", "Play / pausa", "Ripetizione off").forEach {
                compose.onNodeWithContentDescription(it).assertIsDisplayed()
            }
            compose.onNodeWithTag("preview-music-seek").assertIsDisplayed()
            compose.onNodeWithTag("music-volume").assertDoesNotExist()
            compose.onNodeWithTag("music-volume-toggle").performClick()
            val volume = compose.onNodeWithTag("music-volume").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Volume is a vertical touch target", volume.height > volume.width * 2)
            val content = compose.onNodeWithTag("preview-music-panel").fetchSemanticsNode().boundsInRoot
            assertTrue(volume.right <= content.right && volume.bottom <= content.bottom)
            assertTrue(volume.left > content.center.x)
            compose.mainClock.advanceTimeBy(5300)
            compose.onNodeWithTag("music-volume").assertDoesNotExist()
            compose.onNodeWithTag("music-volume-toggle").assertIsDisplayed()
            compose.onNodeWithTag("music-preview-favorite").assertIsDisplayed()
            compose.onNodeWithTag("music-cover-effect:0").performClick()
            compose.onNodeWithTag("music-cover-effect:2").performClick()
            compose.onNodeWithTag("music-cover-effect:1").performClick()
                compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
                compose.onNodeWithTag("music-cover-effect:3").performClick()
            compose.onNodeWithTag("music-cover-effect:0").assertExists()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun artistSearchFolderAndPlaylistNavigationDoNotAutoplayAndBackRestoresContext() = verify(false)
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun musicLibraryStaysUsableWithLargeText() = verify(true)
    private fun verify(large: Boolean) {
        if (large) RuntimeEnvironment.setFontScale(1.4f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.music"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.music", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.music", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val folders = listOf(AlbumEntity("a", "folder-a", "content://ayngallery.music/tree/root/document/child", "A", "Local / A", null),
            AlbumEntity("b", "folder-b", "content://ayngallery.music/tree/root/document/destination", "B", "Local / B", null))
        val songs = listOf("one", "two", "three").mapIndexed { index, id -> MediaEntity(id, id, "content://ayngallery.music/tree/root/document/$id", if (index < 2) "a" else "b", "$id.wav", "audio/wav", "AUDIO", 100, 1,
            title = if (index == 0) "Track 10" else "Track 2", artist = if (index < 2) "Aster" else "Vega", musicAlbum = "Volume I") }
        songs.forEachIndexed { index, song -> provider.nodes[song.id] = OperationDocuments.Node(song.id,
            if (index < 2) "child" else "destination", song.name, song.mime,
            File(app.filesDir, "${song.id}.wav").apply { writeBytes(ByteArray(100)) }) }
        val playlist = runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "Local", "MUSIC"))
            app.catalog.dao.saveAlbums(folders); app.catalog.dao.saveAlbumSources(folders.map { AlbumSource(it.id, "source", 1) })
            app.catalog.dao.saveMedia(songs); app.catalog.dao.saveMembership(songs.map { MediaSource(it.id, "source", 1) })
            app.catalog.collection("My order", "PLAYLIST", listOf("one", "two"))
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val panel = controller.get(); val vm = panel.windowModel
        vm.go("MUSIC"); compose.waitForIdle()
        compose.onNodeWithTag("music-mode-menu").performClick()
        compose.onNodeWithTag("music-mode:ARTISTS").performClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Aster").fetchSemanticsNodes().isNotEmpty() }
        capture(panel, if (large) "artists-large-text" else "artists")
        compose.onNodeWithText("Aster").performClick()
        compose.waitUntil(5000) { vm.listing.value.route.musicGroup == "artist:aster" && vm.listing.value.items.size == 2 }
        assertEquals("artist:aster", vm.route.value.musicGroup); assertNull(app.music.state.value.id)
        vm.search("Track 2"); compose.waitForIdle()
        compose.waitUntil(5000) { vm.listing.value.items.size == 1 }
        assertEquals("two", vm.listing.value.items.single().id)
        compose.onNodeWithText("Track 2").assertIsDisplayed()
        compose.onNodeWithTag("music-play-group").assertDoesNotExist()
        vm.go("PLAYER"); compose.waitForIdle(); assertTrue(vm.back()); compose.waitForIdle()
        assertEquals("artist:aster", vm.route.value.musicGroup); assertEquals("Track 2", vm.query.value)
        vm.musicMode("FOLDERS"); compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Local / Destination").fetchSemanticsNodes().isNotEmpty() }
        capture(panel, if (large) "folders-large-text" else "folders")
        vm.musicMode("PLAYLISTS"); compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithText("My order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("My order").performClick()
        compose.waitUntil(5000) { vm.listing.value.route.musicGroup == "playlist:$playlist" && vm.listing.value.sort == "PLAYLIST" && vm.listing.value.items.size == 2 }
        assertEquals(listOf("one", "two"), vm.listing.value.items.map { it.id })
        assertEquals("PLAYLIST", vm.sort.value)
        compose.waitForIdle()
        compose.onNodeWithTag("music-play-group").assertDoesNotExist()
        capture(panel, if (large) "playlist-large-text" else "playlist")
        controller.recreate(); compose.waitForIdle()
        assertEquals("playlist:$playlist", controller.get().windowModel.route.value.musicGroup)
        assertNull(app.music.state.value.id)
        controller.pause().stop().destroy()
    }
    private fun capture(panel: PanelActivity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = panel.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/music-library-$name.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
}
