package it.ayngallery

import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

@Composable
private fun CollectionNameInput(field: androidx.compose.foundation.text.input.TextFieldState, enabled: Boolean) {
    Text(localized(R.string.text_name_508690, "Nome"), style = MaterialTheme.typography.labelLarge)
    BasicTextField(field, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("collection-name")
        .semantics { contentDescription = localized(R.string.text_collection_name_5d060a, "Nome della raccolta") }.background(MaterialTheme.colorScheme.surfaceVariant, GalleryShape).padding(14.dp),
        enabled = enabled, lineLimits = TextFieldLineLimits.SingleLine, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
}

@Composable
fun CollectionNameDialog(title: String, initial: String = "", submit: suspend (String) -> Unit, done: () -> Unit, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val field = rememberTextFieldState(initial)
    GalleryAlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CollectionNameInput(field, !busy)
            Text(localized(R.string.text_local_references_only_original_files_stay_in_the_1c6b0a, "Solo riferimenti locali: i file originali rimangono nelle loro cartelle."), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ val name = field.text.toString(); busy = true; error = null; scope.launch {
        try { withContext(Dispatchers.IO) { submit(name) }; done() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: localized(R.string.text_could_not_save_9b6ca7, "Salvataggio non riuscito") }
        finally { busy = false }
    } }, enabled = !busy && field.text.isNotBlank() && field.text.trim().length <= 120, modifier = Modifier.testTag("collection-save")) { Text(if (busy) localized(R.string.text_saving_c4f57f, "Salvataggio…") else localized(R.string.text_save_c5997e, "Salva")) } },
        dismissButton = { TextButton(dismiss, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), modifier = Modifier) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}

@Composable
fun CollectionTargetDialog(app: GalleryApp, data: CatalogSnapshot, ids: List<String>, cover: Boolean = false, done: () -> Unit, dismiss: () -> Unit) {
    var create by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val audioOnly = ids.isNotEmpty() && ids.all { data.byId[it]?.kind == "AUDIO" }
    val targets = data.collections.filter { cover || it.kind != "PLAYLIST" || audioOnly }
    if (create != null) {
        CollectionNameDialog(if (create == "PLAYLIST") localized(R.string.text_new_playlist_6ebfb3, "Nuova playlist") else localized(R.string.text_new_collection_656a05, "Nuova raccolta"), submit = { name -> app.catalog.collection(name, create!!, ids); Unit }, done = done, dismiss = { create = null })
        return
    }
    GalleryAlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { GalleryDialogTitle(R.drawable.ph_heart, if (cover) localized(R.string.text_collection_cover_7ec6e5, "Copertina della raccolta") else localized(R.string.text_add_to_0791d4, "Aggiungi a…")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (cover) localized(R.string.text_choose_a_collection_the_image_remains_a_local_re_698b12, "Scegli la raccolta: l'immagine rimane un riferimento locale.") else localized(R.string.text_1_s_items_no_files_copied_9ee36c, "%1\$s elementi · nessuna copia dei file", ids.size), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(targets, key = { it.id }) { collection ->
                    GalleryButton(collection.name + if (collection.kind == "PLAYLIST") " · Playlist" else localized(R.string.text_collection_97d9d7, " · Raccolta"), Modifier.fillMaxWidth().testTag("collection-target:${collection.id}"), enabled = !busy) {
                        busy = true; error = null
                        scope.launch { try {
                            withContext(Dispatchers.IO) { if (cover) app.catalog.setCollectionCover(collection.id, ids.single()) else app.catalog.addCollectionItems(collection.id, ids) }
                            done()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: localized(R.string.text_operation_failed_f17e12, "Operazione non riuscita") }
                        finally { busy = false } }
                    }
                }
            }
            if (targets.isEmpty()) Text(localized(R.string.text_no_compatible_collection_c6ea58, "Nessuna raccolta compatibile."))
            if (!cover) {
                GalleryButton(localized(R.string.text_new_collection_656a05, "Nuova raccolta"), Modifier.fillMaxWidth(), enabled = !busy) { create = "MEDIA" }
                if (audioOnly) GalleryButton(localized(R.string.text_new_playlist_6ebfb3, "Nuova playlist"), Modifier.fillMaxWidth(), enabled = !busy) { create = "PLAYLIST" }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(dismiss, enabled = !busy) { Text(localized(R.string.text_close_32d407, "Chiudi")) } })
}

@Composable
fun CollectionScreen(activity: PanelActivity, data: CatalogSnapshot) {
    val app = activity.application as GalleryApp
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    var create by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryButton(localized(R.string.text_new_collection_656a05, "Nuova raccolta"), Modifier.weight(1f).testTag("collection-create"), primary = true) { create = "MEDIA" }
            GalleryButton(localized(R.string.text_new_playlist_6ebfb3, "Nuova playlist"), Modifier.weight(1f)) { create = "PLAYLIST" }
        }
        if (data.collections.isEmpty()) EmptyGallery(localized(R.string.text_your_collections_b5ac86, "Le tue raccolte"), localized(R.string.text_bring_together_images_gifs_videos_and_music_with_1ca575, "Riunisci immagini, GIF, video e musica senza copiarli. Le playlist contengono soltanto brani."), R.drawable.ph_squares_four)
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data.collections.sortedWith(compareBy(MediaNames.natural) { it.name }), key = { it.id }) { collection ->
                var focused by remember(collection.id) { mutableStateOf(false) }
                val links = data.collectionItems.filter { it.collectionId == collection.id }
                val visible = links.count { data.byId[it.mediaId]?.let(data::visible) == true }
                Surface(Modifier.fillMaxWidth().animateItem(placementSpec = null, fadeInSpec = tween(if (prefs.reducedMotion) 0 else 240), fadeOutSpec = tween(if (prefs.reducedMotion) 0 else 160))
                    .onFocusChanged { focused = it.hasFocus }.clickable { activity.windowModel.go("COLLECTION", collection = collection.id) }.testTag("collection:${collection.id}"), shape = GalleryShape,
                    border = if (focused) BorderStroke(3.dp, GalleryFocus) else null) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val cover = data.collectionCover(collection)
                        MediaThumbnail(cover, data, app.catalog, prefs, Modifier.size(64.dp), artwork = cover?.kind in setOf("IMAGE", "GIF"))
                        Column(Modifier.weight(1f)) {
                            Text(collection.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(localized(R.string.text_1_s_2_s_visible_items_c963ae, "%1\$s · %2\$s elementi visibili", if (collection.kind == "PLAYLIST") "Playlist" else localized(R.string.text_mixed_collection_6834fb, "Raccolta mista"), visible), style = MaterialTheme.typography.bodySmall)
                        }
                        GalleryIcon(R.drawable.ph_arrow_right, null)
                    }
                }
            }
        }
    }
    create?.let { kind -> CollectionNameDialog(if (kind == "PLAYLIST") localized(R.string.text_new_playlist_6ebfb3, "Nuova playlist") else localized(R.string.text_new_collection_656a05, "Nuova raccolta"),
        submit = { app.catalog.collection(it, kind, emptyList()); Unit }, done = { create = null }, dismiss = { create = null }) }
}

@Composable
fun CollectionDetailScreen(activity: PanelActivity, id: String, data: CatalogSnapshot, prefs: GalleryPreferences,
                           task: (suspend () -> Unit) -> Unit, activate: (MediaEntity) -> Unit, actions: (MediaEntity) -> Unit) {
    val app = activity.application as GalleryApp
    val collection = data.collections.firstOrNull { it.id == id }
    if (collection == null) { EmptyGallery(localized(R.string.text_collection_removed_7205b1, "Raccolta rimossa"), localized(R.string.text_original_files_remain_intact_go_back_to_choose_a_b50e67, "I file originali rimangono intatti. Torna indietro per scegliere un'altra raccolta."), R.drawable.ph_squares_four); return }
    val links = data.collectionItems.filter { it.collectionId == id }
    val items = links.mapNotNull { data.byId[it.mediaId]?.takeIf(data::visible) }
    var rename by rememberSaveable(id) { mutableStateOf(false) }
    var remove by rememberSaveable(id) { mutableStateOf(false) }
    var chooser by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var manage by remember(id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(collection.name, style = MaterialTheme.typography.titleLarge)
            Text(localized(R.string.text_1_s_visible_items_df95fc, "%1\$s elementi visibili", items.size) + if (links.size > items.size) localized(R.string.text_1_s_excluded_or_missing_references_retained_034d70, " · %1\$s riferimenti esclusi o mancanti conservati", links.size - items.size) else "", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GalleryButton(localized(R.string.text_add_items_49e7fb, "Aggiungi elementi"), Modifier.weight(1f).testTag("collection-add"), primary = true) { chooser = "ADD" }
                Box {
                    GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.text_manage_collection_557c66, "Gestisci raccolta")) { manage = true }
                    GalleryDropdownMenu(manage, { manage = false }) {
                        DropdownMenuItem({ Text(localized(R.string.text_rename_98b79b, "Rinomina")) }, { rename = true; manage = false }, modifier = Modifier.testTag("collection-rename"))
                        DropdownMenuItem({ Text(localized(R.string.text_cover_7be01e, "Copertina")) }, { chooser = "COVER"; manage = false }, modifier = Modifier.testTag("collection-cover"))
                        DropdownMenuItem({ Text(localized(R.string.text_remove_collection_e81de6, "Rimuovi raccolta")) }, { remove = true; manage = false }, modifier = Modifier.testTag("collection-delete"))
                    }
                }
            }
            if (collection.kind == "PLAYLIST") GalleryButton(localized(R.string.text_play_playlist_5451fd, "Riproduci playlist"), enabled = items.any(data::usable)) {
                val queue = items.filter(data::usable).map { it.id }
                app.music.play(queue.first(), queue); activity.windowModel.go("PLAYER")
            }
        }
        if (items.isEmpty()) EmptyGallery(localized(R.string.text_space_for_your_choices_9db02b, "Spazio per le tue scelte"), localized(R.string.text_add_items_to_this_collection_excluded_links_are_21b137, "Aggiungi elementi a questa raccolta. I collegamenti esclusi non vengono cancellati."), R.drawable.ph_plus)
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(items, key = { _, media -> media.id }) { index, media ->
                var menu by remember(media.id) { mutableStateOf(false) }
                var focused by remember(media.id) { mutableStateOf(false) }
                Surface(Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }.animateItem(placementSpec = null, fadeInSpec = tween(if (prefs.reducedMotion) 0 else 220), fadeOutSpec = tween(if (prefs.reducedMotion) 0 else 160)), shape = GalleryShape,
                    border = if (focused) BorderStroke(3.dp, GalleryFocus) else null) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MediaThumbnail(media, data, app.catalog, prefs, Modifier.size(56.dp))
                        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = data.usable(media)) { activate(media) }.testTag("collection-item:${media.id}")) {
                            Text(media.title ?: media.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (data.usable(media)) media.artist ?: media.kind else localized(R.string.text_unavailable_ce1391, "Non disponibile"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.text_collection_item_actions_81ff41, "Azioni sull'elemento della raccolta")) { menu = true }
                            GalleryDropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(localized(R.string.text_move_up_666f06, "Sposta su")) }, { val before = items[index - 1].id; task { app.catalog.moveCollectionItem(id, media.id, before) }; menu = false }, enabled = index > 0)
                                DropdownMenuItem({ Text(localized(R.string.text_move_down_d0bb80, "Sposta giù")) }, { val before = items.getOrNull(index + 2)?.id; task { app.catalog.moveCollectionItem(id, media.id, before) }; menu = false }, enabled = index < items.lastIndex)
                                DropdownMenuItem({ Text(localized(R.string.text_remove_from_collection_d95612, "Rimuovi dalla raccolta")) }, { task { app.catalog.removeCollectionItem(id, media.id) }; menu = false })
                                DropdownMenuItem({ Text(localized(R.string.text_media_actions_0e2aa2, "Azioni sul media")) }, { actions(media); menu = false })
                            }
                        }
                    }
                }
            }
        }
    }
    if (rename) CollectionNameDialog(localized(R.string.text_rename_collection_afd80a, "Rinomina raccolta"), collection.name, { app.catalog.renameCollection(id, it) }, { rename = false }, { rename = false })
    if (remove) GalleryAlertDialog(onDismissRequest = { remove = false }, title = { Text(localized(R.string.text_remove_this_collection_aec0c3, "Rimuovere questa raccolta?")) }, text = {
        Text(localized(R.string.text_1_s_n_nonly_the_collection_and_its_links_will_be_1073ca, "%1\$s\n\nVerranno rimossi soltanto la raccolta e i suoi collegamenti. Nessun file verrà cancellato; i Preferiti rimangono.", collection.name))
    }, confirmButton = { TextButton({ task { app.catalog.deleteCollection(id) }; remove = false }, Modifier.testTag("collection-delete-confirm")) { Text(localized(R.string.text_remove_collection_e81de6, "Rimuovi raccolta")) } }, dismissButton = { TextButton({ remove = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    chooser?.let { mode -> LocalMediaPicker(app, data, prefs, collection.id, collection.name, collection.kind,
        data.collectionItems.filter { it.collectionId == id }.map { it.mediaId }.toSet(), mode == "COVER",
        onSave = { ids -> if (mode == "COVER") app.catalog.setCollectionCover(id, ids.singleOrNull()) else app.catalog.addCollectionItems(id, ids) },
        done = { chooser = null }, dismiss = { chooser = null }) }
}

@Composable
fun LocalMediaPicker(app: GalleryApp, data: CatalogSnapshot, prefs: GalleryPreferences, key: String, title: String, kind: String,
                     excluded: Set<String>, cover: Boolean, onSave: suspend (List<String>) -> Unit, done: () -> Unit, dismiss: () -> Unit, albumId: String? = null, titleOverride: String? = null, automaticLabel: String? = null, browseAlbums: Boolean = false) {
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var browsingAlbum by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val albumCoverPicker = cover
    val filterAlbum = albumId ?: browsingAlbum
    val albums = remember(data, browseAlbums) { data.visibleAlbums().filter { album ->
        data.visibleByAlbum[album.id].orEmpty().any { it.kind in setOf("IMAGE", "GIF") && data.artworkAllowed(it) }
    } }
    val search = rememberTextFieldState()
    val query = search.text.toString()
    val candidates by produceState<List<MediaEntity>?>(null, data, key, cover, query, filterAlbum) {
        value = null
        value = withContext(Dispatchers.Default) {
            data.media.filter { media ->
                (filterAlbum == null || media.albumId == filterAlbum) && (if (cover) media.kind in (if (albumId != null) setOf("IMAGE", "GIF", "VIDEO") else setOf("IMAGE", "GIF")) && data.artworkAllowed(media)
                else data.visible(media) && media.id !in excluded && (kind != "PLAYLIST" || media.kind == "AUDIO")) &&
                    (albumCoverPicker || listOfNotNull(media.name, media.title, media.artist).any { it.contains(query, ignoreCase = true) })
            }.sortedWith(compareBy(MediaNames.natural) { it.title ?: it.name })
        }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(ids: List<String>) { app.sounds.play("save"); busy = true; error = null; scope.launch {
        try { withContext(Dispatchers.IO) { onSave(ids) }; done() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: localized(R.string.text_could_not_save_9b6ca7, "Salvataggio non riuscito") }
        finally { busy = false }
    } }
    GalleryAlertDialog(onDismissRequest = { if (!busy) dismiss() },
        modifier = if (albumCoverPicker) Modifier.fillMaxWidth(.95f) else Modifier,
        properties = if (albumCoverPicker) androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false) else androidx.compose.ui.window.DialogProperties(),
        title = { Text(titleOverride ?: if (cover) localized(R.string.text_choose_cover_254a2f, "Scegli copertina") else localized(R.string.text_add_items_49e7fb, "Aggiungi elementi")) }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!albumCoverPicker) {
                Text(localized(R.string.text_search_name_or_artist_44c15b, "Cerca nome o artista"), style = MaterialTheme.typography.labelLarge)
                BasicTextField(search, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("collection-media-search").semantics { contentDescription = localized(R.string.text_search_media_626c4c, "Cerca media") }
                    .background(MaterialTheme.colorScheme.surfaceVariant, GalleryShape).border(1.dp, MaterialTheme.colorScheme.outline, GalleryShape).padding(14.dp), lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
            }
            if (browseAlbums && browsingAlbum != null) TextButton({ browsingAlbum = null; selected.clear() }) {
                GalleryIcon(R.drawable.ph_arrow_left, null); Text(localized(R.string.nav_albums, "Album"), Modifier.padding(start = 8.dp))
            }
            if (browseAlbums && browsingAlbum == null) LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(albums, key = { it.id }) { album ->
                    Surface(onClick = { browsingAlbum = album.id }, modifier = Modifier.testTag("picker-album:${album.id}"), shape = GalleryShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        AlbumTileContent(album, data, app.catalog, prefs, true, 5, false, MaterialTheme.colorScheme.secondary, false) {}
                    }
                }
            }
            else if (albumCoverPicker) LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(candidates.orEmpty().size, key = { candidates!![it].id }) { index ->
                    val media = candidates!![index]
                    var focused by remember(media.id) { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("collection-choose:${media.id}")
                        .onFocusChanged { focused = it.hasFocus }
                        .border(if (media.id in selected || focused) 3.dp else 1.dp, if (media.id in selected) MaterialTheme.colorScheme.primary else if (focused) GalleryFocus else MaterialTheme.colorScheme.outlineVariant, GalleryShape)
                        .selectable(media.id in selected, enabled = !busy, role = Role.RadioButton, onClick = { selected = arrayListOf(media.id) })) {
                        MediaThumbnail(media, data, app.catalog, prefs, Modifier.fillMaxSize().padding(2.dp), artwork = true)
                        if (media.id in selected) Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primary) {
                            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
                                GalleryIcon(R.drawable.ph_checks, null, Modifier.padding(4.dp).size(16.dp))
                            }
                        }
                    }
                }
            } else LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(candidates.orEmpty(), key = { it.id }) { media ->
                    var focused by remember(media.id) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("collection-choose:${media.id}")
                        .onFocusChanged { focused = it.hasFocus }.border(if (focused) 3.dp else 0.dp, if (focused) GalleryFocus else MaterialTheme.colorScheme.surface, GalleryShape)
                        .selectable(media.id in selected, enabled = !busy, role = if (cover) Role.RadioButton else Role.Checkbox, onClick = {
                        selected = if (cover) arrayListOf(media.id) else ArrayList(if (media.id in selected) selected - media.id else selected + media.id)
                    }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (cover) RadioButton(media.id in selected, null) else Checkbox(media.id in selected, null)
                        MediaThumbnail(media, data, app.catalog, prefs, Modifier.size(40.dp), artwork = cover)
                        Text(media.title ?: media.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (candidates == null) Text(localized(R.string.text_loading_fda05a, "Caricamento…"))
            else if (candidates!!.isEmpty()) Text(localized(R.string.text_no_compatible_items_for_this_choice_9187e2, "Nessun elemento compatibile con questa scelta."))
            if (cover) GalleryButton(automaticLabel ?: localized(R.string.text_automatic_cover_99768a, "Copertina automatica"), enabled = !busy) { save(emptyList()) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ save(selected.toList()) }, enabled = !busy && selected.isNotEmpty(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), modifier = (if (key == "gallery-background") Modifier.height(36.dp) else Modifier).testTag("collection-add-save")) { Text(if (busy) localized(R.string.text_saving_c4f57f, "Salvataggio…") else localized(R.string.text_save_c5997e, "Salva")) } },
        dismissButton = { TextButton(dismiss, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), modifier = if (key == "gallery-background") Modifier.height(36.dp) else Modifier) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}
