package it.ayngallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun tvKey(entry: TvCatalogEntry): String = entry.key

internal data class TvSavedChannel(val entry: TvCatalogEntry, val savedAt: Long, val customName: String? = null, val artwork: String? = null,
    val id: String = tvKey(entry), val displayTitle: String? = null, val favorite: Boolean = true, val favoriteAddedAt: Long = savedAt) {
    val name get() = customName ?: entry.name
    fun matches(other: TvCatalogEntry) = tvKey(entry) == tvKey(other) || entry.streamUrl == other.streamUrl
}

/** Private TV records never enter Room's local-file catalog or file operations. */
internal class TvStore(context: Context) {
    private val ui = context.getSharedPreferences("tv-ui", Context.MODE_PRIVATE)
    var acknowledged by mutableStateOf(ui.getBoolean("authorized-use-v1", false))
        private set
    fun acknowledge() { check(ui.edit().putBoolean("authorized-use-v1", true).commit()); acknowledged = true }
    var sourceAcknowledged by mutableStateOf(ui.getBoolean("personal-sources-v1", false))
        private set
    fun acknowledgeSources() { check(ui.edit().putBoolean("personal-sources-v1", true).commit()); sourceAcknowledged = true }
    var sources by mutableStateOf(readSources())
        private set
    private fun readSources(): List<TvCatalog> = runCatching {
        val rows = JSONArray(ui.getString("personal-sources", "[]"))
        require(rows.length() <= 20)
        List(rows.length()) { i -> rows.getJSONObject(i).let { TvCatalog(it.getString("id"), it.getString("name"), it.getString("url")) } }
            .filter { TvNetwork.validUrl(it.url) }
    }.getOrDefault(emptyList())
    fun addSource(name: String, url: String) {
        val clean = url.trim()
        require(TvNetwork.validUrl(clean) && name.trim().length in 1..128)
        if (sources.any { it.url == clean }) return
        require(sources.size < 20)
        val next = sources + TvCatalog(tvDigest(clean), name.trim(), clean)
        val json = JSONArray(); next.forEach { json.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        check(ui.edit().putString("personal-sources", json.toString()).commit()); sources = next
    }
    fun removeSource(id: String) {
        val next = sources.filterNot { it.id == id }
        val json = JSONArray(); next.forEach { json.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        check(ui.edit().putString("personal-sources", json.toString()).commit()); sources = next
    }
    var grid by mutableStateOf(ui.getBoolean("grid", false))
        private set
    fun grid(value: Boolean) { grid = value; ui.edit().putBoolean("grid", value).apply() }
    private val file = AtomicFile(File(context.filesDir, "tv-channels.json"))
    private val lock = Mutex()
    private val artwork = File(context.filesDir, "tv-user-artwork")
    var channels by mutableStateOf<List<TvSavedChannel>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock {
        if (loaded) return@withLock
        val records = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            require(file.baseFile.length() <= TvPlaylist.MAX_BYTES)
            val rows = file.openRead().use { JSONArray(it.readBytes().toString(Charsets.UTF_8)) }
            require(rows.length() <= 1000)
            val parsed = List(rows.length()) { i -> rows.getJSONObject(i).let { row ->
                fun optional(key: String) = row.optString(key).takeIf { it.isNotEmpty() && it != "null" }
                TvSavedChannel(TvCatalogEntry(row.getString("url"), row.getString("name"), optional("channelId"), optional("logo"), optional("group")),
                    row.getLong("savedAt"), optional("customName"), optional("artwork"), optional("id") ?: tvDigest(optional("channelId")?.let { "iptv:$it" } ?: row.getString("url")), optional("displayTitle"), row.optBoolean("favorite", true), row.optLong("favoriteAddedAt", row.getLong("savedAt")))
            } }
            if ((0 until rows.length()).all { rows.getJSONObject(it).has("position") }) parsed else parsed.sortedByDescending { it.savedAt }
        } else emptyList()
        channels = records; loaded = true
        // Recover abandoned drafts after process death without touching a currently imported image.
        discardArtworkFiles(artwork.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }.map { it.name }.toSet())
    } }
    suspend fun set(entry: TvCatalogEntry, save: Boolean) = mutate { old ->
        if (save && old.none { it.matches(entry) }) listOf(TvSavedChannel(entry, System.currentTimeMillis(), favorite = false)) + old
        else if (!save) old.filterNot { it.matches(entry) } else old
    }
    suspend fun favorite(entry: TvCatalogEntry, value: Boolean) = mutate { old ->
        val saved = old.firstOrNull { it.matches(entry) }
        if (saved == null) listOf(TvSavedChannel(entry, System.currentTimeMillis(), favorite = value)) + old
        else old.map { if (it.id == saved.id) it.copy(favorite = value, favoriteAddedAt = if (value && !it.favorite) System.currentTimeMillis() else it.favoriteAddedAt) else it }
    }
    suspend fun customize(entry: TvCatalogEntry, name: String?, artwork: String?, displayTitle: String? = null) = mutate { old ->
        val existing = old.firstOrNull { it.matches(entry) } ?: TvSavedChannel(entry, System.currentTimeMillis(), favorite = false)
        existing.copy(customName = name?.trim()?.takeIf { it.isNotEmpty() }, artwork = artwork, displayTitle = if (displayTitle == null) existing.displayTitle else displayTitle.trim().take(128).takeIf { it.isNotEmpty() }).let { edited ->
            if (old.any { it.id == edited.id }) old.map { if (it.id == edited.id) edited else it } else listOf(edited) + old
        }
    }
    suspend fun reorder(ids: List<String>) = mutate { old ->
        if (ids.size != old.size || ids.toSet() != old.map { it.id }.toSet()) old
        else ids.map(old.associateBy { it.id }::getValue)
    }
    suspend fun refresh(entries: List<TvCatalogEntry>) = mutate { old ->
        val byKey = entries.associateBy(::tvKey)
        val byUrl = entries.associateBy { it.streamUrl }
        old.map { saved -> (byKey[tvKey(saved.entry)] ?: byUrl[saved.entry.streamUrl])?.let { saved.copy(entry = it) } ?: saved }
    }
    private suspend fun mutate(change: (List<TvSavedChannel>) -> List<TvSavedChannel>) {
        load()
        withContext(Dispatchers.IO) { lock.withLock {
            val next = change(channels)
            if (next == channels) return@withLock
            require(next.size <= 1000)
            val array = JSONArray()
            next.forEachIndexed { index, row ->
                require(TvNetwork.validUrl(row.entry.streamUrl) && row.name.length in 1..512 && row.name.none { it.isISOControl() })
                array.put(JSONObject().put("position", index).put("id", row.id).put("url", row.entry.streamUrl).put("name", row.entry.name).put("channelId", row.entry.channelId)
                    .put("logo", row.entry.logoUrl).put("group", row.entry.group).put("savedAt", row.savedAt)
                    .put("favoriteAddedAt", row.favoriteAddedAt).put("favorite", row.favorite).put("customName", row.customName).put("artwork", row.artwork).put("displayTitle", row.displayTitle))
            }
            val bytes = array.toString().toByteArray()
            require(bytes.size <= TvPlaylist.MAX_BYTES)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) } catch (failure: Exception) { file.failWrite(output); throw failure }
            val retired = channels.mapNotNull { it.artwork }.toSet() - next.mapNotNull { it.artwork }.toSet()
            channels = next
            discardArtworkFiles(retired)
        } }
    }
    suspend fun discardArtwork(names: Set<String>) {
        load()
        withContext(Dispatchers.IO) { lock.withLock { discardArtworkFiles(names) } }
    }
    private fun discardArtworkFiles(names: Set<String>) {
        val retained = channels.mapNotNull { it.artwork }.toSet()
        for (name in names - retained) if (name.matches(Regex("[a-f0-9-]+\\.png"))) {
            val target = File(artwork, name)
            runCatching { if (target.isFile && target.canonicalFile == File(artwork.canonicalFile, target.name)) target.delete() }
        }
    }

}

internal data class TvCatalog(val id: String, val name: String, val url: String)

internal class TvLibrary(private val app: GalleryApp) {
    val store = TvStore(app)
    var noticeVisible by mutableStateOf(false)
    var addingSource by mutableStateOf(false)
    var removingSource by mutableStateOf<TvCatalog?>(null)
    var selected by mutableStateOf<TvCatalogEntry?>(null)
    var editing by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var favoritePreview by mutableStateOf(false)
    var browsing by mutableStateOf(false)
    var group by mutableStateOf("sources")
    var catalog by mutableStateOf<TvCatalog?>(null)
    var query by mutableStateOf("")
    var catalogEntries: List<TvCatalogEntry> = emptyList()
        private set
    private var playingChannels: List<TvCatalogEntry> = emptyList()
    fun play(entry: TvCatalogEntry, slot: Int, confirmed: Boolean = false) {
        if (!store.acknowledged || !store.sourceAcknowledged) { noticeVisible = true; return }
        if (app.video.liveLoadingChannel != null) return
        if (app.video.liveChannel?.let(::tvKey) == tvKey(entry) && app.video.player != null && app.video.error == null) {
            app.video.toggle()
            return
        }
        if (!confirmed && app.video.liveChannel == null && app.video.player != null && app.video.playing.value) {
            pendingPlay = entry to slot; return
        }
        playingChannels = catalogEntries.takeIf { rows -> rows.any { tvKey(it) == tvKey(entry) } }
            ?: store.channels.map { it.entry }.takeIf { rows -> rows.any { tvKey(it) == tvKey(entry) } }
            ?: listOf(entry)
        app.video.playLive(entry, slot)
    }
    fun changeChannel(direction: Int, slot: Int) {
        if (app.video.liveLoadingChannel != null) return
        val current = app.video.liveChannel ?: return
        adjacentChannel(current, direction)?.let { select(it); app.video.playLive(it, slot) }
    }
    fun adjacentChannel(current: TvCatalogEntry, direction: Int): TvCatalogEntry? {
        val rows = playingChannels.takeIf { list -> list.any { tvKey(it) == tvKey(current) } } ?: catalogEntries.takeIf { list -> list.any { tvKey(it) == tvKey(current) } }
            ?: store.channels.map { it.entry }
        val index = rows.indexOfFirst { tvKey(it) == tvKey(current) }
        return if (index < 0 || rows.size < 2) null else rows[Math.floorMod(index + direction, rows.size)]
    }
    var pendingFavorite by mutableStateOf<Pair<TvCatalogEntry, Boolean>?>(null)
    var sleepResume by mutableStateOf<TvCatalogEntry?>(null)
    fun abandonTransientActions() { pendingPlay = null; pendingFavorite = null; sleepResume = null }
    fun select(entry: TvCatalogEntry) {
        if (selected?.let(::tvKey) != tvKey(entry)) {
            abandonTransientActions()
            app.sounds.play("navigate")
        }
        selected = entry
    }
    fun toggleSaved(entry: TvCatalogEntry) {
        app.catalog.scope.launch {
            try { store.set(entry, store.channels.none { it.matches(entry) }); app.sounds.play("save") }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { this@TvLibrary.failure = tvError(failure) }
        }
    }
    fun requestFavorite(entry: TvCatalogEntry) {
        abandonTransientActions()
        if (store.channels.any { it.matches(entry) && it.favorite }) pendingFavorite = entry to false
        else app.catalog.scope.launch {
            try { store.favorite(entry, true); app.sounds.play("favorite") }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { this@TvLibrary.failure = tvError(failure) }
        }
    }
    fun pauseForSleep() {
        val entry = app.video.liveChannel ?: app.video.liveLoadingChannel ?: return
        abandonTransientActions(); sleepResume = entry
        if (app.video.liveChannel != null) app.video.closeLive() else app.video.cancelLiveRequest()
    }
    var pendingPlay by mutableStateOf<Pair<TvCatalogEntry, Int>?>(null)
    var failure by mutableStateOf<String?>(null)
    var retryAction: (() -> Unit)? = null
    var announcement by mutableStateOf<Pair<TvCatalogEntry, Long>?>(null)
    var draftKey: String? = null
    var draftName by mutableStateOf("")
    var draftUrl by mutableStateOf("")
    var draftTitle by mutableStateOf("")
    var draftArtwork by mutableStateOf<String?>(null)
    var artworkPickerPending by mutableStateOf(false)
        private set
    private var artworkPickerEntry: TvCatalogEntry? = null
    private var artworkPickerEditing = false
    fun beginArtworkPicker() { artworkPickerEntry = selected; artworkPickerEditing = editing; artworkPickerPending = true }
    suspend fun finishArtworkPicker(uri: android.net.Uri?) {
        if (!artworkPickerPending) return
        val entry = artworkPickerEntry
        val wasEditing = artworkPickerEditing
        app.session.libraryModel()?.go("TV")
        selected = entry; editing = wasEditing; adding = !wasEditing
        try { if (uri != null) {
            val previous = draftArtwork
            draftArtwork = importArtwork(uri); revision++
            store.discardArtwork(setOfNotNull(previous))
        } }
        catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; this.failure = tvError(failure); retryAction = null }
        finally { artworkPickerPending = false }
    }
    fun beginDraft(entry: TvCatalogEntry?) {
        val key = entry?.let(::tvKey) ?: "new"
        if (draftKey == key) return
        val previous = draftArtwork
        if (previous != null) app.catalog.scope.launch { store.discardArtwork(setOf(previous)) }
        draftKey = key
        val saved = entry?.let { value -> store.channels.firstOrNull { it.matches(value) } }
        draftTitle = saved?.displayTitle.orEmpty()
        draftName = saved?.name ?: entry?.name.orEmpty(); draftUrl = entry?.streamUrl.orEmpty(); draftArtwork = saved?.artwork
    }
    fun closeDraft() {
        val discarded = draftArtwork
        draftKey = null; draftArtwork = null; adding = false; editing = false; artworkPickerPending = false
        if (discarded != null) app.catalog.scope.launch { store.discardArtwork(setOf(discarded)) }
    }
    fun restoreDraftArtwork() {
        val discarded = draftArtwork
        draftArtwork = null
        if (discarded != null) app.catalog.scope.launch { store.discardArtwork(setOf(discarded)) }
    }
    var revision by mutableIntStateOf(0)
    private val cache = File(app.cacheDir, "tv-catalogs")
    private val artwork = File(app.filesDir, "tv-user-artwork")
    private val thumbnails = File(app.cacheDir, "tv-thumbnails")
    private val imageFiles = Mutex()
    private val logoSlots = kotlinx.coroutines.sync.Semaphore(3)
    fun enter() { if (!artworkPickerPending) closeDraft(); catalogEntries = emptyList(); browsing = false; catalog = null; query = ""; favoritePreview = false; selected = selected?.takeIf { entry -> store.channels.any { it.matches(entry) } } ?: app.video.liveChannel ?: store.channels.firstOrNull()?.entry }
    fun editSelected() { selected?.let { if (store.channels.any { row -> row.matches(it) }) editing = true else requestFavorite(it) } }
    fun backNavigation(): Boolean {
        if (app.video.liveChannel != null && app.video.closePausedViewer()) return true
        return back() || app.preferences.state.value.tvInNavbar
    }
    fun back(): Boolean = when {
        addingSource -> { addingSource = false; true }
        removingSource != null -> { removingSource = null; true }
        editing || adding -> { closeDraft(); true }
        catalog != null -> { catalog = null; query = ""; true }
        browsing -> { browsing = false; query = ""; true }
        else -> false
    }
    suspend fun catalogs(): List<TvCatalog> = store.sources
    suspend fun searchSources(): List<TvCatalogEntry> = withContext(Dispatchers.IO) {
        val all = mutableListOf<TvCatalogEntry>()
        for (source in store.sources) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val parsed = TvPlaylist.parse(cached(source.url).inputStream())
            require(all.size + parsed.size <= TvPlaylist.MAX_CHANNELS)
            all += parsed
        }
        all.distinctBy(::tvKey).also { catalogEntries = it }
    }
    suspend fun entries(source: TvCatalog): List<TvCatalogEntry> = withContext(Dispatchers.IO) {
        TvPlaylist.parse(cached(source.url).inputStream()).map { it.copy(name = it.name.take(512)) }.distinctBy(::tvKey).also { catalogEntries = it; store.refresh(it) }
    }
    private suspend fun cached(url: String): ByteArray {
        cache.mkdirs()
        val key = tvDigest(url)
        val file = File(cache, key)
        fun existing() = file.takeIf { it.isFile && it.length() <= TvPlaylist.MAX_BYTES }?.readBytes()
        if (System.currentTimeMillis() - file.lastModified() < 6 * 60 * 60 * 1000L) existing()?.let { return it }
        return try {
            val bytes = TvNetwork.bytes(url, TvPlaylist.MAX_BYTES)
            // Bound disk use independently of the gallery's caches.
            val files = cache.listFiles().orEmpty().sortedBy { it.lastModified() }
            if (files.sumOf { it.length() } + bytes.size > 30L * 1024 * 1024) files.take(files.size / 2 + 1).forEach { it.delete() }
            val atomic = AtomicFile(file); val out = atomic.startWrite()
            try { out.write(bytes); atomic.finishWrite(out) } catch (error: Exception) { atomic.failWrite(out); throw error }
            bytes
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { existing() ?: throw failure }
    }
    private val logoIndexLock = Mutex()
    private var logoIndexVersion = emptyList<String>()
    private var logoIndex = emptyMap<String, String>()
    private suspend fun cachedLogo(channelId: String?): String? {
        if (channelId == null) return null
        return logoIndexLock.withLock {
            val files = store.sources.map { File(cache, tvDigest(it.url)) }.filter { it.isFile && it.length() <= TvPlaylist.MAX_BYTES }
            val version = files.map { "${it.name}/${it.lastModified()}/${it.length()}" }
            if (version != logoIndexVersion) {
                val next = linkedMapOf<String, String>()
                for (file in files) {
                    val entries = runCatching { file.inputStream().use(TvPlaylist::parse) }.getOrDefault(emptyList())
                    for (entry in entries) if (next.size < TvPlaylist.MAX_CHANNELS && entry.channelId != null && entry.logoUrl != null) next.putIfAbsent(entry.channelId, entry.logoUrl)
                }
                logoIndex = next; logoIndexVersion = version
            }
            logoIndex[channelId]
        }
    }
    suspend fun image(entry: TvCatalogEntry, overrideArtwork: String? = null, allowRemote: Boolean = true, maxSide: Int = 512, logoOnly: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        app.tvCacheReady.await()
        logoSlots.acquire()
        try {
            val custom = if (logoOnly) null else overrideArtwork ?: store.channels.firstOrNull { it.matches(entry) }?.artwork
            val local = custom?.takeIf { it.matches(Regex("[a-f0-9-]+\\.png")) }?.let { File(artwork, it) }
            val target = local?.takeIf { it.isFile } ?: File(thumbnails, tvKey(entry) + ".png")
            imageFiles.withLock {
                if (target.isFile) decodeTvArtworkFile(target, maxSide)?.let { target.setLastModified(System.currentTimeMillis()); return@withContext it }
            }
            if (!allowRemote) return@withContext null
            val url = entry.logoUrl ?: store.channels.firstOrNull { it.matches(entry) }?.entry?.logoUrl
                ?: cachedLogo(entry.channelId) ?: return@withContext null
            val bytes = TvNetwork.bytes(url, 2 * 1024 * 1024)
            val bitmap = decodeTvArtwork(bytes) ?: return@withContext null
            imageFiles.withLock {
                thumbnails.mkdirs()
                val atomic = AtomicFile(File(thumbnails, tvKey(entry) + ".png"))
                val output = atomic.startWrite()
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); atomic.finishWrite(output) }
                catch (failure: Exception) { atomic.failWrite(output); throw failure }
                trimTvThumbnails(thumbnails)
            }
            if (maxOf(bitmap.width, bitmap.height) > maxSide) {
                val ratio = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            } else bitmap
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null } finally { logoSlots.release() }
    }
    suspend fun clearThumbnails(): CacheResult = withContext(Dispatchers.IO) {
        app.tvCacheReady.await()
        imageFiles.withLock {
            var count = 0; var bytes = 0L; var failed = 0
            for (file in thumbnails.listFiles().orEmpty()) if (file.name.matches(Regex("[a-f0-9]{64}\\.png")) && file.isFile && file.canonicalFile == File(thumbnails.canonicalFile, file.name)) {
                val size = file.length()
                if (file.delete()) { count++; bytes += size } else failed++
            }
            CacheResult(count, bytes, failed)
        }
    }
    suspend fun importArtwork(uri: android.net.Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 8 * 1024 * 1024) throw TvNetworkFailure("LARGE"); out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: error("Artwork unavailable")
        val bitmap = decodeTvArtwork(bytes) ?: error("Invalid artwork")
        artwork.mkdirs(); val name = java.util.UUID.randomUUID().toString() + ".png"
        File(artwork, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }
}

internal fun decodeTvArtwork(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 || bounds.outWidth.toLong() * bounds.outHeight > 32_000_000 ||
            bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.outWidth / options.inSampleSize > 512 || bounds.outHeight / options.inSampleSize > 512) options.inSampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }


/** Only generated thumbnails: retain offline previews, evict oldest files at seven days or 20 MiB. */
@Synchronized
internal fun trimTvThumbnails(directory: File, now: Long = System.currentTimeMillis(), limit: Long = 20L * 1024 * 1024) {
    val files = directory.listFiles().orEmpty().filter {
        it.name.matches(Regex("[a-f0-9]{64}\\.png")) && it.isFile && it.canonicalFile == File(directory.canonicalFile, it.name)
    }.sortedBy { it.lastModified() }
    var bytes = files.sumOf { it.length() }
    for (file in files) if (now - file.lastModified() > 7 * 24 * 60 * 60 * 1000L || bytes > limit) {
        val size = file.length()
        if (file.delete()) bytes -= size
    }
}

internal fun decodeTvArtworkFile(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 || bounds.outWidth.toLong() * bounds.outHeight > 32_000_000) return null
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxSide.coerceAtLeast(1)) options.inSampleSize *= 2
    return BitmapFactory.decodeFile(file.path, options)
}
