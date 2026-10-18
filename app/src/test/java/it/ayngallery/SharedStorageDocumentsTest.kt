package it.ayngallery

import android.os.Environment
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, shadows = [SharedStorageDocumentsTest.StorageEnvironment::class])
class SharedStorageDocumentsTest {
    @org.robolectric.annotation.Implements(Environment::class)
    class StorageEnvironment : ShadowEnvironment() {
        companion object {
            var permitted = false
            @JvmStatic @org.robolectric.annotation.Implementation fun isExternalStorageManager() = permitted
        }
    }

    @get:Rule val temporary = TemporaryFolder()
    @Test fun pathsCannotEscapeSharedStorageOrEnterPrivateAndroidFolders() {
        val root = temporary.newFolder("storage")
        assertEquals(File(root, "Download/picture.png"), SharedStorageDocuments.resolve(root, "shared/Download/picture.png"))
        assertEquals(File(root, "Android/media/music.mp3"), SharedStorageDocuments.resolve(root, "shared/Android/media/music.mp3"))
        listOf("shared/../secret", "shared/android/data/a", "shared/Android/data/a", "shared/Download/../Android/obb/a", "other/a").forEach {
            try { SharedStorageDocuments.resolve(root, it); fail(it) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun broadPermissionIsRequiredAndRevocationStopsReads() {
        val provider = Robolectric.buildContentProvider(SharedStorageDocuments::class.java).create().get()
        StorageEnvironment.permitted = false
        assertEquals(0, provider.queryRoots(null).use { it.count })
        try { provider.queryDocument("shared", null); fail("permission required") } catch (_: IllegalStateException) { }
        StorageEnvironment.permitted = true
        assertEquals(1, provider.queryRoots(null).use { it.count })
        assertEquals(1, provider.queryDocument("shared", null).use { it.count })
        StorageEnvironment.permitted = false
        try { provider.queryChildDocuments("shared", null, null as String?); fail("revoked") } catch (_: IllegalStateException) { }
    }
    @Test fun selectedDocumentsUseExistingReadWriteAndNonRecursiveDeleteOperations() {
        StorageEnvironment.permitted = true
        Environment.getExternalStorageDirectory().mkdirs()
        val provider = Robolectric.buildContentProvider(SharedStorageDocuments::class.java).create().get()
        val folder = provider.createDocument("shared", DocumentsContract.Document.MIME_TYPE_DIR, "storage-test-${java.util.UUID.randomUUID()}")
        try {
            val file = provider.createDocument(folder, "text/plain", "sample.txt")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(file, "w", null)).use { it.write("sample".toByteArray()) }
            assertTrue(provider.isChildDocument(folder, file))
            assertEquals(1, provider.queryChildDocuments(folder, null, null as String?).use { it.count })
            try { provider.deleteDocument(folder); fail("must not recursively delete") } catch (_: IllegalStateException) { }
            val renamed = provider.renameDocument(file, "renamed.txt")
            assertEquals("sample", File(Environment.getExternalStorageDirectory(), renamed.removePrefix("shared/")).readText())
            provider.deleteDocument(renamed)
            val staging = provider.createDocument(folder, "application/octet-stream", ".ayngallery-test")
            provider.deleteDocument(staging)
        } finally { provider.deleteDocument(folder) }
    }
}
