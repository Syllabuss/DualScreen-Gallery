package it.ayngallery

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Network jobs share the existing Room journal; a failed job never authorizes source deletion. */
internal class ExplorerTransfer(private val app: GalleryApp) {
    val running = kotlinx.coroutines.flow.MutableStateFlow(false)
    val bytes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val name = kotlinx.coroutines.flow.MutableStateFlow("")
    val completed = kotlinx.coroutines.flow.MutableStateFlow(0)
    val total = kotlinx.coroutines.flow.MutableStateFlow(0)
    val current = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    var job: Job? = null
    fun cancel() { job?.cancel() }

    suspend fun copy(entry: ExplorerEntry, destination: ExplorerLocation, move: Boolean,
        stat: () -> ExplorerEntry, read: ((InputStream) -> FileDigest) -> FileDigest,
        publish: suspend (FileOperation, java.io.File, FileDigest, suspend (FileOperation) -> Unit) -> Pair<String, FileDigest>, delete: (FileDigest) -> Unit, initial: FileOperation? = null): FileOperation {
        require(!entry.directory && entry.readable && entry.size != null && entry.size >= 0)
        FileNames.validateBase(entry.name)
        val id = initial?.id ?: UUID.randomUUID().toString()
        var op = initial ?: FileOperation(id, id, if (move) "NETWORK_MOVE" else "NETWORK_COPY", entry.id,
            entry.location.providerId, entry.location.directoryId, entry.name, "application/octet-stream",
            entry.size, entry.modified ?: 0, destination.directoryId, destination.providerId, entry.name, true, id)
        suspend fun save(value: FileOperation) { op = value.copy(updatedAt = System.currentTimeMillis()); withContext(NonCancellable) { app.catalog.dao.saveOperation(op) } }
        val folder = java.io.File(app.filesDir, "network-stage").apply { check(isDirectory || mkdirs()) }
        require(entry.size < android.os.StatFs(folder.path).availableBytes / 2) { "Not enough space for verified staging" }
        val staged = java.io.File(folder, "$id.part")
        save(op)
        current.value = id; name.value = entry.name
        try {
            val before = stat()
            check(before.size == entry.size && before.modified == entry.modified) { "Source changed" }
            save(op.copy(phase = "COPYING", temporaryUri = staged.toURI().toString(), digest = null, bytes = 0))
            val owner = currentCoroutineContext()
            var nextSpaceCheck = 0L
            val hash = staged.outputStream().use { output -> read { input -> networkDigest(input, output) { count ->
                owner.ensureActive()
                require(count <= entry.size) { "Source exceeds declared size" }
                if (count >= nextSpaceCheck) {
                    check(android.os.StatFs(folder.path).availableBytes > 8L * 1024 * 1024) { "Insufficient staging space" }
                    nextSpaceCheck = count + 1024 * 1024
                }
                bytes.value = count
            } } }
            check(hash.bytes == entry.size) { "Incomplete source" }
            save(op.copy(phase = "VERIFIED", digest = hash.sha256, bytes = hash.bytes))
            owner.ensureActive()
            val (target, verified) = publish(op, staged, hash, ::save)
            check(hash == verified) { "Destination verification failed" }
            save(op.copy(phase = "PUBLISHED", publishedUri = target))
            owner.ensureActive()
            if (move) {
                val after = stat()
                check(after.size == before.size && after.modified == before.modified) { "Source changed" }
                check(read { networkDigest(it, check = { owner.ensureActive(); require(it <= entry.size) { "Source exceeds declared size" } }) } == hash) { "Source changed" }
                save(op.copy(phase = "REMOVING_SOURCE", sourceDeletionStartedAt = System.currentTimeMillis()))
                delete(hash)
                save(op.copy(sourceDeletedAt = System.currentTimeMillis()))
            }
            save(op.copy(phase = "COMPLETED"))
            staged.delete() // Completed verified results do not become failures because private cleanup was denied.
        } catch (cancelled: CancellationException) {
            save(op.copy(phase = "INTERRUPTED")); throw cancelled
        } catch (failure: Exception) {
            logExplorerFailure(failure, op.phase)
            save(op.copy(phase = if (op.publishedUri != null) "PARTIAL" else "FAILED", message = localized(R.string.explorer_transfer_error, "Operazione interrotta. Controlla originali e copie prima di riprovare.")))
            throw failure
        } finally {
            if (op.digest == null) staged.delete() // An unverified partial is always re-read on retry.
            current.value = null
        }
        return op
    }
}

internal fun networkDigest(input: InputStream, output: OutputStream? = null, check: (Long) -> Unit = {}): FileDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        check(total)
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        check(total + count) // Validate limits before writing even one excess byte.
        output?.write(buffer, 0, count); digest.update(buffer, 0, count); total += count
    }
    output?.flush()
    return FileDigest(total, digest.digest().joinToString("") { "%02x".format(it) })
}
