package it.ayngallery

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class PreferencesTransferTest {
    @Test fun directVideoPlaybackIsOptInAndSurvivesPersistenceAndBackup() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        assertFalse(store.state.value.videoSkipPreview)
        val chosen = store.state.value.copy(videoSkipPreview = true)
        store.save(chosen)
        assertTrue(PreferenceStore(RuntimeEnvironment.getApplication()).state.value.videoSkipPreview)
        assertEquals(chosen, PreferencesTransfer.decode(PreferencesTransfer.encode(chosen)))
    }

    @Test fun singleSectionNavbarChoiceSurvivesBackupAndDoesNotHideMultipleSections() {
        val chosen = GalleryPreferences(showEmptyFolders = true, horizontalAlbums = true, albumCarousel = true, disabledCategories = NavbarOrder.DEFAULT.filterNot { it == "PHOTO" }.toSet(),
            hiddenTools = setOf(InterfaceTools.SINGLE_NAVBAR))
        val restored = PreferencesTransfer.decode(PreferencesTransfer.encode(chosen))
        assertEquals(chosen, restored)
        assertFalse(navbarVisible(restored))
        assertTrue(navbarVisible(restored.copy(disabledCategories = restored.disabledCategories - "MUSIC")))
        assertTrue(navbarVisible(restored.copy(hiddenTools = emptySet())))
    }

    @Test fun replacementConfirmationIsEnabledByDefaultPersistentAndPortable() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        assertTrue(store.state.value.confirmVideoReplacement)
        val chosen = store.state.value.copy(confirmVideoReplacement = false, showUpperStatusBar = true)
        store.save(chosen)
        assertFalse(PreferenceStore(RuntimeEnvironment.getApplication()).state.value.confirmVideoReplacement)
        assertEquals(chosen, PreferencesTransfer.decode(PreferencesTransfer.encode(chosen)))
        val old = JSONObject(PreferencesTransfer.encode(chosen)).withoutTv().put("version", 29)
        old.getJSONObject("preferences").remove("confirmVideoReplacement")
        old.getJSONObject("preferences").remove("showUpperStatusBar")
        assertTrue(PreferencesTransfer.decode(old.toString()).confirmVideoReplacement)
        assertFalse(PreferencesTransfer.decode(old.toString()).showUpperStatusBar)
    }
    @Test fun nearestPreviewIsOptInPersistentAndPortableWithLegacyDefault() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        assertFalse(store.state.value.previewNearest)
        assertEquals(androidx.compose.ui.graphics.FilterQuality.Low, previewFilter(store.state.value))
        val chosen = store.state.value.copy(previewNearest = true)
        store.save(chosen)
        assertTrue(PreferenceStore(RuntimeEnvironment.getApplication()).state.value.previewNearest)
        assertEquals(androidx.compose.ui.graphics.FilterQuality.None, previewFilter(chosen))
        assertEquals(chosen, PreferencesTransfer.decode(PreferencesTransfer.encode(chosen)))
        val legacy = JSONObject(PreferencesTransfer.encode(chosen)).withoutTv().put("version", 26).apply {
            listOf("showUpperStatusBar", "confirmVideoReplacement", "musicColorMode", "musicBackgroundColor", "musicBarsColor", "imageBackdrop", "imageBackdropColor", "imageBackdropEnd", "imageBackdropOpacity").forEach { getJSONObject("preferences").remove(it) }
        }
        legacy.getJSONObject("preferences").remove("previewNearest")
        assertFalse(PreferencesTransfer.decode(legacy.toString()).previewNearest)
    }
    @Test fun previewFeaturesPersistAndOldSettingsRemainOptIn() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        val value = store.state.value.copy(previewPinEnabled = true, comparisonEnabled = true, animatedAlbumCovers = false, videoKeepPlaying = true)
        store.save(value)
        assertEquals(value, PreferenceStore(app).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val old = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(23)
        old.getJSONObject("preferences").apply { listOf("previewPinEnabled", "comparisonEnabled", "animatedAlbumCovers", "videoKeepPlaying").forEach { remove(it) } }
        val restored = PreferencesTransfer.decode(old.toString())
        assertFalse(restored.previewPinEnabled); assertFalse(restored.comparisonEnabled)
        assertTrue(restored.animatedAlbumCovers); assertTrue(restored.videoKeepPlaying)
    }
    @Test fun ftpIsOptInAndPortableWithoutSharingRootsOrCredentials() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        assertFalse(store.state.value.ftpEnabled)
        val value = store.state.value.copy(ftpEnabled = true)
        store.save(value)
        assertTrue(PreferenceStore(RuntimeEnvironment.getApplication()).state.value.ftpEnabled)
        val encoded = PreferencesTransfer.encode(value)
        assertEquals(value, PreferencesTransfer.decode(encoded))
        val old = JSONObject(encoded).legacyVersion(22)
        old.getJSONObject("preferences").apply { remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") }
        assertFalse(PreferencesTransfer.decode(old.toString()).ftpEnabled)
        assertFalse(encoded.contains("password")); assertFalse(encoded.contains("ftp_share"))
    }
    @Test fun gifFrameControlsPersistAndOldBackupsDefaultToTheCounterWithoutTheStrip() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        val value = store.state.value.copy(gifFrameCounter = false, gifFrameSelector = true)
        store.save(value)
        assertEquals(value, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val old = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(21)
        old.getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") }
        val restored = PreferencesTransfer.decode(old.toString())
        assertTrue(restored.gifFrameCounter); assertFalse(restored.gifFrameSelector)
    }

    @Test fun sharedVideoAudioPersistsAndOlderBackupsKeepItDisabled() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        assertFalse(store.state.value.videoMixAudio)
        val enabled = store.state.value.copy(videoMixAudio = true)
        store.save(enabled)
        assertTrue(PreferenceStore(app).state.value.videoMixAudio)
        assertEquals(enabled, PreferencesTransfer.decode(PreferencesTransfer.encode(enabled)))
        val old = JSONObject(PreferencesTransfer.encode(enabled)).legacyVersion(20)
        old.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }
        old.getJSONObject("preferences").remove("videoMixAudio")
        assertEquals(enabled.copy(videoMixAudio = false, musicColorMode = "THEME"), PreferencesTransfer.decode(old.toString()))
    }
    @Test fun autoHideIsOptInPortableAndRetiredMusicAlbumFlagCannotBeRestored() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        assertFalse(store.state.value.imageOverlayAutoHide)
        val value = store.state.value.copy(imageOverlayAutoHide = true)
        store.save(value)
        assertEquals(value, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val legacy = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(19)
        legacy.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }
        legacy.getJSONObject("preferences").apply { remove("imageOverlayAutoHide"); remove("videoMixAudio"); put("showMusicAlbums", true) }
        val restored = PreferencesTransfer.decode(legacy.toString())
        assertFalse(restored.imageOverlayAutoHide)
        assertFalse(restored.showMusicAlbums)
    }
    @Test fun coverChoiceAndIdleTimerPersistAndTransferWithSafeLegacyDefaults() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        assertEquals(0, store.state.value.videoDimSeconds)
        for (effect in MusicCoverEffects.ORDER) {
            val value = GalleryPreferences(musicCoverEffect = effect, videoDimSeconds = 30)
            store.save(value)
            assertEquals(effect, store.musicCoverEffect.value)
            assertEquals(value, PreferenceStore(app).state.value)
            assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        }
        val old = JSONObject(PreferencesTransfer.encode(store.state.value)).legacyVersion(18)
        old.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }
        old.getJSONObject("preferences").apply { remove("musicCoverEffect"); remove("videoDimSeconds"); remove("imageOverlayAutoHide"); remove("videoMixAudio") }
        val restored = PreferencesTransfer.decode(old.toString())
        assertEquals(0, restored.musicCoverEffect); assertEquals(0, restored.videoDimSeconds)
        assertThrows(IllegalArgumentException::class.java) { store.state.value.copy(videoDimSeconds = 7).validate() }
        Unit
    }
    @Test fun previewSettingsPersistAndOldExportsKeepDefaults() = runBlocking {
        val prefs = GalleryPreferences(imagePreviewName = false, gridPreviewMotion = false, controllerSorting = true)
        val store = PreferenceStore(RuntimeEnvironment.getApplication()); store.save(prefs)
        assertEquals(prefs, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        assertEquals(prefs, PreferencesTransfer.decode(PreferencesTransfer.encode(prefs)))
        val older = JSONObject(PreferencesTransfer.encode(prefs)).legacyVersion(16)
        older.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }
        older.getJSONObject("preferences").apply { remove("imagePreviewName"); remove("gridPreviewMotion"); remove("controllerSorting"); remove("musicCoverEffect"); remove("videoDimSeconds"); remove("imageOverlayAutoHide"); remove("videoMixAudio") }
        val decoded = PreferencesTransfer.decode(older.toString())
        assertTrue(decoded.imagePreviewName); assertTrue(decoded.gridPreviewMotion); assertFalse(decoded.controllerSorting)
    }

    @Test fun defaultSectionIsSavedAndOlderExportsStartOnAlbums() = runBlocking {
        val value = GalleryPreferences(defaultSection = "VIDEO")
        PreferenceStore(RuntimeEnvironment.getApplication()).save(value)
        assertEquals("VIDEO", PreferenceStore(RuntimeEnvironment.getApplication()).state.value.defaultSection)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val legacy = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(15)
        legacy.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }
        legacy.getJSONObject("preferences").apply { listOf("defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { remove(it) } }
        assertEquals("ALBUM", PreferencesTransfer.decode(legacy.toString()).defaultSection)
    }

    @Test fun backgroundPositionAndZoomPersistTransferAndDefaultForOlderExports() = runBlocking {
        val value = GalleryPreferences(backgroundX = -42, backgroundY = 68, backgroundZoom = 175)
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        store.save(value)
        assertEquals(value, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val older = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(14)
        older.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }
        older.getJSONObject("preferences").apply { listOf("backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { remove(it) } }
        val restored = PreferencesTransfer.decode(older.toString())
        assertEquals(0, restored.backgroundX); assertEquals(0, restored.backgroundY); assertEquals(100, restored.backgroundZoom)
        assertThrows(IllegalArgumentException::class.java) { value.copy(backgroundZoom = 301).validate() }
        Unit
    }

    @Test fun videoStickChoicePersistsAndOlderBackupsKeepControlsEnabled() = runBlocking {
        val value = GalleryPreferences(videoStickControls = false)
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        store.save(value)
        assertFalse(PreferenceStore(RuntimeEnvironment.getApplication()).state.value.videoStickControls)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val old = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(13)
        old.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }
        old.getJSONObject("preferences").apply { listOf("videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { remove(it) } }
        assertTrue(PreferencesTransfer.decode(old.toString()).videoStickControls)
    }
    @Test fun categoryAndSoundChoicesPersistAndTransferWithAtLeastOneCategory() = runBlocking {
        val value = GalleryPreferences(disabledCategories = setOf("PHOTO", "VIDEO"), disabledSounds = setOf("favorite", "save"), persistSelectedMedia = true, directDeleteEnabled = true, nestedAlbums = true)
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        store.save(value)
        store.settingsGrid(true)
        val reopened = PreferenceStore(RuntimeEnvironment.getApplication())
        assertEquals(value, reopened.state.value)
        assertTrue(reopened.settingsGrid.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        assertThrows(IllegalArgumentException::class.java) { value.copy(disabledCategories = NavbarOrder.DEFAULT.toSet()).validate() }
        assertThrows(IllegalArgumentException::class.java) { value.copy(disabledSounds = setOf("unknown")).validate() }
        val old = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(8)
        old.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }
        listOf("showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { old.getJSONObject("preferences").remove(it) }
        assertEquals(GalleryPreferences(musicColorMode = "THEME"), PreferencesTransfer.decode(old.toString()))
    }
    @Test fun soundAndGifChoicesPersistAndRoundTripIncludingUnlimitedVisibleAnimation() = runBlocking {
        val value = GalleryPreferences(gifMode = "VISIBLE", gifLimit = 0, hideGifBadge = true, uiSounds = false)
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        store.save(value)
        assertEquals(value, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val old = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(7)
        old.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }.apply { listOf("showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { getJSONObject("preferences").remove(it) } }
        old.getJSONObject("preferences").remove("hideGifBadge"); old.getJSONObject("preferences").remove("uiSounds")
        assertEquals(value.copy(hideGifBadge = false, uiSounds = true, musicColorMode = "THEME"), PreferencesTransfer.decode(old.toString()))
    }
    @Test fun visibleSwapDefaultsKeepExplicitHiddenToolsAcrossRestart() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val raw = context.getSharedPreferences("ui_preferences_v1", android.content.Context.MODE_PRIVATE)
        raw.edit().clear().putStringSet("hidden_tools", setOf("PHOTO:ZOOM")).commit()
        val first = PreferenceStore(context)
        assertEquals(setOf("PHOTO:ZOOM") + InterfaceTools.defaultHidden, first.state.value.hiddenTools)
        first.update { it.copy(hiddenTools = it.hiddenTools + "VIDEO:SWAP") }
        val reopened = PreferenceStore(context).state.value
        assertTrue(InterfaceTools.shown(reopened, "PHOTO", "SWAP"))
        assertFalse(InterfaceTools.shown(reopened, "VIDEO", "SWAP"))
        assertFalse(InterfaceTools.shown(reopened, "PHOTO", "ZOOM"))
    }
    @Test fun hiddenToolsAndTimelinePersistPerSectionAndRoundTripWithoutPinData() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        val value = GalleryPreferences(hiddenTools = setOf("PHOTO:ZOOM", "ALBUM:SWAP", "MUSIC:VIEW"), timelineModes = mapOf("PHOTO" to "DAY", "VIDEO" to "MONTH"))
        store.save(value)
        assertEquals(value, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        assertFalse(InterfaceTools.shown(value, "PHOTO", "ZOOM"))
        assertTrue(InterfaceTools.shown(value, "VIDEO", "ZOOM"))
        val old = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(6)
        old.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }.apply { listOf("showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { getJSONObject("preferences").remove(it) } }
        old.getJSONObject("preferences").remove("hiddenTools")
        old.getJSONObject("preferences").remove("timelineModes"); old.getJSONObject("preferences").remove("hideGifBadge"); old.getJSONObject("preferences").remove("uiSounds")
        assertEquals(GalleryPreferences(musicColorMode = "THEME"), PreferencesTransfer.decode(old.toString()))
        val exported = JSONObject(PreferencesTransfer.encode(value)).getJSONObject("preferences")
        assertFalse(exported.has("hash"))
        assertFalse(exported.has("salt"))
        assertFalse(exported.has("protectedAlbums"))
        assertThrows(IllegalArgumentException::class.java) { value.copy(hiddenTools = setOf("unknown:TOOL")).validate() }
        Unit
    }
    @Test fun allPreferencesAndUnicodeProfilesRoundTripWithoutDeviceOrMediaState() {
        val profile = PadProfile().assign(PadContext.LIBRARY, PadAction.PLAY_PAUSE, PadBinding(setOf(PadButton.R1), "Musica ♫"), replace = true)
        GalleryThemes.all.forEach { theme ->
            val value = GalleryPreferences(theme.id, true, "VISIBLE", 4, true, true, 120, ArtworkOrders.DEFAULT, true,
                PadSettings(mapOf("default" to PadProfile(), "qa-controller" to profile), .7f, .2f, 500, 120))
            val raw = PreferencesTransfer.encode(value)
            assertEquals(value, PreferencesTransfer.decode(raw))
            assertFalse(raw.contains("ceded")); assertFalse(raw.contains("\"uri\"")); assertFalse(raw.contains("trash"))
        }
    }
    @Test fun viewerPreferencesAreOptInAndPortableWithLegacyDefaults() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        val enabled = GalleryPreferences(showMediaNames = true, viewerArrows = true, viewerZoom = true, photoNavigator = true, gifFrameCount = true, navbarLabels = true)
        store.save(enabled)
        assertEquals(enabled, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        assertEquals(enabled, PreferencesTransfer.decode(PreferencesTransfer.encode(enabled)))
        val v3 = JSONObject(PreferencesTransfer.encode(enabled)).legacyVersion(3)
        v3.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }.apply { listOf("showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { getJSONObject("preferences").remove(it) } }.apply { getJSONObject("preferences").remove("navbarLabels"); getJSONObject("preferences").remove("navbarOrder"); getJSONObject("preferences").remove("customTheme"); getJSONObject("preferences").remove("hiddenTools"); getJSONObject("preferences").remove("timelineModes"); getJSONObject("preferences").remove("hideGifBadge"); getJSONObject("preferences").remove("uiSounds") }
        assertEquals(enabled.copy(navbarLabels = false, musicColorMode = "THEME"), PreferencesTransfer.decode(v3.toString()))
        val legacy = JSONObject(PreferencesTransfer.encode(enabled)).legacyVersion(2)
        legacy.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }.apply { listOf("showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { getJSONObject("preferences").remove(it) } }.apply {
            listOf("showMediaNames", "viewerArrows", "viewerZoom", "photoNavigator", "gifFrameCount", "navbarLabels", "navbarOrder", "customTheme", "hiddenTools", "timelineModes", "hideGifBadge", "uiSounds").forEach { getJSONObject("preferences").remove(it) }
        }
        assertEquals(GalleryPreferences(musicColorMode = "THEME"), PreferencesTransfer.decode(legacy.toString()))
    }
    @Test fun navbarOrderRoundTripsAndOldExportsUseDefaultOrder() {
        val value = GalleryPreferences(navbarOrder = NavbarOrder.DEFAULT.reversed())
        assertEquals(value, PreferencesTransfer.decode(PreferencesTransfer.encode(value)))
        val old = JSONObject(PreferencesTransfer.encode(value)).legacyVersion(4)
        old.apply { getJSONObject("preferences").apply { remove("gifFrameCounter"); remove("gifFrameSelector"); remove("ftpEnabled"); remove("previewPinEnabled"); remove("comparisonEnabled"); remove("animatedAlbumCovers"); remove("videoKeepPlaying") } }.apply { listOf("showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio").forEach { getJSONObject("preferences").remove(it) } }
        old.getJSONObject("preferences").remove("navbarOrder")
        old.getJSONObject("preferences").remove("customTheme"); old.getJSONObject("preferences").remove("hiddenTools"); old.getJSONObject("preferences").remove("timelineModes"); old.getJSONObject("preferences").remove("hideGifBadge"); old.getJSONObject("preferences").remove("uiSounds")
        assertEquals(NavbarOrder.DEFAULT, PreferencesTransfer.decode(old.toString()).navbarOrder)
        val bad = JSONObject(PreferencesTransfer.encode(value))
        bad.getJSONObject("preferences").put("navbarOrder", org.json.JSONArray(listOf("PHOTO", "PHOTO", "VIDEO", "MUSIC", "FAVORITE")))
        assertThrows(IllegalArgumentException::class.java) { PreferencesTransfer.decode(bad.toString()) }
    }
    @Test fun invalidFilesCannotOverwritePreferences() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        val previous = GalleryPreferences(themeId = "rose_milk")
        store.save(previous)
        val valid = PreferencesTransfer.encode(previous)
        val bad = listOf("not json", valid + " {}", valid.replace("ayngallery-settings", "other-app"),
            JSONObject(valid).put("version", 999).toString(), JSONObject(valid).put("display", "new screen").toString()) +
            listOf("theme" to "unknown", "compact" to "true", "gifLimit" to 2.5, "slideshowSeconds" to 0,
                "artworkOrder" to "unknown").map { (key, value) -> JSONObject(valid).apply { getJSONObject("preferences").put(key, value) }.toString() } +
            JSONObject(valid).apply { getJSONObject("preferences").getJSONObject("pad").getJSONObject("profiles")
                .getJSONObject("default").getJSONObject("DIALOG").remove("BACK") }.toString() +
            listOf("delay" to 360.5, "repeat" to "100", "press" to "0.6", "version" to "1").map { (key, value) ->
                JSONObject(valid).apply { getJSONObject("preferences").getJSONObject("pad").put(key, value) }.toString()
            }
        bad.forEach { input ->
            try { store.importSettings(PreferencesTransfer.decode(input), previous, true); fail("Invalid file accepted") }
            catch (_: Exception) { }
            assertEquals(previous, store.state.value); assertEquals(previous, PreferenceStore(app).state.value)
        }
    }
    @Test fun importRequiresUnchangedPreviewAndKeepsCommonControlsUnlessChosen() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        val base = store.state.value
        val alternate = PadProfile().assign(PadContext.DIALOG, PadAction.CONFIRM, PadBinding(setOf(PadButton.R1)))
        val imported = base.copy(themeId = "sky_milk", pad = PadSettings(mapOf("default" to alternate, "foreign-pad" to alternate)))
        store.importSettings(imported, base, false)
        assertEquals("sky_milk", store.state.value.themeId)
        assertEquals(base.pad.profile(null), store.state.value.pad.profile(null))
        assertEquals(alternate, store.state.value.pad.profile("foreign-pad"))
        val expected = store.state.value
        store.update { it.copy(themeId = "stocking_pink") }
        try { store.importSettings(imported, expected, true); fail("Stale import overwrote newer settings") }
        catch (_: IllegalStateException) { }
        assertEquals("stocking_pink", store.state.value.themeId)
        store.importSettings(imported, store.state.value, true)
        assertEquals(imported, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
    }
    @Test fun providerReadsAreBoundedStrictUtf8AndClosedEvenOnFailure() = runBlocking {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val inputs = listOf(PreferencesTransfer.encode(GalleryPreferences()).toByteArray(),
            ByteArray(PreferencesTransfer.MAX_BYTES + 1) { 'a'.code.toByte() }, byteArrayOf(0xc3.toByte(), 0x28))
        inputs.forEachIndexed { index, bytes ->
            var closed = false
            val uri = Uri.parse("content://transfer.test/$index")
            shadowOf(resolver).registerInputStream(uri, object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } })
            if (index == 0) assertEquals(GalleryPreferences(), PreferencesTransfer.decode(PreferencesTransfer.read(resolver, uri)))
            else try { PreferencesTransfer.read(resolver, uri); fail("Unsafe document accepted") } catch (_: Exception) { }
            assertTrue(closed)
        }
        try { PreferencesTransfer.read(resolver, Uri.parse("file:///not-allowed")); fail("Non-provider URL accepted") }
        catch (_: IllegalArgumentException) { }
    }
}

private fun JSONObject.legacyVersion(value: Int): JSONObject = withoutTv().apply { put("version", value); getJSONObject("preferences").apply { remove("showUpperStatusBar"); remove("confirmVideoReplacement"); remove("musicColorMode"); remove("musicBackgroundColor"); remove("musicBarsColor"); remove("previewNearest"); remove("imageBackdrop"); remove("imageBackdropColor"); remove("imageBackdropEnd"); remove("imageBackdropOpacity"); remove("actionMenusBelow"); remove("albumCoverLimit"); remove("allAnimatedAlbumCovers") } }

private fun JSONObject.withoutTv(): JSONObject = apply { getJSONObject("preferences").apply { remove("videoSkipPreview"); remove("albumCarousel"); remove("horizontalAlbums"); remove("showEmptyFolders"); remove("tvEnabled"); remove("tvInNavbar"); remove("tvInMediaFavorites"); remove("musicDuringSleep"); remove("videoDuringSleep") } }
