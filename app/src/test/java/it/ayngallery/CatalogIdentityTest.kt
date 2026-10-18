package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
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
class CatalogIdentityTest {
    private lateinit var catalog: Catalog
    @Before fun setup() { catalog = Catalog(RuntimeEnvironment.getApplication()) }
    @After fun release() { runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin() }; catalog.db.close() }
    private fun provider(authority: String): OperationDocuments {
        val provider = OperationDocuments()
        provider.attachInfo(catalog.context, ProviderInfo().apply { this.authority = authority; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return provider
    }
    private fun source(authority: String, id: String): SourceEntity {
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        catalog.context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return SourceEntity(id, tree.toString(), id, "VISUAL")
    }
    @Test fun addingMusicToAnExistingVisualRootPreservesBothRoles() = runBlocking {
        provider("ayngallery.review")
        val source = source("ayngallery.review", "source")
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source)
        val photo = catalog.dao.media().single()
        assertTrue(catalog.readSnapshot().visible(photo))
        catalog.addSource(Uri.parse(source.treeUri), SourceRole.MUSIC, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals("MIXED", catalog.dao.source(source.id)!!.role)
        assertTrue(catalog.readSnapshot().visible(photo))
    }
    @Test fun overlappingLocalProvidersRetainMediaIdAndFavorite() = checkLocalProviders(false)
    @Test fun oldLocalAliasesMergeWithoutLosingFavoriteOrSourceMemberships() = checkLocalProviders(true)
    private fun checkLocalProviders(legacyAlias: Boolean) = runBlocking {
        fun local(authority: String, prefix: String): SourceEntity {
            val docs = provider(authority)
            val nodes = docs.nodes.values.toList(); docs.nodes.clear()
            nodes.forEach { n -> docs.nodes[prefix + n.id] = n.copy(id = prefix + n.id, parent = n.parent?.let { prefix + it }) }
            val tree = DocumentsContract.buildTreeDocumentUri(authority, prefix + "root")
            catalog.context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return SourceEntity(authority, tree.toString(), authority, "VISUAL")
        }
        val a = local("com.android.externalstorage.documents", "primary:")
        val b = local(SharedStorageDocuments.AUTHORITY, "shared/")
        catalog.dao.saveSource(a); SourceScanner(catalog).scan(a)
        val original = catalog.dao.media().single()
        catalog.favorite(original.id)
        catalog.dao.saveSource(b)
        if (legacyAlias) {
            catalog.dao.saveMedia(listOf(original.copy(id = "legacy-alias", documentKey = "${SharedStorageDocuments.AUTHORITY}:shared/image",
                uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(b.treeUri), "shared/image").toString())))
            catalog.dao.saveMembership(listOf(MediaSource("legacy-alias", b.id, 0)))
        }
        SourceScanner(catalog).scan(b)
        val data = catalog.readSnapshot()
        assertEquals(1, data.visibleMedia.size)
        assertEquals(original.id, data.visibleMedia.single().id)
        assertTrue(data.visibleMedia.single().favorite)
        assertEquals(2, data.membership.count { it.mediaId == original.id })
        val readable = catalog.uri(data.visibleMedia.single(), data)
        assertEquals(DocumentIdentity.key(Uri.parse(original.uri)), DocumentIdentity.key(readable))
        val fallback = catalog.uri(data.visibleMedia.single(), data.copy(sources = data.sources.map {
            if (it.id == b.id) it.copy(enabled = false) else it
        }))
        assertEquals("com.android.externalstorage.documents", fallback.authority)
        assertEquals(DocumentIdentity.key(readable), DocumentIdentity.key(fallback))
    }
    @Test fun unknownProvidersAreNeverMergedByFilename() {
        val a = DocumentsContract.buildDocumentUri("unknown.first", "primary:image")
        val b = DocumentsContract.buildDocumentUri("unknown.second", "primary:image")
        assertNotEquals(DocumentIdentity.key(a), DocumentIdentity.key(b))
    }
}
