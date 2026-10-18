package it.ayngallery

import android.Manifest
import android.content.pm.ProviderInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestGalleryApp::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceGalleryRefreshTest {
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun release() { app.onTerminate() }
    private fun provider(): DeviceMediaFixture = DeviceMediaFixture().also {
        it.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("media", it)
    }
    private fun source() = SourceEntity(DeviceGallery.SOURCE, DeviceGallery.ROOT, "Device", "VISUAL")
    @Test fun directReadDoesNotBuildAnIndexAndOnlyExplicitFavoriteIsStored() = runBlocking {
        val fixture = provider()
        Shadows.shadowOf(app).grantPermissions(*DeviceGallery.permissions())
        val first = app.deviceGallery.browse(source())
        val second = app.deviceGallery.browse(source())
        assertEquals(3, first.media.size)
        assertEquals(3, first.visibleMedia.size)
        assertEquals(first.media.map { it.id }, second.media.map { it.id })
        assertTrue(app.catalog.dao.media().isEmpty()); assertTrue(app.catalog.dao.albums().isEmpty())
        assertTrue(app.catalog.dao.membership().isEmpty()); assertTrue(app.catalog.dao.sources().isEmpty())
        app.catalog.browsed.value = mapOf(DeviceGallery.SOURCE to first)
        val id = first.media.first().id
        app.catalog.favorite(id)
        assertEquals(listOf(id), app.catalog.dao.media().map { it.id })
        assertTrue(app.catalog.dao.media(id)!!.favorite)
        assertTrue(app.catalog.readSnapshot().byId.getValue(id).favorite)
        app.catalog.hideAlbums(listOf(first.byId.getValue(id).albumId), true)
        assertFalse(app.catalog.readSnapshot().visible(app.catalog.readSnapshot().byId.getValue(id)))
        app.catalog.hideAlbums(listOf(first.byId.getValue(id).albumId), false)
        assertFalse(app.catalog.readSnapshot().visible(app.catalog.readSnapshot().byId.getValue(id))) // Legacy global access never authorizes the folder-based gallery.
        assertEquals(id, app.deviceGallery.browse(source()).media.first().id)
        assertEquals(0, fixture.deletes)
        repeat(3) { app.deviceGallery.refresh(force = false) }
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("scan:${DeviceGallery.SOURCE}").get().isEmpty())
        assertFalse(app.catalog.progress.value.running)
    }
    @Test @Config(sdk = [34], qualifiers = "it") fun selectedPhotosAreReadAgainWithoutWritingOrKeepingRevokedItemsVisible() = runBlocking {
        val fixture = provider()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        fixture.selected = setOf("images:1")
        val first = app.deviceGallery.browse(source())
        fixture.selected = setOf("video:3")
        val next = app.deviceGallery.browse(source())
        assertEquals(listOf("IMAGE"), first.media.map { it.kind })
        assertEquals(listOf("VIDEO"), next.media.map { it.kind })
        Shadows.shadowOf(app).denyPermissions(*DeviceGallery.permissions())
        assertTrue(app.deviceGallery.browse(source()).media.isEmpty())
        assertTrue(app.catalog.dao.media().isEmpty())
    }
    @Test fun tenThousandMediaUseAndroidMetadataWithoutPersistentRows() = runBlocking {
        val fixture = provider(); fixture.entries.clear()
        repeat(10_000) { fixture.entries += DeviceMediaFixture.Entry(it.toLong() + 1, "images", "photo$it.png", "image/png", "Pictures/", "pictures") }
        Shadows.shadowOf(app).grantPermissions(*DeviceGallery.permissions())
        val data = app.deviceGallery.browse(source())
        assertEquals(10_000, data.media.size)
        assertEquals(10_000, data.visibleMedia.size)
        assertEquals(10_000, data.media.map { it.id }.toSet().size)
        assertTrue(app.catalog.dao.media().isEmpty()); assertTrue(app.catalog.dao.membership().isEmpty())
        assertEquals(0, fixture.deletes)
    }
}
