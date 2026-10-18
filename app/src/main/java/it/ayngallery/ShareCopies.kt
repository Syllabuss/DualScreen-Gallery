package it.ayngallery

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Raster-only conversion is explicit; animations/video/audio never pass through a static decoder. */
data class ShareCopyOptions(val longest: Int = 0, val format: String = "PNG", val quality: Int = 92, val allowGame: Boolean = false) {
    init { ImageRecipe(longest = longest, format = format, quality = quality) }
    fun recipe() = ImageRecipe(longest = longest, format = format, quality = quality)
    fun encode() = JSONObject().put("v", 1).put("longest", longest).put("format", format).put("quality", quality).put("allowGame", allowGame).toString()
    companion object {
        fun decode(raw: String): ShareCopyOptions {
            require(raw.length <= 4096)
            val data = JSONObject(raw); require(data.getInt("v") == 1)
            return ShareCopyOptions(data.getInt("longest"), data.getString("format"), data.getInt("quality"), data.optBoolean("allowGame", false))
        }
    }
}
data class SharedImage(val sourceId: String, val name: String, val uri: String, val width: Int, val height: Int,
    val size: Long, val hash: String, val sourceHash: String)
data class PreparedShare(val id: String, val images: List<SharedImage>)

/** Separate private copy cache, with a durable lease before IO and read-only publication after review. */
class ShareCopies(private val catalog: Catalog, private val now: () -> Long = System::currentTimeMillis,
    private val delete: (File) -> Boolean = File::delete, private val gameMode: () -> Boolean = { false }) {
    private val context get() = catalog.context
    private val active = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    companion object {
        const val RETENTION = 24 * 60 * 60 * 1000L
        private const val MAX_FILE = 64L * 1024 * 1024
        private const val MAX_BATCH = 256L * 1024 * 1024
        // AtomicFile recovery is not thread-safe: the thumbnail provider reads during publication.
        private val ledgerLock = Any()
        private val names = Regex("image-[0-9]{3}\\.(png|jpg)")
        private fun uuid(id: String) { require(UUID.fromString(id).toString() == id) }
        internal fun directory(context: Context, id: String): File {
            uuid(id)
            val parent = File(context.filesDir, "share-copies").canonicalFile
            return File(parent, id).canonicalFile.also { require(it.parentFile == parent) }
        }
        private fun ledger(context: Context, id: String): AtomicFile {
            uuid(id)
            val parent = File(context.filesDir, "share-copy-ledgers").canonicalFile
            return AtomicFile(File(parent, "$id.json").canonicalFile.also { require(it.parentFile == parent) })
        }
        private fun readLedger(context: Context, id: String): JSONObject = synchronized(ledgerLock) {
            val file = ledger(context, id)
            val text = file.openRead().use { input ->
                val buffer = ByteArray(8192); val bytes = ByteArrayOutputStream()
                while (true) { val count = input.read(buffer); if (count < 0) break; require(bytes.size() + count <= 512 * 1024); bytes.write(buffer, 0, count) }
                bytes.toString(Charsets.UTF_8.name())
            }
            val root = JSONObject(text); require(root.getInt("v") == 1 && root.getString("id") == id)
            val created = root.getLong("created"); val expires = root.getLong("expires")
            require(created >= 0 && expires >= created && expires - created <= 2 * RETENTION)
            root
        }
        private fun writeLedger(context: Context, id: String, data: JSONObject) = synchronized(ledgerLock) {
            val file = ledger(context, id); file.baseFile.parentFile!!.mkdirs()
            val output = file.startWrite()
            try { output.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
        /** Also called by FileProvider: unreviewed work/ledger files can never be opened by a recipient. */
        internal fun allowed(context: Context, uri: Uri, own: Boolean) {
            val path = uri.pathSegments
            if (own && uri.authority == "${context.packageName}.share" && path.size == 2 && path[0] == "network_previews") {
                require(path[1].matches(Regex("[a-f0-9]{64}\\.[a-z0-9]{1,12}")))
                val directory = File(context.filesDir, "network-preview").canonicalFile
                val file = File(directory, path[1]).canonicalFile
                require(file.parentFile == directory && file.isFile)
                return
            }
            require(uri.scheme == "content" && uri.authority == "${context.packageName}.share" && path.size == 3 && path[0] == "copies" && names.matches(path[2]))
            val data = readLedger(context, path[1])
            require(System.currentTimeMillis() <= data.getLong("expires")) { localized(R.string.text_the_temporary_copy_expired_2d727d, "La copia temporanea è scaduta") }
            require(data.getString("phase") == "PUBLISHED" || (own && data.getString("phase") == "PREPARED")) { localized(R.string.text_copy_not_published_855942, "Copia non pubblicata") }
            val images = data.getJSONArray("images")
            require((0 until images.length()).any { images.getJSONObject(it).getString("name") == path[2] })
        }
    }
    private fun file(id: String, name: String): File {
        require(names.matches(name))
        val parent = directory(context, id)
        return File(parent, name).canonicalFile.also { require(it.parentFile == parent) }
    }
    private suspend fun digest(input: InputStream): FileDigest {
        val sha = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(256 * 1024); var size = 0L
        input.use { while (true) { currentCoroutineContext().ensureActive(); val count = it.read(buffer); if (count < 0) break
            sha.update(buffer, 0, count); size += count } }
        return FileDigest(size, sha.digest().joinToString("") { "%02x".format(it) })
    }
    private fun encoded(images: List<SharedImage>) = JSONArray().apply { images.forEach { image -> put(JSONObject()
        .put("source", image.sourceId).put("name", image.name).put("width", image.width).put("height", image.height)
        .put("size", image.size).put("hash", image.hash).put("sourceHash", image.sourceHash)) } }
    private fun images(id: String, data: JSONObject): List<SharedImage> = data.getJSONArray("images").let { array ->
        require(array.length() in 1..128)
        (0 until array.length()).map { index -> val entry = array.getJSONObject(index); val name = entry.getString("name")
            val output = file(id, name)
            SharedImage(entry.getString("source"), name, FileProvider.getUriForFile(context, "${context.packageName}.share", output).toString(),
                entry.getInt("width"), entry.getInt("height"), entry.getLong("size"), entry.getString("hash"), entry.getString("sourceHash"))
                .also { require(it.width > 0 && it.height > 0 && it.size in 1..MAX_FILE && it.hash.matches(Regex("[a-f0-9]{64}")) && it.sourceHash.matches(Regex("[a-f0-9]{64}"))) }
        }
    }
    suspend fun prepare(request: ExternalRequest, progress: suspend (Int, Int) -> Unit = { _, _ -> }): PreparedShare = withContext(Dispatchers.IO) {
        val options = request.copy ?: error(localized(R.string.text_choose_copy_transformations_923466, "Scegli le trasformazioni della copia"))
        require(!gameMode() || options.allowGame) { localized(R.string.text_game_mode_allow_preparation_or_try_later_f52946, "Modalità Gioco: autorizza la preparazione o riprova più tardi") }
        val readable = ExternalMedia.readable(catalog, request)
        val id = UUID.randomUUID().toString(); val created = now()
        val root = JSONObject().put("v", 1).put("id", id).put("created", created).put("expires", created + RETENTION)
            .put("phase", "PREPARING").put("request", JSONObject(request.encode())).put("images", JSONArray())
        active.add(id)
        try {
            writeLedger(context, id, root)
            check(directory(context, id).mkdirs()) { localized(R.string.text_temporary_storage_unavailable_e47ae5, "Spazio temporaneo non disponibile") }
            val results = mutableListOf<SharedImage>(); var total = 0L
            val documents = FileDocuments(context)
            for ((index, target) in readable.withIndex()) {
                currentCoroutineContext().ensureActive(); progress(index, readable.size)
                require(!gameMode() || options.allowGame) { localized(R.string.text_preparation_interrupted_for_game_mode_originals_f79ced, "Preparazione interrotta per modalità Gioco; originali conservati") }
                val uri = target.uri.toUri(); val original = digest(documents.input(uri))
                val recipe = options.recipe(); val bitmap = ImageEditing.render(context.contentResolver, uri, recipe)
                val name = "image-${(index + 1).toString().padStart(3, '0')}.${recipe.extension}"
                val output = file(id, name); val cancellation = currentCoroutineContext()
                try {
                    require(StatFs(context.filesDir.absolutePath).availableBytes >= bitmap.allocationByteCount.toLong() + 10L * 1024 * 1024) { localized(R.string.text_insufficient_space_for_copies_originals_preserve_ee396b, "Spazio insufficiente per le copie; originali conservati") }
                    FileOutputStream(output).use { native ->
                        var written = 0L; var checked = -1L
                        val bounded = object : OutputStream() {
                            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                cancellation.ensureActive(); require(written + length <= MAX_FILE && total + written + length <= MAX_BATCH) { localized(R.string.text_copies_too_large_reduce_dimensions_or_image_coun_db61cb, "Copie troppo grandi: riduci dimensioni o numero di immagini") }
                                if (written / (1024 * 1024) != checked) {
                                    require(StatFs(context.filesDir.absolutePath).availableBytes >= 10L * 1024 * 1024) { localized(R.string.text_temporary_storage_full_34bbc4, "Spazio temporaneo esaurito") }
                                    checked = written / (1024 * 1024)
                                }
                                native.write(bytes, offset, length); written += length
                            }
                            override fun flush() = native.flush()
                        }
                        ImageEditing.write(bitmap, recipe, bounded); native.fd.sync()
                    }
                    val outputDigest = digest(output.inputStream()); total += outputDigest.bytes
                    require(outputDigest.bytes > 0)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(output.absolutePath, bounds)
                    require(bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height && bounds.outMimeType == recipe.mime) { localized(R.string.text_copy_not_verified_b25152, "Copia non verificata") }
                    val after = documents.stat(uri)
                    require(after.name == target.name && after.mime == target.mime && !DocumentVersions.changed(target.size, target.modified, after.size, after.modified) && digest(documents.input(uri)) == original) { localized(R.string.text_the_source_changed_during_preparation_c794a3, "La sorgente è cambiata durante la preparazione") }
                    results += SharedImage(target.id, name, FileProvider.getUriForFile(context, "${context.packageName}.share", output).toString(), bitmap.width, bitmap.height, outputDigest.bytes, outputDigest.sha256, original.sha256)
                    root.put("images", encoded(results)); writeLedger(context, id, root)
                } finally { bitmap.recycle() }
            }
            catalog.writer.withLock { ExternalMedia.readable(catalog, request); root.put("phase", "PREPARED"); writeLedger(context, id, root) }
            currentCoroutineContext().ensureActive(); progress(results.size, results.size)
            PreparedShare(id, results)
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { discard(id) }.exceptionOrNull()?.let { error.addSuppressed(it) } }
            throw error
        } finally { active.remove(id) }
    }
    suspend fun read(id: String, request: ExternalRequest): PreparedShare = withContext(Dispatchers.IO) {
        val root = readLedger(context, id)
        require(root.getString("phase") == "PREPARED" && now() <= root.getLong("expires") && ExternalRequest.decode(root.getJSONObject("request").toString()) == request) { localized(R.string.text_copies_expired_or_selection_changed_prepare_agai_88a079, "Copie scadute o selezione modificata: preparale di nuovo") }
        ExternalMedia.readable(catalog, request)
        val outputs = images(id, root)
        require(outputs.map { it.sourceId } == request.targets.map { it.id })
        for (image in outputs) require(digest(file(id, image.name).inputStream()) == FileDigest(image.size, image.hash)) { localized(R.string.text_temporary_copy_changed_prepare_again_04811b, "Copia temporanea modificata: preparala di nuovo") }
        PreparedShare(id, outputs)
    }
    suspend fun publish(id: String, request: ExternalRequest): Intent = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val checked = read(id, request); val documents = FileDocuments(context)
            val sources = ExternalMedia.readable(catalog, request)
            for ((index, target) in sources.withIndex()) require(digest(documents.input(target.uri.toUri())).sha256 == checked.images[index].sourceHash) { localized(R.string.text_the_original_changed_prepare_copies_again_92bdd2, "L'originale è cambiato: prepara nuovamente le copie") }
            ExternalMedia.readable(catalog, request); currentCoroutineContext().ensureActive()
            val root = readLedger(context, id); root.put("phase", "PUBLISHED").put("expires", now() + RETENTION); writeLedger(context, id, root)
            val outputs = checked.images.mapIndexed { index, image -> request.targets[index].copy(uri = image.uri, name = image.name,
                mime = request.copy!!.recipe().mime, kind = "IMAGE", size = image.size, modified = 0) }
            ExternalMedia.chooser(ExternalMedia.intent(ExternalAction.SHARE, outputs), localized(R.string.text_share_image_copies_63f7df, "Condividi copie d'immagine"))
        }
    }
    suspend fun discard(id: String, includePublished: Boolean = false) = withContext(Dispatchers.IO) {
        catalog.writer.withLock { remove(id, includePublished) }
    }
    private fun remove(id: String, includePublished: Boolean) {
        val data = runCatching { readLedger(context, id) }.getOrNull()
        if (!includePublished && data?.optString("phase") == "PUBLISHED") return
        val directory = directory(context, id)
        data?.let { it.put("phase", "CLEANING"); writeLedger(context, id, it) }
        try {
            for (child in directory.listFiles().orEmpty()) {
                require(names.matches(child.name) && child.canonicalFile.parentFile == directory && child.isFile) { localized(R.string.text_unrecognized_temporary_file_cleanup_paused_257383, "Temporaneo non riconosciuto: pulizia sospesa") }
                check(delete(child)) { localized(R.string.text_temporary_copy_cleanup_denied_61395b, "Pulizia di una copia temporanea negata") }
            }
            if (directory.exists()) check(delete(directory)) { localized(R.string.text_temporary_folder_cleanup_denied_d615be, "Pulizia della cartella temporanea negata") }
            synchronized(ledgerLock) { ledger(context, id).delete() }
        } catch (error: Exception) { data?.let { it.put("phase", "CLEANUP_FAILED"); writeLedger(context, id, it) }; throw error }
    }
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        catalog.writer.withLock {
            val roots = File(context.filesDir, "share-copies").listFiles().orEmpty().map { it.name } +
                File(context.filesDir, "share-copy-ledgers").listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9-]+\\.json(\\.new|\\.bak)?")) }.map { it.name.substringBefore(".json") }
            for (id in roots.distinct()) {
                currentCoroutineContext().ensureActive()
                if (runCatching { uuid(id) }.isFailure || id in active) continue
                val root = runCatching { readLedger(context, id) }.getOrNull()
                val directory = directory(context, id)
                val lease = ledger(context, id).baseFile
                val fallback = maxOf(directory.lastModified(), directory.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0,
                    lease.lastModified(), File("${lease.path}.new").lastModified(), File("${lease.path}.bak").lastModified())
                if (root?.optString("phase") == "CLEANUP_FAILED" || (root != null && now() > root.getLong("expires")) ||
                    (root == null && now() >= fallback && now() - fallback > RETENTION)) remove(id, true)
            }
        }
    }
    fun maintenance() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("share-copy-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ShareCleanupWorker>(6, TimeUnit.HOURS).build())
        catalog.scope.launch { runCatching { cleanup() } }
    }
}

/** Restricted root and leases supplement Android's exact URI grant; no writes, private manifests or preparing images. */
class ShareFileProvider : FileProvider() {
    private fun check(uri: Uri) = ShareCopies.allowed(requireNotNull(context), uri, Binder.getCallingUid() == android.os.Process.myUid())
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? { if (mode != "r") throw SecurityException(localized(R.string.text_shared_copies_are_read_only_1aeab8, "Le copie condivise sono in sola lettura")); check(uri); return super.openFile(uri, mode) }
    override fun getType(uri: Uri): String? { check(uri); return super.getType(uri) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor { check(uri); return super.query(uri, projection, selection, selectionArgs, sortOrder) }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException(localized(R.string.text_shared_copies_are_read_only_1aeab8, "Le copie condivise sono in sola lettura"))
}
class ShareCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { (applicationContext as GalleryApp).shares.cleanup(); Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
}
