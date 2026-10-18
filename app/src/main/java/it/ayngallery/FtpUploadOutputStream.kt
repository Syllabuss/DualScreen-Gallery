package it.ayngallery

import java.io.IOException
import java.io.OutputStream

/** Keep headroom on internal storage even when the eventual destination is an SD card. */
internal class FtpUploadOutputStream(private val output: OutputStream, private val available: () -> Long,
    private val limit: Long = MAX_BYTES, private val reserve: Long = RESERVED_BYTES) : OutputStream() {
    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
        const val RESERVED_BYTES = 128L * 1024 * 1024
        private val writes = Any()
    }
    private var written = 0L
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        synchronized(writes) {
            if (length.toLong() > limit - written || available() - reserve < length)
                throw IOException("FTP upload limit reached or insufficient internal storage")
            output.write(bytes, offset, length)
            written += length
        }
    }
    override fun flush() = output.flush()
    override fun close() = output.close()
}
