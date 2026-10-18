package it.ayngallery

import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MusicModeMenu(vm: GalleryWindowModel, mode: String) {
    val choices = listOf("TRACKS" to localized(R.string.text_tracks_ac264a, "Brani"), "ALBUMS" to localized(R.string.text_music_albums_e85fef, "Album musicali"), "ARTISTS" to localized(R.string.text_artists_bbd1aa, "Artisti"), "FOLDERS" to localized(R.string.text_folders_23cc92, "Cartelle"), "PLAYLISTS" to localized(R.string.music_mode_playlists, "Playlist"))
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton({ expanded = true }, Modifier.testTag("music-mode-menu")) {
            Text(choices.firstOrNull { it.first == mode }?.second ?: choices.first().second,
                Modifier.widthIn(max = 150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            GalleryIcon(R.drawable.ph_arrow_right, null, Modifier.padding(start = 6.dp).size(16.dp).rotate(90f))
        }
        GalleryDropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (view, label) -> DropdownMenuItem(text = { Text(label) },
                onClick = { expanded = false; vm.musicMode(view) }, modifier = Modifier.testTag("music-mode:$view"),
                trailingIcon = { if (mode == view) Text("✓", color = MaterialTheme.colorScheme.primary) }) }
        }
    }
}

@Composable
fun MusicLibraryScreen(activity: PanelActivity, route: GalleryRoute, data: CatalogSnapshot, items: List<MediaEntity>, prefs: GalleryPreferences,
                       multi: Boolean, selected: Set<String>, activate: (MediaEntity) -> Unit, actions: (MediaEntity) -> Unit,
                       onMulti: () -> Unit, onSelectionAction: (String) -> Unit,
                       onChooseFolder: (SourceRole) -> Unit = { role -> activity.chooseSource(role) },
                       activeScene: Boolean = true, renderedSortRevision: Long? = null,
                       timelineDescending: Boolean = activity.windowModel.descending.value) {
    val app = activity.application as GalleryApp
    val vm = activity.windowModel
    val groups by vm.musicGroups.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var artworkTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var artworkTitle by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        if (route.musicView == "TRACKS" || route.musicGroup != null) {
            val group = groups.groups.find { it.id == route.musicGroup }
            if (group != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(group.title, style = MaterialTheme.typography.titleLarge)
                Text(group.origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (route.musicView == "PLAYLISTS") GalleryButton(localized(R.string.text_manage_playlists_e10ca3, "Gestisci playlist")) { vm.go("COLLECTION", collection = route.musicGroup!!.removePrefix("playlist:")) }
                if (route.musicView in setOf("ALBUMS", "FOLDERS")) GalleryButton(localized(R.string.text_artwork_and_priority_b87104, "Copertina e priorità"), Modifier.testTag("music-group-artwork")) {
                    val target = if (route.musicView == "ALBUMS") group.tracks.firstOrNull()?.let(::musicAlbumTarget) else ArtworkOrders.folder(group.id.removePrefix("folder:"))
                    if (target != null) { artworkTarget = target; artworkTitle = group.title }
                }
            }
            LibraryToolbar(vm, route, items.size, multi, onMulti)
            val available = items.filter(data::usable)
            if (items.isNotEmpty() && !multi) {
                if (available.size < items.size) Text(localized(R.string.text_1_s_unavailable_tracks_will_be_skipped_9556f5, "%1\$s brani non disponibili verranno saltati", items.size - available.size),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.weight(1f)) { LibraryGrid(activity, route, data, items, prefs, multi, selected, activate, actions,
                activeScene = activeScene, renderedSortRevision = renderedSortRevision, timelineDescending = timelineDescending) }
        } else {
            if (!groups.initialized || groups.view != route.musicView || groups.query != query) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (prefs.reducedMotion) Text(localized(R.string.text_loading_fda05a, "Caricamento…")) else CircularProgressIndicator()
            } else if (groups.groups.isEmpty() && query.isBlank() && route.musicView != "PLAYLISTS") EmptyFolderButton { onChooseFolder(SourceRole.MUSIC) }
            else if (groups.groups.isEmpty()) EmptyGallery(if (route.musicView == "PLAYLISTS") localized(R.string.text_your_playlists_f30fdf, "Le tue playlist") else localized(R.string.text_your_music_starts_here_22d615, "La musica parte da qui"),
                if (query.isNotBlank()) localized(R.string.text_no_results_try_another_name_f78f41, "Nessun risultato. Prova un altro nome.") else if (route.musicView == "PLAYLISTS") localized(R.string.text_save_the_player_queue_to_find_it_here_tracks_sta_7b739d, "Salva la coda dal player per ritrovarla qui. I brani rimangono nelle loro cartelle.")
                else localized(R.string.text_choose_music_folders_artists_and_albums_use_loca_0dbc29, "Scegli le cartelle musicali. Artisti e album usano soltanto i metadati locali, senza cercare informazioni online."), R.drawable.ph_music_notes,
                if (route.musicView != "PLAYLISTS" && query.isBlank()) localized(R.string.text_choose_a_folder_56d054, "Scegli una cartella") else null,
                featured = true, animateGlow = !prefs.reducedMotion) { onChooseFolder(SourceRole.MUSIC) }
            else LazyColumn(Modifier.weight(1f).testTag("music-groups"), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups.groups, key = { it.id }) { group ->
                    var focused by remember(group.id) { mutableStateOf(false) }
                    val hover = remember { MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    val scrolling = if ((focused || hovered) && !prefs.reducedMotion && !prefs.gameMode) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                    Surface(Modifier.fillMaxWidth().hoverable(hover).testTag("music-group:${group.id}")
                        .animateItem(placementSpec = null, fadeInSpec = tween(if (prefs.reducedMotion) 0 else 260), fadeOutSpec = tween(if (prefs.reducedMotion) 0 else 160))
                        .onFocusChanged { focused = it.isFocused }.clickable { vm.openMusicGroup(group.id) }, shape = GalleryShape,
                        color = MaterialTheme.colorScheme.surface, border = BorderStroke(if (focused) 3.dp else 1.dp,
                            if (focused) GalleryFocus else MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val cover = if (route.musicView == "PLAYLISTS") data.collections.firstOrNull { "playlist:${it.id}" == group.id }?.let(data::collectionCover)
                                else group.tracks.firstOrNull(data::usable) ?: if (route.musicView == "FOLDERS")
                                    data.artworkByTarget[ArtworkOrders.folder(group.id.removePrefix("folder:"))]?.coverMediaId?.let(data.byId::get)?.takeIf(data::artworkAllowed) else null
                            MediaThumbnail(cover, data, app.catalog, prefs, Modifier.size(38.dp), artwork = cover?.kind in setOf("IMAGE", "GIF"))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(group.title, scrolling, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(localized(R.string.text_1_s_visible_tracks_9940ce, "%1\$s brani visibili", group.tracks.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(group.origin, scrolling, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GalleryIcon(R.drawable.ph_arrow_right, null)
                        }
                    }
                }
            }
        }
    }
    artworkTarget?.let { target -> MusicArtworkDialog(app, data, prefs, target, artworkTitle) { artworkTarget = null } }
}
