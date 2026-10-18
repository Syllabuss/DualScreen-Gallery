package it.ayngallery

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
class ExplorerSafFolderTest {
    private fun fixture(): Pair<OperationDocuments, android.net.Uri> {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "ayngallery.explorer"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("ayngallery.explorer", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.explorer", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return provider to tree
    }

    @Test fun readOnlyRootListsRealDocumentsWithoutImportingMediaOrChangingFiles() = runBlocking {
        val (provider, tree) = fixture()
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val ids = provider.nodes.keys.toSet()
        val folder = ExplorerSafFolder(app, tree)
        val root = folder.root()
        val result = folder.list(root)
        assertTrue(result.any { it.directory })
        assertTrue(result.none { it.writable })
        val images = result.first { it.name == "Origin" }
        assertTrue(folder.list(ExplorerLocation(root.providerId, images.id)).any { it.name == "image.png" })
        assertEquals(ids, provider.nodes.keys)
        assertTrue(app.catalog.dao.media().isEmpty())
        assertTrue(provider.deletions.isEmpty())
    }

    @Test fun forgedLocationsAreRejectedBeforeListingAndRevocationBlocksKnownLocations() = runBlocking {
        val (provider, tree) = fixture()
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val folder = ExplorerSafFolder(app, tree)
        val root = folder.root()
        val before = provider.childQueries
        val forged = DocumentsContract.buildDocumentUriUsingTree(tree, "not-enumerated").toString()
        assertTrue(runCatching { folder.list(root.copy(directoryId = forged)) }.isFailure)
        assertTrue(runCatching { folder.list(root.copy(providerId = "another-provider")) }.isFailure)
        assertEquals(before, provider.childQueries)
        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(runCatching { folder.list(root) }.isFailure)
        assertEquals(before, provider.childQueries)
    }

    @Test fun protectedMetadataIsNotPresentedAsAFileAndFolderIdsComeFromProviderEnumeration() = runBlocking {
        val (provider, tree) = fixture()
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        provider.add("root", ".ayngallery-pin", byteArrayOf(1, 2, 3))
        val folder = ExplorerSafFolder(app, tree)
        val root = folder.root()
        val result = folder.list(root)
        assertFalse(result.any { it.name.startsWith(".ayngallery-") })
        val child = result.first { it.directory }
        val children = folder.list(ExplorerLocation(root.providerId, child.id))
        assertTrue(children.all { it.location.directoryId == child.id })
        assertTrue(provider.deletions.isEmpty())
    }
}
