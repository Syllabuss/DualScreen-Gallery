package it.ayngallery

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream

internal data class ExplorerZipEntry(val parts: List<String>, val file: File?, val size: Long)
/** Validate every path/size before staging; extraction never writes through archive paths to a provider. */
internal fun stageExplorerZip(archive: File, stage: File, check: () -> Unit): List<ExplorerZipEntry> {
    require(archive.length() in 1..16L * 1024 * 1024)
    require(stage.isDirectory || stage.mkdirs())
    return ZipFile(archive).use { zip ->
        require(zip.size() in 1..1000)
        val entries = zip.entries().asSequence().toList()
        var total = 0L
        val names = hashSetOf<String>()
        val prepared = entries.map { entry ->
            val name = entry.name.removeSuffix("/")
            require(name.length <= 1024 && !name.startsWith('/') && !name.contains('\\'))
            val parts = name.split('/')
            require(parts.size <= 32 && parts.all { it.isNotEmpty() && ':' !in it })
            parts.forEach(FileNames::validateBase)
            require(names.add(parts.joinToString("/").lowercase(java.util.Locale.ROOT)))
            require(entry.size in 0..256L * 1024 * 1024 && entry.compressedSize >= 0 && entry.crc >= 0)
            total += entry.size; require(total <= 1024L * 1024 * 1024)
            require(entry.size <= maxOf(1024L * 1024, entry.compressedSize * 100))
            entry to parts
        }
        require(total < stage.usableSpace / 2)
        prepared.mapIndexed { index, (entry, parts) ->
            check()
            if (entry.isDirectory) ExplorerZipEntry(parts, null, 0) else {
                val file = File(stage, "$index.bin")
                val crc = CRC32()
                val digest = zip.getInputStream(entry).use { input -> CheckedInputStream(input, crc).use { checked ->
                    file.outputStream().use { output -> networkDigest(checked, output) { check(); require(it <= entry.size) } }
                } }
                require(digest.bytes == entry.size && crc.value == entry.crc)
                ExplorerZipEntry(parts, file, digest.bytes)
            }
        }
    }
}
