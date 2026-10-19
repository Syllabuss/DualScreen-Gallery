package it.ayngallery

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One browsing session for both displays. Passwords stay transient; transfers use the Room journal. */
internal class ExplorerSession(private val context: Context) {
    private val preferences = context.getSharedPreferences("network-explorer", Context.MODE_PRIVATE)
    private val draftAddress = androidx.compose.runtime.mutableStateOf(preferences.getString("address", "").orEmpty())
    private val draftUser = androidx.compose.runtime.mutableStateOf(preferences.getString("user", "").orEmpty())
    val lastAddress get() = draftAddress.value
    val lastUser get() = draftUser.value
    fun draftConnection(address: String, user: String) { draftAddress.value = address; draftUser.value = user }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(ExplorerNavigation())
    val state = mutable.asStateFlow()
    val grids = MutableStateFlow(preferences.getStringSet("grids", emptySet()).orEmpty().mapNotNull { runCatching { ExplorerSide.valueOf(it) }.getOrNull() }.toSet())
    fun grid(side: ExplorerSide, enabled: Boolean) {
        grids.value = if (enabled) grids.value + side else grids.value - side
        preferences.edit().putStringSet("grids", grids.value.map { it.name }.toSet()).apply()
    }
    val actions = androidx.compose.runtime.mutableStateOf<ExplorerSide?>(null)
    fun options(side: ExplorerSide) {
        active(side)
        val pane = mutable.value.pane(side)
        pane.entries.firstOrNull { it.id == pane.focusedId }?.let { entry ->
            if (pane.selected.values.none { it.id == entry.id && it.location == entry.location }) select(side, entry)
            actions.value = side
        }
    }
    val ftpTransfersVisible = androidx.compose.runtime.mutableStateOf(false)
    val jobsVisible = androidx.compose.runtime.mutableStateOf(false)
    val errors = MutableStateFlow<Map<ExplorerSide, Int>>(emptyMap())
    var browsing = androidx.compose.runtime.mutableStateOf(false)
        private set
    val connectionForm = androidx.compose.runtime.mutableStateOf(false)
    val localFolderName = MutableStateFlow("")
    private var local: ExplorerSafFolder? = null
    private var remote: ExplorerSmbFolder? = null
    private val remoteLock = Mutex()
    private val jobs = mutableMapOf<ExplorerSide, Job>()
    private val scroll = mutableMapOf<Pair<ExplorerSide, ExplorerLocation>, Pair<Int, Int>>()
    fun position(side: ExplorerSide, location: ExplorerLocation?) = location?.let { scroll[side to it] } ?: (0 to 0)
    fun position(side: ExplorerSide, location: ExplorerLocation, index: Int, offset: Int) { scroll[side to location] = index to offset }

    val transfers = ExplorerTransfer(context.applicationContext as GalleryApp)
    val transferRequest = androidx.compose.runtime.mutableStateOf<Pair<ExplorerSide, Boolean>?>(null)
    fun transfer(side: ExplorerSide, move: Boolean, initial: FileOperation? = null) {
        if (transfers.running.value) return
        val snapshot = mutable.value
        val source = snapshot.pane(side).frozenSelection()
        val targetSide = if (side == ExplorerSide.LOCAL) ExplorerSide.REMOTE else ExplorerSide.LOCAL
        val destination = if (initial != null) ExplorerLocation(checkNotNull(initial.destinationAlbumId), checkNotNull(initial.destinationParent)) else snapshot.pane(targetSide).location ?: return
        if (initial == null && source.isEmpty()) return
        val localSource = local ?: return
        val remoteSource = remote ?: return
        val app = context.applicationContext as GalleryApp
        val batch = initial?.id ?: java.util.UUID.randomUUID().toString()
        var parent = initial
        app.sounds.play("network-transfer")
        transfers.running.value = true; transfers.completed.value = 0; transfers.total.value = 0
        transfers.job = scope.launch {
            suspend fun saveParent(phase: String, manifest: org.json.JSONObject? = null) {
                parent = checkNotNull(parent).copy(phase = phase, recipe = manifest?.toString() ?: parent!!.recipe, updatedAt = System.currentTimeMillis())
                withContext(NonCancellable) { app.catalog.dao.saveOperation(parent!!) }
            }
            try { withContext(Dispatchers.IO) {
                val manifest = initial?.recipe?.let { org.json.JSONObject(it) }
                    ?: org.json.JSONObject().put("trees", explorerTreeJson(explorerTree(source) { location -> if (side == ExplorerSide.LOCAL) localSource.list(location) else remoteSource.list(location) })).put("folders", org.json.JSONObject())
                val frozen = explorerTreeFromJson(manifest.getJSONArray("trees"))
                require(frozen.isNotEmpty() && frozen.all { it.entry.location.providerId == snapshot.pane(side).location?.providerId })
                if (parent == null) {
                    val e = frozen.first().entry
                    parent = FileOperation(batch, batch, if (move) "NETWORK_MOVE_BATCH" else "NETWORK_COPY_BATCH", e.id, e.location.providerId,
                        e.location.directoryId, e.name, "application/octet-stream", 0, 0, destination.directoryId, destination.providerId, e.name, true, batch)
                }
                fun size(tree: ExplorerTree): Long = if (tree.entry.directory) tree.children.fold(0L) { sum, child -> Math.addExact(sum, size(child)) } else checkNotNull(tree.entry.size)
                parent = parent!!.copy(expectedSize = frozen.fold(0L) { sum, tree -> Math.addExact(sum, size(tree)) })
                saveParent("PREPARING", manifest)
                val root = if (targetSide == ExplorerSide.LOCAL) localSource.resolveDirectory(destination.directoryId) else remoteSource.resolveDirectory(destination.directoryId)
                check(root.providerId == destination.providerId)
                val folders = manifest.getJSONObject("folders")
                val prepared = mutableListOf<Pair<ExplorerEntry, Pair<ExplorerLocation, FileOperation>>>()
                suspend fun prepare(tree: ExplorerTree, at: ExplorerLocation) {
                    ensureActive()
                    val e = tree.entry
                    require(e.location.providerId == parent!!.sourceKey)
                    if (e.directory) {
                        val created = if (folders.has(e.id)) {
                            if (targetSide == ExplorerSide.LOCAL) localSource.resolveDirectory(folders.getString(e.id)) else remoteSource.resolveDirectory(folders.getString(e.id))
                        } else {
                            val names = if (targetSide == ExplorerSide.LOCAL) localSource.list(at).map { it.name }.toSet() else remoteSource.list(at).map { it.name }.toSet()
                            val name = FileNames.available(e.name, names, true)
                            val made = if (targetSide == ExplorerSide.LOCAL) {
                                check(localSource.writable(at))
                                localSource.registerDirectory(at, app.files.createFtpDirectory(Uri.parse(at.directoryId), name).toString())
                            } else {
                                val id = java.util.UUID.randomUUID().toString()
                                val job = FileOperation(id, batch, "NETWORK_MKDIR", e.id, e.location.providerId, e.location.directoryId, name,
                                    android.provider.DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, at.directoryId, at.providerId, name, true, id)
                                app.catalog.dao.saveOperation(job)
                                val result = remoteSource.directory(at, name)
                                app.catalog.dao.saveOperation(job.copy(phase = "COMPLETED", publishedUri = result.directoryId)); result
                            }
                            folders.put(e.id, made.directoryId); saveParent("PREPARING", manifest); made
                        }
                        tree.children.forEach { prepare(it, created) }
                        return
                    }
                    val id = java.util.UUID.nameUUIDFromBytes("$batch/${e.location.providerId}/${e.id}".toByteArray()).toString()
                    val saved = app.catalog.dao.operation(id)
                    if (saved?.phase == "COMPLETED") { transfers.completed.value++; return }
                    val from = if (side == ExplorerSide.LOCAL) localSource.resolveDirectory(e.location.directoryId) else remoteSource.resolveDirectory(e.location.directoryId)
                    val current = (if (side == ExplorerSide.LOCAL) localSource.list(from) else remoteSource.list(from)).singleOrNull { !it.directory && it.id == e.id }
                    if (current == null && saved != null && reconcileMissingSource(saved, at, targetSide, localSource, remoteSource)) { transfers.completed.value++; return }
                    checkNotNull(current)
                    check(current.size == e.size && current.modified == e.modified)
                    val queued = saved ?: FileOperation(id, batch, if (move) "NETWORK_MOVE" else "NETWORK_COPY", e.id, e.location.providerId, e.location.directoryId,
                        e.name, "application/octet-stream", e.size ?: error("Unknown size"), e.modified ?: 0, at.directoryId, at.providerId, e.name, true, id)
                    app.catalog.dao.saveOperation(queued)
                    prepared += current to (at to queued)
                }
                frozen.forEach { prepare(it, root) }
                transfers.total.value = prepared.size + transfers.completed.value
                saveParent("COPYING", manifest)
                for ((entry, target) in prepared) {
                    val (at, queued) = target
                    transfers.copy(entry, at, move,
                        stat = { if (side == ExplorerSide.LOCAL) localSource.stat(entry) else remoteSource.stat(entry) },
                        read = { action -> if (side == ExplorerSide.LOCAL) localSource.read(entry, action) else remoteSource.read(entry.id, action) },
                        publish = { op, stage, digest, save -> publishTo(targetSide, at, entry.name, op, stage, digest, save, localSource, remoteSource, true) },
                        delete = { digest -> if (side == ExplorerSide.LOCAL) localSource.delete(entry, digest) else remoteSource.deleteVerified(entry, digest) }, initial = queued.copy(cancelRequested = false))
                    transfers.completed.value++
                    parent = parent!!.copy(bytes = parent!!.bytes + (entry.size ?: 0))
                    saveParent("COPYING")
                }
                saveParent("COMPLETED")
            }
                mutable.value = mutable.value.update(side) { it.copy(selected = emptyMap()) }
                refresh(ExplorerSide.LOCAL); refresh(ExplorerSide.REMOTE)
            } catch (cancelled: CancellationException) { if (parent != null) saveParent("INTERRUPTED"); throw cancelled }
            catch (failure: Exception) { logExplorerFailure(failure, parent?.phase ?: "PREPARING"); if (parent != null) saveParent("PARTIAL"); errors.value = errors.value + (side to R.string.explorer_transfer_error) }
            finally { transfers.running.value = false }
        }
    }
    private suspend fun reconcileMissingSource(op: FileOperation, at: ExplorerLocation, targetSide: ExplorerSide, localSource: ExplorerSafFolder, remoteSource: ExplorerSmbFolder): Boolean {
        if (op.publishedUri == null || op.digest == null || (op.kind == "NETWORK_MOVE" && op.sourceDeletionStartedAt == null)) return false
        val expected = FileDigest(op.bytes, op.digest)
        val (_, actual) = publishTo(targetSide, at, op.originalName, op, java.io.File(context.filesDir, "network-stage/${op.id}.part"), expected, {}, localSource, remoteSource, true)
        check(actual == expected)
        (context.applicationContext as GalleryApp).catalog.dao.saveOperation(op.copy(phase = "COMPLETED", sourceDeletedAt = if (op.kind == "NETWORK_MOVE") System.currentTimeMillis() else op.sourceDeletedAt))
        return true
    }
    private suspend fun publishTo(
        targetSide: ExplorerSide,
        destination: ExplorerLocation,
        name: String,
        op: FileOperation,
        stage: java.io.File,
        digest: FileDigest,
        save: suspend (FileOperation) -> Unit,
        localSource: ExplorerSafFolder,
        remoteSource: ExplorerSmbFolder,
        resume: Boolean = false,
    ): Pair<String, FileDigest> {
        val app = context.applicationContext as GalleryApp
        val transferContext = currentCoroutineContext()
        val documents = FileDocuments(context)
        val localTarget = targetSide == ExplorerSide.LOCAL
        suspend fun readDigest(id: String): FileDigest {
            val check: (Long) -> Unit = { transferContext.ensureActive() }
            return if (localTarget) documents.input(Uri.parse(id)).use { networkDigest(it, check = check) }
            else remoteSource.read(id) { networkDigest(it, check = check) }
        }

        val restored = recoverNetworkPublication(op, digest,
            find = { planned ->
                val entries = if (localTarget) localSource.list(destination) else remoteSource.list(destination)
                entries.singleOrNull { !it.directory && it.name == planned }?.let { it.id to readDigest(it.id) }
            }, save = save)
        if (restored != null) return restored

        op.publishedUri?.let { target ->
            if (localTarget) check(localSource.list(destination).any { !it.directory && it.id == target })
            else remoteSource.list(destination)
            return target to readDigest(target)
        }

        if (localTarget) {
            val parent = Uri.parse(destination.directoryId)
            val finalName = FileNames.available(name, documents.children(parent).map { it.name }.toSet(), true)
            save(op.copy(finalName = finalName, recipe = org.json.JSONObject().put("publicationName", finalName).toString()))
            val (receive, stream) = app.files.beginFtpReceive(parent, finalName)
            try {
                stream.use { output -> stage.inputStream().use { networkDigest(it, output, { transferContext.ensureActive() }) } }
                val uri = app.files.finishFtpReceive(receive)
                save(op.copy(publishedUri = uri.toString(), finalName = finalName, phase = "PUBLISHED"))
                return uri.toString() to readDigest(uri.toString())
            } catch (failure: Exception) {
                withContext(NonCancellable) { app.files.abortFtpReceive(receive) }
                throw failure
            }
        }

        val finalName = FileNames.available(name, remoteSource.list(destination).map { it.name }.toSet(), true)
        val temporaryName = ".ayngallery-${op.id}.part"
        // Persist both names before the external rename, including when resuming an interrupted write.
        save(op.copy(finalName = finalName, recipe = org.json.JSONObject()
            .put("temporaryName", temporaryName).put("publicationName", finalName).toString()))
        val temporary = remoteSource.create(destination, temporaryName, resume)
        remoteSource.write(temporary) { output -> stage.inputStream().use { networkDigest(it, output, { transferContext.ensureActive() }) } }
        check(readDigest(temporary) == digest)
        val published = remoteSource.publish(temporary, destination, finalName)
        save(op.copy(publishedUri = published, finalName = finalName, phase = "PUBLISHED"))
        return published to readDigest(published)
    }
    val recoveryLocalPicker = androidx.compose.runtime.mutableStateOf(false)
    val recoveryRequest = androidx.compose.runtime.mutableStateOf<FileOperation?>(null)
    fun retry(op: FileOperation) { recoveryRequest.value = op; jobsVisible.value = false; browse(true) }
    fun recoveryReady(op: FileOperation): Boolean {
        val providers = setOfNotNull(mutable.value.local.location?.providerId, mutable.value.remote.location?.providerId)
        if (op.kind == "NETWORK_MKDIR") return op.destinationAlbumId in providers && !transfers.running.value
        return (op.sourceKey in providers || op.sourceKey.startsWith("zip:")) && op.destinationAlbumId in providers && !transfers.running.value
    }
    fun recover(op: FileOperation) {
        if (!recoveryReady(op)) return
        if (op.kind == "NETWORK_MKDIR") {
            recoveryRequest.value = null
            val app = context.applicationContext as GalleryApp
            val localTarget = op.destinationAlbumId?.startsWith("saf:") == true
            val side = if (localTarget) ExplorerSide.LOCAL else ExplorerSide.REMOTE
            transfers.running.value = true; transfers.completed.value = 0; transfers.total.value = 1
            transfers.job = scope.launch {
                try { withContext(Dispatchers.IO) { remoteLock.withLock {
                    val at = if (localTarget) checkNotNull(local).resolveDirectory(checkNotNull(op.destinationParent))
                        else checkNotNull(remote).resolveDirectory(checkNotNull(op.destinationParent))
                    check(at.providerId == op.destinationAlbumId)
                    recoverNetworkDirectory(op,
                        list = { if (localTarget) checkNotNull(local).list(at) else checkNotNull(remote).list(at) },
                        create = { name -> if (localTarget) {
                            check(checkNotNull(local).writable(at))
                            checkNotNull(local).registerDirectory(at, app.files.createFtpDirectory(Uri.parse(at.directoryId), name).toString())
                        } else checkNotNull(remote).directory(at, name) },
                        save = { app.catalog.dao.saveOperation(it) })
                } }
                    transfers.completed.value = 1; refresh(side)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { errors.value = errors.value + (side to explorerConnectionError(failure)); recoveryRequest.value = op }
                finally { transfers.running.value = false }
            }
            return
        }
        if (op.kind in setOf("NETWORK_COPY_BATCH", "NETWORK_MOVE_BATCH")) {
            recoveryRequest.value = null
            transfer(if (op.sourceKey.startsWith("saf:")) ExplorerSide.LOCAL else ExplorerSide.REMOTE, op.kind == "NETWORK_MOVE_BATCH", op)
            return
        }
        val app = context.applicationContext as GalleryApp
        val localSource = local ?: return
        val remoteSource = remote ?: return
        if (op.kind == "NETWORK_EXTRACT") {
            val file = runCatching { java.io.File(java.net.URI(op.sourceUri)) }.getOrNull()
            val directory = java.io.File(app.filesDir, "network-preview")
            if (file == null || file.canonicalFile.parentFile != directory.canonicalFile || !file.isFile) { errors.value = errors.value + (ExplorerSide.REMOTE to R.string.explorer_transfer_error); return }
            recoveryRequest.value = null
            val entry = ExplorerEntry(ExplorerLocation(op.sourceKey, ""), op.sourceUri, op.originalName, false, file.length(), file.lastModified(), true)
            extract(ExplorerPreview(entry, file), if (op.destinationAlbumId?.startsWith("saf:") == true) ExplorerSide.LOCAL else ExplorerSide.REMOTE, op)
            return
        }
        recoveryRequest.value = null
        val pending = app.files.operations.value.filter { it.mediaId == op.mediaId && it.kind in setOf("NETWORK_COPY", "NETWORK_MOVE") && it.phase !in setOf("COMPLETED", "CANCELLED") }.ifEmpty { listOf(op) }
        app.sounds.play("network-transfer")
        transfers.running.value = true; transfers.completed.value = 0; transfers.total.value = pending.size
        transfers.job = scope.launch {
            try { withContext(Dispatchers.IO) { remoteLock.withLock {
                for (saved in pending) {
                    ensureActive()
                    val localOrigin = saved.sourceKey.startsWith("saf:")
                    val zipFile = if (saved.sourceKey.startsWith("zip:")) java.io.File(java.net.URI(saved.sourceUri)).also { file ->
                        val staging = java.io.File(app.filesDir, "network-extract").canonicalFile
                        require(file.canonicalFile.toPath().startsWith(staging.toPath()) && file.isFile)
                    } else null
                    val from = if (zipFile != null) ExplorerLocation(saved.sourceKey, "") else if (localOrigin) localSource.resolveDirectory(saved.sourceAlbumId) else remoteSource.resolveDirectory(saved.sourceAlbumId)
                    check(from.providerId == saved.sourceKey)
                    val entries = if (zipFile != null) listOf(ExplorerEntry(from, saved.sourceUri, saved.originalName, false, zipFile.length(), zipFile.lastModified(), true)) else if (localOrigin) localSource.list(from) else remoteSource.list(from)
                    val current = entries.single { !it.directory && it.id == saved.sourceUri }
                    check(current.size == saved.expectedSize && (current.modified ?: 0) == saved.expectedModified)
                    val targetSide = if (saved.destinationAlbumId?.startsWith("saf:") == true) ExplorerSide.LOCAL else ExplorerSide.REMOTE
                    val to = if (targetSide == ExplorerSide.LOCAL) localSource.resolveDirectory(checkNotNull(saved.destinationParent)) else remoteSource.resolveDirectory(checkNotNull(saved.destinationParent))
                    check(to.providerId == saved.destinationAlbumId)
                    transfers.copy(current, to, saved.kind == "NETWORK_MOVE",
                        stat = { if (zipFile != null) current.copy(size = zipFile.length(), modified = zipFile.lastModified()) else if (localOrigin) localSource.stat(current) else remoteSource.stat(current) },
                        read = { action -> if (zipFile != null) zipFile.inputStream().use(action) else if (localOrigin) localSource.read(current, action) else remoteSource.read(current.id, action) },
                        publish = { job, stage, digest, save -> publishTo(targetSide, to, current.name, job, stage, digest, save, localSource, remoteSource, true) },
                        delete = { digest -> if (localOrigin) localSource.delete(current, digest) else remoteSource.deleteVerified(current, digest) }, initial = saved.copy(cancelRequested = false, message = null))
                    transfers.completed.value++
                }
            } }
                if (op.kind == "NETWORK_EXTRACT") app.catalog.dao.saveOperation(op.copy(phase = "COMPLETED"))
                refresh(ExplorerSide.LOCAL); refresh(ExplorerSide.REMOTE)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { errors.value = errors.value + (ExplorerSide.REMOTE to explorerConnectionError(failure)); recoveryRequest.value = op }
            finally { transfers.running.value = false }
        }
    }
    val zipRequest = androidx.compose.runtime.mutableStateOf<ExplorerPreview?>(null)
    fun extract(archive: ExplorerPreview, targetSide: ExplorerSide, initial: FileOperation? = null) {
        if (transfers.running.value || archive.file == null) return
        val destination = if (initial != null) ExplorerLocation(checkNotNull(initial.destinationAlbumId), checkNotNull(initial.destinationParent)) else mutable.value.pane(targetSide).location ?: return
        val localSource = local ?: return
        val remoteSource = remote ?: return
        val app = context.applicationContext as GalleryApp
        val id = initial?.id ?: java.util.UUID.randomUUID().toString()
        val parent = java.io.File(context.filesDir, "network-extract").apply { check(isDirectory || mkdirs()) }
        val stage = java.io.File(parent, id)
        var operation = initial ?: FileOperation(id, id, "NETWORK_EXTRACT", archive.file.toURI().toString(), "zip:${archive.file.name}", "", archive.entry.name,
            "application/zip", archive.file.length(), archive.file.lastModified(), destination.directoryId, destination.providerId, archive.entry.name, true, id)
        app.sounds.play("network-transfer")
        transfers.running.value = true; transfers.completed.value = 0; transfers.total.value = 0
        transfers.job = scope.launch {
            try { withContext(Dispatchers.IO) { remoteLock.withLock {
                val digest = archive.file.inputStream().use { input -> val active = currentCoroutineContext(); networkDigest(input, check = { active.ensureActive() }) }
                val manifest = initial?.recipe?.let { org.json.JSONObject(it) } ?: org.json.JSONObject().put("archiveDigest", digest.sha256).put("folders", org.json.JSONObject())
                check(manifest.getString("archiveDigest") == digest.sha256)
                val locations = manifest.getJSONObject("folders")
                operation = operation.copy(recipe = manifest.toString())
                app.catalog.dao.saveOperation(operation)
                val owner = currentCoroutineContext()
                val files = stageExplorerZip(archive.file, stage) { owner.ensureActive() }
                transfers.total.value = files.count { it.file != null }
                operation = operation.copy(phase = "COPYING", expectedSize = files.sumOf { it.size })
                app.catalog.dao.saveOperation(operation)
                suspend fun directory(at: ExplorerLocation, name: String): ExplorerLocation {
                    val names = if (targetSide == ExplorerSide.LOCAL) localSource.list(at).map { it.name }.toSet() else remoteSource.list(at).map { it.name }.toSet()
                    val available = FileNames.available(name, names, true)
                    if (targetSide == ExplorerSide.LOCAL) {
                        check(localSource.writable(at))
                        return localSource.registerDirectory(at, app.files.createFtpDirectory(Uri.parse(at.directoryId), available).toString())
                    }
                    val jobId = java.util.UUID.randomUUID().toString()
                    val folderJob = FileOperation(jobId, jobId, "NETWORK_MKDIR", archive.file.toURI().toString(), operation.sourceKey, "", available,
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, at.directoryId, at.providerId, available, true, jobId)
                    app.catalog.dao.saveOperation(folderJob)
                    val made = remoteSource.directory(at, available)
                    app.catalog.dao.saveOperation(folderJob.copy(phase = "COMPLETED", publishedUri = made.directoryId))
                    return made
                }
                val targetRoot = if (targetSide == ExplorerSide.LOCAL) localSource.resolveDirectory(destination.directoryId) else remoteSource.resolveDirectory(destination.directoryId)
                check(targetRoot.providerId == destination.providerId)
                val root = if (operation.publishedUri != null) {
                    if (targetSide == ExplorerSide.LOCAL) localSource.resolveDirectory(operation.publishedUri!!) else remoteSource.resolveDirectory(operation.publishedUri!!)
                } else directory(targetRoot, archive.entry.name.substringBeforeLast('.').take(180))
                operation = operation.copy(publishedUri = root.directoryId)
                app.catalog.dao.saveOperation(operation)
                val directories = mutableMapOf("" to root)
                val prepared = mutableListOf<Pair<ExplorerEntry, Pair<ExplorerLocation, FileOperation>>>()
                for (entry in files) {
                    ensureActive()
                    val directoriesCount = if (entry.file == null) entry.parts.size else entry.parts.size - 1
                    var at = root
                    for (depth in 1..directoriesCount) {
                        val key = entry.parts.take(depth).joinToString("/")
                        at = directories[key] ?: if (locations.has(key)) {
                            (if (targetSide == ExplorerSide.LOCAL) localSource.resolveDirectory(locations.getString(key)) else remoteSource.resolveDirectory(locations.getString(key))).also { directories[key] = it }
                        } else directory(at, entry.parts[depth - 1]).also {
                            directories[key] = it; locations.put(key, it.directoryId)
                            operation = operation.copy(recipe = manifest.toString()); app.catalog.dao.saveOperation(operation)
                        }
                    }
                    val file = entry.file ?: continue
                    val source = ExplorerEntry(ExplorerLocation(operation.sourceKey, ""), file.toURI().toString(), entry.parts.last(), false, file.length(), file.lastModified(), true)
                    val childId = java.util.UUID.nameUUIDFromBytes("$id/${entry.parts.joinToString("/")}".toByteArray()).toString()
                    val previous = app.catalog.dao.operation(childId)
                    if (previous?.phase == "COMPLETED") { transfers.completed.value++; continue }
                    val child = previous?.copy(expectedSize = file.length(), expectedModified = file.lastModified(), cancelRequested = false) ?: FileOperation(childId, operation.id, "NETWORK_COPY", source.id, source.location.providerId, "", source.name,
                        "application/octet-stream", file.length(), file.lastModified(), at.directoryId, at.providerId, source.name, true, childId)
                    app.catalog.dao.saveOperation(child)
                    prepared += source to (at to child)
                }
                for ((source, target) in prepared) {
                    val (at, child) = target
                    val file = java.io.File(java.net.URI(source.id))
                    transfers.copy(source, at, false, stat = { source.copy(size = file.length(), modified = file.lastModified()) }, read = { action -> file.inputStream().use(action) },
                        publish = { job, staged, hash, save -> publishTo(targetSide, at, source.name, job, staged, hash, save, localSource, remoteSource, true) }, delete = {}, initial = child)
                    transfers.completed.value++
                    operation = operation.copy(bytes = operation.bytes + file.length())
                    app.catalog.dao.saveOperation(operation)
                }
                operation = operation.copy(phase = "COMPLETED")
                app.catalog.dao.saveOperation(operation)
                check(stage.canonicalFile.parentFile == parent.canonicalFile && stage.name == id)
                stage.deleteRecursively()
            } }
                refresh(targetSide)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { app.catalog.dao.saveOperation(operation.copy(phase = "INTERRUPTED")) }; throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { app.catalog.dao.saveOperation(operation.copy(phase = if (operation.publishedUri == null) "FAILED" else "PARTIAL")) }
                errors.value = errors.value + (targetSide to R.string.explorer_transfer_error)
            } finally { transfers.running.value = false }
        }
    }
    val streams = java.util.concurrent.ConcurrentHashMap<String, ExplorerStream>()
    val preview = androidx.compose.runtime.mutableStateOf<ExplorerPreview?>(null)
    private var previewJob: Job? = null
    fun preview(side: ExplorerSide, entry: ExplorerEntry) {
        if (entry.directory || transfers.running.value) return
        previewJob?.cancel()
        previewJob = scope.launch {
            try {
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                if (MediaNames.kind(mime, entry.name)?.name in setOf("VIDEO", "AUDIO")) {
                    val source = if (side == ExplorerSide.REMOTE) withContext(Dispatchers.IO) { remoteLock.withLock { checkNotNull(remote).stat(entry) } } else withContext(Dispatchers.IO) { checkNotNull(local).stat(entry) }
                    check(source.size == entry.size && source.modified == entry.modified)
                    val uri = if (side == ExplorerSide.LOCAL) Uri.parse(entry.id) else {
                        val id = java.util.UUID.randomUUID().toString()
                        check(streams.size < 8)
                        streams[id] = ExplorerStream(entry, checkNotNull(remote))
                        Uri.parse("content://${context.packageName}.network/$id")
                    }
                    preview.value = ExplorerPreview(entry, uri = uri)
                    return@launch
                }
                require(entry.size != null && entry.size in 1..64L * 1024 * 1024)
                val cache = java.io.File(context.filesDir, "network-preview")
                val identity = "${entry.location.providerId}/${entry.id}/${entry.size}/${entry.modified}"
                val key = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
                val extension = entry.name.substringAfterLast('.', "bin").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"
                val file = java.io.File(cache, "$key.$extension")
                withContext(Dispatchers.IO) { remoteLock.withLock {
                    check(cache.isDirectory || cache.mkdirs())
                    fun stat() = if (side == ExplorerSide.LOCAL) checkNotNull(local).stat(entry) else checkNotNull(remote).stat(entry)
                    check(stat().let { it.size == entry.size && it.modified == entry.modified })
                    if (!file.isFile || file.length() != entry.size) {
                        val pendingArchives = (context.applicationContext as GalleryApp).catalog.dao.operations().filter { it.kind == "NETWORK_EXTRACT" && !FileOperations.terminal(it.phase) }.map { it.sourceUri }.toSet()
                        cache.listFiles().orEmpty().filter { it != preview.value?.file && it != zipRequest.value?.file && it.toURI().toString() !in pendingArchives }.sortedBy { it.lastModified() }.let { old ->
                            var size = cache.listFiles().orEmpty().sumOf { it.length() }
                            old.forEach { val length = it.length(); if (size + entry.size > 128L * 1024 * 1024 && (context.applicationContext as GalleryApp).video.surface != "received" && it.delete()) size -= length }
                            require(size + entry.size <= 128L * 1024 * 1024)
                        }
                        val stage = java.io.File(cache, "$key.part")
                        try {
                            val owner = currentCoroutineContext()
                            val action: (java.io.InputStream) -> FileDigest = { input -> stage.outputStream().use { output -> networkDigest(input, output) { owner.ensureActive(); require(it <= 64L * 1024 * 1024) } } }
                            val digest = if (side == ExplorerSide.LOCAL) checkNotNull(local).read(entry, action) else checkNotNull(remote).read(entry.id, action)
                            check(digest.bytes == entry.size && stat().let { it.size == entry.size && it.modified == entry.modified })
                            check(stage.renameTo(file))
                        } finally { stage.delete() }
                    }
                } }
                preview.value = ExplorerPreview(entry, file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { errors.value = errors.value + (side to R.string.text_preview_unavailable_135044) }
        }
    }
    fun active(side: ExplorerSide) { mutable.value = mutable.value.copy(active = side) }
    fun focus(side: ExplorerSide, id: String) { mutable.value = mutable.value.update(side) { it.copy(focusedId = id) } }
    fun select(side: ExplorerSide, entry: ExplorerEntry) { mutable.value = mutable.value.update(side) { it.toggle(entry) } }
    fun browse(value: Boolean) {
        browsing.value = value
        if (value && local == null && jobs[ExplorerSide.LOCAL]?.isActive != true)
            preferences.getString("tree", null)?.let { choose(Uri.parse(it)) }
    }
    fun disconnect() {
        transfers.cancel(); previewJob?.cancel(); preview.value = null; streams.clear()
        jobs[ExplorerSide.REMOTE]?.cancel()
        mutable.value = mutable.value.copy(remote = ExplorerPaneState())
        errors.value = errors.value - ExplorerSide.REMOTE
        val transfer = transfers.job
        jobs[ExplorerSide.REMOTE] = scope.launch { transfer?.join(); withContext(Dispatchers.IO) { remoteLock.withLock {
            remote?.close(); remote = null
        } } }
    }
    fun controller(action: PadAction): Boolean {
        if (!browsing.value || connectionForm.value) return false
        val side = mutable.value.active
        val pane = mutable.value.pane(side)
        if (action in setOf(PadAction.SETTINGS, PadAction.MENU) && pane.entries.isNotEmpty()) { options(side); return true }
        if (action == PadAction.TOGGLE_VIEW) { grid(side, side !in grids.value); return true }
        if (action == PadAction.BACK) {
            if (pane.back.isNotEmpty()) previous(side) else browse(false)
            return true
        }
        if (pane.entries.isEmpty()) return false
        val index = pane.entries.indexOfFirst { it.id == pane.focusedId }.coerceAtLeast(0)
        when (action) {
            PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT -> focus(side, pane.entries[(index + when(action) { PadAction.UP -> if (side in grids.value) -3 else -1; PadAction.DOWN -> if (side in grids.value) 3 else 1; PadAction.LEFT -> -1; else -> 1 }).coerceIn(0, pane.entries.lastIndex)].id)
            PadAction.CONFIRM -> pane.entries[index].let { if (it.directory) open(side, it.location.copy(directoryId = it.id)) else if (pane.selected.values.any { selected -> selected.id == it.id && selected.location == it.location }) preview(side, it) else select(side, it) }
            else -> return false
        }
        return true
    }
    fun choose(tree: Uri) {
        jobs[ExplorerSide.LOCAL]?.cancel()
        local = ExplorerSafFolder(context, tree)
        mutable.value = mutable.value.copy(local = ExplorerPaneState())
        run(ExplorerSide.LOCAL) {
            val root = checkNotNull(local).root()
            preferences.edit().putString("tree", tree.toString()).apply()
            open(ExplorerSide.LOCAL, root)
        }
    }
    fun connect(address: String, user: String, password: CharArray) {
        if (transfers.running.value) { password.fill('\u0000'); return }
        if (jobs[ExplorerSide.REMOTE]?.isActive == true) { password.fill('\u0000'); return }
        draftConnection(address, user)
        run(ExplorerSide.REMOTE) {
            val parsed = try { ExplorerShareAddress.parse(address) } catch (failure: Exception) {
                password.fill('\u0000'); errors.value = errors.value + (ExplorerSide.REMOTE to R.string.explorer_address_invalid)
                mutable.value = mutable.value.update(ExplorerSide.REMOTE) { it.copy(loading = false) }; return@run
            }
            preferences.edit().putString("address", address.trim()).putString("user", user.trim()).apply()
            mutable.value = mutable.value.copy(remote = ExplorerPaneState(loading = true))
            val root = withContext(Dispatchers.IO) { remoteLock.withLock {
                remote?.close(); remote = null
                remote = ExplorerSmbFolder(parsed, user.trim(), password)
                checkNotNull(remote).root
            } }
            mutable.value = mutable.value.copy(remote = ExplorerPaneState())
            open(ExplorerSide.REMOTE, root)
        }
        jobs[ExplorerSide.REMOTE]?.invokeOnCompletion { password.fill('\u0000') }
    }
    fun open(side: ExplorerSide, location: ExplorerLocation) {
        if (side == ExplorerSide.LOCAL) localFolderName.value = local?.name(location).orEmpty()
        mutable.value = mutable.value.update(side) { it.open(location) }
        load(side)
    }
    fun previous(side: ExplorerSide) {
        val pane = mutable.value.pane(side)
        if (pane.back.isNotEmpty()) {
            mutable.value = mutable.value.update(side) { it.previous() }
            if (side == ExplorerSide.LOCAL) localFolderName.value = local?.name(mutable.value.local.location!!).orEmpty()
            load(side)
        }
    }
    fun refresh(side: ExplorerSide) { mutable.value.pane(side).location?.let { open(side, it) } }
    private fun load(side: ExplorerSide) {
        val pane = mutable.value.pane(side)
        val location = pane.location ?: return
        run(side) {
            val entries = if (side == ExplorerSide.LOCAL) checkNotNull(local).list(location)
                else withContext(Dispatchers.IO) { remoteLock.withLock { checkNotNull(remote).list(location) } }
            mutable.value = mutable.value.update(side) { it.listed(pane.request, location,
                entries.sortedWith(compareByDescending<ExplorerEntry> { it.directory }.thenBy { it.name.lowercase() })) }
        }
    }
    private fun run(side: ExplorerSide, action: suspend () -> Unit) {
        // Connecting/opening may schedule the listing as the next job; launch undispatched is deliberately avoided.
        jobs[side]?.cancel()
        errors.value = errors.value - side
        mutable.value = mutable.value.update(side) { it.copy(loading = true) }
        jobs[side] = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (side == ExplorerSide.REMOTE) android.util.Log.w("DSGalleryNetwork", generateSequence<Throwable>(failure) { it.cause }.take(8)
                    .joinToString(" -> ") { cause -> cause.javaClass.name + ((cause as? com.hierynomus.mssmb2.SMBApiException)?.statusCode?.let { ":0x${it.toString(16)}" } ?: "") })
                errors.value = errors.value + (side to if (side == ExplorerSide.LOCAL) R.string.explorer_local_error else explorerConnectionError(failure))
                mutable.value = mutable.value.update(side) { it.copy(loading = false) }
            }
        }
    }
    fun close() { transfers.cancel(); streams.clear(); scope.cancel(); runBlocking(Dispatchers.IO) { remoteLock.withLock { remote?.close(); remote = null } } }
}

/** Recover only the recorded folder: no invented children, overwrite or source deletion. */
internal suspend fun recoverNetworkDirectory(op: FileOperation, list: suspend () -> List<ExplorerEntry>,
    create: suspend (String) -> ExplorerLocation, save: suspend (FileOperation) -> Unit): ExplorerLocation {
    require(op.kind == "NETWORK_MKDIR")
    val name = op.finalName ?: op.requestedName
    FileNames.validateBase(name)
    val existing = list().filter { it.name == name }
    check(existing.size <= 1)
    val location = existing.singleOrNull()?.let {
        check(it.directory)
        if (op.publishedUri != null) check(it.id == op.publishedUri)
        ExplorerLocation(it.location.providerId, it.id)
    } ?: run {
        check(op.publishedUri == null) { "The previously created folder is unavailable" }
        currentCoroutineContext().ensureActive()
        create(name)
    }
    check(location.providerId == op.destinationAlbumId)
    withContext(NonCancellable) {
        save(op.copy(phase = "COMPLETED", publishedUri = location.directoryId, cancelRequested = false,
            message = null, updatedAt = System.currentTimeMillis()))
    }
    return location
}


/** A reserved publication name is durable before rename; never guess using an unrelated filename. */
internal suspend fun recoverNetworkPublication(op: FileOperation, expected: FileDigest,
    find: suspend (String) -> Pair<String, FileDigest>?, save: suspend (FileOperation) -> Unit): Pair<String, FileDigest>? {
    if (op.publishedUri != null) return null
    val intent = op.recipe?.let { org.json.JSONObject(it) } ?: return null
    val planned = intent.optString("publicationName").ifBlank {
        if (intent.has("temporaryName")) op.finalName.orEmpty() else ""
    }
    if (planned.isBlank()) return null
    FileNames.validateBase(planned)
    val existing = find(planned) ?: return null
    check(existing.second == expected) { "Published destination changed; preserve both files for manual recovery" }
    save(op.copy(publishedUri = existing.first, phase = "PUBLISHED"))
    return existing
}
