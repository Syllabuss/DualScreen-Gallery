package it.ayngallery

import android.app.Application
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GalleryVideoNavigationTest {
    @Test fun shuffleVisitsEveryVideoBeforeRepeatingAndNeverRepeatsAdjacentItems() {
        val ids = listOf("a", "b", "c", "d")
        val played = mutableSetOf<String>()
        var current = "a"
        repeat(20) {
            val cycle = mutableSetOf(current)
            repeat(ids.size - 1) {
                val next = shuffledVideoNext(ids, current, played)!!
                assertNotEquals(current, next); assertTrue(cycle.add(next)); current = next
            }
            assertEquals(ids.toSet(), cycle)
            val first = shuffledVideoNext(ids, current, played)!!
            assertNotEquals(current, first); current = first
        }
        assertNull(shuffledVideoNext(listOf("only"), "only", mutableSetOf()))
    }
    @Test fun doubleTapSeeksOnlyInPictureSideBands() {
        val context = android.view.ContextThemeWrapper(org.robolectric.RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.gallery_preview_video, null) as GalleryVideoView
        view.layout(0, 0, 1000, 600)
        val seeks = mutableListOf<Int>()
        view.onDoubleTapSeek = seeks::add
        fun tap(x: Float, y: Float, time: Long) {
            for ((action, offset) in listOf(android.view.MotionEvent.ACTION_DOWN to 0L, android.view.MotionEvent.ACTION_UP to 30L)) {
                val event = android.view.MotionEvent.obtain(time, time + offset, action, x, y, 0)
                view.dispatchTouchEvent(event); event.recycle()
            }
        }
        tap(200f, 300f, 1000); tap(200f, 300f, 1150)
        tap(800f, 300f, 2000); tap(800f, 300f, 2150)
        tap(500f, 300f, 3000); tap(500f, 300f, 3150)
        tap(800f, 550f, 4000); tap(800f, 550f, 4150)
        assertEquals(listOf(-1, 1), seeks)
    }
    @Test fun subtitlesJoinTheBottomTransportBetweenPipAndSettingsAndHideWithoutTracks() {
        val context = android.view.ContextThemeWrapper(org.robolectric.RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.gallery_preview_video, null) as GalleryVideoView
        val gear = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
        val row = gear.parent as android.widget.LinearLayout
        val cc = row.getChildAt(row.indexOfChild(gear) - 2) as android.widget.ImageButton
        val pip = row.getChildAt(row.indexOfChild(gear) - 3) as android.widget.ImageButton
        assertEquals(android.view.View.GONE, cc.visibility)
        val time = view.findViewById<android.widget.LinearLayout>(androidx.media3.ui.R.id.exo_time)
        val audio = row.getChildAt(row.indexOfChild(gear) - 1) as android.widget.ImageButton
        assertEquals(android.view.View.GONE, audio.visibility)
        var audioOpened = false
        view.onAudio = { audioOpened = true }
        assertEquals(android.view.View.VISIBLE, audio.visibility)
        audio.performClick(); assertTrue(audioOpened)
        assertSame(row, audio.parent)
        val volume = time.getChildAt(0) as android.widget.ImageButton
        assertEquals(android.view.View.GONE, volume.visibility)
        var volumeOpened = false
        view.onVolume = { volumeOpened = it === volume }
        volume.performClick(); assertTrue(volumeOpened)
        assertEquals(android.view.View.VISIBLE, volume.visibility)
        assertTrue(time.indexOfChild(view.findViewById(androidx.media3.ui.R.id.exo_position)) > 0)
        var subtitles = 0; var windows = 0
        view.onPip = { windows++ }; view.onSubtitles = { subtitles++ }
        assertEquals(android.view.View.VISIBLE, cc.visibility)
        assertEquals(android.graphics.Color.WHITE, cc.imageTintList!!.defaultColor)
        assertEquals(android.view.View.VISIBLE, pip.visibility)
        cc.performClick(); pip.performClick()
        assertEquals(1, subtitles); assertEquals(1, windows)
        view.onSubtitles = null
        assertEquals(android.view.View.GONE, cc.visibility)
        assertEquals(android.view.View.VISIBLE, pip.visibility)
    }

    @Test fun resumeDialogMapsAtoContinueYtoRestartAndBDoesNotRestart() {
        assertEquals(true, videoResumeChoice(PadAction.CONFIRM))
        assertEquals(false, videoResumeChoice(PadAction.SETTINGS))
        assertNull(videoResumeChoice(PadAction.BACK))
        assertNull(videoResumeChoice(PadAction.UP))
    }

    @Test fun transportRestartsThenNavigatesAndRespectsGalleryBoundaries() {
        var position = 9000L
        val visits = mutableListOf<Int>()
        val delegate = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentPosition" -> position
                "seekTo" -> { position = args!!.last() as Long; null }
                "getAvailableCommands" -> Player.Commands.EMPTY
                "isCommandAvailable" -> false
                else -> null
            }
        } as Player
        val player = GalleryVideoNavigation(delegate, visits::add)
        player.previousAvailable = true; player.nextAvailable = true
        player.seekToPrevious()
        assertEquals(0L, position); assertTrue(visits.isEmpty())
        player.seekToPrevious(); player.seekToNext()
        assertEquals(listOf(-1, 1), visits)
        player.previousAvailable = false; player.nextAvailable = false
        player.seekToPrevious(); player.seekToNext()
        assertEquals(2, visits.size)
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
    }
}
