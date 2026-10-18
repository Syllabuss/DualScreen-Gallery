package it.ayngallery

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
class ExplorerTransferTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @org.junit.Before fun storage() { org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir, 1_000_000, 1_000_000, 1_000_000) }
    private val entry = ExplorerEntry(ExplorerLocation("smb:fixture/share", ""), "song.mp3", "song.mp3", false, 3, 1, true, true)
    @Test fun sourceExceedingDeclaredSizeStopsBeforeWritingAndNeverPublishesOrDeletes() = runBlocking {
        var reads = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int = error("Bulk reads only")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return length }
        }
        var published = false; var deleted = false
        val failure = runCatching { ExplorerTransfer(app).copy(entry, ExplorerLocation("saf:fixture", "root"), true,
            stat = { entry }, read = { it(endless) },
            publish = { _, _, digest, _ -> published = true; "target" to digest }, delete = { deleted = true }) }
        assertTrue(failure.isFailure); assertEquals(1, reads)
        assertFalse(published); assertFalse(deleted)
        val operation = app.catalog.dao.operations().single()
        assertEquals("FAILED", operation.phase)
        assertFalse(java.io.File(java.net.URI(operation.temporaryUri!!)).exists())
        app.onTerminate()
    }
    @Test fun digestChecksTheLimitBeforeWritingAnExcessChunk() {
        val output = java.io.ByteArrayOutputStream()
        assertTrue(runCatching { networkDigest(ByteArray(100).inputStream(), output) { require(it <= 3) } }.isFailure)
        assertEquals(0, output.size())
    }
    @Test fun verifiedMoveDeletesOnlyAfterPublicationAndSourceRecheck() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        var published = false; var deleted = false
        val result = ExplorerTransfer(app).copy(entry, ExplorerLocation("saf:fixture", "root"), true,
            stat = { entry }, read = { it(bytes.inputStream()) },
            publish = { op, stage, digest, save -> assertFalse(deleted); assertArrayEquals(bytes, stage.readBytes()); save(op.copy(finalName = "song.mp3")); published = true; "target" to digest },
            delete = { assertTrue(published); deleted = true })
        assertTrue(deleted); assertEquals("COMPLETED", result.phase)
        assertEquals("target", app.catalog.dao.operation(result.id)!!.publishedUri)
        app.onTerminate()
    }
    @Test fun changedSourceAfterCopyNeverDeletesTheOriginal() = runBlocking {
        var checks = 0; var deleted = false
        val result = runCatching { ExplorerTransfer(app).copy(entry, ExplorerLocation("saf:fixture", "root"), true,
            stat = { if (++checks == 1) entry else entry.copy(modified = 2) }, read = { it(byteArrayOf(1, 2, 3).inputStream()) },
            publish = { _, _, digest, _ -> "target" to digest }, delete = { deleted = true }) }
        assertTrue(result.isFailure); assertFalse(deleted)
        assertEquals("PARTIAL", app.catalog.dao.operations().last().phase)
        app.onTerminate()
    }
    @Test fun mismatchingDestinationAndTruncatedSourceNeverDelete() = runBlocking {
        var deleted = false
        for (short in listOf(false, true)) {
            val result = runCatching { ExplorerTransfer(app).copy(entry, ExplorerLocation("saf:fixture", "root"), true,
                stat = { entry }, read = { it(if (short) byteArrayOf(1).inputStream() else byteArrayOf(1, 2, 3).inputStream()) },
                publish = { _, _, _, _ -> "target" to FileDigest(3, "wrong") }, delete = { deleted = true }) }
            assertTrue(result.isFailure)
        }
        assertFalse(deleted); app.onTerminate()
    }
}
