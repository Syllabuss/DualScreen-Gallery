package it.ayngallery

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in physical-device test. Uses existing GIFs and restores preferences; never edits media. */
class GifStressInstrumentation : Instrumentation() {
    private var seconds = 60
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        seconds = arguments?.getString("seconds")?.toIntOrNull()?.coerceIn(15, 300) ?: 60
        start()
    }
    override fun onStart() {
        waitForIdleSync()
        val result = Bundle()
        val app = targetContext.applicationContext as GalleryApp
        val panels = mutableListOf<PanelActivity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { if (a is PanelActivity) panels.add(a) }
            override fun onActivityDestroyed(a: Activity) { panels.remove(a) }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        }
        val rawPreferences = app.getSharedPreferences("ui_preferences_v1", 0)
        val savedPreferences = rawPreferences.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        val original = app.preferences.state.value
        val grid = app.preferences.photoGrid.value
        val columns = app.preferences.photoColumns.value
        val samples = JSONArray()
        val temporaryIds = mutableListOf<String>()
        var temporaryTree: android.net.Uri? = null
        val fixtureUris = mutableListOf<android.net.Uri>()
        var fixtureSource: SourceEntity? = null
        fun sample(phase: String) {
            val runtime = Runtime.getRuntime()
            val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            samples.put(JSONObject().put("phase", phase).put("uptimeMs", android.os.SystemClock.uptimeMillis()).put("javaUsed", runtime.totalMemory() - runtime.freeMemory())
                .put("javaLimit", runtime.maxMemory()).put("nativeUsed", Debug.getNativeHeapAllocatedSize())
                .put("pssKb", info.totalPss))
        }
        fun main(action: () -> Unit) = runOnMainSync(action)
        main { app.registerActivityLifecycleCallbacks(callbacks) }
        try {
            runBlocking {
                uiAutomation.executeShellCommand("am start -n it.ayngallery/.LauncherLight").close()
                withTimeout(30000) { app.catalog.snapshot.first { it.initialized } }
                delay(4000)
                var data = app.catalog.snapshot.value
                var gifs = data.visibleMedia.filter { it.kind == "GIF" && it.available && !data.albumLocked(it.albumId) }
                if (gifs.isEmpty()) {
                    val source = data.sources.firstOrNull { it.enabled && it.reachable && it.role != "MUSIC" }
                        ?: error("Link an image/video folder before running the device test")
                    fixtureSource = source
                    val docs = FileDocuments(app)
                    val tree = android.net.Uri.parse(source.treeUri)
                    val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                    temporaryTree = docs.create(root, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "DS Gallery stress " + UUID.randomUUID())
                    val bytes = context.assets.open("stress.gif").use { it.readBytes() }
                    for (i in 0 until 40) {
                        val uri = docs.create(temporaryTree!!, "image/gif", "stress-$i.gif")
                        fixtureUris.add(uri)
                        docs.output(uri).use { it.write(bytes) }
                    }
                    SourceScanner(app.catalog).scan(source)
                    app.albums.refreshAndWait()
                    data = app.catalog.readSnapshot()
                    gifs = data.visibleMedia.filter { it.uri in fixtureUris.map(android.net.Uri::toString) }
                    temporaryIds.addAll(gifs.map { it.id })
                    result.putBoolean("synthetic", true)
                }
                check(gifs.isNotEmpty()) { "No accessible GIFs in the linked library" }
                sample("baseline")
                for (limit in listOf(4, 40)) {
                    app.preferences.update { original.copy(gifMode = "VISIBLE", gifLimit = limit,
                        gifFrameSelector = true, gifFrameCount = true, gifFrameCounter = true) }
                    main {
                        app.preferences.galleryView(false, "PHOTO", grid = true, columns = 8)
                        app.session.libraryModel()?.let { it.go("PHOTO"); it.filter("GIF") }
                    }
                    delay(1000)
                    repeat(seconds * 2) { n ->
                        main {
                            val vm = app.session.libraryModel() ?: error("Navigation panel unavailable")
                            if (n % 20 == 19) { vm.go("ALBUM"); vm.go("PHOTO"); vm.filter("GIF") }
                            app.session.selectMedia(gifs[(n * 7) % gifs.size].id)
                            panels.firstOrNull { it.windowModel === vm }?.let { pane ->
                                pane.enterPadMode?.invoke(); pane.ensurePadFocus?.invoke()
                                pane.moveFocus?.invoke(if (n % 12 < 6) PadAction.DOWN else PadAction.UP)
                            }
                        }
                        delay(500)
                        if (n % 10 == 0) sample("limit-$limit")
                    }
                    sample("end-$limit")
                }
                result.putInt("gifCount", gifs.size)
            }
            result.putString("status", "passed")
        } catch (error: Throwable) {
            result.putString("status", "failed")
            result.putString("error", error.javaClass.simpleName + ": " + error.message)
        } finally {
            runBlocking { app.preferences.update { original } }
            main {
                app.preferences.galleryView(false, "PHOTO", grid, columns)
                app.session.libraryModel()?.go("ALBUM")
                app.unregisterActivityLifecycleCallbacks(callbacks)
            }
            runBlocking {
                app.catalog.db.withTransaction {
                    app.catalog.dao.clearMemberships(temporaryIds)
                    app.catalog.dao.deleteMediaBatch(temporaryIds)
                }
            }
            val docs = FileDocuments(app)
            fixtureUris.forEach { runCatching { docs.delete(it) } }
            temporaryTree?.let { runCatching { docs.delete(it) } }
            runBlocking { fixtureSource?.let { SourceScanner(app.catalog).scan(it) } }
            runBlocking { app.albums.refreshAndWait() }
            val editor = rawPreferences.edit().clear()
            savedPreferences.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            check(editor.commit()) { "Could not restore test preferences" }
            sample("restored")
            result.putString("samples", samples.toString())
            java.io.File(app.filesDir, "gif-stress-result.json").writeText(JSONObject().put("status", result.getString("status"))
                .put("gifCount", result.getInt("gifCount")).put("synthetic", result.getBoolean("synthetic")).put("error", result.getString("error")).put("samples", samples).toString())
        }
        finish(if (result.getString("status") == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
