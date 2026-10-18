package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun FrameSetupScreen(activity: PanelActivity, vm: GalleryWindowModel, data: CatalogSnapshot) {
    val app = activity.application as GalleryApp
    val prefs by app.preferences.state.collectAsState()
    val config by vm.frameConfig.collectAsState()
    val playing by vm.framePlaying.collectAsState()
    val configuring by vm.frameConfiguring.collectAsState()
    var stage by rememberSaveable { mutableIntStateOf(if (configuring) 0 else 1) }
    val saver = remember { Saver<FrameConfig, String>({ it.encode(false) }, { FrameConfig.decode(it, false) }) }
    var draft by rememberSaveable(stateSaver = saver) {
        mutableStateOf(config.copy(scope = if (config.scope == "FAVORITE") "FAVORITE" else "SELECTED", collections = emptySet()))
    }
    val eligible = remember(draft.scope, draft.albums, data) { draft.media(data) }
    val albums = remember(data) {
        val direct = data.media.filter { it.kind in setOf("IMAGE", "GIF") && data.usable(it) }.map { it.albumId }.toSet()
        val populated = direct + data.ancestry.filter { it.albumId in direct }.map { it.ancestorId }
        data.visibleAlbums().filter { it.id in populated && !data.albumLocked(it.id) }.sortedWith(compareBy(MediaNames.natural) { it.name })
    }
    DisposableEffect(activity, vm, data, stage, eligible, draft, playing) {
        val handler: (PadAction) -> Boolean = { action -> when (action) {
            PadAction.CONFIRM -> {
                if (eligible.isNotEmpty()) {
                    if (stage == 0) stage = 1 else {
                        vm.configureFrame(draft.copy(animatedGif = true, immersive = false))
                        vm.frameConfiguring(false)
                        vm.framePlaying(!playing)
                    }
                }
                true
            }
            PadAction.MEDIA_PREVIOUS, PadAction.MEDIA_NEXT -> {
                vm.frameCursor(vm.frameCursor.value.move(if (action == PadAction.MEDIA_PREVIOUS) -1 else 1)); true
            }
            else -> false
        } }
        activity.frameAction = handler
        onDispose { if (activity.frameAction === handler) activity.frameAction = null }
    }
    Column(Modifier.fillMaxSize().padding(20.dp).testTag("frame-setup"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GalleryIcon(R.drawable.ph_images, null, Modifier.size(28.dp))
            Text(localized(R.string.text_photo_frame_b05b1e, "Cornice"), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
            Text("${stage + 1} / 2", style = MaterialTheme.typography.labelLarge)

        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("frame-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stage == 0) {
                item { Text(localized(R.string.frame_source_guidance, "Scegli un album o i preferiti. La presentazione apparirà sullo schermo della preview.")) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(draft.scope == "SELECTED", { draft = draft.copy(scope = "SELECTED"); vm.framePlaying(false) }, { Text(localized(R.string.nav_albums, "Album")) }, Modifier.testTag("frame-scope:SELECTED"))
                    FilterChip(draft.scope == "FAVORITE", { draft = draft.copy(scope = "FAVORITE"); vm.framePlaying(false) }, { Text(localized(R.string.text_favorites_4c7a1b, "Preferite")) }, Modifier.testTag("frame-scope:FAVORITE"))
                } }
                if (draft.scope == "SELECTED") items(albums, key = { it.id }) { album ->
                    OutlinedCard(onClick = { draft = draft.copy(albums = setOf(album.id)); vm.framePlaying(false) }, modifier = Modifier.fillMaxWidth().testTag("frame-album:${album.id}")) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(album.id in draft.albums, onClick = null)
                            AlbumCover(album, data, app.catalog, prefs, Modifier.size(56.dp))
                            Text(album.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (eligible.isEmpty()) item { Text(localized(R.string.text_no_image_available_470187, "Nessuna immagine disponibile"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { Text(if (draft.scope == "FAVORITE") localized(R.string.text_favorites_4c7a1b, "Preferite") else draft.albums.mapNotNull(data.albumsById::get).joinToString { it.name }, style = MaterialTheme.typography.titleMedium) }
                item {
                    Text(localized(R.string.text_change_image_every_75bf32, "Cambia immagine ogni"))
                    Text("${draft.seconds} s", style = MaterialTheme.typography.titleMedium)
                    GallerySlider(draft.seconds.toFloat(), { draft = draft.copy(seconds = it.toInt().coerceIn(2, 120)) }, enabled = !playing, valueRange = 2f..120f, steps = 117, modifier = Modifier.testTag("frame-seconds"))
                }
                item {
                    var transitionMenu by rememberSaveable { mutableStateOf(false) }
                    val effects = listOf("NONE" to R.string.widget_effect_none, "FADE" to R.string.widget_photo_fade,
                        "LEFT" to R.string.photo_slide_left, "RIGHT" to R.string.photo_slide_right,
                        "UP" to R.string.photo_slide_up, "DOWN" to R.string.photo_slide_down, "ROTATE" to R.string.photo_rotate)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(localized(R.string.frame_transition_title, "Effetto di transizione"), Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { transitionMenu = true }, enabled = !playing, modifier = Modifier.testTag("frame-transition-menu")) {
                                Text(androidx.compose.ui.res.stringResource(effects.first { it.first == draft.transition }.second))
                            }
                            DropdownMenu(expanded = transitionMenu, onDismissRequest = { transitionMenu = false }) {
                                effects.forEach { (effect, label) ->
                                    DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(label)) }, modifier = Modifier.testTag("frame-effect:$effect"),
                                        onClick = { draft = draft.copy(transition = effect, fade = effect != "NONE"); vm.configureFrame(draft); transitionMenu = false })
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stage == 1) GalleryButton(localized(R.string.tutorial_previous, "Precedente"), Modifier.weight(1f).testTag("frame-back")) { vm.framePlaying(false); stage = 0 }
            if (stage == 0) GalleryButton(localized(R.string.tutorial_next, "Prossimo"), Modifier.weight(1f).testTag("frame-next"), enabled = eligible.isNotEmpty(), primary = true) { stage = 1 }
            else GalleryButton(if (playing) localized(R.string.text_pause_photo_frame_ead8b9, "Pausa cornice") else localized(R.string.text_start_photo_frame_b1bade, "Avvia cornice"),
                Modifier.weight(1f).testTag("frame-save"), enabled = eligible.isNotEmpty(), primary = true, icon = if (playing) R.drawable.ph_pause else R.drawable.ph_play) {
                vm.configureFrame(draft.copy(animatedGif = true, immersive = false))
                vm.frameConfiguring(false)
                vm.framePlaying(!playing)
            }

        }
    }
}
