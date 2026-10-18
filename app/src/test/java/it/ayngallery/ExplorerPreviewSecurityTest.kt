package it.ayngallery
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
class ExplorerPreviewSecurityTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { app.onTerminate() }
    @Test fun verifiedPrivatePreviewDoesNotBecomeAnImportSourceOrAnExternalShare() {
        val directory = File(app.filesDir, "network-preview").apply { mkdirs() }
        val file = File(directory, "${"a".repeat(64)}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = Uri.parse("content://${app.packageName}.share/network_previews/${file.name}")
        val target = IncomingFile(UUID.randomUUID().toString(), uri.toString(), "test.png", "image/png", "IMAGE", file.length(), file.lastModified())
        assertTrue(networkPreviewReadable(app, target))
        ShareCopies.allowed(app, uri, true)
        assertTrue(runCatching { ShareCopies.allowed(app, uri, false) }.isFailure)
        assertTrue(runCatching { IncomingMedia.stat(app, uri) }.isFailure)
        assertTrue(runCatching { networkPreviewReadable(app, target.copy(size = 99)) }.isFailure)
    }
    @Test fun unregisteredRemoteHandleAndWritableModesAreRejectedWithoutNetworkIo() {
        val provider = org.robolectric.Robolectric.buildContentProvider(ExplorerStreamProvider::class.java).create("${app.packageName}.network").get()
        val uri = Uri.parse("content://${app.packageName}.network/${UUID.randomUUID()}")
        assertTrue(runCatching { provider.openFile(uri, "w") }.isFailure)
        assertTrue(runCatching { provider.openFile(uri, "r") }.isFailure)
    }
}
