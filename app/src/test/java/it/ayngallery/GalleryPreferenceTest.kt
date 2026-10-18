package it.ayngallery

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class GalleryPreferenceTest {
    @Test fun albumViewsPersistIndependentlyFromOtherAlbumsAndVideoFolders() {
        val context = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(context)
        val defaultGrid = store.mediaGrid("ALBUM").value
        val defaultColumns = store.mediaColumns("ALBUM").value
        store.galleryView(false, "ALBUM", grid = !defaultGrid, columns = 8, albumId = "first")
        assertEquals(defaultGrid, store.mediaGrid("ALBUM", "second").value)
        assertEquals(defaultColumns, store.mediaColumns("ALBUM", "second").value)
        store.galleryView(false, "VIDEO", grid = false, columns = 2, albumId = "first")
        val restored = PreferenceStore(context)
        assertEquals(!defaultGrid, restored.mediaGrid("ALBUM", "first").value)
        assertEquals(8, restored.mediaColumns("ALBUM", "first").value)
        assertEquals(2, restored.mediaColumns("VIDEO", "first").value)
        assertEquals(defaultColumns, restored.mediaColumns("ALBUM").value)
    }

    @Test fun videosStartInFoldersAndPreserveAnExplicitViewChoice() {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        assertTrue(store.videoFolderView.value)
        store.videoFolderView(false)
        assertFalse(PreferenceStore(app).videoFolderView.value)
    }

    @Test fun everyCategoryStartsWithSwapVisibleAndFavoritesHasIndependentTools() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(context)
        assertEquals(NavbarOrder.DEFAULT.toSet(), InterfaceTools.sections.toSet())
        InterfaceTools.sections.forEach { assertTrue(InterfaceTools.shown(store.state.value, it, "SWAP")) }
        store.save(store.state.value.copy(hiddenTools = setOf("FAVORITE:SWAP")))
        val restored = PreferenceStore(context).state.value
        assertFalse(InterfaceTools.shown(restored, "FAVORITE", "SWAP"))
        assertTrue(InterfaceTools.shown(restored, "PHOTO", "SWAP"))
    }

    @Test fun gifLimitsPersistAndTransferWithoutAllowingUnboundedNumbers() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        assertEquals(4, store.state.value.gifLimit)
        for (limit in listOf(4, 6, 12, 20, 24, 35, 40)) {
            store.save(store.state.value.copy(gifLimit = limit))
            val restored = PreferenceStore(RuntimeEnvironment.getApplication()).state.value
            assertEquals(limit, restored.gifLimit)
            assertEquals(restored, PreferencesTransfer.decode(PreferencesTransfer.encode(restored)))
        }
        assertTrue(runCatching { store.save(store.state.value.copy(gifLimit = 41)) }.isFailure)
        assertEquals(40, store.state.value.gifLimit)
    }

    @Test fun navigatorAndFrameDefaultsPreserveExplicitOptOutAndWhiteIconIsTheFallback() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        assertTrue(store.state.value.photoNavigator)
        assertTrue(store.state.value.gifFrameCount)
        assertEquals("LIGHT", LauncherIcon.current(app))
        store.save(store.state.value.copy(photoNavigator = false, gifFrameCount = false, albumShadows = "SIDES", albumStudio = true))
        val reopened = PreferenceStore(app).state.value
        assertFalse(reopened.photoNavigator)
        assertFalse(reopened.gifFrameCount)
        assertEquals("SIDES", reopened.albumShadows)
        assertTrue(reopened.albumStudio)
        assertEquals(reopened, PreferencesTransfer.decode(PreferencesTransfer.encode(reopened)))
    }
    @Test fun musicCoverEffectSurvivesReopeningAndRejectsUnknownModes() {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        for (effect in 0..3) {
            store.musicCoverEffect(effect)
            assertEquals(effect, PreferenceStore(app).musicCoverEffect.value)
        }
        assertThrows(IllegalArgumentException::class.java) { store.musicCoverEffect(4) }
    }
    @Test fun favoritesViewAndVideoListDensityPersistIndependently() {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        store.galleryView(false, "FAVORITE", grid = false, columns = 5)
        store.videoSmallList(true)
        store.favoriteSmallList(true)
        val reopened = PreferenceStore(app)
        assertFalse(reopened.favoriteGrid.value)
        assertEquals(5, reopened.favoriteColumns.value)
        assertTrue(reopened.photoGrid.value)
        assertTrue(reopened.videoSmallList.value)
        assertTrue(reopened.favoriteSmallList.value)
        assertTrue(reopened.settingsGrid.value)
    }
    @Test fun legacyModeMigratesWithoutLosingOtherPreferences() {
        val app = RuntimeEnvironment.getApplication()
        val file = app.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE)
        file.edit().putBoolean("dark", false).putBoolean("compact", true).putString("gif_mode", "OFF").commit()
        val state = PreferenceStore(app).state.value
        assertEquals(GalleryThemes.WHITE, state.themeId)
        assertTrue(state.compact); assertEquals("OFF", state.gifMode)
        assertFalse(file.contains("theme")) // Reading or displaying a preview must not save anything.
    }
    @Test fun onlyCommittedThemesSurviveReopeningAndInvalidIdsCannotOverwrite() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        val draft = store.state.value.copy(themeId = "stocking_pink", compact = true)
        assertEquals("ayn_rainbow", PreferenceStore(app).state.value.themeId)
        store.save(draft)
        assertEquals(draft, PreferenceStore(app).state.value)
        try { store.save(draft.copy(themeId = "unknown")); fail("Unknown theme accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(draft, store.state.value)
        assertEquals(draft, PreferenceStore(app).state.value)
    }
    @Test fun unknownPersistedThemeFallsBackToLegacyMode() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE).edit()
            .putString("theme", "future_palette").putBoolean("dark", false).commit()
        assertEquals(GalleryThemes.WHITE, PreferenceStore(app).state.value.themeId)
    }
    @Test fun previousBehaviorIsOptInAndRequiresSave() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        assertFalse(store.state.value.alwaysPrevious)
        val draft = store.state.value.copy(alwaysPrevious = true)
        assertFalse(PreferenceStore(app).state.value.alwaysPrevious)
        store.save(draft)
        assertTrue(PreferenceStore(app).state.value.alwaysPrevious)
        store.save(draft.copy(alwaysPrevious = false))
        assertFalse(PreferenceStore(app).state.value.alwaysPrevious)
    }
}
