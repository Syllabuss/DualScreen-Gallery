package it.ayngallery

import android.media.SoundPool
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
class UiSoundsTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() = app.onTerminate()
    @Test fun restoringSelectionAfterASectionChangeIsQuietButTheFollowingMoveStillSounds() = runBlocking {
        val pool = shadowOf(ReflectionHelpers.getField<SoundPool>(app.sounds, "pool"))
        pool.notifyResourceLoaded(R.raw.ui_navigate, true); pool.notifyResourceLoaded(R.raw.ui_section, true)
        app.sounds.navigation("media:music")
        app.sounds.play("section")
        pool.clearPlayed()
        SystemClock.sleep(200)
        app.sounds.navigation("album:movies")
        assertFalse(pool.wasResourcePlayed(R.raw.ui_navigate))
        app.sounds.navigation("album:another")
        assertTrue(pool.wasResourcePlayed(R.raw.ui_navigate))
    }

    @Test fun customSoundImportIsPrivateAndInvalidReplacementPreservesItUntilReset() = runBlocking {
        val pool = shadowOf(ReflectionHelpers.getField<SoundPool>(app.sounds, "pool"))
        val source = java.io.File(app.cacheDir, "custom-test.wav")
        val buffer = java.nio.ByteBuffer.allocate(16044).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(16036).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(16000)
        val original = buffer.array(); source.writeBytes(original)
        val importing = async(Dispatchers.IO) { app.sounds.import("favorite", android.net.Uri.fromFile(source)) }
        val deadline = System.nanoTime() + 4_000_000_000L
        while (!importing.isCompleted && System.nanoTime() < deadline) {
            java.io.File(app.filesDir, "ui-sounds").listFiles()?.filter { it.name.startsWith("cue-") }?.forEach {
                runCatching { pool.notifyPathLoaded(it.path, true) }
            }
            delay(10)
        }
        importing.await()
        assertEquals(setOf("favorite"), app.sounds.customCues.value)
        val stored = java.io.File(app.filesDir, "ui-sounds/favorite.wav")
        val saved = stored.readBytes()
        assertArrayEquals(original, source.readBytes())
        source.writeBytes(ByteArray(44))
        try { app.sounds.import("favorite", android.net.Uri.fromFile(source)); fail("Invalid file accepted") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(saved, stored.readBytes())
        app.sounds.reset("favorite")
        assertFalse(stored.exists())
        assertTrue(app.sounds.customCues.value.isEmpty())
        pool.notifyResourceLoaded(R.raw.ui_favorite, true)
        app.sounds.play("favorite")
        assertTrue(pool.wasResourcePlayed(R.raw.ui_favorite))
    }
    @Test fun previewIsExclusiveSuppressesUiCuesAndCannotBeSupersededByRapidClicks() {
        val pool = shadowOf(ReflectionHelpers.getField<SoundPool>(app.sounds, "pool"))
        listOf(R.raw.ui_favorite, R.raw.ui_save, R.raw.ui_settings).forEach { pool.notifyResourceLoaded(it, true) }
        SystemClock.sleep(300)
        assertTrue(app.sounds.preview("favorite"))
        assertFalse(app.sounds.preview("save"))
        app.sounds.play("settings")
        assertFalse(pool.wasResourcePlayed(R.raw.ui_settings))
        assertEquals("favorite", app.sounds.preview.value?.cue)
        app.sounds.stopPreview()
        assertNull(app.sounds.preview.value)
        assertFalse(app.sounds.preview("save"))
        SystemClock.sleep(300)
        assertTrue(app.sounds.preview("save"))
        assertEquals("save", app.sounds.preview.value?.cue)
        app.sounds.stopPreview()
        app.music.state.value = app.music.state.value.copy(playing = true)
        SystemClock.sleep(300)
        assertFalse(app.sounds.preview("favorite"))
    }
    @Test fun newActionCuesCanBePreviewedAndIndividuallyMuted() = runBlocking {
        val pool = shadowOf(ReflectionHelpers.getField<SoundPool>(app.sounds, "pool"))
        val cues = mapOf("restore" to R.raw.ui_restore, "unlock" to R.raw.ui_unlock, "unlock-error" to R.raw.ui_unlock_error, "reorder" to R.raw.ui_reorder, "pin-digit-1" to R.raw.ui_pin_digit_1, "pin-digit-2" to R.raw.ui_pin_digit_2, "pin-digit-3" to R.raw.ui_pin_digit_3, "pin-digit-4" to R.raw.ui_pin_digit_4, "video-replace" to R.raw.ui_video_replace)
        cues.values.forEach { pool.notifyResourceLoaded(it, true) }
        for ((cue, resource) in cues) {
            assertTrue(app.sounds.preview(cue))
            assertTrue(app.sounds.preview.value!!.duration in 1..6000)
            app.sounds.stopPreview(); SystemClock.sleep(300)
            pool.clearPlayed()
            app.preferences.update { it.copy(disabledSounds = setOf(cue)) }
            app.sounds.play(cue); assertFalse(pool.wasResourcePlayed(resource))
            app.preferences.update { it.copy(disabledSounds = emptySet()) }
            app.sounds.play(cue); assertTrue(pool.wasResourcePlayed(resource))
        }
    }
    @Test fun individualMutedCueDoesNotMuteOtherCues() = runBlocking {
        val pool = shadowOf(ReflectionHelpers.getField<SoundPool>(app.sounds, "pool"))
        pool.notifyResourceLoaded(R.raw.ui_favorite, true); pool.notifyResourceLoaded(R.raw.ui_save, true)
        app.preferences.update { it.copy(disabledSounds = setOf("favorite")) }
        SystemClock.sleep(200)
        app.sounds.play("favorite"); app.sounds.play("save")
        assertFalse(pool.wasResourcePlayed(R.raw.ui_favorite)); assertTrue(pool.wasResourcePlayed(R.raw.ui_save))
        app.preferences.update { it.copy(disabledSounds = emptySet()) }
        SystemClock.sleep(200)
        app.sounds.play("favorite")
        assertTrue(pool.wasResourcePlayed(R.raw.ui_favorite))
    }
    @Test fun videoPlaybackSuppressesEveryUiCueAndSoundPreviewUntilPaused() {
        val pool = shadowOf(ReflectionHelpers.getField<SoundPool>(app.sounds, "pool"))
        val resources = listOf(R.raw.ui_navigate, R.raw.ui_notice, R.raw.ui_delete, R.raw.ui_favorite, R.raw.ui_settings,
            R.raw.ui_settings_navigation, R.raw.ui_save, R.raw.ui_restore, R.raw.ui_unlock, R.raw.ui_unlock_error,
            R.raw.ui_reorder, R.raw.ui_pin_digit_1, R.raw.ui_pin_digit_2, R.raw.ui_pin_digit_3)
        resources.forEach { pool.notifyResourceLoaded(it, true) }
        app.video.playing.value = true
        SystemClock.sleep(300)
        UiSounds.CUES.forEach { app.sounds.play(it); assertFalse(app.sounds.preview(it)) }
        resources.forEach { assertFalse(pool.wasResourcePlayed(it)) }
        app.video.playing.value = false
        app.sounds.play("favorite")
        assertTrue(pool.wasResourcePlayed(R.raw.ui_favorite))
        assertTrue(app.sounds.preview("save"))
    }
    @Test fun uiCuesAreDisabledByPreferenceAndMusicAndInitialFocusIsQuiet() = runBlocking {
        val pool = shadowOf(ReflectionHelpers.getField<SoundPool>(app.sounds, "pool"))
        listOf(R.raw.ui_navigate, R.raw.ui_notice, R.raw.ui_delete, R.raw.ui_favorite, R.raw.ui_settings, R.raw.ui_settings_navigation, R.raw.ui_save).forEach { pool.notifyResourceLoaded(it, true) }
        SystemClock.sleep(200)
        app.sounds.navigation("album:a")
        assertFalse(pool.wasResourcePlayed(R.raw.ui_navigate))
        app.sounds.navigation("album:b")
        assertTrue(pool.wasResourcePlayed(R.raw.ui_navigate))
        pool.clearPlayed()
        SystemClock.sleep(200)
        app.music.state.value = app.music.state.value.copy(playing = true)
        listOf("favorite", "settings", "settings-navigation", "save").forEach { app.sounds.play(it) }
        listOf(R.raw.ui_favorite, R.raw.ui_settings, R.raw.ui_settings_navigation, R.raw.ui_save).forEach { assertFalse(pool.wasResourcePlayed(it)) }
        app.sounds.play("notice"); app.sounds.play("delete"); app.sounds.navigation("album:c")
        assertFalse(pool.wasResourcePlayed(R.raw.ui_notice)); assertFalse(pool.wasResourcePlayed(R.raw.ui_delete))
        assertFalse(pool.wasResourcePlayed(R.raw.ui_navigate))
        app.music.state.value = app.music.state.value.copy(playing = false)
        app.preferences.save(app.preferences.state.value.copy(uiSounds = false))
        app.sounds.play("delete")
        assertFalse(pool.wasResourcePlayed(R.raw.ui_delete))
        app.preferences.save(app.preferences.state.value.copy(uiSounds = true))
        app.sounds.play("delete")
        assertTrue(pool.wasResourcePlayed(R.raw.ui_delete))
    }
}
