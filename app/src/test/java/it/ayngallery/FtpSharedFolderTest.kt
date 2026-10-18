package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class FtpSharedFolderTest {
    @Test fun legacyFtpToggleNeverAddsAHomeCategory() {
        val order = listOf("FAVORITE", "VIDEO", "MUSIC", "ALBUM", "PHOTO")
        assertEquals(listOf("FAVORITE", "VIDEO", "MUSIC", "ALBUM", "PHOTO"),
            visibleNavbarTabs(GalleryPreferences(navbarOrder = order, ftpEnabled = true)).map { it.key })
        assertEquals(order, visibleNavbarTabs(GalleryPreferences(navbarOrder = order)).map { it.key })
        assertEquals(listOf("VIDEO", "MUSIC", "ALBUM", "PHOTO"),
            visibleNavbarTabs(GalleryPreferences(navbarOrder = order, disabledCategories = setOf("FAVORITE"), ftpEnabled = true)).map { it.key })
    }
    @Test fun relativePathsRemainInsideTheSharedRootAndPreserveUnicodeNames() {
        assertEquals(listOf("Music", "新しい曲.mp3"), FtpSharedFolder.components("../新しい曲.mp3", "/Music/Album"))
        assertEquals(listOf("Download", "file.zip"), FtpSharedFolder.components("/Download//./file.zip", "/Music"))
        assertEquals(emptyList<String>(), FtpSharedFolder.components("/"))
    }
    @Test fun traversalAndPrivateMetadataAreRejectedBeforeAnyProviderIo() {
        for (path in listOf("../file", "/../../file", "/.ayngallery-pin", "/Album/.secret", "Album\\file", "file\u0000.zip"))
            assertTrue(path, runCatching { FtpSharedFolder.components(path) }.isFailure)
        assertTrue(runCatching { FtpSharedFolder.components("x/".repeat(65)) }.isFailure)
    }
}
