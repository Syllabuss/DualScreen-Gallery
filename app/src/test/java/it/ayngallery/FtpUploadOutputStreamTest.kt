package it.ayngallery

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FtpUploadOutputStreamTest {
    @Test fun oversizedUploadsStopBeforeWritingPastTheirQuota() {
        val target = ByteArrayOutputStream()
        val stream = FtpUploadOutputStream(target, { 100L }, limit = 10, reserve = 4)
        stream.write(ByteArray(8))
        assertThrows(IOException::class.java) { stream.write(ByteArray(3)) }
        assertEquals(8, target.size())
    }
    @Test fun storageConsumedByAnotherOperationStopsTheNextChunkWithoutUsingTheReserve() {
        val target = ByteArrayOutputStream()
        var free = 100L
        val stream = FtpUploadOutputStream(target, { free }, limit = 100, reserve = 20)
        stream.write(ByteArray(10))
        free = 25
        assertThrows(IOException::class.java) { stream.write(ByteArray(6)) }
        assertEquals(10, target.size())
        stream.write(ByteArray(5))
        assertEquals(15, target.size())
    }
}
