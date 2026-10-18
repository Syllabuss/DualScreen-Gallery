package it.ayngallery
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.junit.Test
import org.junit.Assert.*
class ExplorerZipTest {
    private fun archive(entries: Map<String, ByteArray>, body: (File, File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("zip-check").toFile()
        try {
            val zip = File(root, "test.zip")
            ZipOutputStream(zip.outputStream()).use { output -> entries.forEach { (name, bytes) -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() } }
            body(zip, File(root, "stage"))
        } finally { root.deleteRecursively() }
    }
    @Test fun nestedFilesAreStagedUnderGeneratedNamesAndKeepTheirExactBytes() {
        archive(mapOf("album/a.png" to byteArrayOf(1, 2, 3), "album/b.txt" to "hello".toByteArray())) { zip, stage ->
            val result = stageExplorerZip(zip, stage) {}
            assertEquals(listOf("album", "a.png"), result.first().parts)
            assertArrayEquals(byteArrayOf(1, 2, 3), result.first().file!!.readBytes())
            assertTrue(result.all { it.file!!.canonicalFile.parentFile == stage.canonicalFile })
        }
    }
    @Test fun traversalDriveNamesAndExcessiveExpansionAreRejectedBeforeStagingFiles() {
        for (name in listOf("../outside.png", "/outside.png", "C:/outside.png", "folder/../outside.png")) {
            archive(mapOf(name to byteArrayOf(1))) { zip, stage -> assertTrue(runCatching { stageExplorerZip(zip, stage) {} }.isFailure); assertTrue(stage.listFiles().orEmpty().isEmpty()) }
        }
        archive(mapOf("bomb.bin" to ByteArray(2 * 1024 * 1024))) { zip, stage -> assertTrue(runCatching { stageExplorerZip(zip, stage) {} }.isFailure); assertTrue(stage.listFiles().orEmpty().isEmpty()) }
    }
}
