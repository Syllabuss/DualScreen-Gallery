package it.ayngallery

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

private val PhotoWidgetDraftSaver = Saver<PhotoWidgetConfig, String>(
    save = { it.encode(requireSelection = false) }, restore = { PhotoWidgetConfig.decode(it, requireSelection = false) })

/** A host-owned configuration window; saving never opens a gallery panel. */
class PhotoWidgetConfigActivity : ComponentActivity() {
    internal var cropMove: ((Float, Float, Float) -> Unit)? = null
    private var lastCropMotion = 0L
    // Same public Android override as PanelActivity; androidx annotates its implementation as restricted.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = if (handleCropKey(event)) true else super.dispatchKeyEvent(event)
    internal fun handleCropKey(event: android.view.KeyEvent): Boolean {
        val move = cropMove
        if (move != null && event.action == android.view.KeyEvent.ACTION_DOWN) when (padButton(event.keyCode)) {
            PadButton.LEFT -> { move(-.06f, 0f, 0f); return true }; PadButton.RIGHT -> { move(.06f, 0f, 0f); return true }
            PadButton.UP -> { move(0f, -.06f, 0f); return true }; PadButton.DOWN -> { move(0f, .06f, 0f); return true }
            else -> Unit
        }
        return false
    }
    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean = if (handleCropMotion(event)) true else super.onGenericMotionEvent(event)
    internal fun handleCropMotion(event: android.view.MotionEvent): Boolean {
        val move = cropMove
        if (move != null && event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCropMotion >= 32L) {
                lastCropMotion = now
                val axes = padAxes(event)
                val x = listOf(axes[PadButton.RIGHT] ?: 0f, axes[PadButton.RS_RIGHT] ?: 0f).maxBy { kotlin.math.abs(it) }
                val y = listOf(axes[PadButton.DOWN] ?: 0f, axes[PadButton.RS_DOWN] ?: 0f).maxBy { kotlin.math.abs(it) }
                if (kotlin.math.abs(x) > .2f || kotlin.math.abs(y) > .2f) move(x * .045f, y * .045f, 0f)
            }
            return true
        }
        return false
    }
    override fun onStart() {
        super.onStart()
        (application as GalleryApp).albums.show(-1, GalleryRoute(tab = "ALBUM"))
    }
    override fun onStop() {
        (application as GalleryApp).albums.leave(-1)
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val app = application as GalleryApp
        app.sounds.play("settings")
        applyGalleryWindowTheme(this, app.preferences.state.value)
        lifecycleScope.launch { app.preferences.state.collect { applyGalleryWindowTheme(this@PhotoWidgetConfigActivity, it) } }
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (!app.photoWidgets.owns(id)) { finish(); return }
        app.session.refreshDisplays()
        setContent {
            val prefs by app.preferences.state.collectAsStateWithLifecycle()
            val data by app.catalog.snapshot.collectAsStateWithLifecycle()
            val initialDraft = remember { app.photoWidgets.settings.get(id).let { saved ->
                val initial = if (saved.fixedMedia == null && (saved.selection.scope != "SELECTED" || saved.selection.collections.isNotEmpty()))
                    saved.copy(selection = saved.selection.copy(scope = "SELECTED", albums = emptySet(), collections = emptySet())) else saved
                initial.copy(seconds = initial.seconds ?: 30)
            } }
            var draft by rememberSaveable(stateSaver = PhotoWidgetDraftSaver) { mutableStateOf(initialDraft) }
            var mode by rememberSaveable { mutableStateOf(if (draft.fixedMedia != null) "PHOTO" else "ALBUM") }
            val fixed = mode == "PHOTO"
            var crop by remember { mutableStateOf(false) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var entered by remember { mutableStateOf(false) }
            val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(if (prefs.reducedMotion) 0 else 320), label = "apertura-config-widget")
            LaunchedEffect(Unit) { entered = true }
            val visuals = remember(data) { FrameConfig().media(data) }
            val photoAlbums = remember(data, visuals) { val ids = visuals.map { it.albumId }.toSet(); data.visibleAlbums().filter { it.id in ids } }
            val valid = data.initialized && draft.media(data).isNotEmpty() && (!fixed || draft.fixedMedia != null) && runCatching { draft.validate() }.isSuccess
            fun select(selection: FrameConfig) { draft = draft.copy(selection = selection) }
            fun save() {
                saving = true
                val config = draft.copy(fixedMedia = if (fixed) draft.fixedMedia else null, seconds = if (fixed) null else draft.seconds,
                    zoom = if (fixed) draft.zoom else 1f, panX = if (fixed) draft.panX else 0f, panY = if (fixed) draft.panY else 0f)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { app.photoWidgets.configure(id, config) }
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = localized(R.string.text_configuration_incomplete_try_again_0d7d29, "Configurazione non completata. Riprova."); saving = false }
                }
            }
            GalleryDesign(prefs) {
                val exit = WidgetExitConfirmation(draft != initialDraft || mode != (if (initialDraft.fixedMedia != null) "PHOTO" else "ALBUM"), saving, { finish() })
                Surface(Modifier.fillMaxSize().graphicsLayer { this.alpha = if (prefs.reducedMotion) 1f else alpha }, color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GalleryIconButton(R.drawable.ph_arrow_left, localized(R.string.text_cancel_18c9d9, "Annulla"), enabled = !saving) { exit() }
                            Text(localized(R.string.text_photo_widget_911f94, "Widget fotografico"), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.headlineSmall)
                            GalleryIconButton(R.drawable.ph_floppy_disk, localized(R.string.text_save_c5997e, "Salva"), Modifier.testTag("photo-config-save"), enabled = valid && !saving) { save() }
                        }

                        if (crop) PhotoWidgetCropDialog(this@PhotoWidgetConfigActivity, app, data, prefs, draft, { draft = it }, { crop = false })
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("photo-config-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("ALBUM" to "Album", "PHOTO" to localized(R.string.text_single_photo_5ed01a, "Foto singola")).forEach { (value, label) ->
                                        FilterChip(mode == value, onClick = {
                                            mode = value
                                            draft = draft.copy(fixedMedia = null, zoom = 1f, panX = 0f, panY = 0f, minutes = draft.minutes.takeIf { it > 0 } ?: 30, selection = FrameConfig(scope = if (value == "PHOTO") "ALL" else "SELECTED", fill = draft.selection.fill))
                                        }, label = { Text(label) }, modifier = Modifier.testTag("photo-config-mode:$value"))
                                    }
                                }
                            }
                            if (valid && fixed) item { GalleryButton(localized(R.string.widget_crop, "Inquadra la foto"), Modifier.fillMaxWidth().testTag("photo-config-crop")) { crop = true } }
                            if (fixed) {
                                if (visuals.isEmpty()) item { Text(localized(R.string.text_choose_a_photo_folder_in_the_gallery_first_bcb193, "Scegli prima una cartella di foto dalla galleria.")) }
                                item {
                                    LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.fillMaxWidth().height(360.dp).testTag("photo-config-grid"),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(visuals, key = { it.id }) { item ->
                                            val selected = draft.fixedMedia == item.id
                                            Box(Modifier.aspectRatio(1f).border(if (selected) 3.dp else 1.dp,
                                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                                .selectable(selected, role = Role.RadioButton) { draft = draft.copy(fixedMedia = item.id, zoom = 1f, panX = 0f, panY = 0f) }
                                                .testTag("photo-config-media:${item.id}")) {
                                                MediaThumbnail(item, data, app.catalog, prefs, Modifier.fillMaxSize().padding(3.dp))
                                                if (selected) RadioButton(true, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp))
                                            }
                                        }
                                    }
                                }
                            } else {
                                if (mode == "ALBUM") {
                                    val albums = photoAlbums
                                    if (albums.isEmpty()) item { Text(localized(R.string.text_no_photo_albums_available_7e331a, "Nessun album di foto disponibile.")) }
                                    items(albums.sortedWith(compareBy(MediaNames.natural) { it.path }), key = { "album:${it.id}" }) { album ->
                                        PhotoConfigChoice(album.path, album.id in draft.selection.albums, "photo-config-album:${album.id}") {
                                            select(draft.selection.copy(scope = "SELECTED", albums = setOf(album.id), collections = emptySet()))
                                        }
                                    }
                                }
                                if (valid) {
                                    item {
                                        Text(localized(R.string.photo_widget_order, "Ordine delle foto"), style = MaterialTheme.typography.titleMedium)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("NEWEST" to R.string.photo_widget_newest, "OLDEST" to R.string.photo_widget_oldest, "SHUFFLE" to R.string.photo_widget_random).forEach { (value, label) ->
                                                FilterChip(draft.order == value, { draft = draft.copy(order = value, selection = draft.selection.copy(shuffled = value == "SHUFFLE")) }, label = { Text(androidx.compose.ui.res.stringResource(label)) })
                                            }
                                        }
                                    }
                                    item {
                                        Text(localized(R.string.text_change_photo_f1bc64, "Cambia foto"), style = MaterialTheme.typography.titleMedium)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(5, 10, 30, 60, 300).forEach { seconds ->
                                                FilterChip(draft.seconds == seconds, { draft = draft.copy(seconds = seconds) },
                                                    label = { Text(if (seconds < 60) "$seconds s" else "${seconds / 60} min") }, modifier = Modifier.testTag("photo-config-interval:$seconds"))
                                            }
                                        }
                                    }
                                    item { PhotoConfigToggle(localized(R.string.widget_photo_fade, "Dissolvenza"), draft.fade, "photo-config-fade") { draft = draft.copy(fade = it, transition = "NONE") } }
                                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("NONE" to R.string.widget_effect_none, "LEFT" to R.string.photo_slide_left, "RIGHT" to R.string.photo_slide_right,
                                            "UP" to R.string.photo_slide_up, "DOWN" to R.string.photo_slide_down, "ROTATE" to R.string.photo_rotate).forEach { (effect, label) ->
                                            FilterChip(draft.transition == effect && !draft.fade, { draft = draft.copy(transition = effect, fade = false) }, label = { Text(androidx.compose.ui.res.stringResource(label)) })
                                        }
                                    } }
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoConfigChoice(label: String, selected: Boolean, tag: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).selectable(selected, role = Role.RadioButton, onClick = click), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null); Text(label, Modifier.weight(1f))
    }
}
@Composable
private fun PhotoConfigToggle(label: String, selected: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).toggleable(selected, role = Role.Checkbox, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, null); Text(label, Modifier.weight(1f))
    }
}

@Composable
internal fun PhotoWidgetAssignmentDialog(app: GalleryApp, item: MediaEntity, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val widgets = remember { app.photoWidgets.ids() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val height = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
    }
    GalleryAlertDialog(onDismissRequest = { if (!saving) dismiss() }, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(.94f).height(height * .9f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { Text(localized(R.string.text_use_in_a_photo_frame_4b03fb, "Usa in una cornice")) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = height * .58f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            Text(if (widgets.isEmpty()) localized(R.string.text_add_thor_gallery_photo_frame_from_your_launcher_628bfc, "Aggiungi «DS Gallery · Cornice fotografica» dai widget del launcher, poi torna qui.")
                else localized(R.string.text_replaces_the_widget_selection_with_this_fixed_im_ecfdfb, "Sostituisce la scelta del widget con questa immagine fissa. Il file originale rimane intatto."), style = MaterialTheme.typography.bodySmall)
            widgets.forEachIndexed { index, id -> GalleryButton(localized(R.string.text_photo_frame_1_s_9917f0, "Cornice %1\$s", index + 1), Modifier.fillMaxWidth(), enabled = !saving) {
                saving = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { app.photoWidgets.assign(id, item) }; dismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: localized(R.string.text_widget_not_updated_try_again_d5d426, "Widget non aggiornato. Riprova."); saving = false }
                }
            } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(dismiss, enabled = !saving) { Text(localized(R.string.text_close_32d407, "Chiudi")) } })
}
