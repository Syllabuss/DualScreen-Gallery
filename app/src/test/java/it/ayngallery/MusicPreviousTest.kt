package it.ayngallery

import android.app.Application
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

/** Real ExoPlayer timeline/seeks, with no prepare or playback of the synthetic URIs. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
class MusicPreviousTest {
    @Test fun defaultRestartsThenMovesBackAndHonorsRepeatAndShuffle() {
        val app = RuntimeEnvironment.getApplication()
        val delegate = ExoPlayer.Builder(app).build()
        var invalidations = 0
        val player = MusicSessionPlayer(delegate, PreferenceStore(app), invalidateVideoResume = { invalidations++ })
        try {
            player.seekToPrevious(); assertEquals(0, invalidations)
            delegate.setMediaItems((1..3).map { MediaItem.Builder().setMediaId("song$it").setUri("file:///fixture-$it.wav").build() }, 1, 5000)
            idle()
            player.seekToPrevious()
            assertEquals(1, delegate.currentMediaItemIndex); assertEquals(0, delegate.currentPosition)
            player.seekToPreviousMediaItem() // The other standard transport command uses the same policy.
            assertEquals(0, delegate.currentMediaItemIndex); assertEquals(0, delegate.currentPosition)
            delegate.seekTo(6000); idle(); player.seekToPrevious()
            assertEquals(0, delegate.currentMediaItemIndex); assertEquals(0, delegate.currentPosition)
            delegate.repeatMode = Player.REPEAT_MODE_ALL
            idle()
            player.seekToPrevious()
            assertEquals(2, delegate.currentMediaItemIndex)
            delegate.shuffleModeEnabled = true
            delegate.seekTo(1, 0)
            idle()
            val previous = delegate.previousMediaItemIndex
            player.seekToPrevious()
            assertEquals(previous, delegate.currentMediaItemIndex)
            assertFalse(delegate.playWhenReady)
            assertEquals(listOf("song1", "song2", "song3"), (0 until delegate.mediaItemCount).map { delegate.getMediaItemAt(it).mediaId })
        } finally { player.release() }
    }
    @Test fun committedAlternativeUpdatesListenersAndNeverRecreatesOrAutoplays() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val preferences = PreferenceStore(app)
        val delegate = ExoPlayer.Builder(app).build()
        val player = MusicSessionPlayer(delegate, preferences, invalidateVideoResume = {})
        val thresholds = mutableListOf<Long>()
        player.addListener(object : Player.Listener {
            override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) { thresholds += maxSeekToPreviousPositionMs }
        })
        try {
            delegate.setMediaItems((1..2).map { MediaItem.Builder().setMediaId("song$it").setUri("file:///fixture-$it.wav").build() }, 1, 9000)
            idle()
            assertEquals(3000, player.maxSeekToPreviousPosition)
            preferences.save(preferences.state.value.copy(alwaysPrevious = true)); player.refreshPreferences()
            assertEquals(Long.MAX_VALUE, player.maxSeekToPreviousPosition)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(thresholds.contains(Long.MAX_VALUE))
            assertEquals(1, delegate.currentMediaItemIndex); assertEquals(9000, delegate.currentPosition)
            player.seekToPrevious(); assertEquals(0, delegate.currentMediaItemIndex)
            delegate.seekTo(5000); idle(); player.seekToPrevious(); assertEquals(0, delegate.currentPosition)
            preferences.save(preferences.state.value.copy(alwaysPrevious = false)); player.refreshPreferences()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(thresholds.contains(3000))
            delegate.seekTo(1, 9000); idle(); player.seekToPreviousMediaItem()
            assertEquals(1, delegate.currentMediaItemIndex); assertEquals(0, delegate.currentPosition)
            assertFalse(delegate.playWhenReady)
        } finally { player.release() }
    }
    private fun idle() = Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
}
