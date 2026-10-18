package it.ayngallery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PhotoFrameScreen(activity: PanelActivity, data: CatalogSnapshot, prefs: GalleryPreferences, blocked: Boolean,
    actions: (MediaEntity) -> Unit, compare: (MediaEntity) -> Unit, remove: (MediaEntity) -> Unit, task: (suspend () -> Unit) -> Unit, model: GalleryWindowModel = activity.windowModel, previewOnly: Boolean = false, controlsOnly: Boolean = false) {
    val app = activity.application as GalleryApp
    val vm = model
    val config by vm.frameConfig.collectAsState()
    val playing by vm.framePlaying.collectAsState()
    val route by vm.route.collectAsState()
    val savedCursor by vm.frameCursor.collectAsState()
    val visible by activity.isStartedFlow.collectAsState()
    val configuring by vm.frameConfiguring.collectAsState()
    if (controlsOnly || (configuring && !previewOnly)) {
        SettingsControllerContent(activity, "SETTINGS_FRAME", "FRAME") { FrameSetupScreen(activity, vm, data) }
        return
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(emptyMap<String, Pair<Long, Long>>()) }
    val selected = remember(config, data) { if (config.scope == "ALL" && previewOnly) emptyList() else config.media(data) }
    val media = selected.filter { failed[it.id] != (it.modified to it.size) }
    val cursor = remember(savedCursor, media.map { it.id }, config.shuffled) { savedCursor.reconcile(media.map { it.id }, config.shuffled) }
    LaunchedEffect(cursor) { if (cursor != vm.frameCursor.value) vm.frameCursor(cursor) }
    val current = media.firstOrNull { it.id == cursor.current }
    var decoded by remember(current?.id, current?.modified, current?.size) { mutableStateOf<Boolean?>(null) }
    val manager = remember(activity) { activity.getSystemService(PowerManager::class.java) }
    var powerSave by remember { mutableStateOf(manager.isPowerSaveMode) }
    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { powerSave = manager.isPowerSaveMode }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { activity.unregisterReceiver(receiver) }
    }
    val active = visible && route.tab == "FRAME" && route.viewer == null
    val running = active && playing && !settings && !blocked && !powerSave && !prefs.gameMode
    val liveCursor by rememberUpdatedState(cursor)
    val liveCurrent by rememberUpdatedState(current)
    fun move(delta: Int) { vm.frameCursor(liveCursor.move(delta)) }
    fun open() {
        liveCurrent?.let { item ->
            vm.framePlaying(false)
            app.session.updateMediaContext(media.map { it.id }); app.session.selectMedia(item.id)
            vm.open(item.id)
        }
    }
    DisposableEffect(activity, current?.id, data, config) {
        val handler: (PadAction) -> Boolean = { action ->
            when (action) {
                PadAction.MEDIA_PREVIOUS -> { move(-1); true }
                PadAction.MEDIA_NEXT -> { move(1); true }
                PadAction.FAVORITE -> { liveCurrent?.let { item -> task { app.catalog.favorite(item.id) } }; true }
                PadAction.MENU -> { liveCurrent?.let(actions); true }
                PadAction.PIN -> true // Retained profile entry; comparison is dormant.
                PadAction.DELETE -> { liveCurrent?.let(remove); true }
                PadAction.ZOOM_IN, PadAction.ZOOM_OUT, PadAction.PAN_UP, PadAction.PAN_DOWN, PadAction.PAN_LEFT, PadAction.PAN_RIGHT -> true
                else -> false // Music actions continue to control the common player.
            }
        }
        activity.frameAction = handler
        onDispose { if (activity.frameAction === handler) activity.frameAction = null }
    }
    DisposableEffect(activity, active, settings, blocked, config.keepAwake, powerSave, prefs.gameMode) {
        val view = activity.window.decorView
        val old = view.keepScreenOn
        view.keepScreenOn = active && !settings && !blocked && config.keepAwake && !powerSave && !prefs.gameMode
        onDispose { view.keepScreenOn = old }
    }
    LaunchedEffect(running, decoded, cursor.current, config.seconds, media.size) {
        if (running && decoded == true && media.size > 1) {
            delay(config.seconds * 1000L)
            move(1)
        }
    }
    val next = cursor.nextId()?.let { id -> media.firstOrNull { it.id == id } }
    val window = LocalWindowInfo.current.containerSize
    LaunchedEffect(next?.id, next?.modified, next?.size, running, decoded, window) {
        if (running && decoded == true && next != null && window.width > 0 && window.height > 0) withContext(Dispatchers.IO) {
            try {
                val fresh = app.catalog.readMediaSnapshot(next.id)
                val target = fresh.byId[next.id]?.takeIf { fresh.usable(it) && !DocumentVersions.changed(next.size, next.modified, it.size, it.modified) } ?: return@withContext
                val request = ImageRequest.Builder(app).data(app.catalog.uri(target, fresh))
                    .size(window.width.coerceAtMost(2048), window.height.coerceAtMost(2048))
                    .memoryCacheKey("${target.id}/${target.modified}/${target.size}/false")
                    .apply { if (target.kind == "GIF") decoderFactory(BitmapFactoryDecoder.Factory()) }.build()
                SingletonImageLoader.get(app).execute(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Current-image decoding reports an actionable error; prefetch never blocks navigation. */ }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("photo-frame")) {
        Box(Modifier.weight(1f).fillMaxWidth().testTag("frame-picture").semantics {
            stateDescription = when { current == null -> localized(R.string.text_no_image_0fca79, "Nessuna immagine"); decoded == true -> localized(R.string.text_image_ready_c2b75d, "Immagine pronta"); else -> localized(R.string.text_loading_image_b4f633, "Caricamento immagine") }
        }, contentAlignment = Alignment.Center) {
            if (current == null) EmptyGallery(if (selected.isNotEmpty()) localized(R.string.text_no_readable_preview_58c498, "Nessuna anteprima leggibile") else localized(R.string.text_choose_photo_frame_images_3d6082, "Scegli le immagini della cornice"),
                if (selected.isNotEmpty()) localized(R.string.text_the_selected_images_cannot_be_opened_try_again_o_edf6e5, "Le immagini selezionate non si aprono. Puoi riprovare o scegliere altri contenuti.")
                else localized(R.string.text_available_images_and_gifs_only_hidden_albums_rev_d46c35, "Solo immagini e GIF disponibili. Album nascosti, sorgenti revocate e cestino restano esclusi."),
                action = if (selected.isNotEmpty()) localized(R.string.text_retry_20b5f4, "Riprova") else localized(R.string.text_configure_photo_frame_bbd19b, "Configura cornice")) { if (selected.isNotEmpty()) failed = emptyMap() else settings = true }
            else FrameTransition(current.id, config.transition.takeIf { config.fade && !prefs.reducedMotion } ?: "NONE") { id ->
                val item = media.firstOrNull { it.id == id }
                if (item != null) MediaThumbnail(item, data, app.catalog, prefs, Modifier.fillMaxSize().clickable { if (previewOnly) vm.framePlaying(!playing) else open() }.testTag("frame-media:$id"),
                    animated = config.animatedGif, active = active,
                    animationPaused = !running || item.id != current.id, scale = if (config.fill) ContentScale.Crop else ContentScale.Fit, crossfadeMillis = 0, backgroundColor = MaterialTheme.colorScheme.background,
                    decoded = { state -> if (item.id == liveCurrent?.id) {
                        decoded = state
                        if (state == false) failed = failed + (item.id to (item.modified to item.size))
                    } })
            }


        }
        if (!previewOnly) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: localized(R.string.text_no_image_available_470187, "Nessuna immagine disponibile"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("frame-name"))
                    Text(when { !active -> localized(R.string.text_panel_not_visible_4f52a6, "Pannello non visibile"); powerSave -> localized(R.string.text_paused_power_saving_44a049, "In pausa · risparmio energetico")
                        prefs.gameMode -> localized(R.string.text_paused_game_mode_853b95, "In pausa · modalità Gioco"); settings || blocked -> localized(R.string.text_paused_menu_open_fc2df2, "In pausa · menu aperto")
                        !playing -> localized(R.string.text_paused_2f4e01, "In pausa"); current == null -> localized(R.string.text_no_image_available_470187, "Nessuna immagine disponibile"); media.size == 1 -> localized(R.string.text_one_image_only_7f300d, "Una sola immagine")
                        decoded != true -> localized(R.string.text_loading_fda05a, "Caricamento…"); else -> "${config.seconds} s · ${if (config.shuffled) localized(R.string.text_shuffle_0eb69b, "Casuale") else localized(R.string.text_sequential_937369, "Sequenziale")} · ${cursor.ids.indexOf(cursor.current) + 1}/${media.size}" },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("frame-status"))
                }
                GalleryIconButton(R.drawable.ph_gear_six, localized(R.string.text_configure_photo_frame_bbd19b, "Configura cornice")) { settings = true }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GalleryIconButton(R.drawable.ph_arrow_left, localized(R.string.text_previous_photo_a41ac2, "Foto precedente"), enabled = media.size > 1) { move(-1) }
                GalleryIconButton(if (playing) R.drawable.ph_pause else R.drawable.ph_play, if (playing) localized(R.string.text_pause_photo_frame_ead8b9, "Pausa cornice") else localized(R.string.text_start_photo_frame_b1bade, "Avvia cornice")) { vm.framePlaying(!playing) }
                GalleryIconButton(R.drawable.ph_arrow_right, localized(R.string.text_next_photo_17e053, "Foto successiva"), enabled = media.size > 1) { move(1) }
                GalleryButton(localized(R.string.text_open_12abcf, "Apri"), enabled = current != null, modifier = Modifier.testTag("frame-open")) { open() }
            }
        }
    }
    if (settings) { vm.framePlaying(false); vm.frameConfiguring(true); settings = false }
}
