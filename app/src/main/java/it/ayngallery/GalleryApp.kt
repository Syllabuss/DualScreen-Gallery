// DS Gallery - https://github.com/Syllabuss/DualScreen-Gallery
package it.ayngallery

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class GalleryApp : Application(), SingletonImageLoader.Factory, androidx.work.Configuration.Provider {
    internal var playbackSleeping = false
        private set
    internal val playbackInteractive: Boolean get() = !playbackSleeping &&
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    internal fun playbackSleep() {
        playbackSleeping = true
        music.invalidateVideoResume()
        if (!preferences.state.value.musicDuringSleep) music.pauseForSleep()
        if (!preferences.state.value.videoDuringSleep) {
            if (video.liveChannel != null || video.liveLoadingChannel != null) tv.pauseForSleep()
            else video.pauseForSleep()
        }
    }
    internal fun playbackWake() { playbackSleeping = false }

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
    lateinit var catalog: Catalog
        private set
    lateinit var deviceGallery: DeviceGallery
        private set
    lateinit var albums: DirectAlbums
        private set
    lateinit var music: MusicCoordinator
        private set
    lateinit var preferences: PreferenceStore
        private set
    lateinit var sounds: UiSounds
        private set
    internal lateinit var tvCacheReady: kotlinx.coroutines.Deferred<Unit>
    internal val tv by lazy { TvLibrary(this) }
    lateinit var video: VideoPlayback
        private set
    lateinit var session: GallerySession
        private set
    lateinit var comparison: ComparisonStore
        private set
    lateinit var files: FileOperations
        private set
    lateinit var widgets: MusicWidgets
        private set
    lateinit var photoWidgets: PhotoWidgets
        private set
    lateinit var duplicates: DuplicateSearch
        private set
    lateinit var shares: ShareCopies
        private set
    lateinit var cache: CacheMaintenance
        private set
    internal lateinit var ftp: FtpSharing
        private set
    internal lateinit var explorer: ExplorerSession
        private set
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        GalleryText.resources = AppLanguage.resources(this)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        GalleryText.resources = AppLanguage.resources(this)
        GalleryText.revision++
        if (::widgets.isInitialized) widgets.refresh()
        if (::photoWidgets.isInitialized) photoWidgets.refresh()
    }
    override fun onCreate() {
        super.onCreate()
        catalog = Catalog(this)
        tvCacheReady = catalog.scope.async(Dispatchers.IO) {
            val folder = java.io.File(cacheDir, "tv-thumbnails")
            trimTvThumbnails(folder); Unit
        }
        deviceGallery = DeviceGallery(catalog)
        albums = DirectAlbums(this)
        deviceGallery.observe()
        androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag(SourceScanWorker::class.java.name)
        music = MusicCoordinator(this)
        video = VideoPlayback(this)
        preferences = PreferenceStore(this)
        video.observe()
        sounds = UiSounds(this)
        session = GallerySession(this)
        duplicates = DuplicateSearch(catalog, preferences, session)
        shares = ShareCopies(catalog, gameMode = { preferences.state.value.gameMode })
        shares.maintenance()
        comparison = ComparisonStore(this, catalog.scope, catalog)
        // Comparison is retired from the gallery; retain saved panes and sessions for future use.
        comparison.update(comparison.state.value.copy(enabled = false))
        files = FileOperations(catalog, releaseMedia = { id ->
            val musical = catalog.dao.media(id)?.kind == "AUDIO"
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                if (musical) music.releaseForMutation(id)
                if (video.mediaId == id) video.stop(video.owner)
            }
        }, gameMode = { preferences.state.value.gameMode })
        widgets = MusicWidgets(this)
        widgets.observe()
        photoWidgets = PhotoWidgets(this)
        photoWidgets.observe()
        files.maintenance()
        cache = CacheMaintenance(this)
        ftp = FtpSharing(this)
        explorer = ExplorerSession(this)
        cache.recover()
        catalog.scope.launch { catalog.refreshAccess() }
        catalog.scope.launch(Dispatchers.Main.immediate) {
            catalog.snapshot.collect { data ->
                if (data.initialized && session.selectedMediaId != null && data.byId[session.selectedMediaId]?.let(data::visible) != true)
                    session.selectMedia(null)
                if (data.initialized && video.mediaId != null && video.liveChannel == null && video.surface != "received" && data.byId[video.mediaId]?.let(data::usable) != true)
                    video.stop(video.owner)
            }
        }
    }
    internal fun trimTvArtworkIfIdle() {
        if (video.floating || video.pipOpening || video.player != null || music.state.value.playing) return
        catalog.scope.launch(Dispatchers.IO) { tvCacheReady.await(); trimTvThumbnails(java.io.File(cacheDir, "tv-thumbnails")) }
    }
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(AnimatedImageDecoder.Factory()); add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(CacheMaintenance.memoryLimit(Runtime.getRuntime().maxMemory()))
            .weakReferencesEnabled(false).build() }
        .diskCache(null).build()
    override fun onTerminate() {
        if (::explorer.isInitialized) explorer.close()
        if (::ftp.isInitialized) kotlinx.coroutines.runBlocking(Dispatchers.IO) { ftp.close() }
        deviceGallery.close()
        video.stop(video.owner)
        sounds.close(); music.close(); session.close()
        val catalogJob = catalog.scope.coroutineContext[kotlinx.coroutines.Job]
        if (catalogJob == null) catalog.db.close() else {
            catalogJob.invokeOnCompletion { catalog.db.close() }
            catalogJob.cancel()
        }
        SingletonImageLoader.get(this).shutdown()
        GalleryText.resources = null
        super.onTerminate()
    }
}
