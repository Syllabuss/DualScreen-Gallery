package it.ayngallery

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

class MainPanelActivity : PanelActivity() { override val slot = 0 }
class OtherPanelActivity : PanelActivity() { override val slot = 1 }

abstract class PanelActivity : ComponentActivity() {
    abstract val slot: Int
    lateinit var session: GallerySession
        private set
    var screenKey: String = ""
        private set
    var focused by mutableStateOf(false)
        private set
    private var accepted = false
    private val visualAccess = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val app = application as GalleryApp
        app.deviceGallery.refresh()
        if (!DeviceGallery.hasAccess(this)) session.noticeSource(localized(R.string.text_photos_and_videos_not_authorized_open_folders_an_7b898b, "L’accesso generale a foto e video è disattivato. Puoi scegliere cartelle specifiche da Libreria > Cartelle multimediali."))
    }
    fun requestDeviceAccess() {
        chooseSource(SourceRole.VISUAL)
    }
    fun devicePermissionSettings() {
        if (!accepted || isFinishing) return
        val here = session.current(this) ?: return
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)), ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())
        } catch (_: RuntimeException) { session.fail(localized(R.string.text_android_settings_unavailable_open_app_permission_fef31a, "Impostazioni Android non disponibili. Apri i permessi dell'app dalle impostazioni del dispositivo.")) }
    }
    internal var controllerModal: PadModalRuntime? = null
    internal var infoHeaderBottom = false
    internal var infoDetailsTop = false
    internal var settingsScrollEnd: (() -> Boolean)? = null
    internal var settingsInputAction: ((PadAction) -> Boolean)? = null
    internal var clearPadFocus: (() -> Unit)? = null
    internal var sliderAction: ((PadAction) -> Boolean)? = null
    var padContext = PadContext.LIBRARY
    var armSettingsFocus: (() -> Boolean)? = null
    var padAction: ((PadAction) -> Unit)? = null
    var frameAction: ((PadAction) -> Boolean)? = null
    var receivedAction: ((PadAction) -> Boolean)? = null
    var imageSticks: ((Float, Float, Float, Float, Long) -> Boolean)? = null
    private var previewAxes: FloatArray? = null
    var padTransform: ((PadAction, String?) -> Unit)? = null
    var enterPadMode: (() -> Unit)? = null
    var ensurePadFocus: (() -> Unit)? = null
    var focusedMediaItemId: String? = null
    var videoControllerAction: ((PadAction) -> Boolean)? = null
    var videoStickAction: ((Float, Float, Long) -> Boolean)? = null
    internal var brightnessAccessRequested by mutableStateOf(false)
    private val consoleStick = ConsoleStickControls()
    private var consoleAxes: FloatArray? = null
    private var brightnessToast: Toast? = null
    private var lastBrightnessFeedback = 0L
    private var videoStickX = 0f
    private var videoStickY = 0f
    var settingsHeaderHasFocus = false
    var lastPadDevice by mutableStateOf<InputDevice?>(null)
        private set
    private val input = PadWindowInput({ (application as GalleryApp).preferences.state.value.pad }, { padContext }, ::emitPad,
        detected = { lastPadDevice = it }, leftStick = { x, y, now ->
            val handled = videoStickAction?.invoke(x, y, now) == true
            videoStickX = if (handled) x else 0f; videoStickY = if (handled) y else 0f
            handled
        }, previewSticks = { event, now ->
            fun strongest(a: Int, b: Int): Float = listOf(event.getAxisValue(a), event.getAxisValue(b)).maxBy { kotlin.math.abs(it) }
            val axes = floatArrayOf(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y),
                strongest(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX), strongest(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY))
            val handled = handlePreviewSticks(axes, now)
            previewAxes = axes.takeIf { handled }
            handled
        }, rightStick = { x, y, now ->
            val handled = handleConsoleStick(x, y, now)
            consoleAxes = floatArrayOf(x, y).takeIf { handled && consoleStick.active }
            handled
        })
    private fun handlePreviewSticks(axes: FloatArray, now: Long): Boolean {
        if (controllerModal != null || session.settingsOwner != null || session.mediaEditor != null ||
            windowModel.route.value.tab !in setOf("ALBUM", "PHOTO", "VIDEO", "FAVORITE") && windowModel.route.value.viewer == null) return false
        val preview = session.previewActivity() ?: this
        return preview.imageSticks?.invoke(axes[0], axes[1], axes[2], axes[3], now) == true
    }
    internal fun consoleControlsAvailable(): Boolean {
        val route = session.libraryModel()?.route?.value ?: windowModel.route.value
        return route.tab in setOf("VIDEO", "MUSIC") && session.settingsOwner == null &&
            session.mediaEditor == null && controllerModal == null && !brightnessAccessRequested
    }
    private fun handleConsoleStick(x: Float, y: Float, now: Long): Boolean {
        if (!consoleControlsAvailable()) { consoleStick.clear(); return false }
        val step = consoleStick.update(x, y, now)
        if (step?.axis == ConsoleAxis.VOLUME) {
            getSystemService(android.media.AudioManager::class.java).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                if (step.direction > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI)
        } else if (step?.axis == ConsoleAxis.BRIGHTNESS) {
            if (!android.provider.Settings.System.canWrite(this)) {
                consoleStick.clear(); consoleAxes = null; brightnessAccessRequested = true
                return true
            }
            val current = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
            val value = (current + step.direction * 13).coerceIn(13, 255)
            if (value != current) {
                try {
                    if (!android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ||
                        !android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, value)) return consoleStick.active
                } catch (_: SecurityException) {
                    consoleStick.clear(); consoleAxes = null; brightnessAccessRequested = true; return true
                }
                if (now - lastBrightnessFeedback >= 400) {
                    lastBrightnessFeedback = now
                    brightnessToast?.cancel()
                    brightnessToast = uiToast(this, localized(R.string.console_brightness_level, "Luminosità · %1\$d%%", value * 100 / 255), Toast.LENGTH_SHORT).also { it.show() }
                }
            }
        }
        return consoleStick.active
    }
    internal fun openBrightnessPermission() {
        brightnessAccessRequested = false; clearInput()
        val here = session.current(this) ?: return
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")),
                ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())
        } catch (_: RuntimeException) { devicePermissionSettings() }
    }
    lateinit var windowModel: GalleryWindowModel
        private set
    var navigateMedia: ((Int) -> Unit)? = null
    internal var manuallyDimmed by androidx.compose.runtime.mutableStateOf(false)
    fun performTouchAction(action: TouchAction) {
        if (!accepted || !isStartedFlow.value || isFinishing || action == TouchAction.NONE || action == TouchAction.TOGGLE_ZOOM) return
        if (session.tutorialInProgress) return
        if (action in setOf(TouchAction.TAB_PREVIOUS, TouchAction.TAB_NEXT)) {
            val browser = session.libraryModel() ?: windowModel
            val tabs = visibleNavbarTabs((application as GalleryApp).preferences.state.value)
            val index = tabs.indexOfFirst { it.key == browser.route.value.tab }
            browser.go(tabs[Math.floorMod(index + if (action == TouchAction.TAB_NEXT) 1 else -1, tabs.size)].key)
            return
        }
        if (action == TouchAction.DIM) { manuallyDimmed = true; return }
        if (action == TouchAction.BACK) onBackPressedDispatcher.onBackPressed()
        else padAction?.invoke(PadAction.valueOf(action.name))
    }
    val isStartedFlow = MutableStateFlow(false)
    var moveFocus: ((PadAction) -> Unit)? = null
    var albumGridFocus: ((PadAction) -> Boolean)? = null
    var mediaGridFocus: ((PadAction) -> Boolean)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        session.record(localized(R.string.text_p_1_s_returning_from_picker_2_s_no_files_read_or_f1fc77, "P%1\$s ritorno dal picker: %2\$s; nessun file letto o modificato", slot, if (result.resultCode == RESULT_OK) localized(R.string.text_selected_3da0d3, "selezionato") else localized(R.string.text_canceled_000f5b, "annullato")))
    }
    private val tvArtworkPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tv = (application as GalleryApp).tv
        lifecycleScope.launch { tv.finishArtworkPicker(result.data?.data.takeIf { result.resultCode == RESULT_OK }) }
    }
    internal fun chooseTvArtwork() {
        val tv = (application as GalleryApp).tv
        tv.beginArtworkPicker()
        try {
            tvArtworkPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp", "image/gif"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
                    ActivityOptions.makeBasic().setLaunchDisplayId(session.current(this)?.id ?: android.view.Display.DEFAULT_DISPLAY).toBundle()))
        } catch (failure: RuntimeException) {
            lifecycleScope.launch { tv.finishArtworkPicker(null); tv.failure = tvError(failure); tv.retryAction = { chooseTvArtwork() } }
        }
    }
    private val sourcePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null && accepted) lifecycleScope.launch {
            try {
                val role = SourceRole.valueOf(windowModel.pendingSourceRole)
                withContext(Dispatchers.IO) { (application as GalleryApp).catalog.addSource(uri, role, result.data?.flags ?: 0) }
                if (session.tutorialInProgress) (session.libraryModel() ?: windowModel).go("ALBUM")
                else {
                    windowModel.go("SOURCES")
                    session.noticeSource(localized(R.string.roots_added_scanning, "Cartella aggiunta. La scansione degli album è in corso."))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { session.fail(localized(R.string.text_folder_not_added_1_s_82ccb6, "Cartella non aggiunta: %1\$s", error.message ?: error.javaClass.simpleName)) }
        }
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (accepted && result.resultCode == RESULT_OK && result.data != null) {
            try { windowModel.incoming(IncomingSelection.from(result.data!!).encode()) }
            catch (failure: Exception) { session.fail(failure.message ?: localized(R.string.text_selection_unavailable_3930d5, "Selezione non disponibile")) }
        }
    }
    private val documentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (accepted && result.resultCode == RESULT_OK && result.data != null) {
            try { windowModel.received(ReceivedMedia.from(result.data!!.apply { action = Intent.ACTION_OPEN_DOCUMENT }).encode()) }
            catch (failure: Exception) { session.fail(failure.message ?: localized(R.string.text_file_cannot_be_linked_403bb3, "File non collegabile")) }
        }
    }
    fun chooseDocument() {
        if (!accepted || !isStartedFlow.value || isFinishing) return
        val here = session.current(this) ?: return
        if (here.key != screenKey || here.key in session.state.ceded) return
        try { documentPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE, ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())) }
        catch (_: android.content.ActivityNotFoundException) { session.fail(localized(R.string.text_file_picker_unavailable_be119c, "Selettore di file non disponibile")) }
    }
    fun chooseImport(music: Boolean = false) {
        if (!accepted || !isStartedFlow.value || isFinishing) return
        val here = session.current(this) ?: return
        if (here.key != screenKey || here.key in session.state.ceded) return
        try { importPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (music) "audio/*" else "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, if (music) arrayOf("audio/*") else arrayOf("image/*", "video/*"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE, ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())) }
        catch (_: android.content.ActivityNotFoundException) { session.fail(localized(R.string.text_file_picker_unavailable_on_this_system_327275, "Selettore di file non disponibile su questo sistema")) }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val repeatInput = object : Runnable {
        override fun run() {
            if (session.controllerTarget(this@PanelActivity) !== this@PanelActivity ||
                (!focused && !session.receivesController(this@PanelActivity))) { input.clear(); previewAxes = null; consoleAxes = null; consoleStick.clear(); videoStickX = 0f; videoStickY = 0f; return }
            input.poll()
            consoleAxes?.let { if (!handleConsoleStick(it[0], it[1], SystemClock.uptimeMillis())) consoleAxes = null }
            previewAxes?.let { if (!handlePreviewSticks(it, SystemClock.uptimeMillis())) previewAxes = null }
            if (controllerModal == null) {
                if (videoStickAction?.invoke(videoStickX, videoStickY, SystemClock.uptimeMillis()) != true) { videoStickX = 0f; videoStickY = 0f }
            } else { previewAxes = null; videoStickX = 0f; videoStickY = 0f }
            handler.postDelayed(this, 20)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = (application as GalleryApp).session
        windowModel = ViewModelProvider(this)[GalleryWindowModel::class.java]
        screenKey = session.current(this)?.key.orEmpty()
        accepted = session.accept(this)
        if (!accepted) {
            finishAndRemoveTask()
            return
        }
        onBackPressedDispatcher.addCallback(this) {
            // Back navigates within the gallery; leaving a panel is an explicit display action.
            if (!session.settingsBack()) {
                if (session.isPairedPreview(this@PanelActivity)) windowModel.restorePanelRole() else windowModel.back()
            }
        }
        val preferences = (application as GalleryApp).preferences
        applyWindowTheme(preferences.state.value)
        lifecycleScope.launch { preferences.state.collect { applyWindowTheme(it) } }
        setContent { GalleryHome(this) }
        if (intent.hasExtra("received_media")) receive(intent)
        else if (intent.getBooleanExtra("open_player", false)) windowModel.go("PLAYER")
        else if (intent.getBooleanExtra("open_operations", false)) windowModel.go("OPERATIONS")
        else if (intent.getBooleanExtra("open_photo", false)) openWidgetPhoto(intent)
        else if (intent.getBooleanExtra("open_music", false)) openWidgetMusic(intent)
        (application as GalleryApp).music.observe()
        session.record(localized(R.string.text_p_1_s_created_on_display_2_s_b6f016, "P%1\$s creato su display %2\$s", slot, session.current(this)?.id))
    }

    private fun applyWindowTheme(preferences: GalleryPreferences) = applyGalleryWindowTheme(this, preferences)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accepted = session.accept(this)
        if (!accepted) {
            finishAndRemoveTask()
            return
        }
        // singleTask can reuse this instance for an explicitly authorized display request.
        // Keep lifecycle checks bound to the newly accepted display, not the previous request.
        screenKey = intent.getStringExtra("display_key")!!
        if (intent.hasExtra("received_media")) receive(intent)
        else if (intent.getBooleanExtra("open_player", false)) windowModel.go("PLAYER")
        else if (intent.getBooleanExtra("open_operations", false)) windowModel.go("OPERATIONS")
        else if (intent.getBooleanExtra("open_photo", false)) openWidgetPhoto(intent)
        else if (intent.getBooleanExtra("open_music", false)) openWidgetMusic(intent)
    }

    private fun receive(request: Intent) {
        try { windowModel.received(ReceivedMedia.decode(request.getStringExtra("received_media")!!).encode()) }
        catch (failure: Exception) { session.fail(failure.message ?: localized(R.string.text_content_cannot_be_opened_048ecf, "Contenuto non apribile")) }
        finally { request.removeExtra("received_media") }
    }

    private fun openWidgetMusic(request: Intent) {
        windowModel.go("MUSIC")
        windowModel.musicMode("TRACKS")
        val media = request.getStringExtra("music_media") ?: return
        val app = application as GalleryApp
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { app.catalog.readMediaSnapshot(media) }
            if (intent !== request || isFinishing || !accepted || windowModel.route.value.tab != "MUSIC") return@launch
            data.byId[media]?.takeIf { it.kind == "AUDIO" && data.usable(it) }?.let { session.selectMedia(it.id) }
        }
    }

    private fun openWidgetPhoto(request: Intent) {
        windowModel.go("PHOTO")
        val target = PhotoWidgetTarget.from(request) ?: return
        val app = application as GalleryApp
        lifecycleScope.launch {
            val result = try { withContext(Dispatchers.IO) {
                if (!app.photoWidgets.owns(target.widgetId)) return@withContext null
                val data = app.catalog.readSnapshot()
                val fresh = app.catalog.readMediaSnapshot(target.id)
                val selection = app.photoWidgets.settings.get(target.widgetId).media(data)
                val item = selection.firstOrNull { it.id == target.id && fresh.byId[it.id]?.let(fresh::usable) == true &&
                    !DocumentVersions.changed(target.size, target.modified, it.size, it.modified) }
                item?.let { it to selection.map { media -> media.id } }
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { session.fail(localized(R.string.text_the_widget_photo_could_not_be_opened_try_again_47f055, "La foto del widget non si apre. Riprova.")); return@launch }
            if (intent !== request || isFinishing || !accepted || windowModel.route.value.tab != "PHOTO" || windowModel.route.value.viewer != null) return@launch
            if (result == null) { session.fail(localized(R.string.text_the_widget_photo_is_no_longer_available_d35303, "La foto del widget non è più disponibile.")); return@launch }
            session.updateMediaContext(result.second); session.selectMedia(result.first.id); windowModel.open(result.first.id)
        }
    }

    internal fun refreshRecentVisibility() {
        try {
            getSystemService(android.app.ActivityManager::class.java).appTasks
                .firstOrNull {
                    val info = it.taskInfo
                    @Suppress("DEPRECATION")
                    val id = if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.id
                    id == taskId
                }?.setExcludeFromRecents(slot == 1 && ::session.isInitialized && intent.getBooleanExtra("paired_gallery", false) && session.startupOther(screenKey) != null)
        } catch (failure: RuntimeException) {
            android.util.Log.w("DSGallery", "Could not update Recents task metadata", failure)
        }
    }

    override fun onStart() {
        super.onStart()
        LauncherIcon.applyTaskIcon(this)
        refreshRecentVisibility()
        if (accepted) {
            if (screenKey in session.state.ceded || session.current(this)?.key != screenKey) {
                finishAndRemoveTask()
                return
            }
            session.refreshUsageAccess()
            session.lifecycle(this, true)
            session.restorePairAfterRecentReturn(this)
            isStartedFlow.value = true
            (application as GalleryApp).video.galleryReturned(this)
            (application as GalleryApp).video.resumePipViewer(this)
            refreshSourceAccess()
            (application as GalleryApp).deviceGallery.refresh(force = true)
        }
    }

    override fun onStop() {
        clearInput()
        (application as GalleryApp).video.panelStopped(slot)
        isStartedFlow.value = false
        if (accepted) session.lifecycle(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        clearInput()
        session.detach(this)
        if (isFinishing && !session.hasOtherPanel(this)) (application as GalleryApp).trimTvArtworkIfIdle()
        super.onDestroy()
    }

    private var galleryImmersive = false
    fun immersivePreview(enabled: Boolean) {
        galleryImmersive = enabled
        val app = application as GalleryApp
        val shaded = manuallyDimmed || app.video.dimNavigation && app.video.owner != slot || windowModel.frameDimmed.value
        val showStatus = app.preferences.state.value.showUpperStatusBar &&
            session.screens.firstOrNull { it.key == screenKey }?.id == android.view.Display.DEFAULT_DISPLAY && !shaded
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, showStatus && !enabled)
        val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        if (showStatus) bars.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        if (hasFocus) immersivePreview(galleryImmersive)
        clearInput()
        if (accepted) {
            session.record("P$slot focus=$hasFocus")
            if (hasFocus) {
                (application as GalleryApp).video.resumePipViewer(this)
                handler.post(repeatInput); refreshSourceAccess()
            }
        }
    }

    override fun onUserLeaveHint() {
        if (accepted) { (application as GalleryApp).video.leaveGallery(this); session.userLeftPanel(this) }
        super.onUserLeaveHint()
    }

    private fun clearInput() {
        videoStickX = 0f; videoStickY = 0f
        consoleAxes = null; consoleStick.clear()
        input.clear(); previewAxes = null; handler.removeCallbacks(repeatInput)
        if (::session.isInitialized) session.controllerTarget(this)?.let { target ->
            target.consoleAxes = null; target.consoleStick.clear()
            target.input.clear(); target.previewAxes = null; target.handler.removeCallbacks(target.repeatInput)
            target.videoStickX = 0f; target.videoStickY = 0f
            target.controllerModal?.let { it.input?.clear(); it.clearCapture?.invoke() }
        }
    }
    private var accessRefresh: kotlinx.coroutines.Job? = null
    private fun refreshSourceAccess() {
        if (accessRefresh?.isActive == true) return
        accessRefresh = lifecycleScope.launch {
            try {
                val app = application as GalleryApp
                withContext(Dispatchers.IO) { app.catalog.refreshAccess() }
                app.deviceGallery.refresh(force = false)
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { session.fail(localized(R.string.text_source_verification_failed_try_again_in_sources_be6dc5, "Verifica delle sorgenti non riuscita. Riprova dalle Sorgenti.")) }
        }
    }

    private fun emitPad(action: PadAction) {
        if (!accepted || (!focused && !session.receivesController(this))) return
        if (session.controllerTarget(this) === this) executeControllerAction(action)
    }

    internal fun executeControllerAction(action: PadAction) {
        if (!accepted || !isStartedFlow.value || isFinishing) return
        if (session.pinActivationRequestId != null) {
            if (action == PadAction.CONFIRM) { session.confirmPinnedActivation(); return }
            if (action == PadAction.BACK) { session.pinActivationRequestId = null; return }
        }
        val app = application as GalleryApp
        if (controllerModal == null) app.tv.abandonTransientActions()
        manuallyDimmed = false
        if (windowModel.route.value.tab == "SETTINGS_HUB" && action in setOf(PadAction.CONFIRM, PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT)) {
            enterPadMode?.invoke()
            if (armSettingsFocus?.invoke() == true) return
        }
        if (controllerModal == null && windowModel.route.value.tab == "TRASH" && action in setOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT)) {
            app.cycleRecoveryTool(if (action == PadAction.TAB_NEXT) 1 else -1)
            return
        }
        if ((windowModel.route.value.tab == "FTP" || windowModel.route.value.tab == "TRASH" && session.recoveryTab == "FTP") && action in setOf(PadAction.MENU, PadAction.SETTINGS)) return
        if ((windowModel.route.value.tab == "FTP" || windowModel.route.value.tab == "TRASH" && session.recoveryTab == "FTP") && controllerModal == null && app.explorer.controller(action)) return
        val candidate = focusedMediaItemId ?: session.selectedMediaId
        if (action == PadAction.CONFIRM && session.pinnedPreviewId != null && candidate != session.pinnedPreviewId &&
            (windowModel.route.value.album != null || windowModel.route.value.tab in setOf("PHOTO", "VIDEO", "FAVORITE")) &&
            app.catalog.snapshot.value.byId[candidate]?.kind in setOf("IMAGE", "GIF", "VIDEO")) {
            session.pinActivationRequestId = candidate
            return
        }
        if ((application as GalleryApp).video.handleResumeControllerAction(action)) return
        if (settingsInputAction?.invoke(action) == true) return
        if (session.settingsOwner == slot && windowModel.route.value.tab == "SETTINGS" && session.settingsSection in setOf("THEME", "THEME_CUSTOM", "INTERFACE", "PLAYBACK") && action in setOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT)) {
            if (session.settingsSection == "PLAYBACK") {
                val tabs = listOf("IMAGE", "VIDEO", "MUSIC")
                session.playbackSettingsCategory = tabs[Math.floorMod(tabs.indexOf(session.playbackSettingsCategory) + if (action == PadAction.TAB_NEXT) 1 else -1, tabs.size)]
            } else session.cycleSettingsPreview(if (action == PadAction.TAB_NEXT) 1 else -1)
            return
        }
        if (session.settingsInfoFocused && windowModel.route.value.tab == "SETTINGS_HUB") {
            when (action) {
                PadAction.CONFIRM -> { session.settingsInfoFocused = false; session.settingsSection = "INFO"; windowModel.go("SETTINGS_INFO") }
                PadAction.UP, PadAction.BACK -> { session.settingsInfoFocused = false; ensurePadFocus?.invoke() }
                else -> Unit
            }
            return
        }
        if (session.settingsModel()?.route?.value?.tab == "SETTINGS_INFO" && session.canSwap()) {
            if (action == PadAction.DOWN && infoHeaderBottom) { clearPadFocus?.invoke(); session.infoDetailsActive = true; session.focusInfoDetails?.invoke(); return }
            if (action == PadAction.UP && infoDetailsTop) { clearPadFocus?.invoke(); session.infoDetailsActive = false; session.focusInfoHeader?.invoke(); return }
        }
        enterPadMode?.invoke()
        ensurePadFocus?.invoke()
        if (sliderAction?.invoke(action) == true) return
        if (videoControllerAction?.invoke(action) == true) return
        when (action) {
            PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT -> moveFocus?.invoke(action)
            PadAction.CONFIRM -> {
                window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            }
            PadAction.BACK -> onBackPressedDispatcher.onBackPressed()
            else -> padAction?.invoke(action)
        }
        session.record(localized(R.string.text_p_1_s_local_command_2_s_b9fd52, "P%1\$s comando locale: %2\$s", slot, action))
    }

    fun handleControllerKey(event: KeyEvent): Boolean {
        if (padButton(event.keyCode) != null && event.action == KeyEvent.ACTION_DOWN)
            session.record(localized(R.string.text_p_1_s_key_2_s_source_3_s_focus_4_s_accepted_5_s_10cfe3, "P%1\$s tasto %2\$s, sorgente %3\$s, focus=%4\$s, accettato=%5\$s", slot, event.keyCode, event.source, focused, accepted))
        if (!accepted || !focused || !isStartedFlow.value) return false
        if (padButton(event.keyCode) == null) return false
        val target = session.controllerTarget(this) ?: return true
        return target.receiveControllerKey(event)
    }
    internal fun receiveControllerKey(event: KeyEvent): Boolean {
        if (!accepted || !isStartedFlow.value || isFinishing) return true
        if (session.pinActivationRequestId != null && padButton(event.keyCode) in setOf(PadButton.A, PadButton.B)) {
            // The dialog receives the release of the A press that opened it.
            input.clear()
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (padButton(event.keyCode) == PadButton.A) session.confirmPinnedActivation() else session.pinActivationRequestId = null
            }
            return true
        }
        val browser = session.libraryModel()
        if (session.mediaOptions != null || browser?.albumMenu?.value == true) {
            val button = padButton(event.keyCode)
            val profile = (application as GalleryApp).preferences.state.value.pad.profile(event.device?.descriptor)
            val action = profile.bindings[PadContext.LIBRARY].orEmpty().entries.firstOrNull { (action, binding) -> action in setOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT) && binding.buttons == setOf(button) }?.key
            if (action != null && browser != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val tabs = visibleNavbarTabs((application as GalleryApp).preferences.state.value)
                    val index = tabs.indexOfFirst { it.key == browser.route.value.tab }.coerceAtLeast(0)
                    browser.go(tabs[Math.floorMod(index + if (action == PadAction.TAB_NEXT) 1 else -1, tabs.size)].key)
                }
                return true
            }
        }
        controllerModal?.let { return it.key(event) || padButton(event.keyCode) != null }
        if (windowModel.route.value.tab == "TRASH" || session.settingsOwner == slot && (windowModel.route.value.tab == "SETTINGS_INPUT" ||
            windowModel.route.value.tab == "SETTINGS" && session.settingsSection in setOf("THEME", "THEME_CUSTOM", "INTERFACE", "PLAYBACK"))) {
            val bindings = (application as GalleryApp).preferences.state.value.pad.profile(event.device?.descriptor).bindings[PadContext.LIBRARY].orEmpty()
            val button = padButton(event.keyCode)
            val actions = if (windowModel.route.value.tab == "SETTINGS_INPUT") listOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT, PadAction.SETTINGS)
                else listOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT)
            val action = actions.firstOrNull { bindings[it]?.buttons == setOf(button) }
            if (action != null) {
                lastPadDevice = event.device
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) executeControllerAction(action)
                return true
            }
        }
        val handled = input.key(event)
        if (handled) { handler.removeCallbacks(repeatInput); handler.postDelayed(repeatInput, 20) }
        return handled
    }
    internal fun receiveControllerMotion(event: MotionEvent): Boolean {
        if (!accepted || !isStartedFlow.value || isFinishing) return true
        controllerModal?.let { return it.motion(event) }
        val handled = input.motion(event)
        if (handled) { handler.removeCallbacks(repeatInput); handler.postDelayed(repeatInput, 20) }
        return handled
    }
    // This public platform Window.Callback override must retain the superclass's handling of ordinary keys.
    // androidx.core annotates its implementation RestrictedApi; no other restricted member is used here.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) (application as GalleryApp).tv.abandonTransientActions()
        return super.dispatchTouchEvent(event)
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (handleControllerKey(event)) true else super.dispatchKeyEvent(event)


    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!accepted || !focused || !isStartedFlow.value || event.action != MotionEvent.ACTION_MOVE ||
            (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && !event.isFromSource(InputDevice.SOURCE_GAMEPAD))) {
            return super.dispatchGenericMotionEvent(event)
        }
        val target = session.controllerTarget(this) ?: return true
        return target.receiveControllerMotion(event)
    }

    fun copyReport() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(localized(R.string.text_thor_gallery_m1_417893, "DS Gallery M1"), session.report(this)))
        uiToast(this, localized(R.string.text_report_copied_you_can_paste_it_into_chat_5f32cd, "Rapporto copiato. Puoi incollarlo nella chat."), Toast.LENGTH_SHORT).show()
    }

    fun openExternal(intent: Intent, request: ExternalRequest) {
        val app = application as GalleryApp
        val here = session.current(this)
        check(accepted && isStartedFlow.value && !isFinishing && here != null && here.key == screenKey && here.key !in session.state.ceded) {
            localized(R.string.text_the_panel_is_no_longer_available_try_again_from_88605c, "Il pannello non è più disponibile. Riprova dalla finestra attiva.")
        }
        val data = app.catalog.snapshot.value
        check(request.targets.all { target -> data.byId[target.id]?.let { data.usable(it) && target.matches(it) } == true }) {
            localized(R.string.text_the_selection_is_no_longer_available_reopen_the_672edd, "La selezione non è più disponibile. Riapri il menu.")
        }
        try { startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle()) }
        catch (_: android.content.ActivityNotFoundException) { throw IllegalStateException(localized(R.string.text_no_app_available_to_open_this_6a28bb, "Nessuna app disponibile per questa apertura")) }
        session.record(localized(R.string.text_p_1_s_external_picker_requested_on_display_2_s_o_214785, "P%1\$s selettore esterno richiesto su display %2\$s; esito non dichiarato", slot, here.id))
    }

    private val broadStoragePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager())
            chooseSource(SourceRole.valueOf(windowModel.pendingSourceRole), true)
    }
    fun chooseWholeStorage(role: SourceRole) {
        if (android.os.Build.VERSION.SDK_INT < 30) { chooseSource(role); return }
        windowModel.pendingSourceRole = role.name
        if (android.os.Environment.isExternalStorageManager()) { chooseSource(role, true); return }
        val here = session.current(this) ?: return
        try {
            broadStoragePermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:$packageName")).putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
                    ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle()))
        } catch (_: RuntimeException) {
            try { broadStoragePermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (_: RuntimeException) { devicePermissionSettings() }
        }
    }
    fun chooseSource(role: SourceRole, wholeStorage: Boolean = false) {
        windowModel.pendingSourceRole = role.name
        val here = session.current(this) ?: return
        if (wholeStorage) contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri(SharedStorageDocuments.AUTHORITY), null)
        sourcePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .apply { if (wholeStorage) putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildRootUri(SharedStorageDocuments.AUTHORITY, SharedStorageDocuments.ROOT)) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
                ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle()))
    }

    fun systemPicker() {
        val here = session.current(this) ?: return
        try {
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
                    ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle()))
            session.record(localized(R.string.text_p_1_s_system_picker_requested_on_display_2_s_fe81f3, "P%1\$s picker di sistema richiesto su display %2\$s", slot, here.id))
        } catch (error: RuntimeException) {
            session.fail(localized(R.string.text_picker_unavailable_1_s_bd35d0, "Picker non disponibile: %1\$s", error.javaClass.simpleName))
        }
    }

}
