package it.ayngallery

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Looper
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
class CacheMaintenanceTest {
    @Test fun cachedVideoFramesAreReusedAndShareTheExistingCleanup() {
        val item = MediaEntity("movie", "video-doc", "content://missing/movie", "album", "movie.mp4", "video/mp4", "VIDEO", 100, 20)
        fun key(frame: Long) = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${item.id}/${item.documentKey}/${item.modified}/${item.size}/$frame/video-frame-v1".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(app.cacheDir, "artwork")
        val expected = await { app.catalog.writer.withLock { CacheMaintenance.storeArtwork(directory, "video-${key(5000)}", byteArrayOf(1, 2, 3)) } }
        assertEquals(expected, await { app.catalog.videoThumbnail(item, item.uri, 5000) }?.first)
        assertNull(await { app.catalog.videoThumbnail(item, item.uri, 6000) })
        assertNull(await { app.catalog.videoThumbnail(item.copy(modified = 21), item.uri, 5000) })
        val cleared = await { app.cache.clear() }
        assertTrue(cleared.deletedFiles > 0)
        assertFalse(File(directory, "video-${key(5000)}.art").exists())
    }

    @Test fun recoveryRefusesADirectoryAtThePublishedPathAndPreservesItsContents() {
        val dir = File(app.cacheDir, "artwork").also { it.mkdirs() }
        val target = File(dir, "blocked.art").also { it.mkdirs() }
        val protected = File(target, "original.png").also { it.writeBytes(byteArrayOf(4, 5)) }
        val temporary = File(dir, "blocked.art.new").also { it.writeBytes(byteArrayOf(6)) }
        assertTrue(runCatching { CacheMaintenance.recoverArtwork(dir) }.isFailure)
        assertArrayEquals(byteArrayOf(4, 5), protected.readBytes())
        assertArrayEquals(byteArrayOf(6), temporary.readBytes())
    }
    @Test @Config(sdk = [28, 33], qualifiers = "it") fun interruptedArtworkRestoresBackupDiscardsUnpublishedBytesAndCountsAllTemporaryFiles() {
        // Isolate the simulated interruption from the real startup recovery running in the app.
        val dir = File(app.cacheDir, "artwork-recovery").also { it.mkdirs() }
        val base = File(dir, "previous.art").also { it.writeBytes(byteArrayOf(9)) }
        File(dir, "previous.art.bak").writeBytes(byteArrayOf(1, 2))
        File(dir, "previous.art.new").writeBytes(byteArrayOf(3, 4, 5))
        val orphan = File(dir, "orphan.art.new").also { it.writeBytes(byteArrayOf(6)) }
        val unrelated = File(dir, "keep.new").also { it.writeBytes(byteArrayOf(7)) }
        val nested = File(dir, "nested.art.bak").also { it.mkdirs() }
        val nestedFile = File(nested, "keep.art").also { it.writeBytes(byteArrayOf(8)) }
        assertEquals(7L, CacheMaintenance.artworkFiles(dir, includeTemporary = true).sumOf { it.length() })
        assertEquals(2, await { app.catalog.writer.withLock { CacheMaintenance.recoverArtwork(dir) } })
        assertArrayEquals(byteArrayOf(1, 2), base.readBytes())
        assertFalse(orphan.exists()); assertFalse(File(dir, "orphan.art").exists())
        assertEquals(0, CacheMaintenance.recoverArtwork(dir))
        assertArrayEquals(byteArrayOf(7), unrelated.readBytes()); assertArrayEquals(byteArrayOf(8), nestedFile.readBytes())
        File(dir, "abandoned.art.new").writeBytes(byteArrayOf(10))
        assertEquals(CacheResult(2, 3, 0), CacheMaintenance.deleteArtwork(dir))
        assertTrue(unrelated.exists()); assertTrue(nestedFile.exists())
    }
    @Test fun warmArtworkAccountingTracksReplacementEvictionAndClear() {
        val dir = File(app.cacheDir, "artwork").also { it.mkdirs() }
        await { app.catalog.writer.withLock {
            CacheMaintenance.storeArtwork(dir, "first", ByteArray(30), 100)
            CacheMaintenance.storeArtwork(dir, "second", ByteArray(30), 100)
            CacheMaintenance.storeArtwork(dir, "first", ByteArray(40), 100)
            assertEquals(70L, CacheMaintenance.artworkFiles(dir).sumOf { it.length() })
            CacheMaintenance.deleteArtwork(dir)
            CacheMaintenance.storeArtwork(dir, "third", ByteArray(80), 100)
            assertEquals(listOf("third.art"), CacheMaintenance.artworkFiles(dir).map { it.name })
        } }
    }
    @Test fun artworkBudgetEvictsOldestDerivedFilesAndWritesAtomicallyWithoutTouchingOtherFiles() {
        val dir = File(app.cacheDir, "artwork").also { it.mkdirs() }
        val oldest = File(dir, "old.art").also { it.writeBytes(ByteArray(40)); it.setLastModified(1000) }
        val recent = File(dir, "recent.art").also { it.writeBytes(ByteArray(40)); it.setLastModified(2000) }
        val keep = File(dir, "cover.png").also { it.writeBytes(byteArrayOf(7)) }
        val original = File(app.filesDir, "original.png").also { it.writeBytes(byteArrayOf(9)) }
        val uri = await { app.catalog.writer.withLock { CacheMaintenance.storeArtwork(dir, "new", ByteArray(40) { 3 }, 100) } }
        assertFalse(oldest.exists()); assertTrue(recent.exists())
        assertArrayEquals(ByteArray(40) { 3 }, File(java.net.URI(uri)).readBytes())
        assertEquals(80L, await { app.cache.info() }.artworkBytes)
        assertArrayEquals(byteArrayOf(7), keep.readBytes()); assertArrayEquals(byteArrayOf(9), original.readBytes())
        assertTrue(runCatching { CacheMaintenance.storeArtwork(dir, "../original", byteArrayOf(5), 100) }.isFailure)
        assertTrue(runCatching { CacheMaintenance.storeArtwork(dir, "oversize", ByteArray(101), 100) }.isFailure)
        assertTrue(keep.exists()); assertTrue(original.exists())
        assertNull(app.music.state.value.id)
    }
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @Test fun concurrentExtractionAndCleanupShareWriterAndNeverRemoveOriginals() {
        val dir = File(app.cacheDir, "artwork").also { it.mkdirs() }
        val original = File(app.filesDir, "safe-original.png").also { it.writeBytes(byteArrayOf(11)) }
        await { coroutineScope {
            launch { app.catalog.writer.withLock { CacheMaintenance.storeArtwork(dir, "one", ByteArray(40) { 1 }, 100) } }
            launch { app.catalog.writer.withLock { CacheMaintenance.storeArtwork(dir, "two", ByteArray(40) { 2 }, 100) } }
            launch { app.cache.clear() }
        } }
        assertTrue(await { app.cache.info() }.artworkBytes <= 100)
        CacheMaintenance.artworkFiles(dir).forEach { assertEquals(40L, it.length()) }
        assertArrayEquals(byteArrayOf(11), original.readBytes())
        assertNull(app.music.state.value.id)
    }
    @Before fun start() { shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java)) }
    @After fun close() { app.onTerminate() }
    private fun <T> await(action: suspend () -> T): T {
        val task = app.catalog.scope.async { action() }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Cache action did not finish", task.isCompleted)
        return runBlocking { task.await() }
    }
    @Test fun clearingCacheAllowsAudioMetadataRetryWithoutLosingTagsOrFavorites() {
        val item = MediaEntity("read-audio", "safe:audio", "content://original/audio", "album", "Track", "audio/mpeg", "AUDIO", 64, 1,
            title = "Saved title", favorite = true, audioMetadataRead = true)
        await { app.catalog.dao.saveMedia(listOf(item)); app.cache.clear() }
        assertEquals(item.copy(audioMetadataRead = false), await { app.catalog.dao.media(item.id) })
    }
    @Test fun onlyDerivedLeafArtworkAndMemoryAreClearedWithoutChangingAnyLibraryState() {
        val artwork = File(app.cacheDir, "artwork").also { it.mkdirs() }
        val embedded = File(artwork, "track.art").also { it.writeBytes(ByteArray(64) { 4 }) }
        val unusual = File(artwork, "keep.png").also { it.writeBytes(byteArrayOf(9)) }
        val nested = File(artwork, "nested.art").also { it.mkdirs() }
        val nestedFile = File(nested, "keep.art").also { it.writeBytes(byteArrayOf(5)) }
        val privateFiles = listOf("trash/kept.bin", "import-media/kept.bin", "library-transfer/kept.json", "share-copies/kept.png")
            .map { path -> File(app.filesDir, path).also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2, 3)) } }
        val original = File(app.cacheDir, "original-fixture.png").also { it.writeBytes(byteArrayOf(6, 7)) }
        val item = MediaEntity("track", "safe:track", "content://original/track", "album", "Track", "audio/flac", "AUDIO", 64, 1,
            artworkUri = embedded.toURI().toString(), favorite = true)
        await {
            app.catalog.dao.saveMedia(listOf(item))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("album", "safe:album", "content://original/album", "Hidden", "Hidden", null, hidden = true)))
            app.catalog.dao.saveCollection(CollectionEntity("saved", "Keep", "MEDIA", item.id))
            app.catalog.dao.saveCollectionItems(listOf(CollectionItem("saved", item.id, 0)))
        }
        app.getSharedPreferences("music_service_v1", 0).edit().putString("queue", "[track]").commit()
        val comparison = app.comparison.state.value.pin(item.id, listOf(item.id))!!
        app.comparison.update(comparison)
        await { app.comparison.save("Kept session") }
        val before = await { app.catalog.readSnapshot() }
        val memory = SingletonImageLoader.get(app).memoryCache!!
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        memory[MemoryCache.Key("qa")] = MemoryCache.Value(image.asImage())
        assertTrue(memory.size > 0)
        val info = await { app.cache.info() }; assertEquals(64L, info.artworkBytes); assertEquals(1, info.artworkFiles)
        val revision = app.catalog.cacheRevision.value
        val result = await { app.cache.clear() }
        assertEquals(CacheResult(1, 64, 0), result); assertFalse(embedded.exists())
        assertTrue(memory.keys.isEmpty()); assertEquals(0L, memory.size); assertFalse(image.isRecycled)
        assertEquals(revision + 1, app.catalog.cacheRevision.value)
        assertEquals(before, await { app.catalog.readSnapshot() })
        assertEquals(comparison, app.comparison.state.value); assertTrue("Kept session" in app.comparison.names.value)
        assertEquals("[track]", app.getSharedPreferences("music_service_v1", 0).getString("queue", null))
        (privateFiles + unusual + nestedFile + original).forEach { assertTrue("Protected file changed: $it", it.isFile) }
        assertEquals(0L, await { app.cache.info() }.artworkBytes)
        image.recycle()
    }
    @Test fun sharedLimitIsBoundedAndClearNeverTraversesASubdirectoryOrAnAlias() {
        assertEquals(16L * 1024 * 1024, CacheMaintenance.memoryLimit(128L * 1024 * 1024))
        assertEquals(64L * 1024 * 1024, CacheMaintenance.memoryLimit(8L * 1024 * 1024 * 1024))
        assertTrue(SingletonImageLoader.get(app).memoryCache!!.maxSize <= 64L * 1024 * 1024)
        val directory = File(app.cacheDir, "not-artwork").also { it.mkdirs() }
        val alias = object : File(directory.path) { override fun getCanonicalFile() = File(app.filesDir, "trash") }
        try { CacheMaintenance.deleteArtwork(alias); fail("Alias directory accepted") } catch (_: IllegalStateException) { }
        assertEquals(CacheResult(0, 0, 0), CacheMaintenance.deleteArtwork(File(app.cacheDir, "absent")))
    }
    @Test fun gameModeRequiresAnExplicitChoiceAndConcurrentRequestsDoNotStartTwoCleanups() {
        await { app.preferences.save(app.preferences.state.value.copy(gameMode = true)) }
        val file = File(app.cacheDir, "artwork/track.art").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(8)) }
        try { await { app.cache.clear() }; fail("Game cleanup accepted without consent") } catch (_: IllegalArgumentException) { }
        assertTrue(file.exists()); assertEquals(0L, app.catalog.cacheRevision.value)
        app.cache.requestClear(true); app.cache.requestClear(true)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (app.cache.status.value.busy && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertFalse(app.cache.status.value.busy); assertFalse(app.cache.status.value.failed)
        assertFalse(file.exists()); assertEquals(1L, app.catalog.cacheRevision.value)
    }
}
