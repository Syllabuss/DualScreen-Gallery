package it.ayngallery

import android.graphics.Color
import android.os.Looper
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GifPlaybackTest {
    private val app get() = (RuntimeEnvironment.getApplication() as GalleryApp)
    private val sessions = mutableListOf<GifPlayback>()
    private fun bytes(name: String) = javaClass.getResourceAsStream("/gif/$name")!!.use { it.readBytes() }
    private fun session(name: String) = GifPlayback.fromBytes(bytes(name), app.catalog.scope)!!.also(sessions::add)
    private fun <T> await(block: suspend () -> T): T {
        val task = app.catalog.scope.async { block() }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
        check(task.isCompleted)
        return runBlocking { task.await() }
    }
    @After fun close() { sessions.forEach { it.close() }; app.onTerminate() }
    @Test fun exportedStillMatchesTheChosenFrameAndOldRecipesKeepFrameZero() {
        val first = GifPlayback.decodeStill(bytes("three-frames.gif"), 0, 1280)
        val last = GifPlayback.decodeStill(bytes("three-frames.gif"), 2, 1280)
        try { assertEquals(Color.RED, first.bitmap.getPixel(0, 0)); assertEquals(Color.BLUE, last.bitmap.getPixel(0, 0)) }
        finally { first.bitmap.recycle(); last.bitmap.recycle() }
        val recipe = ImageRecipe(input = "GIF_FIRST_FRAME", gifFrame = 2)
        assertEquals(recipe, ImageRecipe.decode(recipe.encode()))
        val old = org.json.JSONObject(recipe.encode()).also { it.remove("gifFrame") }.toString()
        assertEquals(0, ImageRecipe.decode(old).gifFrame)
    }
    @Test fun selectedFrameIsTheDecodedFrameAndResumeDoesNotResetIt() {
        val gif = session("three-frames.gif")
        assertEquals(3, gif.count)
        await { gif.show(0) }
        assertEquals(Color.RED, gif.bitmap!!.getPixel(0, 0))
        gif.select(2)
        assertTrue(gif.paused); assertTrue(gif.seeking)
        await { gif.animate(false) }
        assertEquals(2, gif.index)
        assertEquals(Color.BLUE, gif.bitmap!!.getPixel(0, 0))
        assertFalse(gif.seeking)
        gif.select(2)
        assertTrue(gif.paused); assertEquals(2, gif.selected)
        gif.resume()
        assertFalse(gif.paused); assertNull(gif.selected); assertEquals(2, gif.index)
        await { gif.show(0) }
        assertEquals(Color.RED, gif.bitmap!!.getPixel(0, 0))
    }
    @Test fun thumbnailsDoNotMoveThePreviewCursorOrModifyItsPixels() {
        val gif = session("three-frames.gif")
        await { gif.show(0) }
        val preview = gif.bitmap!!
        val thumbnail = await { gif.thumbnail(2) }!!
        assertEquals(Color.BLUE, thumbnail.getPixel(0, 0))
        assertEquals(Color.RED, preview.getPixel(0, 0)); assertEquals(0, gif.index)
        assertSame(thumbnail, await { gif.thumbnail(2) })
        await { gif.show(1) }; await { gif.show(2) }; await { gif.show(0) }
        assertEquals(Color.BLUE, thumbnail.getPixel(0, 0))
    }
    @Test fun seekingCompositesInterveningFramesAndClearsThemOnRewind() {
        val gif = session("composited-frames.gif")
        gif.select(2); await { gif.animate(false) }
        assertEquals(Color.RED, gif.bitmap!!.getPixel(0, 0))
        assertEquals(Color.BLUE, gif.bitmap!!.getPixel(3, 3))
        gif.select(0); await { gif.animate(false) }
        assertEquals(Color.rgb(0, 128, 0), gif.bitmap!!.getPixel(3, 3))
    }
    @Test fun unsupportedInputsAreRejectedBeforeAllocatingDecoderBuffers() {
        assertNull(GifPlayback.header(byteArrayOf(1,2,3)))
        val huge = bytes("three-frames.gif").clone()
        huge[6] = 0; huge[7] = 32; huge[8] = 0; huge[9] = 32
        assertNull(GifPlayback.header(huge))
        assertNull(GifPlayback.header(bytes("three-frames.gif").copyOf(25)))
    }
}
