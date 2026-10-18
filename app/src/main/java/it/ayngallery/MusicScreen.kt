package it.ayngallery

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun rememberedMusicId(app: GalleryApp): String? = runCatching {
    val saved = app.getSharedPreferences("music", android.content.Context.MODE_PRIVATE)
    org.json.JSONArray(saved.getString("queue", "[]")).optString(saved.getInt("index", 0)).takeIf { it.isNotBlank() }
}.getOrNull()

@Composable
fun MiniPlayer(app: GalleryApp, state: PlaybackState, data: CatalogSnapshot, prefs: GalleryPreferences, open: () -> Unit) {
    val item = data.byId[state.id]?.takeIf(data::usable)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(item, data, app.catalog, prefs, Modifier.size(52.dp).clip(GalleryShape))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(if (item != null) state.title else localized(R.string.text_track_unavailable_b73d64, "Brano non disponibile"), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(state.artist, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GalleryIconButton(if (state.playing) R.drawable.ph_pause else R.drawable.ph_play, localized(R.string.text_music_play_pause_b180bc, "Play / pausa musica")) { app.music.toggle() }
            GalleryIconButton(R.drawable.ph_skip_forward, localized(R.string.text_next_track_b0ff2f, "Brano successivo"), enabled = state.hasNext ?: (state.index < state.queue.lastIndex)) { app.music.next() }
        }
    }
}

@Composable
fun PreviewMusicPanel(app: GalleryApp, state: PlaybackState, data: CatalogSnapshot, prefs: GalleryPreferences, fullPlayer: Boolean, controlsVisible: Boolean = true, bottomReserve: androidx.compose.ui.unit.Dp = 0.dp, onActions: ((MediaEntity) -> Unit)? = null, onChooseFolder: () -> Unit = {}) {
    val selectedTrack = data.byId[app.session.selectedMediaId]?.takeIf { fullPlayer && it.kind == "AUDIO" && data.usable(it) }
    val track = selectedTrack ?: data.byId[state.id]?.takeIf { it.kind == "AUDIO" && data.usable(it) } ?: if (fullPlayer)
        rememberedMusicId(app)?.let(data.byId::get)?.takeIf { it.kind == "AUDIO" && data.usable(it) }
            ?: data.visibleMedia.filter { it.kind == "AUDIO" && data.usable(it) }.minWithOrNull(compareBy(MediaNames.natural) { it.title ?: it.name })
        else null
    val displayState = if (track == null || track.id == state.id) state else state.copy(id = track.id, title = track.title ?: track.name,
        artist = track.artist.orEmpty(), playing = false, position = 0, duration = track.duration ?: 0, hasNext = false, hasPrevious = false)
    LaunchedEffect(fullPlayer, track?.id, state.id) {
        if (fullPlayer && state.id == null && track != null) app.music.play(track.id,
            data.visibleMedia.filter { it.kind == "AUDIO" && data.usable(it) }.sortedWith(compareBy(MediaNames.natural) { it.title ?: it.name }).map { it.id }, autoPlay = false)
    }
    BoxWithConstraints(Modifier.fillMaxSize().then(if (fullPlayer) Modifier.background(MaterialTheme.colorScheme.background) else Modifier.safeDrawingPadding()).testTag("preview-music-panel")) {
        val compact = !fullPlayer
        if (compact) {
            PreviewDock("preview-music", R.drawable.ph_music_notes, state.id != null && controlsVisible, bottomReserve, parked = !state.playing, onClose = if (!state.playing) { { app.session.hidePausedMini = true } } else null) { dragModifier ->
                Surface(Modifier.widthIn(max = (maxWidth - 28.dp).coerceAtMost(370.dp).coerceAtLeast(0.dp))
                    .testTag("preview-mini-player").then(dragModifier),
                    shape = GalleryShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 8.dp) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MediaThumbnail(track, data, app.catalog, prefs, Modifier.size(50.dp).clip(GalleryShape))
                        Column(Modifier.weight(1f)) {
                            Text(track?.title ?: state.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            Text(track?.artist ?: state.artist, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GalleryIconButton(R.drawable.ph_skip_back, localized(R.string.text_previous_track_b3f5fe, "Brano precedente"), enabled = state.hasPrevious ?: (state.index > 0)) { app.music.previous() }
                        GalleryIconButton(if (state.playing) R.drawable.ph_pause else R.drawable.ph_play, localized(R.string.text_play_pause_7973c0, "Play / pausa")) { app.music.toggle() }
                        GalleryIconButton(R.drawable.ph_skip_forward, localized(R.string.text_next_track_b0ff2f, "Brano successivo"), enabled = state.hasNext ?: (state.index < state.queue.lastIndex)) { app.music.next() }
                    }
                }
            }
        } else if (track == null) {
            EmptyCategoryPreview("MUSIC", prefs, onChooseFolder)
        } else {
            val bars = remember { List(36) { androidx.compose.animation.core.Animatable(.12f + (it % 6) * .055f) } }
            LaunchedEffect(displayState.playing, prefs.reducedMotion) {
                if (displayState.playing && !prefs.reducedMotion) {
                    while (true) {
                        bars.forEachIndexed { index, bar ->
                            launch { bar.animateTo(.16f + kotlin.random.Random.nextFloat() * .8f,
                                androidx.compose.animation.core.tween(260 + (index % 4) * 70)) }
                        }
                        delay(180)
                    }
                } else bars.forEachIndexed { index, bar -> bar.snapTo(.14f + (index % 6) * .04f) }
            }
            val palette = musicPlayerPalette(app, track, data, prefs)
            val musicAccent = palette.accent
            CompositionLocalProvider(LocalContentColor provides palette.ink) {
            Box(Modifier.fillMaxSize()) {
                MusicPlayerBackdrop(palette, prefs)
                IconButton(onClick = { app.catalog.scope.launch { app.catalog.favorite(track.id) } }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).testTag("music-preview-favorite")) {
                    FavoriteHeart(track.favorite, localized(R.string.text_favorite_06bae3, "Preferito"), tint = if (track.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, identity = track.id)
                }

                onActions?.let { options -> GalleryIconButton(R.drawable.ph_dots_three,
                    localized(R.string.text_media_options_8aef8c, "Opzioni del media"), Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("music-preview-options")) { options(track) } }
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = when (prefs.musicColorMode) {
                    "CUSTOM" -> 1f; "ARTWORK" -> if (displayState.playing) .65f else .25f; else -> if (displayState.playing) .22f else .08f
                } }) {
                    val width = size.width / (bars.size * 2f)
                    bars.forEachIndexed { i, bar ->
                        val height = size.height * bar.value.coerceIn(.08f, .95f)
                        drawLine(musicAccent, androidx.compose.ui.geometry.Offset((i * 2 + 1) * width, size.height),
                            androidx.compose.ui.geometry.Offset((i * 2 + 1) * width, size.height - height), width * .75f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                MusicVolumeControl(app, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
                Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(start = 30.dp, end = 78.dp, top = 22.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    InteractiveMusicCover(app, track, data, prefs, displayState.playing, Modifier.weight(.42f).aspectRatio(1f))
                    Column(Modifier.weight(.58f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(track.title ?: track.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(track.artist ?: displayState.artist, style = MaterialTheme.typography.bodyMedium, color = palette.ink.copy(alpha = .75f), maxLines = 1)
                    }
                    var seek by remember(displayState.id) { mutableStateOf<Float?>(null) }
                    GallerySlider((seek ?: displayState.position.toFloat()).coerceIn(0f, displayState.duration.coerceAtLeast(1).toFloat()), { seek = it },
                        onValueChangeFinished = { seek?.let { app.music.seek(it.toLong()) }; seek = null },
                        enabled = track.id == state.id, valueRange = 0f..displayState.duration.coerceAtLeast(1).toFloat(), modifier = Modifier.fillMaxWidth(.85f).testTag("preview-music-seek"))
                    Text("${formatTime(displayState.position)}  /  ${formatTime(displayState.duration)}", style = MaterialTheme.typography.labelSmall, color = palette.ink.copy(alpha = .75f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        GalleryIconButton(R.drawable.ph_skip_back, localized(R.string.text_previous_track_b3f5fe, "Brano precedente"), enabled = displayState.hasPrevious ?: (displayState.index > 0)) { app.music.previous() }
                        FilledIconButton(onClick = {
                            if (track.id == state.id) app.music.toggle()
                            else app.music.play(track.id, app.session.mediaContext.filter { data.byId[it]?.kind == "AUDIO" }.ifEmpty { listOf(track.id) })
                        }, modifier = Modifier.size(64.dp)) {
                            GalleryIcon(if (displayState.playing) R.drawable.ph_pause else R.drawable.ph_play,
                                localized(R.string.text_play_pause_7973c0, "Play / pausa"), Modifier.size(28.dp))
                        }
                        GalleryIconButton(R.drawable.ph_skip_forward, localized(R.string.text_next_track_b0ff2f, "Brano successivo"), enabled = displayState.hasNext ?: (displayState.index < displayState.queue.lastIndex)) { app.music.next() }
                        GalleryIconButton(if (displayState.shuffle) R.drawable.ph_shuffle else if (displayState.repeat != 0) R.drawable.ph_arrows_clockwise else R.drawable.ph_arrows_left_right,
                            if (displayState.shuffle) localized(R.string.text_shuffle_0eb69b, "Casuale") else if (displayState.repeat != 0) localized(R.string.text_repeat_track_baa748, "Ripeti brano") else localized(R.string.text_repeat_off_700e76, "Ripetizione off"), Modifier.testTag("preview-music-mode")) { app.music.cyclePlaybackMode() }
                    }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun MusicVolumeControl(app: GalleryApp, modifier: Modifier = Modifier) {
    val volume by app.music.volume.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    LaunchedEffect(expanded, interactionRevision, dragging) {
        if (expanded && !dragging) { delay(5000); expanded = false }
    }
    Box(modifier.size(48.dp, 240.dp), contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.animation.AnimatedVisibility(expanded,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 4 },
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 4 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.size(48.dp, 170.dp), contentAlignment = Alignment.Center) {
                        GallerySlider(volume, { interactionRevision++; app.music.setVolume(it) },
                            Modifier.requiredSize(170.dp, 48.dp).rotate(-90f).testTag("music-volume")
                                .semantics { contentDescription = localized(R.string.music_volume, "Volume musica") }, interactionSource = interaction)
                    }
                }
            }
            IconButton({ expanded = !expanded; interactionRevision++ }, Modifier.testTag("music-volume-toggle")) {
                GalleryIcon(playbackVolumeIcon(volume), localized(R.string.music_volume, "Volume musica"), Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun MusicScreen(app: GalleryApp, data: CatalogSnapshot, playback: PlaybackState, task: (suspend () -> Unit) -> Unit, compact: Boolean = false) {
    val preview by app.music.previewToken.collectAsStateWithLifecycle()
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    var savePlaylist by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    if (playback.id == null) { EmptyGallery(localized(R.string.text_your_music_b63856, "La tua musica"), localized(R.string.text_choose_a_track_from_music_the_player_is_shared_a_c71f4b, "Scegli un brano dalla raccolta Musica. Il player è condiviso da tutte le schermate e continua in background."), R.drawable.ph_music_notes, featured = true); return }
    val track = data.byId[playback.id]?.takeIf(data::usable)
    if (track == null) { EmptyGallery(localized(R.string.text_track_unavailable_b73d64, "Brano non disponibile"), localized(R.string.text_the_source_was_hidden_or_disconnected_the_queue_ebde9e, "La sorgente è stata nascosta o scollegata. La coda viene aggiornata conservando i riferimenti."), R.drawable.ph_music_notes); return }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (preview != null) item {
            Text(localized(R.string.text_segment_preview_original_queue_retained_a9766d, "Anteprima del segmento · la coda originale è conservata"), color = MaterialTheme.colorScheme.primary)
            GalleryButton(localized(R.string.text_stop_preview_and_restore_queue_625382, "Ferma anteprima e ritrova la coda"), Modifier.fillMaxWidth()) { preview?.let(app.music::finishPreview) }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().clip(GalleryShape).background(MaterialTheme.colorScheme.surface)) {
                if (app.catalog.artwork(track, data) != null) MediaThumbnail(track, data, app.catalog, prefs,
                    Modifier.matchParentSize().blur(36.dp).graphicsLayer { alpha = 0.12f })
                @Composable fun artwork(modifier: Modifier) {
                    MediaThumbnail(track, data, app.catalog, prefs, modifier.aspectRatio(1f).clip(GalleryShape), scale = ContentScale.Fit)
                }
                if (compact) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 76.dp, top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    artwork(Modifier.width(112.dp))
                    Column(Modifier.weight(1f)) { PlayerControls(app, data, playback, track, task, compact = true, savePlaylist = { savePlaylist = ArrayList(playback.queue) }) }
                } else if (maxWidth >= 640.dp) Row(Modifier.padding(start = 20.dp, end = 80.dp, top = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    artwork(Modifier.weight(0.42f))
                    Column(Modifier.weight(0.58f)) { PlayerControls(app, data, playback, track, task) }
                } else Column(Modifier.padding(start = 20.dp, end = 80.dp, top = 20.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    artwork(Modifier.widthIn(max = 320.dp).fillMaxWidth())
                    PlayerControls(app, data, playback, track, task)
                }
                MusicVolumeControl(app, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            }
        }
        item {
            if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryButton(localized(R.string.text_save_playlist_8d3e69, "Salva playlist"), Modifier.weight(1f), enabled = preview == null) { savePlaylist = ArrayList(playback.queue) }
                Box(Modifier.weight(1f)) {
                    var timer by remember { mutableStateOf(false) }
                    GalleryButton("Timer", Modifier.fillMaxWidth()) { timer = true }
                    GalleryDropdownMenu(timer, { timer = false }) {
                        listOf(15, 30, 60, 90, 0).forEach { minutes -> DropdownMenuItem({ Text(if (minutes == 0) localized(R.string.text_disable_timer_ba37ce, "Disattiva timer") else localized(R.string.text_1_s_minutes_c69ae1, "%1\$s minuti", minutes)) }, { app.music.timer(minutes); timer = false }) }
                    }
                }
            }
            Text(localized(R.string.text_queue_1_s_tracks_5ad2e5, "Coda · %1\$s brani", playback.queue.size), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
        itemsIndexed(playback.queue, key = { index, id -> "$index/$id" }) { index, id ->
            val item = data.byId[id]?.takeIf(data::visible)
            var menu by remember(index, id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (index == playback.index) "▶ " else ""}${item?.title ?: item?.name ?: localized(R.string.text_unavailable_ce1391, "Non disponibile")}",
                    Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = item != null && data.usable(item)) { app.music.playQueueIndex(index) })
                Box {
                    GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.text_reorder_or_remove_from_queue_6df945, "Riordina o rimuovi dalla coda"), enabled = preview == null) { menu = true }
                    GalleryDropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(localized(R.string.text_move_up_666f06, "Sposta su")) }, { app.music.move(index, index - 1); menu = false }, enabled = index > 0)
                        DropdownMenuItem({ Text(localized(R.string.text_move_down_d0bb80, "Sposta giù")) }, { app.music.move(index, index + 1); menu = false }, enabled = index < playback.queue.lastIndex)
                        DropdownMenuItem({ Text(localized(R.string.text_remove_from_queue_f7ebcb, "Rimuovi dalla coda")) }, { app.music.remove(index); menu = false })
                    }
                }
            }
        }
        item { GalleryButton(localized(R.string.text_clear_queue_bb5c72, "Svuota coda"), Modifier.fillMaxWidth(), enabled = preview == null) { app.music.clear() } }
    }
    savePlaylist?.let { ids -> CollectionNameDialog(localized(R.string.text_save_playlist_8d3e69, "Salva playlist"), submit = { app.catalog.collection(it, "PLAYLIST", ids); Unit }, done = { savePlaylist = null }, dismiss = { savePlaylist = null }) }
}

@Composable
private fun PlayerControls(app: GalleryApp, data: CatalogSnapshot, playback: PlaybackState, track: MediaEntity, task: (suspend () -> Unit) -> Unit, compact: Boolean = false, savePlaylist: () -> Unit = {}) {
    var seek by remember(playback.id) { mutableStateOf<Float?>(null) }
    var artwork by androidx.compose.runtime.saveable.rememberSaveable(playback.id) { mutableStateOf(false) }
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    val preview by app.music.previewToken.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(playback.title, Modifier.weight(1f), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (compact) IconButton(onClick = { task { app.catalog.favorite(track.id) } }) {
                FavoriteHeart(track.favorite, localized(R.string.text_favorite_06bae3, "Preferito"), tint = if (track.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, identity = track.id)
            }
        }
        if (playback.artist.isNotBlank()) Text(playback.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        GallerySlider((seek ?: playback.position.toFloat()).coerceIn(0f, playback.duration.toFloat().coerceAtLeast(1f)),
            { seek = it }, onValueChangeFinished = { seek?.let { app.music.seek(it.toLong()) }; seek = null },
            valueRange = 0f..playback.duration.toFloat().coerceAtLeast(1f), enabled = playback.duration > 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime((seek ?: playback.position.toFloat()).toLong())); Text(formatTime(playback.duration)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (compact) Arrangement.SpaceBetween else Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (compact) IconButton(onClick = savePlaylist, enabled = preview == null) {
                Box { Icon(androidx.compose.ui.res.painterResource(R.drawable.ph_list), localized(R.string.text_save_playlist_8d3e69, "Salva playlist"), Modifier.size(24.dp)); Text("+", Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-8).dp), style = MaterialTheme.typography.labelLarge) }
            }
            GalleryIconButton(R.drawable.ph_skip_back, localized(R.string.text_previous_track_b3f5fe, "Brano precedente"), enabled = playback.hasPrevious ?: (playback.index > 0)) { app.music.previous() }
            if (compact) FilledIconButton(onClick = { app.music.toggle() }, modifier = Modifier.size(52.dp), shape = androidx.compose.foundation.shape.CircleShape) {
                Icon(androidx.compose.ui.res.painterResource(if (playback.playing) R.drawable.ph_pause else R.drawable.ph_play), localized(R.string.text_play_pause_7973c0, "Play / pausa"), Modifier.size(28.dp))
            } else GalleryIconButton(if (playback.playing) R.drawable.ph_pause else R.drawable.ph_play, localized(R.string.text_play_pause_7973c0, "Play / pausa")) { app.music.toggle() }
            GalleryIconButton(R.drawable.ph_skip_forward, localized(R.string.text_next_track_b0ff2f, "Brano successivo"), enabled = playback.hasNext ?: (playback.index < playback.queue.lastIndex)) { app.music.next() }
            if (compact) GalleryIconButton(if (playback.shuffle) R.drawable.ph_shuffle else if (playback.repeat != 0) R.drawable.ph_arrows_clockwise else R.drawable.ph_arrows_left_right,
                if (playback.shuffle) localized(R.string.text_shuffle_0eb69b, "Casuale") else if (playback.repeat != 0) localized(R.string.text_repeat_track_baa748, "Ripeti brano") else localized(R.string.text_repeat_off_700e76, "Ripetizione off"), enabled = preview == null) { app.music.cyclePlaybackMode() }
        }
        if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryButton(if (playback.shuffle) localized(R.string.text_shuffle_on_3d4f18, "Casuale attivo") else localized(R.string.text_shuffle_0eb69b, "Casuale"), Modifier.weight(1f), enabled = preview == null) { app.music.shuffle() }
            GalleryButton(when (playback.repeat) { 1 -> localized(R.string.text_repeat_track_baa748, "Ripeti brano"); 2 -> localized(R.string.text_repeat_queue_7bf8e2, "Ripeti coda"); else -> localized(R.string.text_repeat_off_700e76, "Ripetizione off") }, Modifier.weight(1f), enabled = preview == null) { app.music.repeat() }
        }
        if (!compact) {
            GalleryButton(if (track.favorite) localized(R.string.text_remove_favorite_9acd17, "Rimuovi Preferito") else localized(R.string.text_favorite_06bae3, "Preferito"), Modifier.fillMaxWidth()) { task { app.catalog.favorite(track.id) } }
            GalleryButton(localized(R.string.text_artwork_and_priority_b87104, "Copertina e priorità"), Modifier.fillMaxWidth()) { artwork = true }
        }
        playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (artwork) MusicArtworkDialog(app, data, prefs, ArtworkOrders.track(track.id), playback.title) { artwork = false }
}

@Composable
private fun InteractiveMusicCover(app: GalleryApp, track: MediaEntity, data: CatalogSnapshot, prefs: GalleryPreferences, playing: Boolean, modifier: Modifier) {
    val effect by app.preferences.musicCoverEffect.collectAsStateWithLifecycle()
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val pulseLevel by app.widgets.pulseLevel.collectAsStateWithLifecycle()
    DisposableEffect(effect) {
        app.widgets.previewPulseEnabled(effect == 2)
        onDispose { app.widgets.previewPulseEnabled(false) }
    }
    LaunchedEffect(playing, effect, prefs.reducedMotion, prefs.gameMode) {
        if (effect in setOf(1, 3) && playing && !prefs.reducedMotion && !prefs.gameMode) {
            while (true) {
                rotation.animateTo(rotation.value + 360f, androidx.compose.animation.core.tween(9000, easing = androidx.compose.animation.core.LinearEasing))
                rotation.snapTo(rotation.value % 360f)
            }
        }
        rotation.animateTo(0f, androidx.compose.animation.core.tween(550))
    }
    LaunchedEffect(playing, effect, prefs.reducedMotion, prefs.gameMode, pulseLevel) {
        val pulsing = effect == 2 && playing && !prefs.reducedMotion && !prefs.gameMode
        val target = if (pulsing) widgetPulseScale(pulseLevel) else if (effect == 2) .88f else 1f
        scale.animateTo(target, androidx.compose.animation.core.tween(if (pulsing) 60 else 260,
            easing = if (pulsing) androidx.compose.animation.core.LinearEasing else androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val shape = if (effect in setOf(1, 3)) androidx.compose.foundation.shape.CircleShape else GalleryShape
    Box(modifier.clickable { app.preferences.musicCoverEffect(MusicCoverEffects.next(effect)) }.testTag("music-cover-effect:$effect"), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation.value; scaleX = scale.value; scaleY = scale.value }.testTag("music-cover-transform").clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            MediaThumbnail(track, data, app.catalog, prefs, Modifier.fillMaxSize(), scale = ContentScale.Crop)
            if (effect in setOf(1, 3)) Canvas(Modifier.fillMaxSize()) {
                drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = .14f), size.minDimension * .46f, style = Stroke(size.minDimension * .006f))
                drawArc(androidx.compose.ui.graphics.Color.White.copy(alpha = .25f), 25f, 70f, false,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .08f),
                    size = androidx.compose.ui.geometry.Size(size.width * .84f, size.height * .84f), style = Stroke(size.minDimension * .009f))
            }
            if (effect == 1 && app.catalog.artwork(track, data) == null)
                GalleryIcon(R.drawable.ph_music_notes, null, Modifier.align(Alignment.TopCenter).padding(top = 32.dp).size(48.dp))
            if (effect == 1) Box(Modifier.align(Alignment.Center).fillMaxSize(.13f).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.background))
        }
    }
}
