package it.ayngallery

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class TouchSettingsTest {
    @Test fun freshDefaultsPreserveExplicitLegacyViewChoices() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("ui_preferences_v1", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val fresh = PreferenceStore(app)
        assertEquals("ayn_rainbow", fresh.state.value.themeId)
        assertTrue(fresh.albumGrid.value)
        assertFalse(fresh.musicGrid.value)
        assertEquals(4, fresh.photoColumns.value)
        assertEquals(4, fresh.videoColumns.value)
        assertEquals(4, fresh.favoriteColumns.value)
        prefs.edit().putBoolean("dark", false).putBoolean("album_grid", false)
            .putBoolean("media_grid", true).putInt("media_columns", 5).commit()
        val saved = PreferenceStore(app)
        assertEquals(GalleryThemes.WHITE, saved.state.value.themeId)
        assertFalse(saved.albumGrid.value)
        assertTrue(saved.musicGrid.value)
        assertEquals(5, saved.photoColumns.value)
        assertEquals(5, saved.videoColumns.value)
    }

    @Test fun defaultGesturesUpgradeOnlyStockBindingsAndKeepCustomMappings() {
        val defaults = TouchSettings()
        defaults.threeFinger.values.forEach {
            assertEquals(TouchAction.NONE, it[TouchDirection.DOWN])
            assertEquals(TouchAction.DIM, it[TouchDirection.TAP])
            assertEquals(TouchAction.SWAP, it[TouchDirection.UP])
            assertEquals(TouchAction.TAB_PREVIOUS, it[TouchDirection.LEFT])
            assertEquals(TouchAction.TAB_NEXT, it[TouchDirection.RIGHT])
        }
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("ui_preferences_v1", 0)
        val legacy = defaults.copy(threeFinger = defaults.threeFinger.mapValues { (_, directions) -> directions.mapValues { (direction, _) -> if (direction in setOf(TouchDirection.DOWN, TouchDirection.TAP)) TouchAction.DIM else TouchAction.NONE } })
        prefs.edit().putString("touch_bindings", legacy.encode()).putBoolean("touch_defaults_v2", false).commit()
        assertEquals(defaults, PreferenceStore(app).state.value.touch)
        val custom = defaults.copy(threeFinger = defaults.threeFinger.mapValues { (_, directions) -> directions + (TouchDirection.DOWN to TouchAction.SWAP) })
        prefs.edit().putString("touch_bindings", custom.encode()).putBoolean("touch_defaults_v2", false).commit()
        assertEquals(custom, PreferenceStore(app).state.value.touch)
        val reversedStock = defaults.copy(threeFinger = defaults.threeFinger.mapValues { (_, directions) -> directions +
            (TouchDirection.LEFT to TouchAction.TAB_NEXT) + (TouchDirection.RIGHT to TouchAction.TAB_PREVIOUS) })
        prefs.edit().putString("touch_bindings", reversedStock.encode()).putBoolean("touch_directions_v3", false).commit()
        assertEquals(defaults, PreferenceStore(app).state.value.touch)
        prefs.edit().putString("touch_bindings", custom.encode()).putBoolean("touch_directions_v3", false).commit()
        assertEquals(custom, PreferenceStore(app).state.value.touch)
        val oldJson = JSONObject(legacy.encode())
        touchContexts.forEach { oldJson.getJSONObject("threeFinger").getJSONObject(it.name).remove("TAP") }
        assertEquals(TouchAction.DIM, TouchSettings.decode(oldJson.toString()).threeFinger.getValue(PadContext.LIBRARY)[TouchDirection.TAP])
    }
    @Test fun stationaryThreeFingerTapWaitsForReleaseAndNeverFollowsADrag() {
        val tap = ThreeFingerDrag(8f, 32f)
        assertTrue(tap.update(points(0f), 0).consume)
        assertNull(tap.update(points(0f), 80).direction)
        assertEquals(TouchDirection.TAP, tap.update(points(0f, pressed = false), 150).direction)
        assertNull(tap.update(points(0f, pressed = false), 180).direction)
        val moved = ThreeFingerDrag(8f, 32f)
        moved.update(points(0f), 0); moved.update(points(40f), 70)
        assertNull(moved.update(points(0f, pressed = false), 150).direction)
    }
    @Test fun touchPreferencesPersistAndExportWithVersionOneCompatibility() = runBlocking {
        val touch = TouchSettings(doubleTap = TouchAction.NONE, longPress = TouchAction.FAVORITE,
            zones = listOf(TouchZone("user-zone", PadContext.VIEWER, .2f, .3f, .25f, .2f, TouchAction.MEDIA_NEXT)))
        val value = GalleryPreferences(touch = touch)
        assertEquals(touch, TouchSettings.decode(touch.encode()))
        val app = RuntimeEnvironment.getApplication()
        PreferenceStore(app).save(value)
        assertEquals(value, PreferenceStore(app).state.value)
        val encoded = PreferencesTransfer.encode(value)
        assertEquals(value, PreferencesTransfer.decode(encoded))
        val v1 = JSONObject(encoded).put("version", 1).apply { getJSONObject("preferences").remove("showEmptyFolders") }.apply { listOf("musicDuringSleep", "videoDuringSleep", "tvEnabled", "tvInNavbar", "tvInMediaFavorites", "showUpperStatusBar", "confirmVideoReplacement", "showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio", "gifFrameCounter", "gifFrameSelector", "ftpEnabled", "previewPinEnabled", "comparisonEnabled", "animatedAlbumCovers", "videoKeepPlaying", "actionMenusBelow", "albumCoverLimit", "allAnimatedAlbumCovers", "previewNearest", "imageBackdrop", "imageBackdropColor", "imageBackdropEnd", "imageBackdropOpacity", "musicColorMode", "musicBackgroundColor", "musicBarsColor").forEach { getJSONObject("preferences").remove(it) } }.apply { getJSONObject("preferences").apply { remove("touch"); listOf("showMediaNames", "viewerArrows", "viewerZoom", "photoNavigator", "gifFrameCount", "navbarLabels", "navbarOrder", "customTheme", "hiddenTools", "timelineModes", "hideGifBadge", "uiSounds").forEach { remove(it) } } }.toString()
        assertEquals(value.copy(touch = TouchSettings()), PreferencesTransfer.decode(v1))
        val invalid = listOf(encoded + " {}", JSONObject(encoded).apply { getJSONObject("preferences").getJSONObject("touch").put("longPress", "DELETE") }.toString(),
            JSONObject(encoded).put("version", 1).apply { getJSONObject("preferences").remove("showEmptyFolders") }.apply { listOf("musicDuringSleep", "videoDuringSleep", "tvEnabled", "tvInNavbar", "tvInMediaFavorites", "showUpperStatusBar", "confirmVideoReplacement", "showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia", "directDeleteEnabled", "nestedAlbums", "backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity", "albumShadows", "albumStudio", "videoStickControls", "backgroundX", "backgroundY", "backgroundZoom", "defaultSection", "imagePreviewName", "gridPreviewMotion", "controllerSorting", "musicCoverEffect", "videoDimSeconds", "imageOverlayAutoHide", "videoMixAudio", "gifFrameCounter", "gifFrameSelector", "ftpEnabled", "previewPinEnabled", "comparisonEnabled", "animatedAlbumCovers", "videoKeepPlaying", "actionMenusBelow", "albumCoverLimit", "allAnimatedAlbumCovers", "previewNearest", "imageBackdrop", "imageBackdropColor", "imageBackdropEnd", "imageBackdropOpacity", "musicColorMode", "musicBackgroundColor", "musicBarsColor").forEach { getJSONObject("preferences").remove(it) } }.toString())
        invalid.forEach { raw -> try { PreferencesTransfer.decode(raw); fail("Invalid touch accepted") } catch (_: Exception) { } }
        assertEquals(value, PreferenceStore(app).state.value)
    }
    @Test fun unsafeOrOverlappingZonesAndWrongContextsAreRejected() {
        val zone = TouchZone("zone", PadContext.LIBRARY, .1f, .1f, .2f, .2f)
        val invalid = listOf(
            TouchSettings(zones = listOf(zone.copy(x = -.1f))), TouchSettings(zones = listOf(zone.copy(width = Float.NaN))),
            TouchSettings(zones = listOf(zone.copy(x = .9f))), TouchSettings(zones = listOf(zone, zone.copy(id = "other"))),
            TouchSettings(zones = listOf(zone.copy(context = PadContext.DIALOG))),
            TouchSettings(zones = listOf(zone.copy(action = TouchAction.TOGGLE_ZOOM))))
        invalid.forEach { value -> try { value.validate(); fail("Unsafe zone accepted") } catch (_: IllegalArgumentException) { } }
        assertTrue(zone.contains(.15f, .15f)); assertFalse(zone.contains(.7f, .7f))
        TouchSettings(zones = listOf(zone, zone.copy(id = "other", x = .5f))).validate()
        touchContexts.forEach { context -> assertFalse(touchActions(context).any { it.name == "DELETE" }); assertFalse(TouchAction.TOGGLE_ZOOM in touchActions(context)) }
    }
    @Test fun threeFingerActionWaitsForAllReleasesAndRequiresConsistentDirection() {
        val drag = ThreeFingerDrag(8f, 48f)
        assertFalse(drag.update(points(0f, count = 1), 0).consume)
        assertFalse(drag.update(points(0f, count = 2), 20).consume)
        assertTrue(drag.update(points(0f), 40).consume)
        assertNull(drag.update(points(80f), 100).direction)
        assertNull(drag.update(points(80f).mapIndexed { index, point -> point.copy(pressed = index != 0) }, 120).direction)
        assertEquals(TouchDirection.RIGHT, drag.update(points(80f, pressed = false), 140).direction)
        assertNull(drag.update(points(80f, pressed = false), 160).direction)
        val split = ThreeFingerDrag(8f, 48f)
        split.update(points(0f), 0)
        assertNull(split.update(points(80f, pressed = false).mapIndexed { i, point -> if (i == 2) point.copy(x = 20f) else point }, 100).direction)
    }
    @Test fun staggeredFingersWithin350msCanCompleteAShortSwipe() {
        val drag = ThreeFingerDrag(8f, 32f)
        drag.update(points(0f, count = 1), 0)
        drag.update(points(0f, count = 2), 100)
        assertTrue(drag.update(points(0f), 300).consume)
        assertEquals(TouchDirection.RIGHT, drag.update(points(36f, pressed = false), 600).direction)
    }
    @Test fun pinchScrollLateThirdFourthFingerAndReversalCannotTriggerThreeFingerAction() {
        val pinch = ThreeFingerDrag(8f, 48f)
        pinch.update(points(0f, count = 2), 0)
        pinch.update(points(20f, count = 2), 30)
        assertFalse(pinch.update(points(20f), 50).consume)
        assertNull(pinch.update(points(80f, pressed = false), 100).direction)
        val late = ThreeFingerDrag(8f, 48f)
        late.update(points(0f, count = 2), 0)
        assertFalse(late.update(points(0f), 400).consume)
        assertNull(late.update(points(80f, pressed = false), 500).direction)
        val fourth = ThreeFingerDrag(8f, 48f)
        fourth.update(points(0f), 0)
        assertTrue(fourth.update(points(0f, count = 4), 30).consume)
        assertNull(fourth.update(points(80f, count = 4, pressed = false), 100).direction)
        val reversed = ThreeFingerDrag(8f, 48f)
        reversed.update(points(0f), 0); reversed.update(points(80f), 100)
        assertNull(reversed.update(points(0f, pressed = false), 200).direction)
        val resized = ThreeFingerDrag(8f, 48f)
        resized.update(points(0f), 0); resized.cancel()
        assertNull(resized.update(points(80f, pressed = false), 100).direction)
        // Independent window trackers never combine fingers from two displays.
        val one = ThreeFingerDrag(8f, 48f); val two = ThreeFingerDrag(8f, 48f)
        one.update(points(0f, count = 1), 0); two.update(points(0f, count = 2), 0)
        assertNull(one.update(points(80f, count = 1, pressed = false), 100).direction)
        assertNull(two.update(points(80f, count = 2, pressed = false), 100).direction)
    }
    @Test fun touchUpdatesPreserveConcurrentThemeAndPadEditsAndCorruptBindingsFallBackSafely() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        val touch = TouchSettings(doubleTap = TouchAction.NONE)
        coroutineScope {
            launch(Dispatchers.Default) { store.update { it.copy(touch = touch) } }
            launch(Dispatchers.Default) { store.update { it.copy(themeId = GalleryThemes.WHITE, pad = it.pad.copy(delay = 500)) } }
        }
        val restored = PreferenceStore(app).state.value
        assertEquals(touch, restored.touch); assertEquals(GalleryThemes.WHITE, restored.themeId); assertEquals(500, restored.pad.delay)
        app.getSharedPreferences("ui_preferences_v1", 0).edit().putString("touch_bindings", "corrupt").commit()
        assertEquals(TouchSettings(), PreferenceStore(app).state.value.touch)
    }
    private fun points(dx: Float, count: Int = 3, pressed: Boolean = true) = (0 until count).map { TouchPoint(it.toLong(), 40f + it * 20f + dx, 100f, pressed) }
}
