package it.ayngallery

import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class MusicPlayerColorsTest {
    @Test fun redCoverUsesItsGoldDetailRatherThanRedBarsOnARedBackground() {
        val gold = android.graphics.Color.rgb(221, 204, 102)
        val colors = musicArtworkColors(IntArray(80) { android.graphics.Color.RED } + IntArray(20) { gold }, MusicColors(0, -1))
        assertEquals(gold, colors.accent)
    }
    @Test fun colorsAreExtractedFromVisiblePixelsWithoutDiscardingBlackOrKeepingThePreviousCover() {
        val fallback = MusicColors(Color.GRAY, Color.MAGENTA)
        val blueOnBlack = musicArtworkColors(IntArray(100) { if (it < 80) Color.BLACK else Color.BLUE }, fallback)
        assertEquals(Color.BLACK, blueOnBlack.background)
        assertEquals(Color.BLUE, blueOnBlack.accent)
        val darkBlue = Color.rgb(17, 34, 85)
        assertEquals(darkBlue, musicArtworkColors(IntArray(100) { if (it < 80) Color.BLACK else darkBlue }, fallback).accent)
        val next = musicArtworkColors(IntArray(100) { if (it < 80) Color.RED else Color.WHITE }, fallback)
        assertEquals(Color.RED, next.background)
        assertEquals(Color.RED, next.accent)
        assertEquals(fallback, musicArtworkColors(IntArray(100) { Color.TRANSPARENT }, fallback))
        assertEquals(fallback, musicArtworkColors(intArrayOf(), fallback))
    }

    @Test fun customColorsRemainExactWithoutAnyThemeTintOrGradient() {
        for (background in listOf("#000000", "#FFFFFF", "#FF0033")) {
            val palette = customMusicPalette(background, "#102030")
            assertEquals(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(background)), palette.background)
            assertEquals(palette.background, palette.edge)
            assertEquals(androidx.compose.ui.graphics.Color(0xFF102030), palette.accent)
            assertNull(palette.artwork)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
                if (palette.ink == androidx.compose.ui.graphics.Color.White) Color.WHITE else Color.BLACK,
                android.graphics.Color.parseColor(background)) >= 4.5)
        }
    }

    @Test fun nearbyBlueShadesTogetherOutweighAnExactGreyBucket() {
        val pixels = IntArray(100) { index -> when {
            index < 50 -> Color.BLACK
            index < 70 -> Color.GRAY
            else -> Color.rgb(17, 34 + (index % 3) * 17, 85 + (index % 2) * 17)
        } }
        val palette = musicArtworkColors(pixels, MusicColors(Color.BLACK, Color.MAGENTA))
        assertTrue(Color.blue(palette.accent) > Color.red(palette.accent) * 2)
        assertTrue(Color.blue(palette.accent) > Color.green(palette.accent))
    }

    @Test fun customMusicColorsPersistAndRoundTripWhileOlderExportsUseTheTheme() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        assertEquals("THEME", store.state.value.musicColorMode)
        val value = store.state.value.copy(musicColorMode = "CUSTOM", musicBackgroundColor = "#331122", musicBarsColor = "#55CCFF")
        store.save(value)
        assertEquals(value, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
        val encoded = PreferencesTransfer.encode(value)
        assertEquals(value, PreferencesTransfer.decode(encoded))
        val legacy = JSONObject(encoded).put("version", 28).apply {
            getJSONObject("preferences").remove("musicDuringSleep"); getJSONObject("preferences").remove("videoDuringSleep")
            listOf("tvEnabled", "tvInNavbar", "tvInMediaFavorites").forEach { getJSONObject("preferences").remove(it) }
            getJSONObject("preferences").apply { remove("showUpperStatusBar"); remove("confirmVideoReplacement"); remove("musicColorMode"); remove("musicBackgroundColor"); remove("musicBarsColor") }
        }
        assertEquals("THEME", PreferencesTransfer.decode(legacy.toString()).musicColorMode)
        assertThrows(IllegalArgumentException::class.java) { value.copy(musicColorMode = "invalid").validate() }
        assertThrows(IllegalArgumentException::class.java) { value.copy(musicBarsColor = "red").validate() }
        Unit
    }
}
