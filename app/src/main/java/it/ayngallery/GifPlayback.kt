package it.ayngallery

import android.graphics.Bitmap
import androidx.compose.runtime.*
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeader
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

@Composable
internal fun rememberGifPlayback(app: GalleryApp, item: MediaEntity, data: CatalogSnapshot, enabled: Boolean): State<GifPlayback?> =
    produceState<GifPlayback?>(null, item.id, item.modified, item.size, enabled) {
        if (!enabled) return@produceState
        val opened = GifPlayback.open(app, item, data)
        val bookmark = "${item.id}/${item.modified}/${item.size}"
        try {
            app.session.pausedGifFrames[bookmark]?.takeIf { opened != null && it in 0 until opened.count }?.let { frame ->
                opened!!.select(frame)
                opened.show(frame)
            }
            value = opened
            awaitDispose {
                if (opened?.paused == true) {
                    app.session.pausedGifFrames[bookmark] = opened.selected ?: opened.index
                    while (app.session.pausedGifFrames.size > 64) {
                        app.session.pausedGifFrames.remove(app.session.pausedGifFrames.keys.first())
                    }
                } else app.session.pausedGifFrames.remove(bookmark)
            }
        } finally {
            value = null
            opened?.close()
        }
    }

/** Only one preview animation and a lazy, bounded thumbnail strip; never index every frame as a file. */
internal class GifPlayback private constructor(private val bytes: ByteArray, header: GifHeader, private val scope: CoroutineScope) {
    val count = header.numFrames
    private val preview = Frames(header, bytes)
    private var thumbnails: Frames? = null
    private val thumbnailHeader = header
    private val previewLock = Mutex()
    private val thumbnailLock = Mutex()
    private val thumbnailCache = android.util.LruCache<Int, Bitmap>(16)
    private val retired = ArrayDeque<Bitmap>()
    @Volatile private var closed = false
    var bitmap by mutableStateOf<Bitmap?>(null); private set
    var index by mutableIntStateOf(0); private set
    var paused by mutableStateOf(false); private set
    fun cachedThumbnail(frame: Int): Bitmap? = thumbnailCache.get(frame)?.takeUnless { it.isRecycled }
    var stripWidth by mutableFloatStateOf(266f)
    var selected by mutableStateOf<Int?>(null); private set
    var requestRevision by mutableIntStateOf(0); private set
    var failed by mutableStateOf(false); private set
    val seeking get() = paused && selected != null && selected != index && !failed
    fun select(frame: Int) {
        require(frame in 0 until count)
        paused = true; selected = frame; requestRevision++
    }
    fun resume() { selected = null; paused = false; requestRevision++ }
    fun pause() { selected = index; paused = true; requestRevision++ }
    suspend fun show(frame: Int, deadline: Long = 0) = withContext(Dispatchers.Default) {
        previewLock.withLock {
            check(!closed)
            if (frame == index && bitmap != null) return@withLock
            val next = preview.decode(frame)
            var published = false
            try {
                delay((deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
                withContext(Dispatchers.Main.immediate) {
                    bitmap?.let { retired.addLast(it) }
                    bitmap = next; index = frame; published = true
                    if (retired.size > 3) preview.pool.release(retired.removeFirst())
                }
            } finally { if (!published) preview.pool.release(next) }
        }
    }
    suspend fun animate(active: Boolean) {
        try {
            selected?.let { show(it) }
            if (bitmap == null) show(0)
            if (!active || paused || failed || count <= 1) return
            while (currentCoroutineContext().isActive && !closed && !paused) {
                val deadline = android.os.SystemClock.uptimeMillis() + preview.decoder.getDelay(index).coerceAtLeast(20)
                show((index + 1) % count, deadline)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; paused = true }
    }
    suspend fun thumbnail(frame: Int): Bitmap? = withContext(Dispatchers.Default) {
        thumbnailLock.withLock {
            if (closed) return@withLock null
            thumbnailCache.get(frame)?.let { return@withLock it }
            val thumbnails = thumbnails ?: Frames(thumbnailHeader, bytes, 128).also { thumbnails = it }
            val full = thumbnails.decode(frame)
            val scale = minOf(1f, 128f / maxOf(full.width, full.height))
            val scaled = Bitmap.createScaledBitmap(full, maxOf(1, (full.width * scale).toInt()), maxOf(1, (full.height * scale).toInt()), true)
            val result = if (scaled === full) full.copy(Bitmap.Config.ARGB_8888, false) else scaled
            thumbnails.pool.release(full)
            thumbnailCache.put(frame, result)
            result
        }
    }
    fun close() {
        closed = true
        scope.launch(Dispatchers.Default) {
            previewLock.withLock { thumbnailLock.withLock {
                preview.decoder.clear(); thumbnails?.decoder?.clear()
                preview.pool.clear(); thumbnails?.pool?.clear(); thumbnails = null
                retired.clear(); thumbnailCache.evictAll()
            } }
        }
    }
    private class Pool : GifDecoder.BitmapProvider {
        private val bitmaps = ArrayDeque<Bitmap>()
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap {
            val found = bitmaps.firstOrNull { !it.isRecycled && it.width == width && it.height == height && it.config == config }
            if (found != null) { bitmaps.remove(found); return found }
            return Bitmap.createBitmap(width, height, config)
        }
        override fun release(bitmap: Bitmap) { if (bitmaps.size < 5) bitmaps.addLast(bitmap) }
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun release(array: IntArray) = Unit
        fun clear() { bitmaps.clear() } // References held by the outgoing UI remain valid until its fade ends.
    }
    private class Frames(header: GifHeader, bytes: ByteArray, maxSide: Int = 1280) {
        val pool = Pool()
        val decoder = StandardGifDecoder(pool).apply {
            var sample = 1
            while (maxOf(header.width, header.height) / sample > maxSide) sample *= 2
            setData(header, ByteBuffer.wrap(bytes), sample)
        }
        suspend fun decode(target: Int): Bitmap {
            if (target <= decoder.currentFrameIndex) decoder.resetFrameIndex()
            var result: Bitmap? = null
            while (decoder.currentFrameIndex < target) {
                currentCoroutineContext().ensureActive()
                result?.let(pool::release)
                decoder.advance()
                result = decoder.nextFrame
                check(result != null && decoder.status == GifDecoder.STATUS_OK)
            }
            return checkNotNull(result)
        }
    }
    companion object {
        internal fun decodeStill(bytes: ByteArray, index: Int, maxSide: Int): ImagePreview = runBlocking {
            val header = checkNotNull(header(bytes))
            require(index in 0 until header.numFrames)
            val frames = Frames(header, bytes, maxSide)
            try {
                ImagePreview(frames.decode(index).copy(Bitmap.Config.ARGB_8888, false), android.util.Size(header.width, header.height))
            } finally { frames.decoder.clear(); frames.pool.clear() }
        }

        internal fun header(bytes: ByteArray): GifHeader? {
            if (bytes.size !in 13..16 * 1024 * 1024) return null
            val frames = GifFrameCount.read(bytes.inputStream()) ?: return null
            if (frames > 2000) return null
            val parsed = GifHeaderParser().setData(bytes).parseHeader()
            return parsed.takeIf { it.status == GifDecoder.STATUS_OK && it.numFrames in 1..2000 &&
                it.width.toLong() * it.height in 1..4_000_000 }
        }
        internal fun fromBytes(bytes: ByteArray, scope: CoroutineScope): GifPlayback? = header(bytes)?.let { GifPlayback(bytes, it, scope) }
        suspend fun open(app: GalleryApp, item: MediaEntity, data: CatalogSnapshot): GifPlayback? = withContext(Dispatchers.IO) {
            if (!data.usable(item) || item.kind != "GIF") return@withContext null
            try {
                val bytes = app.contentResolver.openInputStream(app.catalog.uri(item, data))?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        if (output.size() + n > 16 * 1024 * 1024) return@withContext null
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                } ?: return@withContext null
                fromBytes(bytes, app.catalog.scope)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}
