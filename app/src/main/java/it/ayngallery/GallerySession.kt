package it.ayngallery

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScreenInfo(val id: Int, val key: String, val name: String, val width: Int, val height: Int, val flags: Int)
internal data class MediaEditorPanel(val request: String, val ownerSlot: Int, val recipe: String? = null,
    val previewPosition: Long = 0, val playing: Boolean = false, val showResult: Boolean = false, val seekRevision: Long = 0)
internal data class ForegroundUsageEvent(val packageName: String, val resumed: Boolean)
internal fun latestResumedPackage(events: Iterable<ForegroundUsageEvent>): String? =
    events.lastOrNull { it.resumed }?.packageName

class GallerySession(private val app: Application) : DisplayManager.DisplayListener {
    var previewSwipe by mutableStateOf(false)
    val imageOverlayState = mutableStateOf(true)
    var imageOverlayVisible by imageOverlayState
    var imageOverlayInteraction by mutableStateOf(android.os.SystemClock.elapsedRealtime())
    fun interactImageOverlay() { imageOverlayInteraction = android.os.SystemClock.elapsedRealtime() }
    private val prefs = app.getSharedPreferences("display_safety_v1", Context.MODE_PRIVATE)
    private val manager = app.getSystemService(DisplayManager::class.java)
    private val panels = mutableMapOf<Int, WeakReference<PanelActivity>>()
    val actionModalOpen get() = panels.values.any { it.get()?.controllerModal != null }
    private val started = mutableSetOf<Int>()
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var homeWatch: Job? = null
    private var returningFromSleep = false
    private val backgroundBothIfHome = Runnable {
        // A phone's only panel also stops for an in-task picker; only paired tasks need this Home fallback.
        val paired = panels.values.mapNotNull { it.get() }.any {
            it.intent.getBooleanExtra("paired_gallery", false) && startupOther(it.screenKey) != null
        }
        if (started.isEmpty() && !returningFromSleep && paired) backgroundBoth("all-panels-stopped")
    }
    val visiblePanels = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var startupGeneration = 0
    private val homeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Explicit HOME is immediate. Gesture/Recents Home is resolved through Usage Access
            // after onUserLeaveHint, so an app or game taking one display does not hide its partner.
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                returningFromSleep = true
                (app as GalleryApp).playbackSleep()
                backgroundHandler.removeCallbacks(backgroundBothIfHome)
                homeWatch?.cancel(); homeWatch = null
                record("Sleep: cancelled pending Home detection")
                return
            }
            if (intent.action == Intent.ACTION_SCREEN_ON) { (app as GalleryApp).playbackWake(); return }
            if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            when (intent.getStringExtra("reason")) {
                "homekey" -> { returningFromSleep = false; backgroundBoth("homekey") }
                // Thor's edge gesture opens Recents without a Home key event. Start watching
                // here, then dismiss both display tasks only once iiSU itself returns.
                "recentapps" -> watchForHomeReturn()
            }
        }
    }
    private val tickets = mutableMapOf<String, String>()
    private val allowed = mutableSetOf<String>()
    var screens by mutableStateOf(emptyList<ScreenInfo>())
        private set
    val activeScreens get() = screens.filter { it.key !in state.ceded }
    val isSingleScreen get() = activeScreens.size == 1
    val librarySlot get() = DisplayPolicy.librarySlot(state, isSingleScreen)
    private var persistedStateValid = true
    var state by mutableStateOf(readState())
        private set
    var startupPair by mutableStateOf(runCatching { prefs.getStringSet("startup_pair", emptySet())!!.toSet() }
        .onFailure { persistedStateValid = false }.getOrDefault(emptySet()))
        private set
    var singleScreenLandscape by mutableStateOf(prefs.getBoolean("single_landscape", false))
        private set
    fun singleScreenLandscape(enabled: Boolean) {
        if (!isSingleScreen) return
        prefs.edit().putBoolean("single_landscape", enabled).apply()
        singleScreenLandscape = enabled
        panels.values.mapNotNull { it.get() }.forEach { it.updateSingleScreenOrientation() }
    }
    var startupAutomatic by mutableStateOf(runCatching { prefs.getBoolean("startup_automatic", !prefs.contains("startup_pair")) }
        .onFailure { persistedStateValid = false }.getOrDefault(false))
        private set
    var usageAccessGranted by mutableStateOf(hasUsageAccess())
        private set
    val shouldPromptUsageAccess get() = !usageAccessGranted && !prefs.getBoolean("usage_access_deferred", false)
    private var pairResumePending = prefs.getBoolean("pair_resume_pending", false)
    private var pairResumeLaunchInProgress = false
    val thorPair get() = DisplayPolicy.thorPair(Build.MODEL, screens)
    private var pendingThorCompanion: String? = null
    private var pendingThorMain: String? = null
    fun startupOther(current: String): String? = if (persistedStateValid)
        DisplayPolicy.startupOther(current, screens.map { it.key }.toSet(), startupPair, state, startupAutomatic) else null
    fun rememberStartupPair(activity: PanelActivity, enabled: Boolean) {
        val pair = if (enabled) {
            val current = current(activity)?.key ?: return
            val other = screens.singleOrNull { it.key != current } ?: run { fail(localized(R.string.text_choose_two_available_screens_first_77329a, "Scegli prima due schermi disponibili.")); return }
            setOf(current, other.key)
        } else emptySet()
        if (prefs.edit().putStringSet("startup_pair", pair).putBoolean("startup_automatic", false).commit()) {
            startupPair = pair; startupAutomatic = false
            if (enabled) {
                prefs.edit().putBoolean("usage_access_deferred", false).apply()
                val target = screens.single { it.key in pair && it.key != activity.screenKey }
                expand(activity, target)
                activity.recreate()
            }
        } else fail(localized(R.string.text_startup_preference_not_saved_febc48, "Preferenza di avvio non salvata."))
    }
    private val restoreSavedPreview = (app as? GalleryApp)?.preferences?.state?.value?.persistSelectedMedia == true
    var selectedMediaId by mutableStateOf(if (restoreSavedPreview) prefs.getString("selected_media", null) else null)
        private set
    var selectedVisualMediaId by mutableStateOf(if (restoreSavedPreview) prefs.getString("selected_visual_media", null) else null)
        private set
    var gridPreviewDirection by mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero)
    fun gridPreviewFocus(ids: List<String>, from: String?, to: String, columns: Int) {
        if (from != to) gridPreviewDirection = gridPreviewDirection(ids.indexOf(from), ids.indexOf(to), columns)
    }
    var previewFocusRevision by mutableIntStateOf(0)
    var previewAlbumId by mutableStateOf<String?>(null)
        private set
    var resetApplicationRequested by mutableStateOf(false)
    var tutorialInProgress by mutableStateOf(false)
    var tutorialLeaving by mutableStateOf(false)
    internal fun hostsTutorial(panel: PanelActivity): Boolean {
        val primary = panels.values.mapNotNull { it.get() }.firstOrNull { candidate ->
            !candidate.isFinishing && candidate.screenKey in allowed && candidate.screenKey !in state.ceded &&
                screens.any { it.key == candidate.screenKey && it.id == android.view.Display.DEFAULT_DISPLAY }
        }
        return primary == null || primary === panel
    }
    var visualSelectedThisSession by mutableStateOf(false)
        private set
    fun clearVisualSelection() { visualSelectedThisSession = false }
    fun dismissVisualPreview() {
        clearVisualSelection()
        selectedVisualMediaId = null
        selectMedia(null)
    }
    var albumCommand by mutableStateOf<Pair<String, Set<String>>?>(null)
    var mediaOptions by mutableStateOf<MediaEntity?>(null)
    var pinnedPreviewId by mutableStateOf<String?>(null)
    var pinActivationRequestId by mutableStateOf<String?>(null)
    var floatingImageRequestId by mutableStateOf<String?>(null)
    fun confirmPinnedActivation() {
        val id = pinActivationRequestId ?: return
        pinActivationRequestId = null
        val gallery = app as GalleryApp
        val target = gallery.catalog.snapshot.value.byId[id]?.takeIf { gallery.catalog.snapshot.value.usable(it) && it.kind in setOf("IMAGE", "GIF", "VIDEO") } ?: return
        pinnedPreviewId = null
        selectMedia(id)
        gallery.video.stop(gallery.video.owner)
        if (target.kind == "VIDEO" && !target.convertedGif) gallery.video.requestPlay(target, previewActivity()?.slot ?: 0, false)
        else if (!canSwap()) libraryModel()?.open(id)
    }
    fun togglePreviewPin(id: String) {
        require((app as GalleryApp).catalog.snapshot.value.byId[id]?.kind in setOf("IMAGE", "GIF"))
        if (pinnedPreviewId == id) {
            (app as GalleryApp).sounds.play("preview-unpin")
            pinnedPreviewId = null
            gridPreviewDirection = androidx.compose.ui.unit.IntOffset.Zero
            pinActivationRequestId = null
            // Reconcile the panel route with browsing focus, never reselect the old pin.
            if (selectedMediaId != id) {
                selectMedia(selectedMediaId)
                previewActivity()?.windowModel?.restorePanelRole()
            }
        } else { (app as GalleryApp).sounds.play("preview-pin"); pinnedPreviewId = id }
    }
    fun previewAlbum(id: String?) {
        gridPreviewDirection = androidx.compose.ui.unit.IntOffset.Zero; previewAlbumId = id
        if (libraryModel()?.route?.value?.album == null) selectedVisualMediaId = null
    }
    internal val albumDialogs = AlbumDialogsState()
    internal val previewDocks = mutableMapOf<String, PreviewDockState>()
    internal var previewGifStripBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    internal var previewNavigatorBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    internal var previewNavigatorOwner: Any? = null
    internal val pausedGifFrames = linkedMapOf<String, Int>()
    var gifStripWidth = 266f
    var gifStripPauseWidth: Float? = null
    var musicMiniSeen by mutableStateOf(false)
    var hidePausedMini by mutableStateOf(false)
    var lastSettingsCategory: String? = null
    var settingsInfoFocused by mutableStateOf(false)
    var infoDetailsActive by mutableStateOf(false)
    var focusInfoHeader: (() -> Unit)? = null
    var focusInfoDetails: (() -> Unit)? = null
    var settingsHubBottomFocused by mutableStateOf(false)
    var albumActionTarget by mutableStateOf<String?>(null)
    fun openAlbumOptions(id: String) {
        val browser = libraryModel() ?: return
        albumActionTarget = id
        browser.albumMenu(true)
    }
    var mediaContext by mutableStateOf(emptyList<String>())
        private set
    fun updateMediaContext(ids: List<String>) { mediaContext = ids.distinct() }
    var events by mutableStateOf(emptyList<String>())
        private set
    var notice by mutableStateOf(localized(R.string.text_at_startup_the_app_uses_both_detected_screens_a_b5d260, "All'avvio l'app usa i due schermi rilevati. Uno schermo ceduto al gioco resta libero."))
        private set
    var error by mutableStateOf<String?>(null)
        private set
    fun clearError() { error = null }
    var revision by mutableIntStateOf(0)
        private set
    internal var mediaEditor by mutableStateOf<MediaEditorPanel?>(null)
        private set

    fun openMediaEditor(origin: PanelActivity, request: String) {
        val target = ExternalRequest.decode(request).targets.single()
        val lowerKey = thorPair?.first?.key ?: screens.firstOrNull { it.id != android.view.Display.DEFAULT_DISPLAY }?.key
        val controlsSlot = if (canSwap()) panels.values.mapNotNull { it.get() }
            .firstOrNull { it.screenKey == lowerKey }?.slot ?: origin.slot else origin.slot
        selectMedia(target.id)
        mediaEditor = MediaEditorPanel(request, controlsSlot)
        revision++
    }
    fun updateMediaEditorRecipe(recipe: String) {
        mediaEditor?.let { if (it.recipe != recipe) mediaEditor = it.copy(recipe = recipe) }
    }
    fun updateMediaEditorPreview(position: Long? = null, playing: Boolean? = null, result: Boolean? = null, seek: Boolean = false) {
        mediaEditor?.let { mediaEditor = it.copy(previewPosition = position ?: it.previewPosition,
            playing = playing ?: it.playing, showResult = result ?: it.showResult, seekRevision = it.seekRevision + if (seek) 1 else 0) }
    }
    fun closeMediaEditor() { mediaEditor = null; revision++ }

    init {
        refreshDisplays()
        state.anchor?.takeIf { anchor -> screens.any { it.key == anchor } && anchor !in state.ceded }
            ?.let(allowed::add)
        manager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        androidx.core.content.ContextCompat.registerReceiver(app, homeReceiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        record(localized(R.string.text_process_started_checking_availability_and_releas_262ec8, "Processo avviato; verifica disponibilità e cessioni"))
    }
    fun close() { startupGeneration++; backgroundHandler.removeCallbacks(backgroundBothIfHome); lifecycleScope.cancel(); app.unregisterReceiver(homeReceiver); manager.unregisterDisplayListener(this); panels.clear(); started.clear(); visiblePanels.value = 0; tickets.clear(); allowed.clear() }

    private fun hasUsageAccess(): Boolean = try {
        @Suppress("DEPRECATION")
        app.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: RuntimeException) { false }

    fun refreshUsageAccess(): Boolean {
        val granted = hasUsageAccess()
        if (usageAccessGranted != granted) { usageAccessGranted = granted; revision++ }
        return granted
    }

    fun deferUsageAccessPrompt() {
        prefs.edit().putBoolean("usage_access_deferred", true).apply()
        revision++
    }

    fun openUsageAccessSettings(activity: PanelActivity) {
        deferUsageAccessPrompt()
        val display = current(activity)?.id ?: 0
        try {
            activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
        } catch (error: RuntimeException) {
            fail(localized(R.string.text_android_settings_unavailable_open_app_permission_fef31a,
                "Impostazioni Android non disponibili. Apri i permessi dell'app dalle impostazioni del dispositivo."))
        }
    }

    fun userLeftPanel(panel: PanelActivity) {
        // Recent Apps / Clear all can stop just one display's Activity while its
        // companion stays started. Do not gate this check on canSwap() or the
        // leaving Activity's started state: those are exactly what Recent Apps
        // changes before the launcher becomes the latest resumed app.
        if (panels[panel.slot]?.get() !== panel ||
            !panel.intent.getBooleanExtra("paired_gallery", false) || startupOther(panel.screenKey) == null) return
        watchForHomeReturn()
    }

    private fun watchForHomeReturn() {
        if (returningFromSleep) return
        if (!refreshUsageAccess() || Build.VERSION.SDK_INT < 29 || homeWatch?.isActive == true ||
            panels.values.mapNotNull { it.get() }.none { it.intent.getBooleanExtra("paired_gallery", false) && startupOther(it.screenKey) != null }) return
        homeWatch = lifecycleScope.launch {
            repeat(20) {
                delay(150)
                if (panels.values.mapNotNull { it.get() }.none {
                        !it.isFinishing && !it.isDestroyed && it.intent.getBooleanExtra("paired_gallery", false) && startupOther(it.screenKey) != null
                    }) return@launch
                val foreground = withContext(Dispatchers.IO) { latestResumedPackage() } ?: return@repeat
                if (foreground == homePackage()) {
                    backgroundBoth("launcher-after-user-leave")
                    return@launch
                }
            }
            homeWatch = null
        }
    }

    private fun homePackage(): String? = app.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
    )?.activityInfo?.packageName

    @androidx.annotation.RequiresApi(29)
    private fun latestResumedPackage(): String? = try {
        val end = System.currentTimeMillis()
        val events = app.getSystemService(UsageStatsManager::class.java).queryEvents(end - 10_000L, end)
        val event = UsageEvents.Event()
        val resumed = mutableListOf<ForegroundUsageEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            resumed += ForegroundUsageEvent(event.packageName, event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
        }
        latestResumedPackage(resumed)
    } catch (_: RuntimeException) { null }

    private fun backgroundBoth(reason: String) {
        // Sleeping/folding the handheld is not a request to return to its launcher.
        if (!app.getSystemService(android.os.PowerManager::class.java).isInteractive) return
        (app as? GalleryApp)?.music?.launcherBackground(true)
        (app as? GalleryApp)?.video?.galleryBackgrounded()
        (app as? GalleryApp)?.catalog?.protection?.lock()
        startupGeneration++
        homeWatch = null
        lifecycleScope.coroutineContext.cancelChildren()
        val livePanels = panels.values.mapNotNull { it.get() }.filter { !it.isFinishing && !it.isDestroyed }
        pairResumePending = livePanels.any { it.intent.getBooleanExtra("paired_gallery", false) && startupOther(it.screenKey) != null }
        prefs.edit().putBoolean("pair_resume_pending", pairResumePending).apply()
        pairResumeLaunchInProgress = false
        livePanels
            .forEach { panel ->
                record("Home ($reason): P${panel.slot} background=${panel.moveTaskToBack(true)}")
            }

    }

    /** A task card restores one Android task. Bring its authorized companion back as well. */
    fun restorePairAfterRecentReturn(activity: PanelActivity) {
        if (!pairResumePending || pairResumeLaunchInProgress || activity.isFinishing || activity.isDestroyed ||
            !activity.intent.getBooleanExtra("paired_gallery", false)) return
        val otherKey = startupOther(activity.screenKey) ?: return
        val otherSlot = 1 - activity.slot
        val other = screens.firstOrNull { it.key == otherKey } ?: return
        pairResumeLaunchInProgress = true
        if (launch(activity, other, otherSlot)) setPairResumePending(false)
        else pairResumeLaunchInProgress = false
    }

    /** LauncherActivity opens the pair itself; don't race it with the Recent-task recovery path. */
    fun beginLauncherLaunch() {
        pairResumeLaunchInProgress = false
        setPairResumePending(false)
    }

    /** Expanding PiP resumes the existing displays without applying a new launcher layout. */
    fun resumeVideoPanels(activity: Activity): Boolean {
        refreshDisplays()
        val targets = panels.values.mapNotNull { it.get() }.filter {
            !it.isFinishing && !it.isDestroyed && it.screenKey in allowed && it.screenKey !in state.ceded
        }.mapNotNull { panel -> screens.firstOrNull { it.key == panel.screenKey }?.let { panel to it } }
        if (targets.isEmpty()) return false
        beginLauncherLaunch()
        val here = current(activity)?.id
        val ordered = targets.sortedBy { if (it.second.id == here) 0 else 1 }
        val (first, screen) = ordered.first()
        var opened = launch(activity, screen, first.slot, pairedGallery = first.intent.getBooleanExtra("paired_gallery", false))
        ordered.drop(1).forEach { (panel, companion) ->
            if (opened && thorPair != null && screen.id == here && startupOther(screen.key) == companion.key)
                deferStartupCompanion(screen.key, companion.key, panel.slot)
            else if (launch(activity, companion, panel.slot, pairedGallery = panel.intent.getBooleanExtra("paired_gallery", false))) opened = true
        }
        return opened
    }

    private fun setPairResumePending(pending: Boolean) {
        pairResumePending = pending
        pairResumeLaunchInProgress = false
        prefs.edit().putBoolean("pair_resume_pending", pending).apply()
    }

    internal fun deferStartupCompanion(firstKey: String, targetKey: String, slot: Int) {
        val generation = ++startupGeneration
        // iiSU migrates Home to the lower screen after launching an upper-screen app.
        // Let that transition settle before claiming the companion display. Never retry
        // or reopen after Home, a stopped first panel, or a changed display permission.
        Handler(Looper.getMainLooper()).postDelayed({
            if (generation != startupGeneration) return@postDelayed
            refreshDisplays()
            val caller = panels.values.mapNotNull { it.get() }.firstOrNull {
                it.screenKey == firstKey && it.slot in started && !it.isFinishing && !it.isDestroyed && current(it)?.key == firstKey
            } ?: return@postDelayed
            if (startupOther(firstKey) != targetKey) return@postDelayed
            val target = screens.firstOrNull { it.key == targetKey } ?: return@postDelayed
            launch(caller, target, slot)
        }, 600L)
    }

    private fun readState(): DisplayState = try {
        DisplayState(prefs.getString("anchor", null), prefs.getStringSet("ceded", emptySet())!!.toSet(),
            prefs.getBoolean("swapped", false), prefs.getInt("anchor_slot", 0).coerceIn(0, 1))
    } catch (_: ClassCastException) {
        persistedStateValid = false
        DisplayState() // Ambiguous persisted state grants no dual-screen permission.
    }

    fun screen(display: Display?): ScreenInfo? = display?.takeIf { it.isValid }?.let {
        val mode = it.mode
        // Android has no public stable Display.uniqueId. Require the ID AND descriptor to match;
        // changed IDs/modes are uncertain and never cause automatic remapping or expansion.
        ScreenInfo(it.displayId, "${it.displayId}|${it.name}|${mode.physicalWidth}x${mode.physicalHeight}|${it.flags}",
            it.name, mode.physicalWidth, mode.physicalHeight, it.flags)
    }

    fun refreshDisplays() {
        screens = manager.displays.mapNotNull(::screen)
        panels.values.mapNotNull { it.get() }.forEach { it.updateSingleScreenOrientation() }
        revision++
    }

    @Suppress("DEPRECATION")
    fun current(activity: Activity): ScreenInfo? = screen(
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay)

    fun label(screen: ScreenInfo): String = thorPair?.let { if (screen.key == it.first.key) localized(R.string.text_lower_fa89db, "Inferiore") else localized(R.string.text_upper_ae009f, "Superiore") }
        ?: prefs.getString("label:${screen.key}", null) ?: localized(R.string.text_screen_1_s_55a281, "Schermo %1\$s", screen.id)

    fun restoreThorLayout(activity: PanelActivity) {
        refreshDisplays()
        val (lower, upper) = thorPair ?: return
        val pair = setOf(lower.key, upper.key)
        if (!prefs.edit().putStringSet("startup_pair", pair).putBoolean("startup_automatic", true).commit()) {
            fail(localized(R.string.text_thor_layout_not_saved_e72a31, "Layout Thor non salvato.")); return
        }
        startupPair = pair; startupAutomatic = true
        if (!save(state.copy(anchor = lower.key, anchorSlot = 1, swapped = true, ceded = state.ceded - pair))) return
        panels.values.mapNotNull { it.get() }.forEach { it.windowModel.restorePanelRole() }
        pendingThorCompanion = lower.key
        // Keep the companion visible while relocating a legacy main task from the lower display.
        val companionFirst = activity.slot == 0 && current(activity)?.key != upper.key
        pendingThorMain = upper.key.takeIf { companionFirst }
        val first = if (companionFirst) lower else upper
        val slot = if (companionFirst) 1 else 0
        if (!launch(activity, first, slot)) { pendingThorCompanion = null; pendingThorMain = null; return }
        panels[slot]?.get()?.takeIf { it.isStartedFlow.value && !it.isFinishing && current(it)?.key == first.key }
            ?.let(::continueThorLayout)
    }

    private fun continueThorLayout(activity: PanelActivity) {
        if (activity.slot == 0) completeThorLayout(activity)
        else if (activity.screenKey == pendingThorCompanion) {
            val key = pendingThorMain ?: return
            pendingThorMain = null
            val upper = screens.find { it.key == key && it.key !in state.ceded }
            if (upper == null || !launch(activity, upper, 0)) pendingThorCompanion = null
        }
    }

    /** Consume the explicit restore once the primary task is visible, then let iiSU settle. */
    private fun completeThorLayout(activity: PanelActivity) {
        val key = pendingThorCompanion ?: return
        pendingThorCompanion = null
        if (startupOther(activity.screenKey) == key) deferStartupCompanion(activity.screenKey, key, 1)
    }

    fun setLabel(screen: ScreenInfo, label: String) {
        if (prefs.edit().putString("label:${screen.key}", label).commit()) {
            revision++
            record(localized(R.string.text_screen_1_s_label_2_s_b21ce5, "Schermo %1\$s: etichetta %2\$s", screen.id, label))
        } else fail(localized(R.string.text_could_not_save_label_1a4871, "Impossibile salvare l'etichetta."))
    }

    fun save(next: DisplayState): Boolean {
        if (!prefs.edit().putString("anchor", next.anchor).putStringSet("ceded", next.ceded)
                .putBoolean("swapped", next.swapped).putInt("anchor_slot", next.anchorSlot).commit()) {
            fail(localized(R.string.text_state_not_saved_no_new_window_opened_eaf55b, "Stato non salvato. Nessuna nuova finestra aperta."))
            return false
        }
        val cessionsChanged = state.ceded != next.ceded
        state = next
        if (cessionsChanged) panels.values.mapNotNull { it.get() }.forEach(PanelActivity::refreshRecentVisibility)
        return true
    }

    fun launch(activity: Activity, target: ScreenInfo, slot: Int, openPlayer: Boolean = false, openOperations: Boolean = false,
        openPhoto: Boolean = false, photo: PhotoWidgetTarget? = null, received: ReceivedMedia? = null, pairedGallery: Boolean = true,
        openMusic: Boolean = false, musicMedia: String? = null): Boolean {
        refreshDisplays()
        val live = screens.find { it.key == target.key } ?: return fail(localized(R.string.text_display_no_longer_available_8322f0, "Display non più disponibile."))
        // A surviving singleTask on the other display cannot reliably be relocated by launch options.
        panels[slot]?.get()?.takeIf { current(it)?.id != live.id }?.finishAndRemoveTask()
        val token = UUID.randomUUID().toString()
        val noAnimation = activity.intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0
        val intent = Intent(activity, if (slot == 0) MainPanelActivity::class.java else OtherPanelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (slot == 1) Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS else 0) or
                (if (noAnimation) Intent.FLAG_ACTIVITY_NO_ANIMATION else 0))
            .putExtra("display_key", live.key).putExtra("ticket", token).putExtra("open_player", openPlayer)
            .putExtra("open_operations", openOperations)
            .putExtra("open_photo", openPhoto)
            .putExtra("open_music", openMusic).putExtra("music_media", musicMedia)
            .putExtra("paired_gallery", pairedGallery && !openPlayer && !openOperations && !openPhoto && !openMusic && received == null)
        photo?.put(intent)
        received?.put(intent)
        if (Build.VERSION.SDK_INT >= 29 &&
            !app.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(activity, live.id, intent)) {
            return fail(localized(R.string.text_android_does_not_allow_an_activity_on_screen_1_s_d097fe, "Android non consente un'Activity sullo schermo %1\$s.", live.id))
        }
        tickets[token] = live.key
        allowed.add(live.key)
        return try {
            val options = if (noAnimation) ActivityOptions.makeCustomAnimation(activity, 0, 0) else ActivityOptions.makeBasic()
            activity.startActivity(intent, options.setLaunchDisplayId(live.id).toBundle())
            record(localized(R.string.text_explicit_panel_1_s_request_screen_2_s_80a0d8, "Richiesta esplicita pannello %1\$s → schermo %2\$s", slot, live.id))
            true
        } catch (error: RuntimeException) {
            tickets.remove(token)
            if (panels.values.none { it.get()?.screenKey == live.key }) allowed.remove(live.key)
            fail(localized(R.string.text_opening_refused_1_s_no_automatic_retry_f38df6, "Apertura rifiutata: %1\$s. Nessun nuovo tentativo automatico.", error.javaClass.simpleName))
        }
    }

    fun accept(activity: PanelActivity): Boolean {
        val actual = current(activity) ?: return fail(localized(R.string.text_window_display_unavailable_3f5404, "Display della finestra non disponibile."))
        val expected = activity.intent.getStringExtra("display_key")
        val ticket = activity.intent.getStringExtra("ticket")
        val explicit = ticket != null && tickets.remove(ticket) == actual.key
        val rejection = when {
            actual.key != expected -> localized(R.string.text_requested_display_1_s_actual_2_s_7980a8, "display richiesto=%1\$s, effettivo=%2\$s", expected, actual.key)
            actual.key in state.ceded -> localized(R.string.text_released_display_52a9ac, "display ceduto")
            !explicit && actual.key !in allowed -> localized(R.string.text_restore_without_verifiable_authorization_a0331e, "ripristino senza autorizzazione verificabile")
            else -> null
        }
        if (rejection != null) return fail(localized(R.string.text_panel_1_s_closed_2_s_907fdd, "Pannello %1\$s chiuso: %2\$s", activity.slot, rejection))
        allowed.add(actual.key)
        panels[activity.slot] = WeakReference(activity)
        revision++
        return true
    }

    fun lifecycle(activity: PanelActivity, isStarted: Boolean) {
        if (panels[activity.slot]?.get() !== activity) return // Ignore a replaced instance during rotation.
        if (isStarted) {
            returningFromSleep = false
            backgroundHandler.removeCallbacks(backgroundBothIfHome)
            started.add(activity.slot)
        } else {
            started.remove(activity.slot)
            // iiSU/Coocoon may use its own Home gesture without Android's homekey broadcast.
            // Wait for both panels to stop; a game covering only one display keeps its partner.
            if (started.isEmpty()) {
                backgroundHandler.removeCallbacks(backgroundBothIfHome)
                backgroundHandler.postDelayed(backgroundBothIfHome, 500L)
            }
        }
        visiblePanels.value = started.size
        if (!isStarted && state.anchor == activity.screenKey && state.anchorSlot == activity.slot) {
            panels.values.mapNotNull { it.get() }.firstOrNull { it.slot in started && it.screenKey !in state.ceded }
                ?.let { save(state.copy(anchor = it.screenKey, anchorSlot = it.slot)) }
        }
        revision++
        record("P${activity.slot} display ${current(activity)?.id}: ${if (isStarted) localized(R.string.text_visible_373a21, "visibile") else localized(R.string.text_not_visible_7a468e, "non visibile")}")
        if (isStarted) continueThorLayout(activity)
        if (isStarted) (app as? GalleryApp)?.music?.launcherBackground(false)
        if (isStarted && canSwap() && settingsOwner == null) {
            panels.values.mapNotNull { it.get() }.firstOrNull { it.windowModel.route.value.tab.startsWith("SETTINGS") }?.let { openSettings(it) }
            panels.values.mapNotNull { it.get() }.filter { it.windowModel.route.value.tab in galleryTabs }
                .forEach { it.windowModel.restorePanelRole() }
        }
    }

    fun detach(activity: PanelActivity) {
        if (panels[activity.slot]?.get() === activity) {
            if (settingsOwner == activity.slot && !activity.isChangingConfigurations) closeSettings()
            panels.remove(activity.slot)
            if (panels.isEmpty() && !activity.isChangingConfigurations) { previewDocks.clear(); gifStripWidth = 266f; gifStripPauseWidth = null; hidePausedMini = false }
            started.remove(activity.slot)
            if (started.isEmpty()) {
                backgroundHandler.removeCallbacks(backgroundBothIfHome)
                backgroundHandler.postDelayed(backgroundBothIfHome, 500L)
            }
            visiblePanels.value = started.size
            revision++
        }
    }

    /** Controller follows the controls pane, never the Android focus of the paired preview. */
    fun controllerTarget(caller: PanelActivity): PanelActivity? {
        fun usable(panel: PanelActivity?) = panel?.takeIf {
            panels[it.slot]?.get() === it && it.slot in started && !it.isFinishing &&
                it.screenKey in allowed && it.screenKey !in state.ceded && screens.any { screen -> screen.key == it.screenKey }
        }
        if (usable(caller) == null) return null
        if (mediaEditor == null) panels.values.mapNotNull { usable(it.get()) }.firstOrNull { it.controllerModal != null }?.let { return it }
        settingsOwner?.let { owner ->
            if (infoDetailsActive && panels[owner]?.get()?.windowModel?.route?.value?.tab == "SETTINGS_INFO")
                return panels.values.mapNotNull { usable(it.get()) }.firstOrNull { it.slot != owner }
            return usable(panels[owner]?.get())
        }
        mediaEditor?.let { editor -> return usable(panels[editor.ownerSlot]?.get()) }
        if (!caller.intent.getBooleanExtra("paired_gallery", false) || screens.size < 2 || state.ceded.isNotEmpty()) return caller
        return usable(panels[settingsOwner ?: librarySlot]?.get())
    }
    internal fun receivesController(panel: PanelActivity) = panels.values.mapNotNull { it.get() }
        .any { (it.focused || it.controllerInputActive || it.controllerModal?.hasFocus?.invoke() == true) && controllerTarget(it) === panel }

    private fun startedKeys() = started.mapNotNull { panels[it]?.get()?.screenKey }.toSet()
    fun canSwap(): Boolean = DisplayPolicy.canSwap(startedKeys(), allowed, state)
    private val galleryTabs = setOf("PHOTO", "ALBUM", "VIDEO", "MUSIC", "FAVORITE", "COLLECTION", "TV")
    var settingsOwner by mutableStateOf<Int?>(null)
        private set
    fun settingsActivity(): PanelActivity? = settingsOwner?.let { panels[it]?.get() }
    fun settingsModel(): GalleryWindowModel? = settingsOwner?.let { panels[it]?.get()?.windowModel }
    var settingsToolsSection by mutableStateOf("ALBUM")
    fun cycleSettingsPreview(delta: Int) {
        val preferences = settingsDraft ?: (app as GalleryApp).preferences.state.value
        val tabs = NavbarOrder.editable(preferences).filter { it != "TV" && it !in preferences.disabledCategories }
        val index = tabs.indexOf(settingsToolsSection).coerceAtLeast(0)
        settingsToolsSection = tabs[Math.floorMod(index + delta, tabs.size)]
    }
    var settingsSection by mutableStateOf("THEME")
    var settingsReturnControl: String? = null
    var playbackSettingsCategory by mutableStateOf("IMAGE")
    var albumUnlockRequest by mutableStateOf<String?>(null)
    var duplicateTransferBusy by mutableStateOf(false)
    var recoveryTab by mutableStateOf("TRASH")
    var trashPreviewId by mutableStateOf<String?>(null)
    var settingsDraft by mutableStateOf<GalleryPreferences?>(null)
    private var settingsReturn: (() -> Unit)? = null
    var settingsTransitionRevision by mutableIntStateOf(0)
        private set
    fun openSettings(activity: PanelActivity) {
        mediaOptions = null
        albumActionTarget = null
        panels.values.mapNotNull { it.get() }.forEach { it.windowModel.albumMenu(false) }
        (app as GalleryApp).sounds.play("settings")
        (app as GalleryApp).video.pauseForSettings()
        val controls = if (canSwap()) thorPair?.second?.key?.let { key -> panels.values.mapNotNull { it.get() }.firstOrNull { it.screenKey == key } } ?: activity else activity
        if (settingsOwner == null) {
            if (controls.windowModel.route.value.tab.startsWith("SETTINGS")) controls.windowModel.restorePanelRole()
            settingsToolsSection = "ALBUM"
            settingsReturn = controls.windowModel.navigationRestore()
            settingsDraft = (app as GalleryApp).preferences.state.value
        }
        controls.windowModel.go("SETTINGS_HUB")
        lastSettingsCategory = null
        settingsReturnControl = null
        settingsOwner = controls.slot
        settingsTransitionRevision++
    }
    fun openPlaybackSettings(activity: PanelActivity, kind: String) {
        openSettings(activity)
        playbackSettingsCategory = when (kind) { "AUDIO" -> "MUSIC"; "VIDEO" -> "VIDEO"; else -> "IMAGE" }
        settingsSection = "PLAYBACK"
        panels[settingsOwner]?.get()?.windowModel?.go("SETTINGS")
    }
    fun closeSettings() {
        settingsReturn?.invoke()
        settingsOwner = null; settingsDraft = null; trashPreviewId = null
        settingsReturn = null
        settingsTransitionRevision++
        (app as GalleryApp).video.returnFromTools()
    }
    fun settingsBack(): Boolean {
        val owner = settingsOwner ?: return false
        val model = panels[owner]?.get()?.windowModel
        if (model != null && settingsSection in setOf("THEME", "LANGUAGE") && model.route.value.tab != "SETTINGS_HUB") {
            settingsReturnControl = settingsSection
            settingsDraft = null; settingsSection = "INTERFACE"; model.go("SETTINGS"); return true
        }
        if (model?.route?.value?.tab == "SETTINGS_PIN") { settingsSection = "INFO"; model.go("SETTINGS_INFO"); return true }
        if (model == null || model.route.value.tab == "SETTINGS_HUB") closeSettings()
        else model.go(if (model.route.value.tab.startsWith("SETTINGS") || settingsSection !in setOf("INPUT", "LIBRARY", "FILES", "DEVICE", "INFO")) "SETTINGS_HUB" else "SETTINGS_$settingsSection")
        return true
    }
    fun isPairedPreview(activity: PanelActivity) = canSwap() && ((activity.slot == 0) == state.swapped)
    fun previewActivity(): PanelActivity? = panels[1 - librarySlot]?.get()?.takeIf { !isSingleScreen && it.slot in started && !it.isFinishing && it.screenKey in allowed && it.screenKey !in state.ceded }
    fun refreshTaskIcons() { panels.values.mapNotNull { it.get() }.filter { !it.isFinishing }.forEach(LauncherIcon::applyTaskIcon) }
    fun libraryModel(): GalleryWindowModel? = panels[librarySlot]?.get()?.windowModel
    fun libraryNavigation(model: GalleryWindowModel) {
        if (!canSwap() || settingsOwner != null) return
        val caller = panels.values.mapNotNull { it.get() }.firstOrNull { it.windowModel === model } ?: return
        if (caller.slot != librarySlot && !save(state.copy(swapped = caller.slot == 1))) return
        panels[1 - caller.slot]?.get()?.windowModel?.restorePanelRole()
    }
    fun hasOtherPanel(activity: PanelActivity) = panels.values.any { it.get()?.let { panel -> panel !== activity && !panel.isFinishing } == true }
    fun remotePreviewVisible(activity: PanelActivity) = panels.values.mapNotNull { it.get() }.any { panel ->
        panel !== activity && panel.slot in started && !panel.isFinishing &&
            (panel.windowModel.route.value.viewer != null || panel.windowModel.route.value.tab == "COMPARE" ||
                (!panel.windowModel.route.value.forceLibrary && ((panel.slot == 0) == state.swapped)))
    }

    var swapFading by mutableStateOf(false)
        private set
    var swapBusy by mutableStateOf(false)
        private set
    fun swapAnimated() {
        if (swapBusy) return
        if (!canSwap()) { swap(); return }
        val gallery = app as GalleryApp
        if (gallery.preferences.state.value.reducedMotion) { swap(); return }
        swapBusy = true
        gallery.catalog.scope.launch(Dispatchers.Main.immediate) {
            try {
                swapFading = true
                kotlinx.coroutines.delay(200)
                if (canSwap()) swap()
                kotlinx.coroutines.delay(80)
                swapFading = false
                kotlinx.coroutines.delay(180)
            } finally { swapFading = false; swapBusy = false }
        }
    }

    fun swap() {
        if (!canSwap()) { fail(localized(R.string.text_swap_available_only_when_both_panels_are_visible_eabf0e, "Scambio disponibile soltanto con entrambi i pannelli visibili.")); return }
        val librarySlot = this.librarySlot
        val library = panels[librarySlot]?.get()?.windowModel
        val destination = panels[1 - librarySlot]?.get()?.windowModel
        if (save(state.copy(swapped = !state.swapped))) {
            (app as GalleryApp).video.transferViewer(librarySlot)
            if (library != null && destination != null) destination.takeLibraryFrom(library)
            record(localized(R.string.text_roles_swapped_no_activity_started_4edeec, "Ruoli scambiati; nessuna Activity avviata"))
            notice = localized(R.string.text_panels_swapped_selection_preserved_ac0b05, "Pannelli scambiati. La selezione è conservata.")
        }
    }

    fun expand(activity: PanelActivity, target: ScreenInfo) {
        val here = current(activity) ?: return
        val before = state
        val next = try { DisplayPolicy.expand(here.key, target.key, screens.map { it.key }.toSet(), before)
            .copy(anchorSlot = activity.slot) }
            catch (_: IllegalArgumentException) { fail(localized(R.string.text_choose_another_available_display_9374e5, "Scegli un altro display disponibile.")); return }
        if (!save(next)) return
        if (!launch(activity, target, 1 - activity.slot)) save(before)
        else notice = localized(R.string.text_request_sent_to_screen_1_s_check_the_actual_pane_24e14b, "Richiesta inviata allo schermo %1\$s. Verifica la posizione reale dei pannelli.", target.id)
    }

    fun single(activity: PanelActivity) {
        val here = current(activity) ?: return
        if (!save(DisplayPolicy.singleHere(here.key, screens.map { it.key }.toSet(), state).copy(anchorSlot = activity.slot, swapped = activity.slot == 1))) return
        rememberStartupPair(activity, false)
        settingsModel()?.takeIf { settingsOwner != activity.slot }?.let { owner ->
            val route = owner.route.value.tab
            settingsOwner = activity.slot
            settingsReturn = activity.windowModel.navigationRestore()
            activity.windowModel.go(route)
        }
        allowed.retainAll(setOf(here.key))
        panels.values.mapNotNull { it.get() }.filter { it !== activity }.forEach { it.finishAndRemoveTask() }
        activity.recreate()
        notice = localized(R.string.text_only_this_screen_others_remain_released_until_ex_55a96a, "Solo questo schermo. Gli altri restano ceduti fino a una richiesta esplicita.")
        record(localized(R.string.text_other_displays_released_screen_1_s_remains_bcd1b9, "Cessione altri display salvata; rimane schermo %1\$s", here.id))
    }

    fun cedeHere(activity: PanelActivity) {
        val here = current(activity) ?: return
        val others = panels.values.mapNotNull { it.get()?.screenKey }.toSet() - here.key
        if (others.isEmpty()) { fail(localized(R.string.text_open_the_second_panel_first_or_use_back_to_close_ceebad, "Apri prima il secondo pannello, oppure usa Indietro per chiudere.")); return }
        if (!save(DisplayPolicy.cede(here.key, others, state).copy(anchorSlot = 1 - activity.slot))) return
        allowed.remove(here.key)
        notice = localized(R.string.text_display_released_the_gallery_continues_on_the_ot_6b18c1, "Display ceduto. La galleria continua sull'altro schermo.")
        record(localized(R.string.text_screen_1_s_release_saved_closing_locally_4b6ad9, "Cessione schermo %1\$s salvata; chiusura locale", here.id))
        activity.finishAndRemoveTask()
    }

    fun afterTrashRequested(ids: Set<String>) {
        if (selectedMediaId !in ids) return
        val index = mediaContext.indexOf(selectedMediaId)
        val data = (app as GalleryApp).catalog.snapshot.value
        val next = mediaContext.take(index.coerceAtLeast(0)).lastOrNull { it !in ids && data.byId[it]?.let(data::usable) == true }
            ?: mediaContext.drop((index + 1).coerceAtLeast(0)).firstOrNull { it !in ids && data.byId[it]?.let(data::usable) == true }
        mediaContext = mediaContext.filterNot { it in ids }
        if (next == null) clearVisualSelection()
        selectMedia(next)
        panels.values.mapNotNull { it.get() }.forEach { panel ->
            if (panel.windowModel.route.value.viewer in ids) { if (next != null) panel.windowModel.open(next) else panel.windowModel.back() }
        }
    }
    fun lastCategoryMedia(category: String?): String? {
        if (category !in setOf("PHOTO", "VIDEO")) return null
        return prefs.getString("last_media:$category", null) ?: selectedVisualMediaId?.takeIf { id ->
            val item = (app as GalleryApp).catalog.snapshot.value.byId[id]
            item != null && category?.let { matchesPreviewCategory(it, item) } == true
        }
    }
    fun selectMedia(id: String?) {
        if (selectedMediaId != id) (app as GalleryApp).tv.abandonTransientActions()
        if ((app as GalleryApp).preferences.state.value.tvEnabled) app.tv.favoritePreview = false
        (app as GalleryApp).video.replacementRequest?.takeIf { it.id != id }?.let { app.video.dismissReplacement() }
        (app as GalleryApp).video.resumeRequest?.takeIf { it.id != id }?.let { app.video.dismissResume() }
        if (selectedMediaId != id) gridPreviewDirection = androidx.compose.ui.unit.IntOffset.Zero
        if (id == null) visualSelectedThisSession = false
        if ((app as GalleryApp).catalog.snapshot.value.byId[id]?.kind in setOf("IMAGE", "GIF", "VIDEO")) {
            visualSelectedThisSession = true
            selectedVisualMediaId = id
            val kind = (app as GalleryApp).catalog.snapshot.value.byId[id]?.kind
            val tab = libraryModel()?.route?.value?.tab
            val category = if (kind == "VIDEO") "VIDEO" else "PHOTO"
            prefs.edit().putString("selected_visual_media", id).putString("last_media:$category", id).apply()
        }
        // Selection is shared even when the preview was temporarily showing settings, or the ID is unchanged.
        if (id != null && settingsOwner == null && canSwap() && (app as GalleryApp).catalog.snapshot.value.byId[id]?.kind != "AUDIO") {
            val previewSlot = if (state.swapped) 0 else 1
            panels[previewSlot]?.get()?.windowModel?.restorePanelRole()
            panels[1 - previewSlot]?.get()?.windowModel?.takeIf { it.route.value.viewer != null }?.restorePanelRole()
        }
        if (selectedMediaId == id) return
        selectedMediaId = id
        prefs.edit().putString("selected_media", id).apply()
        val store = (app as GalleryApp).comparison
        store.update(store.state.value.copy(panes = store.state.value.panes.map {
            if (it.pinned) it else it.copy(view = ViewTransform())
        }))
    }

    fun singleFromLauncher(key: String) {
        allowed.retainAll(setOf(key))
        panels.values.mapNotNull { it.get() }.filter { it.slot != 0 || it.screenKey != key }
            .forEach { it.finishAndRemoveTask() }
    }

    fun record(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        events = (events + "$stamp  $message").takeLast(48)
        android.util.Log.i("AynGallery", message)
    }

    private var errorToast: android.widget.Toast? = null
    internal fun toastContext(): Context = panels.values.mapNotNull { it.get() }
        .firstOrNull { it.slot in started && !it.isFinishing && current(it)?.id == android.view.Display.DEFAULT_DISPLAY }
        ?: manager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.let(app::createDisplayContext) ?: app
    fun fail(message: String): Boolean {
        (app as? GalleryApp)?.sounds?.play("notice")
        error = message; notice = message; record(message)
        errorToast?.cancel()
        errorToast = android.widget.Toast.makeText(toastContext(), message, android.widget.Toast.LENGTH_LONG).also { it.show() }
        backgroundHandler.postDelayed({ if (error == message) clearError() }, 3500)
        return false
    }
    fun noticeSource(message: String) { (app as? GalleryApp)?.sounds?.play("notice"); notice = message; record(message) }

    fun report(activity: PanelActivity): String = buildString {
        appendLine(localized(R.string.text_thor_gallery_beta_development_display_report_dd517c, "DS Gallery · sviluppo beta · rapporto display"))
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        appendLine("Build: ${version.versionName} (${version.longVersionCode})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        appendLine(localized(R.string.text_window_p_1_s_display_2_s_focus_3_s_53bd0a, "Finestra: P%1\$s · display %2\$s · focus %3\$s", activity.slot, current(activity)?.id, activity.hasWindowFocus()))
        appendLine("Viewport: ${activity.resources.configuration.screenWidthDp} × ${activity.resources.configuration.screenHeightDp} dp")
        appendLine(localized(R.string.text_anchor_1_s_released_2_s_roles_swapped_3_s_526aa9, "Ancora: %1\$s; ceduti: %2\$s; ruoli scambiati: %3\$s", state.anchor, state.ceded, state.swapped))
        appendLine(localized(R.string.text_startup_automatic_1_s_saved_pair_2_s_valid_state_1cc6de, "Avvio: automatico=%1\$s; coppia salvata=%2\$s; stato valido=%3\$s", startupAutomatic, startupPair, persistedStateValid))
        appendLine(localized(R.string.text_visible_panels_1_s_f349c1, "Pannelli visibili: %1\$s", visiblePanels.value))
        screens.forEach { appendLine("${label(it)}: id=${it.id}, ${it.name}, ${it.width}×${it.height}, flags=${it.flags}") }
        appendLine(localized(R.string.text_catalog_and_permissions_049549, "--- Catalogo e permessi ---"))
        DeviceGallery.permissions().forEach { permission ->
            appendLine("${permission.substringAfterLast('.')}: ${if (app.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) localized(R.string.text_allowed_9b88cf, "consentito") else localized(R.string.text_not_allowed_0062d3, "non consentito")}")
        }
        val catalog = (app as GalleryApp).catalog
        val data = catalog.snapshot.value
        val device = data.sources.firstOrNull { it.id == DeviceGallery.SOURCE }
        appendLine(localized(R.string.text_android_gallery_1_s_fcad13, "Galleria Android: %1\$s", device?.let { localized(R.string.text_included_1_s_reachable_2_s_last_scan_3_s_error_4_ead44c, "inclusa=%1\$s; raggiungibile=%2\$s; ultima scansione=%3\$s; errore=%4\$s", it.enabled, it.reachable, it.lastScan, it.error ?: localized(R.string.text_none_b8ce01, "nessuno")) } ?: localized(R.string.text_not_yet_in_the_catalog_986ad9, "non ancora presente nel catalogo")))
        appendLine(localized(R.string.text_chosen_sources_1_s_indexed_media_2_s_albums_3_s_83cb72, "Sorgenti scelte: %1\$s; media indicizzati: %2\$s; album: %3\$s", data.sources.count { it.id != DeviceGallery.SOURCE }, data.media.size, data.albums.size))
        val scan = catalog.progress.value
        appendLine(localized(R.string.text_scan_running_1_s_items_2_s_error_3_s_950caf, "Scansione: in corso=%1\$s; elementi=%2\$s; errore=%3\$s", scan.running, scan.count, scan.error ?: localized(R.string.text_none_b8ce01, "nessuno")))
        appendLine(localized(R.string.text_recent_events_2537da, "--- Eventi recenti ---"))
        events.forEach(::appendLine)
        appendLine(localized(R.string.text_t01t12_t83_require_thor_verification_this_report_85f844, "T01–T12/T83: da verificare sul Thor. Questo rapporto non attesta il collaudo hardware."))
    }

    override fun onDisplayAdded(displayId: Int) { refreshDisplays(); record(localized(R.string.text_display_added_1_s_no_window_opened_addc3a, "Display aggiunto %1\$s; nessuna apertura", displayId)) }
    override fun onDisplayChanged(displayId: Int) {
        refreshDisplays()
        panels.values.mapNotNull { it.get() }.filter { p -> current(p)?.key != p.screenKey }
            .forEach { fail(localized(R.string.text_display_identity_changed_reopen_on_one_screen_an_347828, "Identità del display cambiata. Riapri in singolo e verifica l'assegnazione.")); it.finishAndRemoveTask() }
        record(localized(R.string.text_display_configuration_changed_1_s_a9092c, "Configurazione display cambiata %1\$s", displayId))
    }
    override fun onDisplayRemoved(displayId: Int) {
        refreshDisplays()
        panels.values.mapNotNull { it.get() }.filter { p -> screens.none { it.key == p.screenKey } }
            .forEach { it.finishAndRemoveTask() }
        notice = localized(R.string.text_display_1_s_disconnected_no_panel_moved_automati_c72848, "Display %1\$s scollegato. Nessun pannello spostato automaticamente.", displayId)
        record(notice)
    }
}
