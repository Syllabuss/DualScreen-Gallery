package it.ayngallery

import android.app.Application
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class CustomThemeTest {
    @Test fun selectedPaletteSeedsCustomAndAllColorsPersistAndTransfer() = runBlocking {
        val base = GalleryThemes.get("stocking_pink")
        val colors = CustomTheme.from(base).copy(accent = "#63EDFF", border = "#345678")
        val value = GalleryPreferences(themeId = GalleryThemes.CUSTOM, customTheme = colors)
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        store.save(value)
        assertEquals(value, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val theme = GalleryThemes.get(value)
        assertEquals(base.background, theme.background)
        assertEquals(Color(0xFF63EDFF), theme.colors.primary)
        assertEquals(Color(0xFF345678), theme.colors.outlineVariant)
        val old = JSONObject(PreferencesTransfer.encode(GalleryPreferences())).put("version", 5).apply { listOf("tvEnabled", "tvInNavbar", "tvInMediaFavorites", "showUpperStatusBar", "confirmVideoReplacement", "showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio", "gifFrameCounter", "gifFrameSelector", "ftpEnabled", "previewPinEnabled", "comparisonEnabled", "animatedAlbumCovers", "videoKeepPlaying", "actionMenusBelow", "albumCoverLimit", "allAnimatedAlbumCovers", "previewNearest", "imageBackdrop", "imageBackdropColor", "imageBackdropEnd", "imageBackdropOpacity", "musicColorMode", "musicBackgroundColor", "musicBarsColor").forEach { getJSONObject("preferences").remove(it) } }
        old.getJSONObject("preferences").remove("customTheme")
        old.getJSONObject("preferences").remove("musicDuringSleep")
        old.getJSONObject("preferences").remove("videoDuringSleep")
        old.getJSONObject("preferences").remove("hiddenTools")
        old.getJSONObject("preferences").remove("timelineModes"); old.getJSONObject("preferences").remove("hideGifBadge"); old.getJSONObject("preferences").remove("uiSounds")
        assertNull(PreferencesTransfer.decode(old.toString()).customTheme)
    }
    @Test fun invalidCustomColorsCannotOverwriteStoredPreferences() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        val previous = store.state.value
        val valid = CustomTheme.from(GalleryThemes.get(GalleryThemes.BLACK))
        listOf("#FFF", "#00112233", "#GGGGGG", "red").forEach { color ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.save(previous.copy(themeId = GalleryThemes.CUSTOM, customTheme = valid.copy(background = color))) }
            }
            assertEquals(previous, store.state.value)
        }
        assertThrows(IllegalArgumentException::class.java) { GalleryPreferences(themeId = GalleryThemes.CUSTOM).validate() }
        Unit
    }
}
