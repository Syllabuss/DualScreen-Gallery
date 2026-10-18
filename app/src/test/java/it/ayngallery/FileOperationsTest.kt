package it.ayngallery

import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class FileOperationsTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private lateinit var files: FileOperations
    private lateinit var media: MediaEntity
    private lateinit var destination: AlbumEntity
    private var now = TrashClockSample(1_800_000_000_000, 1000, 1)
    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000)
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "ayngallery.operations"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("ayngallery.operations", provider)
        catalog = Catalog(app); files = FileOperations(catalog, clock = { now })
        val source = SourceEntity("scope", DocumentsContract.buildTreeDocumentUri("ayngallery.operations", "root").toString(), "Fixture", "VISUAL")
        catalog.context.contentResolver.takePersistableUriPermission(android.net.Uri.parse(source.treeUri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        media = catalog.dao.media().single()
        catalog.dao.favorite(media.id, true); media = catalog.dao.media(media.id)!!
        catalog.collection("Keep references", "MEDIA", listOf(media.id))
        destination = catalog.dao.albums().single { it.name == "Destination" }
    }
    @After fun cleanup() { if (::catalog.isInitialized) { runBlocking { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }; catalog.db.close() } }
    @Test fun aLongMoveDoesNotBlockFavoritesAndKeepsTheirLatestValue() = runBlocking {
        val id = files.enqueue(media, "MOVE", destination.id, media.name, start = false)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val move = async(kotlinx.coroutines.Dispatchers.IO) { files.execute(id) { entered.complete(Unit); release.await() } }
        try {
            kotlinx.coroutines.withTimeout(5000) { entered.await(); catalog.favorite(media.id) }
            assertFalse(catalog.dao.media(media.id)!!.favorite)
        } finally { release.complete(Unit) }
        kotlinx.coroutines.withTimeout(5000) { move.await() }
        assertEquals("COMPLETED", catalog.dao.operation(id)!!.phase)
        assertFalse(catalog.dao.media(media.id)!!.favorite)
    }
    private suspend fun run(kind: String, target: AlbumEntity? = destination, name: String = media.name): FileOperation {
        val id = files.enqueue(media, kind, target?.id, name, start = false)
        files.execute(id)
        return catalog.dao.operation(id)!!
    }
    @Test fun ftpReceivePublishesOnlyAfterExplicitSuccessfulEndOfTransfer() = runBlocking {
        val payload = byteArrayOf(0, 1, 2, 127, -128, -1)
        val (id, output) = files.beginFtpReceive(Uri.parse(destination.documentUri), "game.zip")
        output.use { it.write(payload) }
        assertFalse(provider.nodes.values.any { it.name == "game.zip" })
        assertEquals("RECEIVING", catalog.dao.operation(id)!!.phase)
        val result = files.finishFtpReceive(id)
        assertEquals(payload.toList(), provider.node(result.toString()).file!!.readBytes().toList())
        assertEquals("COMPLETED", catalog.dao.operation(id)!!.phase)
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun ftpSharedTreeResolvesOnlyGrantedChildrenAndRejectsAFileAsDestinationFolder() {
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.operations", "root")
        val shared = FtpSharedFolder(catalog.context, tree)
        assertEquals("image.png", shared.resolve("/Origin/image.png").name)
        assertEquals("destination", DocumentsContract.getDocumentId(shared.destination("/Destination/new.zip").first))
        assertTrue(runCatching { shared.resolve("/missing/image.png") }.isFailure)
        assertTrue(runCatching { shared.destination("/Origin/image.png/another.zip") }.isFailure)
        assertTrue(runCatching { FtpSharedFolder(catalog.context, Uri.parse("file:///storage/emulated/0")) }.isFailure)
        val ungranted = DocumentsContract.buildTreeDocumentUri("ayngallery.operations", "child")
        assertTrue(runCatching { FtpSharedFolder(catalog.context, ungranted) }.isFailure)
    }
    @Test fun realFtpClientCanReadAndSendOnlyInsideTheGrantedFolderWithoutOverwriteOrAnonymousLogin() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.operations", "root")
        val server = FtpServer(catalog.context, files, tree, java.net.InetAddress.getLoopbackAddress(), port, "test-password-1234")
        val client = org.apache.commons.net.ftp.FTPClient().apply {
            setDefaultTimeout(5000); setDataTimeout(java.time.Duration.ofSeconds(5))
        }
        try {
            server.start(); client.connect(java.net.InetAddress.getLoopbackAddress(), port)
            assertFalse(client.login("anonymous", "test@example.invalid"))
            client.disconnect(); client.connect(java.net.InetAddress.getLoopbackAddress(), port)
            assertTrue(client.login(FtpServer.USER, "test-password-1234"))
            client.enterLocalPassiveMode()
            assertTrue(client.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE))
            provider.childQueries = 0
            assertEquals(setOf("Origin", "Destination"), client.listFiles("/").map { it.name }.toSet())
            assertTrue("Listing metadata must reuse the provider's directory result", provider.childQueries <= 2)
            assertTrue(client.makeDirectory("/Destination/Incoming"))
            assertFalse(client.makeDirectory("/Destination/Incoming"))
            val downloaded = java.io.ByteArrayOutputStream()
            assertTrue(client.retrieveFile("/Origin/image.png", downloaded))
            assertEquals(provider.originalBytes.toList(), downloaded.toByteArray().toList())
            assertTrue(client.storeFile("/Destination/file.zip", byteArrayOf(1, 4, 9).inputStream()))
            assertFalse(client.storeFile("/Destination/file.zip", byteArrayOf(2).inputStream()))
            val sent = provider.nodes.values.single { it.name == "file.zip" }
            assertEquals(listOf<Byte>(1, 4, 9), sent.file!!.readBytes().toList())
            assertFalse(client.deleteFile("/Origin/image.png"))
            assertTrue(provider.deletions.isEmpty())
            assertTrue(client.storeFile("/Destination/Incoming/nested.zip", byteArrayOf(7, 8).inputStream()))
            assertFalse(client.storeFile("/../outside.zip", byteArrayOf(1).inputStream()))
            assertTrue(runBlocking { catalog.dao.operations() }.any { it.kind == "FTP_RECEIVE" && it.phase == "COMPLETED" })
        } finally {
            if (client.isConnected) client.disconnect()
            server.stop()
        }
    }
    @Test fun ftpAbortNeverPublishesPartialBytesAndCannotBeRetriedAsComplete() = runBlocking {
        val (id, output) = files.beginFtpReceive(Uri.parse(destination.documentUri), "partial.zip")
        output.use { it.write(byteArrayOf(1, 2)) }
        files.abortFtpReceive(id)
        assertEquals("CANCELLED", catalog.dao.operation(id)!!.phase)
        assertFalse(provider.nodes.values.any { it.name == "partial.zip" })
        assertTrue(runCatching { files.finishFtpReceive(id) }.isFailure)
    }
    @Test fun stoppingTheFtpServerDuringARealUploadCannotPublishItsPartialBytes() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.operations", "root")
        val server = FtpServer(catalog.context, files, tree, java.net.InetAddress.getLoopbackAddress(), port, "test-password-1234")
        val client = org.apache.commons.net.ftp.FTPClient().apply { setDefaultTimeout(5000) }
        try {
            server.start(); client.connect(java.net.InetAddress.getLoopbackAddress(), port)
            assertTrue(client.login(FtpServer.USER, "test-password-1234")); client.enterLocalPassiveMode()
            client.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
            val output = requireNotNull(client.storeFileStream("/Destination/incomplete.zip"))
            output.write(ByteArray(8192) { 1 }); output.flush()
            var operation: FileOperation? = null
            val deadline = System.nanoTime() + 5_000_000_000L
            while (operation == null && System.nanoTime() < deadline) {
                operation = runBlocking { catalog.dao.operations().firstOrNull { it.kind == "FTP_RECEIVE" } }
                Thread.sleep(5)
            }
            assertNotNull(operation)
            server.stop()
            runCatching { output.close() }
            val stoppedDeadline = System.nanoTime() + 5_000_000_000L
            while (runBlocking { catalog.dao.operation(operation!!.id)!!.phase } == "RECEIVING" && System.nanoTime() < stoppedDeadline)
                Thread.sleep(5)
            assertEquals("CANCELLED", runBlocking { catalog.dao.operation(operation!!.id)!!.phase })
            assertFalse(provider.nodes.values.any { it.name == "incomplete.zip" })
            assertFalse(File(catalog.context.filesDir, "trash/${operation!!.id}.part").exists())
        } finally {
            if (client.isConnected) client.disconnect()
            server.stop()
        }
    }
    @Test fun ftpStartupRecoveryDiscardsUnconfirmedBytesButDoesNotCancelLiveTransfers() = runBlocking {
        val (id, output) = files.beginFtpReceive(Uri.parse(destination.documentUri), "interrupted.zip")
        output.use { it.write(byteArrayOf(1, 2, 3)) }
        files.recoverInterrupted()
        assertEquals("RECEIVING", catalog.dao.operation(id)!!.phase)
        files = FileOperations(catalog, clock = { now }) // A fresh process has no live network transfer.
        files.recoverInterrupted()
        assertEquals("CANCELLED", catalog.dao.operation(id)!!.phase)
        assertFalse(File(catalog.context.filesDir, "trash/$id.part").exists())
        assertFalse(provider.nodes.values.any { it.name == "interrupted.zip" })
    }
    @Test fun ftpCollisionRetainsVerifiedUploadAndDoesNotOverwriteExistingFile() = runBlocking {
        val (id, output) = files.beginFtpReceive(Uri.parse(destination.documentUri), "occupied.zip")
        output.use { it.write(byteArrayOf(9, 8, 7)) }
        val existing = provider.add("destination", "occupied.zip", byteArrayOf(4, 5))
        assertTrue(runCatching { files.finishFtpReceive(id) }.isFailure)
        files.abortFtpReceive(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        assertEquals(listOf<Byte>(4, 5), existing.file!!.readBytes().toList())
        assertTrue(java.io.File(catalog.context.filesDir, "trash/$id.part").exists())
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun ftpPublicationRecoversAProviderFailureAfterRenameWithoutDuplicatingTheUpload() = runBlocking {
        val (id, output) = files.beginFtpReceive(Uri.parse(destination.documentUri), "recovered.zip")
        output.use { it.write(byteArrayOf(3, 2, 1)) }
        provider.failAfterRename = true
        assertTrue(runCatching { files.finishFtpReceive(id) }.isFailure)
        provider.failAfterRename = false
        files.execute(id)
        assertEquals("COMPLETED", catalog.dao.operation(id)!!.phase)
        assertEquals(1, provider.nodes.values.count { it.name == "recovered.zip" })
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun albumCopyCreatesItsFolderAndRejectsADestinationInsideTheSelection() = runBlocking {
        val sourceAlbum = catalog.dao.albums().single { it.id == media.albumId }
        files.enqueueAlbumMedia(listOf(media), listOf(sourceAlbum), "COPY", destination.id, start = false)
        val copy = catalog.dao.operations().single { it.kind == "COPY" }
        val folder = catalog.dao.albums().single { it.id == copy.destinationAlbumId }
        assertEquals(destination.id, folder.parentId)
        assertEquals(sourceAlbum.name, folder.name)
        files.execute(copy.id)
        assertEquals("COMPLETED", catalog.dao.operation(copy.id)!!.phase)
        assertTrue(provider.deletions.isEmpty())
        assertTrue(runCatching { files.enqueueAlbumMedia(listOf(media), listOf(sourceAlbum), "MOVE", sourceAlbum.id) }.isFailure)
    }
    @Test fun duplicateFingerprintRejectsChangesWithIdenticalSizeAndDateBeforeTrash() = runBlocking {
        val expected = FileDigest(provider.originalBytes.size.toLong(), java.security.MessageDigest.getInstance("SHA-256")
            .digest(provider.originalBytes).joinToString("") { "%02x".format(it) })
        val id = files.enqueue(media, "TRASH", start = false, expectedDigest = expected)
        val changed = provider.originalBytes.map { (it.toInt() xor 7).toByte() }.toByteArray()
        provider.nodes.getValue("image").file!!.writeBytes(changed)
        files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        assertEquals(changed.toList(), provider.nodes.getValue("image").file!!.readBytes().toList())
        assertTrue(provider.deletions.isEmpty()); assertNull(catalog.dao.trash(media.id))
        assertTrue(catalog.dao.media(media.id)!!.favorite)
        assertTrue(catalog.dao.collectionItems().any { it.mediaId == media.id })
        assertEquals(expected.sha256, catalog.dao.operation(id)!!.digest)
    }
    @Test fun verifiedDuplicateFingerprintUsesNormalRecoverableTrash() = runBlocking {
        val expected = FileDigest(provider.originalBytes.size.toLong(), java.security.MessageDigest.getInstance("SHA-256")
            .digest(provider.originalBytes).joinToString("") { "%02x".format(it) })
        val id = files.enqueue(media, "TRASH", start = false, expectedDigest = expected)
        files.execute(id)
        assertEquals("COMPLETED", catalog.dao.operation(id)!!.phase)
        assertEquals(expected.sha256, catalog.dao.trash(media.id)!!.digest)
        assertTrue(catalog.dao.media(media.id)!!.trashed)
    }
    @Test fun duplicateKeeperGuardSurvivesRecreationAndBlocksChangedRetainedContent() = runBlocking {
        val copy = provider.add("destination", "copy.png", provider.originalBytes)
        SourceScanner(catalog).scan(catalog.dao.sources().single())
        val kept = catalog.dao.media().single { it.name == copy.name }
        val hash = FileDigest(provider.originalBytes.size.toLong(), java.security.MessageDigest.getInstance("SHA-256")
            .digest(provider.originalBytes).joinToString("") { "%02x".format(it) })
        val id = files.enqueue(media, "TRASH", start = false, expectedDigest = hash, retainedMediaId = kept.id)
        assertEquals(kept.id, catalog.dao.operation(id)!!.retainedMediaId)
        copy.file!!.writeBytes(provider.originalBytes.map { (it.toInt() xor 7).toByte() }.toByteArray())
        val recovered = FileOperations(catalog, clock = { now })
        recovered.execute(id)
        assertNotEquals("COMPLETED", catalog.dao.operation(id)!!.phase)
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.deletions.isEmpty())
        assertFalse(catalog.dao.media(media.id)!!.trashed)
    }
    @Test fun twoPanelsCannotDiscardBothDuplicateCopies() = runBlocking {
        val copy = provider.add("destination", "copy.png", provider.originalBytes)
        SourceScanner(catalog).scan(catalog.dao.sources().single())
        val second = catalog.dao.media().single { it.name == copy.name }
        val hash = FileDigest(provider.originalBytes.size.toLong(), java.security.MessageDigest.getInstance("SHA-256")
            .digest(provider.originalBytes).joinToString("") { "%02x".format(it) })
        val firstRequest = files.enqueue(second, "TRASH", start = false, expectedDigest = hash, retainedMediaId = media.id)
        val secondRequest = files.enqueue(media, "TRASH", start = false, expectedDigest = hash, retainedMediaId = second.id)
        files.execute(firstRequest); files.execute(secondRequest)
        assertEquals("COMPLETED", catalog.dao.operation(firstRequest)!!.phase)
        assertNotEquals("COMPLETED", catalog.dao.operation(secondRequest)!!.phase)
        assertTrue(provider.nodes.containsKey("image")); assertFalse(catalog.dao.media(media.id)!!.trashed)
        assertEquals(listOf(copy.id), provider.deletions)
    }
    @Test fun copyKeepsBothAndNeverInheritsFavoriteOrOverwrites() = runBlocking {
        val existing = provider.add("destination", media.name, "Other content".toByteArray())
        val operation = run("COPY")
        assertEquals("COMPLETED", operation.phase)
        assertEquals("image (1).png", operation.finalName)
        assertTrue(provider.nodes.containsKey("image"))
        assertEquals("Other content", existing.file!!.readText())
        val result = catalog.dao.media(operation.resultMediaId)!!
        assertNotEquals(media.id, result.id); assertFalse(result.favorite)
        assertEquals(provider.originalBytes.toList(), provider.node(result.uri).file!!.readBytes().toList())
    }
    @Test fun audioCopyAndMoveUseDestinationArtworkAndPreserveTrackOverridesByStableId() = runBlocking {
        catalog.dao.saveSource(catalog.dao.sources().single().copy(role = "MIXED"))
        provider.nodes["image"] = provider.nodes.getValue("image").copy(name = "audio.wav", mime = "audio/wav")
        val audio = media.copy(name = "audio.wav", kind = "AUDIO", mime = "audio/wav", artworkUri = File(catalog.context.cacheDir, "artwork/${media.id}.art").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }.toURI().toString(), artworkMediaId = media.id)
        catalog.dao.saveMedia(listOf(audio)); media = audio
        val picture = provider.add("destination", "cover.png", byteArrayOf(1))
        val pictureId = "destination-cover"
        val pictureUri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(catalog.dao.sources().single().treeUri), picture.id)
        catalog.dao.saveMedia(listOf(MediaEntity(pictureId, "ayngallery.operations:${picture.id}", pictureUri.toString(), destination.id, "cover.png", "image/png", "IMAGE", 1, 1)))
        catalog.dao.saveMembership(listOf(MediaSource(pictureId, catalog.dao.sources().single().id, 1)))
        catalog.setMusicArtwork(ArtworkOrders.track(media.id), pictureId, null)
        val copied = run("COPY", name = "copied.wav")
        val copy = catalog.dao.media(copied.resultMediaId)!!
        assertEquals(pictureId, copy.artworkMediaId)
        assertNotEquals(media.artworkUri, copy.artworkUri)
        assertTrue(catalog.dao.artworkChoices().none { it.target == ArtworkOrders.track(copy.id) })
        val moved = run("MOVE", name = "moved.wav")
        val result = catalog.dao.media(moved.resultMediaId)!!
        assertEquals(media.id, result.id); assertEquals(media.artworkUri, result.artworkUri)
        assertEquals(pictureId, result.artworkMediaId)
        assertEquals(pictureId, catalog.dao.artworkChoices().single().coverMediaId)
    }
    @Test fun moveAndRenamePreserveStableReferencesAcrossProviderIdChanges() = runBlocking {
        val moved = run("MOVE")
        assertEquals(moved.message, "COMPLETED", moved.phase); assertFalse(provider.nodes.containsKey("image"))
        assertEquals(1, provider.deletions.count { it == "image" })
        media = catalog.dao.media(media.id)!!
        assertEquals(destination.id, media.albumId); assertTrue(media.favorite)
        assertEquals(media.id, catalog.dao.collectionItems().single().mediaId)
        val rename = run("RENAME", destination, "new-name.png")
        assertEquals("COMPLETED", rename.phase)
        assertEquals("new-name.png", catalog.dao.media(media.id)!!.name)
        assertEquals(media.id, catalog.dao.collectionItems().single().mediaId)
        assertTrue(catalog.dao.media(media.id)!!.favorite)
        val count = provider.creations
        files.execute(rename.id); assertEquals(count, provider.creations)
    }
    @Test fun deniedDeletionIsPartialAndRetryReusesOneVerifiedDestination() = runBlocking {
        provider.denyDelete = true
        val op = run("MOVE")
        assertEquals("PARTIAL", op.phase)
        assertNotNull(op.publishedUri); assertTrue(provider.nodes.containsKey("image"))
        assertEquals(media.uri, catalog.dao.media(media.id)!!.uri)
        val count = provider.creations
        provider.denyDelete = false; files.execute(op.id)
        assertEquals("COMPLETED", catalog.dao.operation(op.id)!!.phase)
        assertEquals(count, provider.creations); assertEquals(1, provider.deletions.count { it == "image" })
    }
    @Test fun aChangedSourceWithIdenticalSizeAndTimestampIsNeverDeleted() = runBlocking {
        var changed = false
        provider.beforeRead = { id -> if (!changed && id.startsWith("copy")) {
            changed = true; provider.nodes.getValue("image").file!!.writeBytes(ByteArray(provider.originalBytes.size) { 7 })
        } }
        val op = run("MOVE")
        assertEquals("PARTIAL", op.phase)
        assertTrue(provider.nodes.containsKey("image")); assertFalse(provider.deletions.contains("image"))
        assertFalse(catalog.dao.media(media.id)!!.available)
        assertTrue(catalog.dao.media(media.id)!!.favorite)
    }
    @Test fun fullStorageLeavesOriginalAndRecognizableTemporary() = runBlocking {
        provider.denyWrite = true
        val op = run("TRASH", null)
        // Private trash bypasses the fixture's write rejection: test destination-provider exhaustion separately.
        assertEquals("COMPLETED", op.phase)
        media = catalog.dao.media(media.id)!!
        val restore = run("RESTORE")
        assertEquals("FAILED", restore.phase)
        assertNotNull(catalog.dao.trash(media.id)); assertTrue(files.trashFile(catalog.dao.trash(media.id)!!).exists())
        assertNotNull(restore.temporaryUri); assertFalse(provider.deletions.any { it.startsWith("copy") })
    }
    @Test fun cancelBeforeCopyPerformsNoWriteAndDuplicateCommandsShareJournal() = runBlocking {
        val first = files.enqueue(media, "TRASH", start = false)
        val second = files.enqueue(media, "TRASH", start = false)
        assertEquals(first, second)
        files.cancel(first); files.execute(first)
        assertEquals("CANCELLED", catalog.dao.operation(first)!!.phase)
        assertEquals(0, provider.creations); assertTrue(provider.nodes.containsKey("image")); assertTrue(catalog.dao.trash().isEmpty())
    }
    @Test fun trashIsVerifiedBeforeRemovalAndRestoreRetainsHiddenFavorite() = runBlocking {
        provider.beforeDelete = { id -> if (id == "image") {
            val pending = File(RuntimeEnvironment.getApplication().filesDir, "trash").listFiles()!!.single { it.extension == "bin" }
            assertEquals(provider.originalBytes.toList(), pending.readBytes().toList())
        } }
        val op = run("TRASH", null)
        assertEquals("COMPLETED", op.phase)
        val entry = catalog.dao.trash(media.id)!!
        assertEquals(FileOperations.SEVEN_DAYS, entry.expiresAt - entry.deletedAt)
        assertTrue(files.trashFile(entry).exists()); assertFalse(files.trashFile(entry).path.startsWith(RuntimeEnvironment.getApplication().cacheDir.path))
        assertTrue(catalog.readSnapshot().visibleMedia.isEmpty())
        provider.beforeDelete = {}
        provider.add("child", media.name, "occupied".toByteArray())
        catalog.hideAlbums(listOf(media.albumId), true)
        media = catalog.dao.media(media.id)!!
        val restored = run("RESTORE", catalog.dao.albums().single { it.id == media.albumId })
        assertEquals("COMPLETED", restored.phase); assertNull(catalog.dao.trash(media.id))
        val result = catalog.dao.media(media.id)!!
        assertEquals("image (1).png", result.name); assertTrue(result.favorite); assertFalse(result.trashed)
        assertFalse(catalog.readSnapshot().visible(result)); assertEquals(media.id, catalog.dao.collectionItems().single().mediaId)
        assertFalse(files.trashFile(entry).exists())
    }
    @Test fun corruptTrashCannotDestroyRecoverableCopy() = runBlocking {
        run("TRASH", null); media = catalog.dao.media(media.id)!!
        val entry = catalog.dao.trash(media.id)!!
        files.trashFile(entry).writeBytes(ByteArray(entry.size.toInt()) { 12 })
        val op = run("RESTORE")
        assertEquals("FAILED", op.phase); assertNotNull(catalog.dao.trash(media.id)); assertTrue(files.trashFile(entry).exists())
        assertTrue(provider.deletions.count { it == "image" } == 1)
    }
    @Test fun crashAfterProviderRenamedTemporaryCanRecoverWithoutCreatingAnotherCopy() = runBlocking {
        provider.failAfterRename = true
        val op = run("MOVE")
        assertEquals("FAILED", op.phase); assertTrue(provider.nodes.containsKey("image"))
        val count = provider.creations
        provider.failAfterRename = false; files.execute(op.id)
        assertEquals("COMPLETED", catalog.dao.operation(op.id)!!.phase); assertEquals(count, provider.creations)
    }
    @Test fun crashAfterSourceRemovalRecoversPublishedBytesAndPersonalLinks() = runBlocking {
        provider.failAfterDelete = true
        val op = run("MOVE")
        assertEquals("PARTIAL", op.phase); assertFalse(provider.nodes.containsKey("image"))
        provider.failAfterDelete = false; files.execute(op.id)
        assertEquals("COMPLETED", catalog.dao.operation(op.id)!!.phase)
        assertTrue(catalog.dao.media(media.id)!!.favorite)
        assertEquals(1, provider.deletions.count { it == "image" })
    }
    @Test fun expiryAndRestoreAreSerializedAndClockJumpStopsAutomaticPurge() = runBlocking {
        run("TRASH", null); media = catalog.dao.media(media.id)!!
        val entry = catalog.dao.trash(media.id)!!
        files.cleanupExpired() // Establish wall/monotonic/boot anchor.
        now = now.copy(wall = now.wall + FileOperations.SEVEN_DAYS + 1, elapsed = now.elapsed + FileOperations.SEVEN_DAYS + 1)
        val restore = files.enqueue(media, "RESTORE", destination.id, start = false)
        files.cleanupExpired(); assertTrue(files.trashFile(entry).exists())
        files.cancel(restore); files.execute(restore)
        files.cleanupExpired(); assertNull(catalog.dao.trash(media.id)); assertFalse(files.trashFile(entry).exists())
        val app = RuntimeEnvironment.getApplication()
        val guard = TrashClockGuard(app)
        assertFalse(guard.accept(now.copy(wall = now.wall + 30 * FileOperations.SEVEN_DAYS)))
        assertTrue(guard.blocked())
        assertFalse(guard.accept(now.copy(wall = now.wall + 30 * FileOperations.SEVEN_DAYS, elapsed = now.elapsed + 100)))
    }
    @Test fun selectedRestoreRejectsTrashMetadataChangedAfterConfirmation() = runBlocking {
        run("TRASH", null)
        media = catalog.dao.media(media.id)!!
        val frozen = catalog.dao.trash(media.id)!!
        val updated = frozen.copy(expiresAt = frozen.expiresAt + 1)
        catalog.dao.saveTrash(updated)
        val operationCount = catalog.dao.operations().size
        try {
            files.enqueue(media, "RESTORE", destination.id, start = false, expectedTrash = frozen)
            fail("A stale selection must not enqueue a restore")
        } catch (_: IllegalArgumentException) { }
        assertEquals(operationCount, catalog.dao.operations().size)
        assertTrue(files.trashFile(updated).exists())
        val id = files.enqueue(media, "RESTORE", destination.id, start = false, expectedTrash = updated)
        assertEquals("RESTORE", catalog.dao.operation(id)!!.kind)
    }
    @Test fun unsupportedPublicationLeavesOriginalAndCancellationCleansOwnedTemporary() = runBlocking {
        provider.canRename = false
        val op = run("MOVE")
        assertEquals("FAILED", op.phase); assertTrue(provider.nodes.containsKey("image"))
        files.cancel(op.id); files.execute(op.id)
        assertEquals("CANCELLED", catalog.dao.operation(op.id)!!.phase)
        assertTrue(provider.nodes.containsKey("image")); assertEquals(1, provider.nodes.values.count { it.file != null })
    }
    @Test fun deniedTemporaryCleanupIsRecordedWithoutEscapingTheProcessedCommand() = runBlocking {
        provider.canRename = false
        val op = run("COPY")
        assertEquals("FAILED", op.phase); assertNotNull(op.temporaryUri)
        provider.denyDelete = true; files.cancel(op.id); files.execute(op.id)
        val result = catalog.dao.operation(op.id)!!
        assertEquals("FAILED", result.phase); assertTrue(result.message!!.startsWith("Temporaneo conservato"))
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.node(op.temporaryUri!!).file!!.exists())
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun insufficientPrivateSpaceNeverTurnsTrashIntoPermanentDeletion() = runBlocking {
        ShadowStatFs.registerStats(RuntimeEnvironment.getApplication().filesDir, 100, 0, 0)
        val op = run("TRASH", null)
        assertEquals("FAILED", op.phase); assertTrue(op.message!!.contains("Spazio insufficiente"))
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.deletions.isEmpty())
        assertTrue(catalog.dao.trash().isEmpty())
    }
    @Test fun revokedPermissionOnRetryKeepsOriginalAndVerifiedCopy() = runBlocking {
        provider.denyDelete = true
        val op = run("MOVE")
        assertEquals("PARTIAL", op.phase)
        provider.revoked = true
        files.execute(op.id)
        assertEquals("PARTIAL", catalog.dao.operation(op.id)!!.phase)
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.deletions.isEmpty())
        assertTrue(provider.node(op.publishedUri!!).file!!.exists())
    }
    @Test fun explicitKeepCopyNeverDeletesOriginalOrTransfersFavorite() = runBlocking {
        provider.denyDelete = true
        val op = run("MOVE")
        files.keepCopy(op.id)
        val resolved = catalog.dao.operation(op.id)!!
        assertEquals("COPY_ONLY", resolved.phase); assertEquals("COPY", resolved.kind)
        assertNotEquals(media.id, resolved.resultMediaId)
        assertTrue(catalog.dao.media(media.id)!!.favorite)
        assertFalse(catalog.dao.media(resolved.resultMediaId)!!.favorite)
        provider.denyDelete = false; files.execute(op.id)
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.deletions.isEmpty())
    }
    @Test fun aFrozenPurgeCannotDeleteAnotherVersionAfterRestoreAndTrashAgain() = runBlocking {
        run("TRASH", null); val first = catalog.dao.trash(media.id)!!
        media = catalog.dao.media(media.id)!!; run("RESTORE")
        media = catalog.dao.media(media.id)!!; run("TRASH", null)
        val second = catalog.dao.trash(media.id)!!
        assertNotEquals(first.operationId, second.operationId)
        files.purge(listOf(first), explicit = true)
        assertEquals(second, catalog.dao.trash(media.id)); assertTrue(files.trashFile(second).exists())
        assertTrue(catalog.dao.operations().any { it.kind == "PURGE" && it.phase == "SKIPPED" })
        files.purge(listOf(second), explicit = true)
        assertNull(catalog.dao.trash(media.id)); assertFalse(files.trashFile(second).exists())
        assertTrue(catalog.dao.operations().any { it.kind == "PURGE" && it.phase == "COMPLETED" })
    }
    @Test fun hiddenAlbumAfterConfirmationPreventsPendingNewWrites() = runBlocking {
        val id = files.enqueue(media, "TRASH", start = false)
        catalog.hideAlbums(listOf(media.albumId), true)
        files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        assertTrue(provider.deletions.isEmpty()); assertTrue(provider.nodes.containsKey("image"))
    }
    @Test fun directDeleteVerifiesOriginalBeforeRemovingPrivateRecoveryCopy() = runBlocking {
        val id = files.enqueue(media, "TRASH", start = false, permanent = true)
        files.execute(id)
        val op = catalog.dao.operation(id)!!
        assertEquals("COMPLETED", op.phase)
        assertNotNull(op.digest)
        assertEquals(provider.originalBytes.size.toLong(), op.bytes)
        assertFalse(provider.nodes.containsKey("image"))
        assertNull(catalog.dao.trash(media.id))
        assertTrue(catalog.dao.operations().any { it.kind == "PURGE" && it.phase == "COMPLETED" })
        files.execute(id)
        assertEquals(1, provider.deletions.count { it == "image" })
    }
    @Test fun directDeleteDeniedByProviderRetainsOriginalAndVerifiedRecoveryCopy() = runBlocking {
        provider.denyDelete = true
        val id = files.enqueue(media, "TRASH", start = false, permanent = true)
        files.execute(id)
        assertEquals("PARTIAL", catalog.dao.operation(id)!!.phase)
        assertTrue(provider.nodes.containsKey("image"))
        assertTrue(File(java.net.URI(catalog.dao.operation(id)!!.publishedUri!!)).exists())
        assertFalse(catalog.dao.operations().any { it.kind == "PURGE" })
    }
    @Test fun emptyAlbumCreationReusesExistingFolderWithoutSuffixOrDataChanges() = runBlocking {
        val first = files.createFolder(destination.id, "Empty album", start = false, reuseExisting = true)
        files.execute(first)
        val original = catalog.dao.operation(first)!!.resultMediaId
        val count = provider.creations
        val second = files.createFolder(destination.id, "Empty album", start = false, reuseExisting = true)
        files.execute(second)
        assertEquals("COMPLETED", catalog.dao.operation(second)!!.phase)
        assertEquals(original, catalog.dao.operation(second)!!.resultMediaId)
        assertEquals(count, provider.creations)
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun createFolderIsRealKeepsBothAndImmediatelyAcceptsMedia() = runBlocking {
        val id = files.createFolder(destination.id, "Variants", start = false)
        files.execute(id)
        val op = catalog.dao.operation(id)!!
        assertEquals(op.message, "COMPLETED", op.phase)
        val folder = catalog.dao.albums().single { it.id == op.resultMediaId }
        assertEquals("Variants", provider.node(folder.documentUri).name)
        val copied = run("COPY", folder)
        assertEquals("COMPLETED", copied.phase)
        assertEquals(folder.id, catalog.dao.media(copied.resultMediaId)!!.albumId)
        val second = files.createFolder(destination.id, "Variants", start = false); files.execute(second)
        assertEquals("Variants (1)", catalog.dao.operation(second)!!.finalName)
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun folderCreationInterruptedAfterProviderEffectRecoversWithoutDuplicate() = runBlocking {
        val id = files.createFolder(destination.id, "New folder", start = false)
        provider.failAfterCreate = true; files.execute(id)
        assertEquals("FAILED", catalog.dao.operation(id)!!.phase)
        val count = provider.creations
        provider.failAfterCreate = false; files.execute(id)
        assertEquals("COMPLETED", catalog.dao.operation(id)!!.phase)
        assertEquals(count, provider.creations)
        assertEquals(1, provider.nodes.values.count { it.name == "New folder" })
    }
    @Test fun concurrentRequestsAndWorkersDeleteExactlyOnce() = runBlocking {
        val ids = coroutineScope { (1..8).map { async { files.enqueue(media, "TRASH", start = false) } }.awaitAll() }
        assertEquals(1, ids.distinct().size); assertEquals(1, catalog.dao.operations().size)
        coroutineScope { ids.map { async { files.execute(it) } }.awaitAll() }
        assertEquals(1, provider.deletions.count { it == "image" }); assertEquals(1, catalog.dao.trash().size)
        assertEquals("COMPLETED", catalog.dao.operation(ids.first())!!.phase)
    }
    @Test fun copyingDoesNotBlockPlaybackOnTheOriginalDuringIndexing() = runBlocking {
        val id = files.enqueue(media, "COPY", destination.id, start = false)
        val operation = catalog.dao.operation(id)!!
        catalog.dao.saveOperation(operation.copy(phase = "INDEXING"))
        assertTrue(catalog.readSnapshot().usable(media))
        catalog.dao.saveOperation(operation.copy(kind = "MOVE", phase = "DELETING"))
        assertFalse(catalog.readSnapshot().usable(media))
    }
    @Test fun batchesReportRejectedElementsWithoutStoppingValidFiles() = runBlocking {
        val other = provider.add("child", "other.png", byteArrayOf(1, 2, 3))
        SourceScanner(catalog).scan(catalog.dao.sources().single())
        val stale = catalog.dao.media().single { it.name == other.name }.copy(uri = "content://stale/document/wrong")
        val ids = files.enqueueAll(listOf(media, stale, media), "COPY", destination.id, start = false)
        assertEquals(2, ids.size)
        ids.forEach { files.execute(it) }
        val results = ids.map { catalog.dao.operation(it)!! }
        assertEquals(listOf("COMPLETED", "SKIPPED"), results.map { it.phase })
        assertTrue(results.last().message!!.contains("già cambiato"))
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.nodes.containsKey(other.id))
        assertTrue(provider.deletions.isEmpty())
    }
    @Test fun cancellingPartialTrashIsDurableEvenWhenPrivateCleanupNeedsRetry() = runBlocking {
        provider.denyDelete = true
        val op = run("TRASH", null)
        var rejectCleanup = true
        files = FileOperations(catalog, clock = { now }, deletePrivate = { if (rejectCleanup) false else it.delete() })
        files.resolveTrash(op.id, keepArchive = false)
        assertEquals("DISCARD_TRASH_BACKUP", catalog.dao.operation(op.id)!!.kind)
        assertEquals("PARTIAL", catalog.dao.operation(op.id)!!.phase)
        assertTrue(File(files.directory, "${op.id}.bin").exists())
        provider.denyDelete = false; rejectCleanup = false
        files.execute(op.id)
        assertEquals("ORIGINAL_ONLY", catalog.dao.operation(op.id)!!.phase)
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.deletions.isEmpty())
        assertFalse(File(files.directory, "${op.id}.bin").exists())
        assertTrue(catalog.dao.media(media.id)!!.favorite); assertTrue(catalog.dao.trash().isEmpty())
    }
    @Test fun archiveResolutionKeepsOriginalReferencesAndCreatesIndependentTrashCopy() = runBlocking {
        provider.denyDelete = true
        val op = run("TRASH", null)
        now = now.copy(wall = now.wall + 1000)
        files.resolveTrash(op.id, keepArchive = true)
        val resolved = catalog.dao.operation(op.id)!!
        val entry = catalog.dao.trash(resolved.resultMediaId)!!
        assertEquals("ARCHIVE", resolved.kind); assertEquals("COMPLETED", resolved.phase)
        assertEquals(resolved.mediaId, entry.mediaId); assertNotEquals(media.id, entry.mediaId)
        assertEquals(now.wall, entry.deletedAt); assertEquals(now.wall + FileOperations.SEVEN_DAYS, entry.expiresAt)
        assertFalse(catalog.dao.media(entry.mediaId)!!.favorite)
        assertTrue(catalog.readSnapshot().usable(catalog.dao.media(media.id)!!))
        assertEquals(media.id, catalog.dao.collectionItems().single().mediaId)
        provider.denyDelete = false; files.execute(op.id)
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.deletions.isEmpty())
        files.purge(listOf(entry), explicit = true)
        assertNull(catalog.dao.trash(entry.mediaId)); assertTrue(provider.nodes.containsKey("image"))
    }
    @Test fun changedOriginalPreventsDiscardingItsLastVerifiedBackup() = runBlocking {
        provider.denyDelete = true
        val op = run("TRASH", null)
        provider.nodes.getValue("image").file!!.writeBytes(ByteArray(provider.originalBytes.size) { 8 })
        files.resolveTrash(op.id, keepArchive = false)
        assertEquals("PARTIAL", catalog.dao.operation(op.id)!!.phase)
        assertTrue(File(files.directory, "${op.id}.bin").exists()); assertTrue(provider.deletions.isEmpty())
        assertFalse(catalog.dao.media(media.id)!!.available)
        files.resolveTrash(op.id, keepArchive = true)
        assertEquals("COMPLETED", catalog.dao.operation(op.id)!!.phase)
        val archived = catalog.dao.trash(media.id)!!
        assertTrue(catalog.dao.media(media.id)!!.favorite); assertTrue(files.trashFile(archived).exists())
    }
    @Test fun restoreCleanupFailurePreservesBackupAndRetryNeverDeletesRestoredMedia() = runBlocking {
        run("TRASH", null); media = catalog.dao.media(media.id)!!
        val entry = catalog.dao.trash(media.id)!!
        var rejectCleanup = true
        files = FileOperations(catalog, clock = { now }, deletePrivate = { if (rejectCleanup) false else it.delete() })
        val restore = run("RESTORE")
        assertEquals("PARTIAL", restore.phase)
        val restored = catalog.dao.media(media.id)!!
        assertFalse(restored.trashed); assertTrue(restored.favorite)
        assertTrue(files.trashFile(entry).exists()); assertEquals(entry, catalog.dao.trash(media.id))
        files.purge(listOf(entry), explicit = true)
        assertTrue(files.trashFile(entry).exists())
        val creations = provider.creations
        rejectCleanup = false; files.execute(restore.id)
        assertEquals("COMPLETED", catalog.dao.operation(restore.id)!!.phase)
        assertEquals(creations, provider.creations); assertNull(catalog.dao.trash(media.id))
        assertTrue(provider.node(restored.uri).file!!.exists()); assertFalse(files.trashFile(entry).exists())
    }
    @Test fun retainedRestoreBackupCanBeRecoveredAsAnotherCopyWithoutStealingFavorites() = runBlocking {
        run("TRASH", null); media = catalog.dao.media(media.id)!!
        val entry = catalog.dao.trash(media.id)!!
        files = FileOperations(catalog, clock = { now }, deletePrivate = { false })
        val restore = run("RESTORE", name = "restored.png")
        assertEquals("PARTIAL", restore.phase)
        files.keepRestoredBackup(restore.id)
        assertEquals("RESTORED_WITH_BACKUP", catalog.dao.operation(restore.id)!!.phase)
        assertEquals(entry.expiresAt, catalog.dao.trash(media.id)!!.expiresAt)
        media = catalog.dao.media(media.id)!!
        val copy = run("RESTORE_COPY", name = entry.originalName)
        assertEquals(copy.message, "COMPLETED", copy.phase)
        val result = catalog.dao.media(copy.resultMediaId)!!
        assertNotEquals(media.id, result.id); assertEquals(entry.originalName, result.name); assertFalse(result.favorite)
        assertTrue(catalog.dao.media(media.id)!!.favorite); assertEquals(media.id, catalog.dao.collectionItems().single().mediaId)
        assertEquals(entry, catalog.dao.trash(media.id)); assertTrue(files.trashFile(entry).exists())
        assertEquals(provider.originalBytes.toList(), provider.node(result.uri).file!!.readBytes().toList())
    }
    @Test fun legacyCompletedRestoreRecoversUntrackedPrivateCopyWithoutRenewingExpiry() = runBlocking {
        run("TRASH", null); media = catalog.dao.media(media.id)!!
        val entry = catalog.dao.trash(media.id)!!
        var rejectCleanup = true
        files = FileOperations(catalog, clock = { now }, deletePrivate = { if (rejectCleanup) false else it.delete() })
        val restore = run("RESTORE")
        catalog.dao.removeTrash(media.id)
        catalog.dao.saveOperation(restore.copy(phase = "COMPLETED")) // Exact 0.2-dev interrupted cleanup state.
        now = now.copy(wall = now.wall + 3 * 24 * 60 * 60_000L)
        files.recoverInterrupted()
        assertEquals("PARTIAL", catalog.dao.operation(restore.id)!!.phase)
        assertEquals(entry, catalog.dao.trash(media.id)); assertTrue(files.trashFile(entry).exists())
        rejectCleanup = false; files.execute(restore.id)
        assertEquals("COMPLETED", catalog.dao.operation(restore.id)!!.phase)
        assertNull(catalog.dao.trash(media.id)); assertFalse(files.trashFile(entry).exists())
        assertTrue(catalog.dao.media(media.id)!!.favorite)
    }
    @Test fun legacyBackupCannotReplaceANewerTrashVersion() = runBlocking {
        run("TRASH", null); media = catalog.dao.media(media.id)!!
        val original = catalog.dao.trash(media.id)!!
        files = FileOperations(catalog, clock = { now }, deletePrivate = { false })
        val restore = run("RESTORE")
        catalog.dao.removeTrash(media.id); catalog.dao.saveOperation(restore.copy(phase = "COMPLETED"))
        media = catalog.dao.media(media.id)!!; run("TRASH", null)
        val newer = catalog.dao.trash(media.id)!!
        files.recoverInterrupted()
        assertEquals(newer, catalog.dao.trash(media.id))
        val recovered = catalog.dao.trash().single { it.fileName == original.fileName }
        assertNotEquals(media.id, recovered.mediaId); assertFalse(catalog.dao.media(recovered.mediaId)!!.favorite)
        assertEquals(original.expiresAt, recovered.expiresAt)
        assertTrue(files.trashFile(recovered).exists()); assertTrue(files.trashFile(newer).exists())
    }
    @Test fun authorizingExistingDestinationPreservesMusicRoleHiddenAlbumsAndStableIds() = runBlocking {
        val source = catalog.dao.sources().single()
        catalog.dao.saveSource(source.copy(role = "MUSIC"))
        val root = catalog.dao.albums().single { it.documentKey.endsWith(":root") }
        catalog.hideAlbums(listOf(root.id), true)
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(root.id, catalog.authorizeDestination(Uri.parse(source.treeUri), SourceRole.VISUAL, flags))
        assertEquals("MUSIC", catalog.dao.sources().single().role)
        assertTrue(catalog.dao.albums().single { it.id == root.id }.hidden)
        assertEquals(media.id, catalog.dao.collectionItems().single().mediaId)
        assertTrue(provider.deletions.isEmpty()); assertEquals(0, provider.creations)
        try { catalog.authorizeDestination(Uri.parse(source.treeUri), SourceRole.MIXED, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); fail("Write grant required") }
        catch (_: IllegalArgumentException) { }
        assertEquals("MUSIC", catalog.dao.sources().single().role)
    }
    @Test fun trashRecoveryDoesNotRestartSevenDaysAfterAnInterruptedRemoval() = runBlocking {
        val deletion = now.wall
        provider.failAfterDelete = true
        val op = run("TRASH", null)
        assertEquals("PARTIAL", op.phase); assertFalse(provider.nodes.containsKey("image"))
        assertEquals(deletion, op.sourceDeletionStartedAt)
        now = now.copy(wall = now.wall + 3 * 24 * 60 * 60 * 1000L, elapsed = now.elapsed + 3 * 24 * 60 * 60 * 1000L)
        provider.failAfterDelete = false; files.execute(op.id)
        val entry = catalog.dao.trash(media.id)!!
        assertEquals(deletion, entry.deletedAt)
        assertEquals(deletion + FileOperations.SEVEN_DAYS, entry.expiresAt)
        assertEquals(1, provider.deletions.count { it == "image" })
    }
}

/** Actual file bytes and deliberately hostile provider outcomes; never user files. */
class OperationDocuments : DocumentsProvider() {
    data class Node(val id: String, var parent: String?, var name: String, val mime: String, val file: File?)
    val nodes = linkedMapOf<String, Node>()
    val originalBytes = ByteArray(300_000) { (it % 251).toByte() }
    val deletions = mutableListOf<String>()
    var creations = 0
    var childQueries = 0
    var denyDelete = false; var denyWrite = false; var failAfterRename = false; var failAfterDelete = false; var canRename = true
    var revoked = false
    val unknownSizeIds = mutableSetOf<String>()
    var failAfterCreate = false
    var beforeRead: (String) -> Unit = {}
    var beforeDelete: (String) -> Unit = {}
    override fun onCreate(): Boolean {
        nodes["root"] = Node("root", null, "Fixture", DocumentsContract.Document.MIME_TYPE_DIR, null)
        nodes["child"] = Node("child", "root", "Origin", DocumentsContract.Document.MIME_TYPE_DIR, null)
        nodes["destination"] = Node("destination", "root", "Destination", DocumentsContract.Document.MIME_TYPE_DIR, null)
        val file = File(context!!.filesDir, "fixture-original.png"); file.writeBytes(originalBytes)
        nodes["image"] = Node("image", "child", "image.png", "image/png", file)
        return true
    }
    fun node(uri: String) = nodes.getValue(DocumentsContract.getDocumentId(Uri.parse(uri)))
    fun add(parent: String, name: String, bytes: ByteArray): Node {
        val id = "copy${++creations}"
        val file = File(context!!.filesDir, "$id-fixture"); file.writeBytes(bytes)
        return Node(id, parent, name, "image/png", file).also { nodes[id] = it }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current: String? = documentId
        repeat(10) { if (current == parentDocumentId) return true; current = nodes[current]?.parent }
        return false
    }
    private fun cursor(projection: Array<out String>?, selected: List<Node>): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { selected.forEach { node -> addRow(columns.map<String, Any?> { column -> when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
            DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mime
            DocumentsContract.Document.COLUMN_SIZE -> if (node.id in unknownSizeIds) null else node.file?.length() ?: 0L
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1L // Content changes deliberately evade metadata checks.
            DocumentsContract.Document.COLUMN_FLAGS -> if (node.file == null) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    (if (canRename) DocumentsContract.Document.FLAG_SUPPORTS_RENAME else 0)
            else -> null
        } }.toTypedArray()) } }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())
    override fun getDocumentType(documentId: String): String {
        if (revoked) throw SecurityException("Permission revoked")
        return nodes[documentId]?.mime ?: throw FileNotFoundException("Removed document")
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (revoked) throw SecurityException("Permission revoked")
        return cursor(projection, listOf(nodes[documentId] ?: throw FileNotFoundException("Removed document")))
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        childQueries++
        if (parentDocumentId !in nodes) throw FileNotFoundException("Removed folder")
        if (revoked) throw SecurityException("Permission revoked")
        return cursor(projection, nodes.values.filter { it.parent == parentDocumentId })
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val id = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            val folder = "folder${++creations}"
            nodes[folder] = Node(folder, parentDocumentId, displayName, mimeType, null); folder
        } else add(parentDocumentId, displayName, byteArrayOf()).id
        if (failAfterCreate) throw IllegalStateException("Provider interrupted after creating folder")
        return id
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val node = nodes.getValue(documentId)
        require(nodes.values.none { it.parent == node.parent && it.name == displayName && it.id != documentId })
        val renamed = node.copy(id = "renamed-$documentId", name = displayName)
        nodes.remove(documentId); nodes[renamed.id] = renamed
        if (failAfterRename) throw IllegalStateException("Provider interrupted after rename")
        return renamed.id
    }
    override fun deleteDocument(documentId: String) {
        if (denyDelete) throw SecurityException("Provider denied deletion")
        beforeDelete(documentId)
        val node = nodes.remove(documentId) ?: throw FileNotFoundException("Already removed")
        check(node.file!!.delete()); deletions.add(documentId)
        if (failAfterDelete && documentId == "image") throw IllegalStateException("Provider interrupted after removal")
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw FileNotFoundException("Missing document")
        if (mode.contains('w') && denyWrite) throw IOException("No space left")
        if (mode == "r") beforeRead(documentId)
        return ParcelFileDescriptor.open(node.file!!, ParcelFileDescriptor.parseMode(mode))
    }
}
