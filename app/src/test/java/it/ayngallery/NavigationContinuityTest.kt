package it.ayngallery

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
class NavigationContinuityTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { app.onTerminate() }
    @Test fun restoringTheTvPanelAfterFavoritesDoesNotFallBackToPhotos() {
        val first = GalleryWindowModel(app, SavedStateHandle())
        first.go("FAVORITE")
        first.go("TV")
        first.restorePanelRole()
        assertEquals("TV", first.route.value.tab)
        assertFalse(first.route.value.forceLibrary)
        val second = GalleryWindowModel(app, SavedStateHandle())
        second.takeLibraryFrom(first)
        second.restorePanelRole()
        assertEquals("TV", second.route.value.tab)
    }
    @Test fun videoFolderReturnsAfterOtherTabsAndPanelRoleTransferAndWakesDimNavigation() {
        val first = GalleryWindowModel(app, SavedStateHandle())
        first.go("VIDEO", album = "episodes")
        first.go("PHOTO")
        app.video.dimNavigation = true
        first.go("VIDEO")
        assertEquals("episodes", first.route.value.album)
        assertFalse(app.video.dimNavigation)
        first.go("ALBUM")
        val other = GalleryWindowModel(app, SavedStateHandle())
        other.takeLibraryFrom(first)
        other.go("VIDEO")
        assertEquals("episodes", other.route.value.album)
    }
    @Test fun previewCategoriesMatchTheirLibraryFilters() {
        assertTrue(matchesPreviewCategory("PHOTO", "IMAGE"))
        assertTrue(matchesPreviewCategory("PHOTO", "GIF"))
        assertTrue(matchesPreviewCategory("VIDEO", "VIDEO"))
        assertFalse(matchesPreviewCategory("VIDEO", "GIF"))
        assertFalse(matchesPreviewCategory("PHOTO", "VIDEO"))
    }
    @Test fun visualPreviewKeepsTheCurrentMediaBeforeUsingCategoryFallback() {
        val photo = MediaEntity("photo", "key:photo", "content://test/photo", "album", "Photo.png", "image/png", "IMAGE", 100, 1)
        val video = MediaEntity("video", "key:video", "content://test/video", "album", "Video.mp4", "video/mp4", "VIDEO", 100, 2)
        val source = SourceEntity("root", "content://test/root", "Root", "VISUAL")
        val data = CatalogSnapshot(sources = listOf(source), media = listOf(photo, video), membership = listOf(
            MediaSource(photo.id, source.id, 1), MediaSource(video.id, source.id, 2)))
        assertEquals(photo, visualPreviewSelection(data, photo.id, video))
        assertEquals(video, visualPreviewSelection(data, null, video))
        assertNull(visualPreviewSelection(data, null))
    }
    @Test fun categoryFallbackOrderPrefersAlbumsThenPhotosThenVideoThenMusic() {
        assertEquals("ALBUM", preferredGalleryTab(emptySet()))
        assertEquals("PHOTO", preferredGalleryTab(setOf("ALBUM")))
        assertEquals("VIDEO", preferredGalleryTab(setOf("ALBUM", "PHOTO")))
        assertEquals("MUSIC", preferredGalleryTab(setOf("ALBUM", "PHOTO", "VIDEO")))
        assertEquals("FAVORITE", preferredGalleryTab(setOf("ALBUM", "PHOTO", "VIDEO", "MUSIC")))
    }
    @Test fun movingTheLibraryBetweenPanelsDoesNotClearTheSelectedAlbumMedia() = runBlocking<Unit> {
        val media = MediaEntity("later", "key:later", "content://test/later", "album", "Later.png", "image/png", "IMAGE", 100, 1)
        app.catalog.dao.saveSource(SourceEntity("root", "content://test/tree/root", "Root", "VISUAL"))
        app.catalog.dao.saveAlbums(listOf(AlbumEntity("album", "key:album", "content://test/album", "Album", "Album", null)))
        app.catalog.dao.saveMembership(listOf(MediaSource(media.id, "root", 1)))
        app.catalog.dao.saveMedia(listOf(media))
        withTimeout(5000) { app.catalog.snapshot.first { it.byId.containsKey(media.id) } }
        val first = GalleryWindowModel(app, SavedStateHandle())
        val second = GalleryWindowModel(app, SavedStateHandle())
        first.go("ALBUM", album = "album")
        second.go("ALBUM", album = "other")
        app.session.selectMedia(media.id)
        second.takeLibraryFrom(first)
        second.syncRole(false); second.syncRole(true)
        assertEquals("album", second.route.value.album)
        assertEquals(media.id, app.session.selectedVisualMediaId)
        assertTrue(app.session.visualSelectedThisSession)
        first.takeLibraryFrom(second)
        assertEquals(media.id, app.session.selectedMediaId)
        assertTrue(app.session.visualSelectedThisSession)
    }
    @Test fun photoAndVideoSelectionsAreRememberedIndependentlyOfMusic() = runBlocking<Unit> {
        val media = listOf(MediaEntity("photo", "key:photo", "content://test/photo", "album", "Photo.png", "image/png", "IMAGE", 100, 1),
            MediaEntity("video", "key:video", "content://test/video", "album", "Video.mp4", "video/mp4", "VIDEO", 100, 1),
            MediaEntity("song", "key:song", "content://test/song", "album", "Song.mp3", "audio/mpeg", "AUDIO", 100, 1))
        app.catalog.dao.saveMedia(media)
        withTimeout(5000) { app.catalog.snapshot.first { it.byId.size == 3 } }
        media.forEach { app.session.selectMedia(it.id) }
        assertEquals("photo", app.session.lastCategoryMedia("PHOTO"))
        assertEquals("video", app.session.lastCategoryMedia("VIDEO"))
    }
    @Test fun galleryBackgroundRoundTripsAndRejectsInvalidOpacityOrColor() = runBlocking<Unit> {
        val value = GalleryPreferences(backgroundMode = "PHOTO", backgroundEnd = "#123456", backgroundStrength = 65, backgroundMedia = "photo", backgroundOpacity = 40)
        app.preferences.save(value)
        assertEquals(value, PreferenceStore(app).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        assertThrows(IllegalArgumentException::class.java) { value.copy(backgroundOpacity = 101).validate() }
        assertThrows(IllegalArgumentException::class.java) { value.copy(backgroundEnd = "not-a-color").validate() }
    }
}
