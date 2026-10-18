package it.ayngallery

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class ExternalMediaTest {
    private lateinit var catalog: Catalog
    private lateinit var provider: OperationDocuments
    private lateinit var source: SourceEntity
    private lateinit var media: MediaEntity
    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        // Robolectric's MimeTypeMap shadow starts empty, unlike the Android platform registry.
        shadowOf(android.webkit.MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("png", "image/png"); addExtensionMimeTypeMapping("jpeg", "image/jpeg")
        }
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.external"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.external", provider)
        catalog = Catalog(app)
        source = SourceEntity("source", DocumentsContract.buildTreeDocumentUri("ayngallery.external", "root").toString(), "Fixture", "MIXED")
        app.contentResolver.takePersistableUriPermission(Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        catalog.dao.saveSource(source); SourceScanner(catalog).scan(source); media = catalog.dao.media().single()
    }
    @After fun close() = runBlocking { catalog.scope.coroutineContext[Job]!!.cancelAndJoin(); catalog.db.close() }
    private fun target() = ExternalTarget.from(media)
    @Test fun persistedDocumentAndVisibilityRemainFreshWhenObservationLags() = runBlocking {
        withTimeout(5000) { catalog.snapshot.first { it.byId[media.id]?.uri == media.uri } }
        // Freeze only observation, leaving Room open to model a delayed flow emission deterministically.
        catalog.scope.coroutineContext[Job]!!.cancelAndJoin()
        val changed = media.copy(uri = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("ayngallery.external", "child"), "image").toString())
        catalog.dao.saveMedia(listOf(changed))
        val fresh = catalog.readMediaSnapshot(media.id)
        assertEquals(changed.uri, fresh.byId.getValue(media.id).uri)
        assertTrue(fresh.usable(fresh.byId.getValue(media.id)))
        catalog.dao.hideAlbums(listOf(media.albumId), true)
        val hidden = catalog.readMediaSnapshot(media.id)
        assertFalse(hidden.usable(hidden.byId.getValue(media.id)))
        catalog.dao.hideAlbums(listOf(media.albumId), false)
        catalog.context.contentResolver.releasePersistableUriPermission(Uri.parse(source.treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val revoked = catalog.readMediaSnapshot(media.id)
        assertFalse(revoked.usable(revoked.byId.getValue(media.id)))
    }
    @Test fun unpersistedDirectoryResultUsesFreshVisibilityRatherThanObservedTables() = runBlocking {
        val stored = catalog.readSnapshot()
        val direct = media.copy(id = "direct", documentKey = "ayngallery.external:direct")
        catalog.browsed.value = mapOf(source.id to stored.copy(media = listOf(direct),
            membership = listOf(MediaSource(direct.id, source.id, 1))))
        val fresh = catalog.readMediaSnapshot(direct.id)
        assertTrue(fresh.usable(fresh.byId.getValue(direct.id)))
        catalog.dao.hideAlbums(listOf(media.albumId), true)
        val hidden = catalog.readMediaSnapshot(direct.id)
        assertFalse(hidden.usable(hidden.byId.getValue(direct.id)))
    }
    @Suppress("DEPRECATION")
    @Test fun singleMultipleAndMixedMimeHaveOnlyExactReadGrantsAndNoFileChanges() = runBlocking {
        val request = ExternalRequest(ExternalAction.SHARE, listOf(target()))
        val prepared = ExternalMedia.prepare(catalog, request)
        val single = prepared.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_CHOOSER, prepared.action)
        assertEquals(Intent.ACTION_SEND, single.action); assertEquals("image/png", single.type)
        assertEquals(Uri.parse(media.uri), single.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, single.flags)
        assertEquals(single.clipData!!.getItemAt(0).uri, prepared.clipData!!.getItemAt(0).uri)
        assertEquals(1, single.clipData!!.itemCount)
        val second = target().copy(id = "second", uri = "content://ayngallery.external/tree/root/document/other", name = "second.jpg", mime = "image/jpeg")
        val multi = ExternalMedia.intent(ExternalAction.SHARE, listOf(target(), second))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, multi.action); assertEquals("image/*", multi.type)
        assertEquals(listOf(Uri.parse(media.uri), Uri.parse(second.uri)), multi.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(2, multi.clipData!!.itemCount); assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, multi.flags)
        val audio = second.copy(kind = "AUDIO", name = "song.mp3", mime = "audio/mpeg")
        assertEquals("*/*", ExternalMedia.intent(ExternalAction.SHARE, listOf(target(), audio)).type)
        assertArrayEquals(provider.originalBytes, provider.nodes.getValue("image").file!!.readBytes())
        assertTrue(provider.deletions.isEmpty()); assertTrue(catalog.dao.operations().isEmpty()); assertEquals(1, catalog.dao.media().size)
    }
    @Test @Config(sdk = [35], qualifiers = "it") fun uriAndMimeRemainReadableOnRecentAndroidAndUnknownMimeFallsBack() = runBlocking {
        val prepared = ExternalMedia.prepare(catalog, ExternalRequest(ExternalAction.OPEN, listOf(target())))
        @Suppress("DEPRECATION") val view = prepared.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, view.action); assertEquals(Uri.parse(media.uri), view.data)
        assertEquals("View MIME", "image/png", view.type); assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, view.flags)
        assertEquals(provider.originalBytes.first().toInt() and 255, catalog.context.contentResolver.openInputStream(view.data!!)!!.use { it.read() })
        assertEquals("PNG extension fallback", "image/png", ExternalMedia.mime(target().copy(mime = "application/octet-stream")))
        assertEquals("image/jpeg", ExternalMedia.mime(target().copy(mime = "application/octet-stream", name = "PHOTO.JPEG")))
        assertEquals("application/octet-stream", ExternalMedia.mime(target().copy(mime = "../../bad", name = "unknown.weird")))
    }
    @Test fun frozenLocatorsHideRevocationAndProviderChangesAreRecheckedBeforeOpening() = runBlocking {
        val request = ExternalRequest(ExternalAction.SHARE, listOf(target()))
        catalog.dao.saveMedia(listOf(media.copy(uri = media.uri + "changed")))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ExternalMedia.prepare(catalog, request) } }
        catalog.dao.saveMedia(listOf(media)); catalog.hideAlbums(listOf(media.albumId), true)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ExternalMedia.prepare(catalog, request) } }
        catalog.hideAlbums(listOf(media.albumId), false)
        provider.nodes.getValue("image").name = "renamed.png"
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ExternalMedia.prepare(catalog, request) } }
        provider.nodes.getValue("image").name = media.name
        provider.beforeRead = { runBlocking { catalog.dao.saveMedia(listOf(media.copy(available = false))) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ExternalMedia.prepare(catalog, request) } }
        provider.beforeRead = {}; catalog.dao.saveMedia(listOf(media))
        catalog.context.contentResolver.releasePersistableUriPermission(Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ExternalMedia.prepare(catalog, request) } }
        assertTrue(provider.deletions.isEmpty()); assertTrue(catalog.dao.operations().isEmpty())
    }
    @Test fun rotationPayloadBoundsAndWallpaperRejectAnimatedOrMultipleFiles() {
        val request = ExternalRequest(ExternalAction.SHARE, listOf(target().copy(name = "Nuvola 🌷.png")))
        assertEquals(request, ExternalRequest.decode(request.encode()))
        assertThrows(IllegalArgumentException::class.java) { ExternalRequest(ExternalAction.OPEN, listOf(target(), target().copy(id = "other"))) }
        assertThrows(IllegalArgumentException::class.java) { ExternalRequest(ExternalAction.WALLPAPER, listOf(target().copy(kind = "GIF", mime = "image/gif"))) }
        assertThrows(IllegalArgumentException::class.java) { ExternalRequest(ExternalAction.SHARE, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ExternalRequest(ExternalAction.SHARE, (0..128).map { target().copy(id = "$it") }) }
        assertThrows(IllegalArgumentException::class.java) { ExternalRequest(ExternalAction.SHARE, listOf(target().copy(uri = "file:///private/file.png"))) }
        assertThrows(IllegalArgumentException::class.java) { ExternalRequest(ExternalAction.SHARE, listOf(target().copy(name = "x".repeat(300_000)))).encode() }
    }
    @Suppress("DEPRECATION")
    @Test fun overlapUsesTheCurrentlyGrantedRootRatherThanAnOldStoredTreeUri() = runBlocking {
        val child = source.copy(id = "child-source", treeUri = DocumentsContract.buildTreeDocumentUri("ayngallery.external", "child").toString())
        catalog.context.contentResolver.takePersistableUriPermission(Uri.parse(child.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        catalog.dao.saveSource(child); SourceScanner(catalog).scan(child)
        // A locator may outlive one of two overlapping grants. Identity remains the document key.
        media = catalog.dao.media(media.id)!!.copy(uri = media.uri); catalog.dao.saveMedia(listOf(media))
        catalog.context.contentResolver.releasePersistableUriPermission(Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val current = catalog.readMediaSnapshot(media.id)
        assertTrue("Granted child source must remain usable: ${current.sources}", current.byId[media.id]?.let(current::usable) == true)
        assertEquals("The frozen media must still match the stored document", target(), ExternalTarget.from(current.byId.getValue(media.id)))
        val prepared = ExternalMedia.prepare(catalog, ExternalRequest(ExternalAction.SHARE, listOf(target())))
        val send = prepared.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("child", DocumentsContract.getTreeDocumentId(uri)); assertEquals("image", DocumentsContract.getDocumentId(uri))
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(provider.originalBytes.first().toInt() and 255, catalog.context.contentResolver.openInputStream(uri)!!.use { it.read() })
    }
    @Test fun cancelledProviderReadCannotProduceALaunchAfterTheWindowIsClosed() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        provider.beforeRead = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val request = ExternalRequest(ExternalAction.SHARE, listOf(target()))
        val pending = async { ExternalMedia.prepare(catalog, request) }
        yield()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); pending.cancel(); release.countDown()
            assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
        } finally { release.countDown(); pending.cancelAndJoin(); provider.beforeRead = {} }
        assertTrue(provider.deletions.isEmpty()); assertTrue(catalog.dao.operations().isEmpty())
    }
    @Test fun missingSystemWallpaperCropperHasActionableErrorAndNeverWritesFile() = runBlocking {
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { ExternalMedia.prepare(catalog, ExternalRequest(ExternalAction.WALLPAPER, listOf(target()))) } }
        assertTrue(error.message!!.contains("ritaglio")); assertTrue(error.message!!.contains("un'altra app"))
        assertArrayEquals(provider.originalBytes, provider.nodes.getValue("image").file!!.readBytes()); assertTrue(catalog.dao.operations().isEmpty())
    }
    @Test fun availableSystemCropperReceivesSingleReadableImageAndOnlyReadGrant() = runBlocking {
        val resources = catalog.context.resources
        val resource = resources.getIdentifier("config_wallpaperCropperPackage", "string", "android")
        val pkg = resources.getString(resource)
        val uri = Uri.parse(media.uri)
        val resolve = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.CropActivity"; exported = true } }
        val manager = shadowOf(catalog.context.packageManager)
        manager.addResolveInfoForIntent(Intent(android.app.WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER).setPackage(pkg).setData(uri), resolve)
        manager.addResolveInfoForIntent(Intent(android.app.WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER).setPackage(pkg).setDataAndType(uri, "image/png"), resolve)
        val intent = ExternalMedia.prepare(catalog, ExternalRequest(ExternalAction.WALLPAPER, listOf(target())))
        assertEquals(android.app.WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER, intent.action)
        assertEquals(uri, intent.data); assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertArrayEquals(provider.originalBytes, provider.nodes.getValue("image").file!!.readBytes())
    }
}
