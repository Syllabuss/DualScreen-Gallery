package it.ayngallery

import coil3.SingletonImageLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class CacheInfo(val memoryBytes: Long, val memoryLimit: Long, val artworkBytes: Long, val artworkFiles: Int)
data class CacheResult(val deletedFiles: Int, val deletedBytes: Long, val failedFiles: Int)
data class CacheStatus(val busy: Boolean = false, val message: String? = null, val failed: Boolean = false)

/** Only derived artwork and Coil's memory cache. Never walk cacheDir/filesDir or use recursive deletion. */
class CacheMaintenance(private val app: GalleryApp) {
    private val mutation = Mutex()
    val status = MutableStateFlow(CacheStatus())
    private fun directory() = File(app.cacheDir, "artwork")
    private fun files(): List<File> = artworkFiles(directory(), includeTemporary = true)
    fun recover() {
        app.catalog.scope.launch(Dispatchers.IO) {
            try {
                app.catalog.writer.withLock {
                    if (recoverArtwork(directory()) > 0) withContext(Dispatchers.Main.immediate) {
                        app.catalog.cacheRevision.value++
                        app.widgets.refresh(); app.photoWidgets.refresh()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    status.value = CacheStatus(message = localized(R.string.text_artwork_recovery_incomplete_try_clearing_preview_225d68, "Recupero delle copertine incompleto. Riprova la pulizia delle anteprime."), failed = true)
                    app.session.record(localized(R.string.text_artwork_cache_recovery_failed_1_s_3bc1c1, "Recupero cache artwork fallito: %1\$s", failure.javaClass.simpleName))
                }
            }
        }
    }
    suspend fun info(): CacheInfo = withContext(Dispatchers.IO) {
        app.catalog.writer.withLock {
            val memory = SingletonImageLoader.get(app).memoryCache
            val files = files()
            CacheInfo(memory?.size ?: 0, memory?.maxSize ?: 0, files.sumOf { it.length() }, files.size)
        }
    }
    suspend fun clear(allowGame: Boolean = false): CacheResult = withContext(Dispatchers.IO) {
        mutation.withLock {
            require(!app.preferences.state.value.gameMode || allowGame) { localized(R.string.text_allow_cleanup_in_game_mode_7fcac9, "Consenti la pulizia durante la modalità Gioco") }
            app.catalog.writer.withLock {
                // Cache removals cannot be rolled back. Finish reporting and invalidation even if the calling window closes.
                withContext(NonCancellable) {
                    val main = deleteArtwork(directory())
                    val temporary = deleteArtwork(java.io.File(app.cacheDir, "video-preview"))
                    val tv = app.tv.clearThumbnails()
                    val result = CacheResult(main.deletedFiles + temporary.deletedFiles + tv.deletedFiles, main.deletedBytes + temporary.deletedBytes + tv.deletedBytes, main.failedFiles + temporary.failedFiles + tv.failedFiles)
                    app.catalog.dao.saveMedia(app.catalog.dao.media().filter { it.audioMetadataRead }.map { it.copy(audioMetadataRead = false) })
                    SingletonImageLoader.get(app).memoryCache?.clear()
                    withContext(Dispatchers.Main.immediate) {
                        app.catalog.cacheRevision.value++
                        app.widgets.refresh(); app.photoWidgets.refresh()
                    }
                    result
                }
            }
        }
    }
    fun requestClear(allowGame: Boolean) {
        if (status.value.busy) return
        status.value = CacheStatus(busy = true)
        app.catalog.scope.launch {
            try {
                val result = clear(allowGame)
                status.value = CacheStatus(message = if (result.failedFiles == 0)
                    localized(R.string.text_in_memory_previews_cleared_removed_1_s_temporary_d1b41f, "Anteprime in memoria svuotate. %1\$s copertine temporanee rimosse (%2\$s).", result.deletedFiles, android.text.format.Formatter.formatFileSize(app, result.deletedBytes))
                    else localized(R.string.text_partial_cleanup_removed_1_s_artwork_files_2_s_co_2e8c9b, "Pulizia parziale: %1\$s copertine rimosse; %2\$s non eliminabili. Puoi riprovare.", result.deletedFiles, result.failedFiles), failed = result.failedFiles > 0)
            } catch (cancelled: CancellationException) { status.value = CacheStatus(); throw cancelled }
            catch (failure: Exception) { status.value = CacheStatus(message = failure.message ?: localized(R.string.text_cleanup_failed_try_again_08cccf, "Pulizia non riuscita. Riprova."), failed = true) }
        }
    }
    companion object {
        private data class ArtworkFile(val file: File, val bytes: Long, val modified: Long)
        private class ArtworkIndex(val files: MutableMap<String, ArtworkFile>) { var bytes = files.values.sumOf { it.bytes } }
        private val accounting = android.util.LruCache<String, ArtworkIndex>(4)
        const val ARTWORK_LIMIT = 128L * 1024 * 1024
        /** Caller owns catalog.writer, shared by scanning and manual cleanup. Only derived leaf files. */
        @Synchronized
        internal fun storeArtwork(directory: File, id: String, bytes: ByteArray, limit: Long = ARTWORK_LIMIT): String {
            require(id.matches(Regex("[A-Za-z0-9._:-]{1,256}")) && bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024)
            require(bytes.size <= limit)
            check(directory.isDirectory || directory.mkdirs()) { localized(R.string.text_artwork_cache_unavailable_81e2e4, "Cache copertine non disponibile") }
            check(directory.canonicalFile == File(requireNotNull(directory.parentFile).canonicalFile, directory.name)) { localized(R.string.text_unsafe_artwork_folder_178f07, "Cartella delle copertine non sicura") }
            val directoryKey = directory.canonicalPath
            val index = accounting.get(directoryKey) ?: run {
                recoverArtwork(directory)
                ArtworkIndex(artworkFiles(directory).associate { it.name to ArtworkFile(it, it.length(), it.lastModified()) }.toMutableMap()).also { accounting.put(directoryKey, it) }
            }
            val known = index.files
            val target = File(directory, "$id.art")
            check(target.canonicalFile == File(directory.canonicalFile, target.name)) { localized(R.string.text_unsafe_temporary_artwork_368bc9, "Copertina temporanea non sicura") }
            for (suffix in listOf(".new", ".bak")) {
                val temporary = File(directory, target.name + suffix)
                check(temporary.canonicalFile == File(directory.canonicalFile, temporary.name)) { localized(R.string.text_unsafe_temporary_write_455c5f, "Scrittura temporanea non sicura") }
            }
            // Reserve the incoming atomic write while the previous version can still be read.
            var used = index.bytes
            // Metadata is recorded only for derived files; sort it only when the byte budget requires eviction.
            if (used + bytes.size > limit) for (entry in known.values.sortedBy { it.modified }) {
                if (used + bytes.size <= limit) break
                if (entry.file == target) continue
                if (entry.file.delete()) { used -= entry.bytes; index.bytes -= entry.bytes; known.remove(entry.file.name) }
            }
            check(used + bytes.size <= limit) { localized(R.string.text_temporary_artwork_storage_unavailable_f8b0fa, "Spazio per le copertine temporanee non disponibile") }
            val atomic = android.util.AtomicFile(target)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output); val old = known.put(target.name, ArtworkFile(target, target.length(), target.lastModified())); index.bytes += target.length() - (old?.bytes ?: 0) }
            catch (failure: Exception) { accounting.remove(directoryKey); atomic.failWrite(output); throw failure }
            return target.toURI().toString()
        }
        fun memoryLimit(heap: Long) = minOf(64L * 1024 * 1024, (heap / 8).coerceAtLeast(1024 * 1024))
        /** Caller owns catalog.writer. Never publish an unfinished .new file. */
        @Synchronized
        internal fun recoverArtwork(directory: File): Int {
            accounting.remove(directory.canonicalPath)
            val pending = artworkFiles(directory, includeTemporary = true).filter { !it.name.endsWith(".art") }
            var recovered = 0
            for (name in pending.map { it.name.removeSuffix(".new").removeSuffix(".bak") }.distinct()) {
                val target = File(directory, name)
                val backup = File(directory, "$name.bak")
                val temporary = File(directory, "$name.new")
                for (file in listOf(target, backup, temporary)) {
                    check(file.canonicalFile == File(directory.canonicalFile, file.name) && (!file.exists() || file.isFile)) {
                        localized(R.string.text_unsafe_artwork_recovery_dbc2b0, "Recupero copertina non sicuro")
                    }
                }
                if (backup.exists()) {
                    java.nio.file.Files.move(backup.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    check(!backup.exists() && target.isFile) { localized(R.string.text_previous_artwork_not_recovered_6904f2, "Copertina precedente non recuperata") }
                }
                check(!temporary.exists() || temporary.delete()) { localized(R.string.text_incomplete_artwork_cannot_be_deleted_5c7b87, "Copertina incompleta non eliminabile") }
                recovered++
            }
            return recovered
        }
        internal fun artworkFiles(directory: File, includeTemporary: Boolean = false): List<File> {
            if (!directory.exists()) return emptyList()
            val parent = requireNotNull(directory.parentFile) { localized(R.string.text_unsafe_artwork_folder_178f07, "Cartella delle copertine non sicura") }
            check(directory.isDirectory && directory.canonicalFile == File(parent.canonicalFile, directory.name)) { localized(R.string.text_unsafe_artwork_folder_178f07, "Cartella delle copertine non sicura") }
            return directory.listFiles()?.filter { file -> (file.name.endsWith(".art") || includeTemporary &&
                (file.name.endsWith(".art.new") || file.name.endsWith(".art.bak"))) && file.isFile &&
                file.canonicalFile == File(directory.canonicalFile, file.name) }
                ?: throw java.io.IOException(localized(R.string.text_unreadable_artwork_folder_c5d7b8, "Cartella delle copertine non leggibile"))
        }
        @Synchronized
        internal fun deleteArtwork(directory: File): CacheResult {
            accounting.remove(directory.canonicalPath)
            var count = 0; var size = 0L; var failed = 0
            for (file in artworkFiles(directory, includeTemporary = true)) {
                val bytes = file.length()
                if (file.delete()) { count++; size += bytes } else failed++
            }
            return CacheResult(count, size, failed)
        }
    }
}
