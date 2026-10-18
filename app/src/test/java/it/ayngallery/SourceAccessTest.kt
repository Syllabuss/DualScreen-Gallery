package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class SourceAccessTest {
    private lateinit var catalog: Catalog
    private lateinit var documents: OperationDocuments
    private val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.access", "root")
    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        documents = OperationDocuments()
        documents.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.access"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.access", documents)
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        catalog = Catalog(app)
        val source = SourceEntity("source", tree.toString(), "Local", "VISUAL")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        val item = catalog.dao.media().single()
        catalog.favorite(item.id); catalog.collection("Keep", "MEDIA", listOf(item.id))
        Unit
    }
    @After fun release() { if (::catalog.isInitialized) { runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin() }; catalog.db.close() } }
    @Test fun permissionRevocationImmediatelyBlocksFreshReadsAndRecoveryPreservesReferences() = runBlocking {
        val before = catalog.readSnapshot(); val item = before.media.single()
        catalog.context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val revoked = catalog.readSnapshot()
        assertFalse(revoked.usable(item)); assertTrue(revoked.byId.getValue(item.id).favorite)
        assertEquals(before.collectionItems, revoked.collectionItems)
        catalog.refreshAccess()
        assertFalse(catalog.dao.source("source")!!.reachable)
        assertEquals("Accesso da ripristinare", catalog.dao.source("source")!!.error)
        assertTrue(documents.deletions.isEmpty())
        catalog.context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        catalog.refreshAccess()
        val recovered = catalog.readSnapshot()
        assertTrue(recovered.usable(recovered.byId.getValue(item.id)))
        assertEquals(item.id, recovered.media.single().id)
        assertTrue(recovered.media.single().favorite); assertEquals(before.collectionItems, recovered.collectionItems)
    }
    @Test fun detachedRootIsUnavailableWithoutTurningMissingAccessIntoDeletedMedia() = runBlocking {
        val item = catalog.dao.media().single()
        val root = documents.nodes.remove("root")!!
        catalog.refreshAccess()
        assertFalse(catalog.readSnapshot().usable(item))
        assertTrue(catalog.dao.media(item.id)!!.available)
        assertTrue(catalog.dao.media(item.id)!!.favorite)
        documents.nodes["root"] = root
        catalog.refreshAccess()
        assertTrue(catalog.readSnapshot().usable(item))
        assertTrue(documents.deletions.isEmpty())
    }
    @Test fun writeOnlyGrantDoesNotAuthorizeReadingAndDisabledSourcesStayDisabled() = runBlocking {
        catalog.context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        catalog.context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(catalog.readSnapshot().usable(catalog.dao.media().single()))
        catalog.dao.saveSource(catalog.dao.source("source")!!.copy(enabled = false))
        catalog.refreshAccess()
        assertFalse(catalog.dao.source("source")!!.enabled)
        assertTrue(catalog.dao.media().single().favorite)
    }
}
