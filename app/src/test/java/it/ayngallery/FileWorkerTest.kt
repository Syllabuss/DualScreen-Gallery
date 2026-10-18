package it.ayngallery

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Looper
import android.provider.DocumentsContract
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
class FileWorkerTest {
    @After fun release() { (RuntimeEnvironment.getApplication() as GalleryApp).onTerminate() }
    @Test fun partialMoveDoesNotCancelTheNextFileAndRetryUsesItsPublishedCopy() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "ayngallery.operations"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("ayngallery.operations", provider)
        val other = provider.add("child", "other.png", byteArrayOf(1, 2, 3))
        val ids = runBlocking {
            val source = SourceEntity("scope", DocumentsContract.buildTreeDocumentUri("ayngallery.operations", "root").toString(), "Fixture", "VISUAL")
            app.contentResolver.takePersistableUriPermission(android.net.Uri.parse(source.treeUri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            val media = app.catalog.dao.media()
            val target = app.catalog.dao.albums().single { it.name == "Destination" }
            provider.denyDelete = true
            listOf(app.files.enqueue(media.single { it.name == "image.png" }, "MOVE", target.id, start = false),
                app.files.enqueue(media.single { it.name == other.name }, "COPY", target.id, start = false))
        }
        // Enqueue both intents before starting workers: the paused test UI must remain free to release playback handles.
        ids.forEach { app.files.schedule(it) }
        val work = WorkManager.getInstance(app)
        waitFor { work.getWorkInfosForUniqueWork("file-queue").get().let { it.size == 2 && it.all { job -> job.state == WorkInfo.State.SUCCEEDED } } }
        assertEquals(listOf("PARTIAL", "COMPLETED"), runBlocking { ids.map { app.catalog.dao.operation(it)!!.phase } })
        assertTrue(provider.nodes.containsKey("image")); assertTrue(provider.nodes.containsKey(other.id))
        assertTrue(provider.deletions.isEmpty())
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel("file-operations"))
        val creations = provider.creations
        provider.denyDelete = false; runBlocking { app.files.retry(ids.first()) }
        waitFor { runBlocking { app.catalog.dao.operation(ids.first())!!.phase == "COMPLETED" } }
        assertEquals(creations, provider.creations); assertEquals(1, provider.deletions.count { it == "image" })
    }
    @Test fun notificationEntryOpensOperationsOnExactlyOneAuthorizedPanel() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java,
            Intent(app, LauncherActivity::class.java).setAction("it.ayngallery.OPEN_OPERATIONS")).create().get()
        val next = shadowOf(launcher).nextStartedActivity
        assertTrue(next.getBooleanExtra("open_operations", false)); assertFalse(next.getBooleanExtra("open_player", false))
        assertNotNull(next.getStringExtra("ticket")); assertNull(shadowOf(launcher).nextStartedActivity)
    }
    @Test @Config(sdk = [35], qualifiers = "it") fun foregroundNotificationDeclaresDataSyncAndProtectsPrivateNames() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val op = FileOperation("job", "media", "COPY", "content://test/document/file", "test:file", "album", "personal.png",
            "image/png", 100, 0, null, null, "personal.png", true, "copy")
        val info = fileForegroundInfo(app, op)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, info.notification.visibility)
        assertNotNull(info.notification.contentIntent)
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel("file-operations"))
        val service = app.packageManager.getServiceInfo(android.content.ComponentName(app, androidx.work.impl.foreground.SystemForegroundService::class.java), 0)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, service.foregroundServiceType)
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        fail("Scheduled file worker did not finish within the test deadline")
    }
}
