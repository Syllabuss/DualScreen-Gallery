package it.ayngallery

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/** Each test owns its scheduler/database. CoroutineWorker and app file IO remain real. */
class TestGalleryApp : GalleryApp() {
    private var terminated = false
    override fun onCreate() {
        // Robolectric API 28 does not grant the signature permission from the merged Core manifest.
        org.robolectric.Shadows.shadowOf(this).grantPermissions("$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        // UI fixtures model a completed permission decision; startup permission flow is tested separately.
        getSharedPreferences("device_gallery_v1", MODE_PRIVATE).edit().putBoolean("asked", true).commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(this, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        super.onCreate()
    }
    override fun onTerminate() {
        if (terminated) return
        terminated = true
        WorkManager.getInstance(this).cancelAllWork().result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        // Drain cancellation before Room closes: a suspended transaction's finalizer must not reopen the database afterwards.
        val catalogJob = catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!
        catalogJob.cancel()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!catalogJob.isCompleted && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        check(catalogJob.isCompleted) { "Catalog callbacks did not stop before database shutdown" }
        WorkManagerTestInitHelper.closeWorkDatabase()
        super.onTerminate()
    }
}
