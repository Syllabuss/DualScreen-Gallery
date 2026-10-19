package it.ayngallery

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun SourceScreen(activity: PanelActivity, data: CatalogSnapshot, task: (suspend () -> Unit) -> Unit) {
    val catalog = (activity.application as GalleryApp).catalog
    val progress by catalog.progress.collectAsStateWithLifecycle()
    var chooseRootType by rememberSaveable { mutableStateOf(false) }
    val missing = remember(data) {
        val active = data.sources.filter { it.enabled && it.id != DeviceGallery.SOURCE }.map { it.id }.toSet()
        val referenced = data.membership.filter { it.sourceId in active }.map { it.mediaId }.toSet()
        MediaRelinking.missing(data).filter { it.id in referenced }
    }
    val missingBySource = remember(data, missing) {
        val ids = missing.map { it.id }.toSet()
        data.membership.filter { it.mediaId in ids }.groupingBy { it.sourceId }.eachCount()
    }
    val roots = data.sources.filter { it.id != DeviceGallery.SOURCE && !it.singleDocument && it.enabled }
    LazyColumn(Modifier.testTag("sources-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(localized(R.string.roots_title, "Le tue roots"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    GalleryButton(localized(R.string.add_roots, "Aggiungi roots"), Modifier.testTag("add-roots"), primary = true) { chooseRootType = true }
                }
                Text(localized(R.string.roots_guidance, "Le sottocartelle diventano album. La scansione parte automaticamente quando aggiungi una cartella."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Surface(Modifier.fillMaxWidth(), shape = GalleryShape, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .48f)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                            GalleryIcon(R.drawable.ph_folders, null, Modifier.size(20.dp))
                        }
                        Text(localized(R.string.shared_storage_help, "Per Download o tutta la memoria interna, autorizza l’accesso a tutti i file nelle impostazioni Android. Poi scegli la cartella. Android/data, Android/obb e le cartelle private restano esclusi."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (progress.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(localized(R.string.roots_scanning, "Scansione di %1\$s… · %2\$s file", progress.source ?: "", progress.count),
                        Modifier.testTag("roots-scan-progress"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (missing.isNotEmpty()) GalleryButton(localized(R.string.text_relink_files_618c8b, "Ricollega file…"), Modifier.fillMaxWidth().testTag("sources-relink")) { activity.windowModel.relinkSource(null) }
            }
        }
        if (roots.isEmpty()) item {
            Surface(Modifier.fillMaxWidth(), shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        GalleryIcon(R.drawable.ph_folders, null, Modifier.padding(12.dp).size(24.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(localized(R.string.roots_empty_title, "Ancora nessuna cartella"), style = MaterialTheme.typography.titleSmall)
                        Text(localized(R.string.roots_empty_message, "Aggiungi una cartella per iniziare a vedere i tuoi album."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(roots, key = { it.id }) { source ->
            Surface(Modifier.fillMaxWidth(), shape = GalleryShape, color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        GalleryIcon(if (source.role == "MUSIC") R.drawable.ph_music_notes else R.drawable.ph_images, null, Modifier.padding(10.dp).size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(sourceFolderPath(source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when (source.role) { "MUSIC" -> localized(R.string.text_music_012df9, "Musica"); "MIXED" -> localized(R.string.text_images_videos_and_music_7e3f80, "Immagini, video e musica"); else -> localized(R.string.text_images_and_videos_731776, "Immagini e video") },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        source.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (source.restoreUri != null) {
                        val count = missingBySource[source.id] ?: 0
                        Text(if (count > 0) localized(R.string.text_1_s_backup_references_still_need_relinking_choos_d8d02b, "%1\$s riferimenti del backup ancora da ricollegare. Scegli i file corretti e conferma ogni associazione.", count) else localized(R.string.text_all_references_have_been_relinked_the_backup_sou_295fc9, "Tutti i riferimenti sono stati ricollegati. La sorgente del backup resta come provenienza."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (count > 0) GalleryButton(localized(R.string.text_relink_files_688750, "Ricollega i file…"), Modifier.fillMaxWidth().testTag("source-relink:${source.id}")) { activity.windowModel.relinkSource(source.id) }
                    }
                    else if (!source.reachable) GalleryButton(localized(R.string.text_restore_access_fa5ec3, "Ripristina accesso")) {
                        if (source.singleDocument) activity.chooseDocument() else activity.chooseWholeStorage(SourceRole.valueOf(source.role))
                    }
                    }
                    IconButton(onClick = { task { catalog.enableSource(source.id, false) } }, modifier = Modifier.testTag("remove-root:${source.id}").semantics {
                        contentDescription = localized(R.string.remove_root, "Rimuovi root: %1\$s", source.title)
                    }) { Text("×", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    if (chooseRootType) GalleryAlertDialog(onDismissRequest = { chooseRootType = false },
        modifier = Modifier.fillMaxWidth(.9f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { Text(localized(R.string.add_roots, "Aggiungi roots")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(localized(R.string.roots_choose_type, "Scegli cosa vuoi sfogliare. Dopo aver autorizzato la cartella, gli album verranno cercati automaticamente."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(localized(R.string.roots_picker_help, "Nel selettore Android apri la memoria interna o la microSD, scegli la cartella radice e conferma con “Usa questa cartella”. Le cartelle di sistema protette non possono essere autorizzate."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GalleryButton(localized(R.string.add_visual_folder, "Foto e video"), Modifier.weight(1f).fillMaxHeight().testTag("add-visual-root"), primary = true, icon = R.drawable.ph_images) { chooseRootType = false; activity.chooseWholeStorage(SourceRole.VISUAL) }
                GalleryButton(localized(R.string.add_music_folder, "Musica"), Modifier.weight(1f).fillMaxHeight().testTag("add-music-root"), icon = R.drawable.ph_music_notes) { chooseRootType = false; activity.chooseWholeStorage(SourceRole.MUSIC) }
            }
        } }, dismissButton = { TextButton({ chooseRootType = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }, confirmButton = {})
}

internal fun albumAnchorAfterRemoval(previous: List<String>, current: List<String>, selected: String?): String? {
    if (selected in current) return selected
    val index = previous.indexOf(selected)
    if (index >= 0) {
        previous.drop(index + 1).firstOrNull { it in current }?.let { return it }
        previous.take(index).lastOrNull { it in current }?.let { return it }
    }
    return current.firstOrNull()
}

private val LocalAlbumTileGrid = androidx.compose.runtime.compositionLocalOf { false }

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AlbumScreen(activity: PanelActivity, data: CatalogSnapshot, task: (suspend () -> Unit) -> Unit, videoOnly: Boolean = false, onChooseFolder: () -> Unit = { activity.chooseWholeStorage(SourceRole.VISUAL) }) {
    val app = activity.application as GalleryApp
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    val videoBranches = remember(data) {
        val direct = data.media.filter { it.kind == "VIDEO" && !it.convertedGif && !it.trashed && it.available }.map { it.albumId }.toSet()
        direct + data.ancestry.filter { it.albumId in direct }.map { it.ancestorId }
    }
    val visible = remember(data, videoOnly, videoBranches, prefs.showEmptyFolders) { data.visibleAlbums().distinctBy { it.id }.filter { (!videoOnly || it.id in videoBranches) && (prefs.showEmptyFolders || it.id in data.albumsWithVisibleMedia) } }
    val query by activity.windowModel.query.collectAsStateWithLifecycle()
    val topIds = remember(data, prefs.nestedAlbums, prefs.showEmptyFolders) { data.topAlbums(prefs).map { it.id }.toSet() }
    val savedOrder by app.preferences.albumOrder.collectAsStateWithLifecycle()
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    val order = dragOrder ?: savedOrder
    val rank = remember(order) { order.withIndex().associate { it.value to it.index } }
    LaunchedEffect(visible.map { it.id }) { app.preferences.rememberAlbums(visible.map { it.id }) }
    val albums = remember(visible, query, videoOnly, topIds, rank) { visible.filter { if (query.isBlank()) videoOnly || it.id in topIds
        else it.name.contains(query.trim(), true) || it.path.contains(query.trim(), true) }
        .sortedWith(compareBy<AlbumEntity> { rank[it.id] ?: -1 }.thenBy { it.name }) }
    val vm = activity.windowModel
    val albumIds = albums.map { it.id }
    var previousAlbumIds by remember { mutableStateOf(emptyList<String>()) }
    val anchor = remember(albumIds) { albumAnchorAfterRemoval(previousAlbumIds, albumIds, vm.albumAnchor) }
    LaunchedEffect(albumIds) {
        val hadAlbums = previousAlbumIds.isNotEmpty()
        previousAlbumIds = albumIds
        if (anchor != null || hadAlbums) {
            vm.albumAnchor = anchor
            app.session.previewAlbum(anchor)
        }
    }
    if (albums.isEmpty() && query.isBlank()) { EmptyFolderButton(chooseFolder = onChooseFolder); return }
    val currentAlbumIds by rememberUpdatedState(albumIds)
    val storedGrid by vm.albumGrid.collectAsStateWithLifecycle()
    val carousel = prefs.albumCarousel
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val horizontal = carousel && configuration.screenWidthDp > configuration.screenHeightDp
    val grid = !carousel && (videoOnly || storedGrid)
    val horizontalGrid = grid && prefs.horizontalAlbums
    val columns by (if (videoOnly) vm.videoAlbumColumns else vm.albumColumns).collectAsStateWithLifecycle()
    val sizes by (if (videoOnly) app.preferences.videoAlbumSizes else app.preferences.albumSizes).collectAsStateWithLifecycle()
    // One ordered packing model is used during a drag and after its persisted commit.
    val geometry = remember(albums, sizes, columns) { albumGridCells(albums.map { sizes[it.id] ?: 2 }, columns) }
    val currentGeometry by rememberUpdatedState(geometry)
    val bandGeometry = remember(geometry) { albumGridLayout(geometry, 0f, 0f) }
    val bands = bandGeometry.bands
    val selected by vm.selectedAlbums.collectAsStateWithLifecycle()
    val menu by vm.albumMenu.collectAsStateWithLifecycle()
    var hide by app.session.albumDialogs::hide
    var request by app.session.albumDialogs::request
    var preparing by remember { mutableStateOf(false) }
    var coverAlbum by app.session.albumDialogs::cover
    var pinMode by app.session.albumDialogs::pinMode
    var pinAlbums by app.session.albumDialogs::pinAlbums
    val selectionColor = when (prefs.themeId) {
        "electric_lime" -> androidx.compose.ui.graphics.Color(0xFFFF87CD)
        "stocking_pink" -> androidx.compose.ui.graphics.Color(0xFFC4F66B)
        else -> MaterialTheme.colorScheme.secondary
    }
    fun choose(album: AlbumEntity) {
        app.video.browsePreview(null)
        app.sounds.play("navigate")
        app.session.previewAlbum(album.id)
        if (selected.isNotEmpty()) vm.selectAlbums(if (album.id in selected) selected - album.id else selected + album.id)
        else { vm.albumAnchor = album.id; if (data.albumLocked(album.id)) app.session.albumUnlockRequest = album.id else vm.go(if (videoOnly) "VIDEO" else "ALBUM", album = album.id) }
    }
    val start = albums.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = bandGeometry.cellBands.getOrNull(start) ?: 0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = start)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gridGap = with(density) { (if (columns >= 5) 3.dp else 10.dp).toPx() }
    val gridPadding = with(density) { (if (!horizontalGrid && columns >= 6) 8.dp else 16.dp).toPx() }
    val gridWidth by remember(gridState, horizontalGrid) { derivedStateOf { (if (horizontalGrid) gridState.layoutInfo.viewportSize.height else gridState.layoutInfo.viewportSize.width) } }
    val gridUnit by rememberUpdatedState(((gridWidth - 2 * gridPadding - gridGap * (columns - 1)) / columns).coerceAtLeast(0f))
    val gridLayout by rememberUpdatedState(remember(geometry, gridUnit, gridGap) { albumGridLayout(geometry, gridUnit, gridGap) })
    val tileOffsets = remember { mutableMapOf<String, androidx.compose.animation.core.Animatable<Offset, androidx.compose.animation.core.AnimationVector2D>>() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val animatedAlbumIds by remember(albums, grid, geometry, sizes, columns, prefs.albumCoverLimit, prefs.animatedAlbumCovers) { derivedStateOf {
        val indices = if (grid) gridState.layoutInfo.visibleItemsInfo.flatMap { band -> bands.getOrNull(band.index).orEmpty() }
            else listState.layoutInfo.visibleItemsInfo.map { it.index }
        val width = if (grid) gridState.layoutInfo.viewportSize.width / columns.coerceAtLeast(1) else 72
        animatedGifIds(indices.mapNotNull { albums.getOrNull(it) }.filter { data.cover(it)?.kind == "GIF" && !data.albumLocked(it.id) }
            .map { it.id to width.toLong() * width * if ((sizes[it.id] ?: 2) == 4) 4 else 1 }, prefs.albumCoverLimit)
    } }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    var focusedAlbum by remember { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(grid, inputMode) { mutableStateOf(false) }
    val focusRequests = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    val focusScope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var preferredColumn by remember(columns) { mutableIntStateOf(-1) }
    var controllerFocusTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(albumIds) {
        if (focusedAlbum != null && focusedAlbum !in albumIds) {
            focusedAlbum = null
            initialFocusRequested = false
            if (anchor != null) {
                val index = albumIds.indexOf(anchor)
                if (grid) gridState.scrollToItem(bandGeometry.cellBands[index]) else listState.scrollToItem(index)
                if (inputMode == androidx.compose.ui.input.InputMode.Keyboard) {
                    androidx.compose.runtime.withFrameNanos { }
                    focusRequests[anchor]?.requestFocus()
                }
            }
        }
    }
    DisposableEffect(activity, grid, currentAlbumIds, columns, focusedAlbum, geometry, horizontalGrid) {
        val handler: (PadAction) -> Boolean = { action ->
            val index = currentAlbumIds.indexOf(focusedAlbum)
            if (!grid || index < 0 || activity.settingsHeaderHasFocus || activity.windowModel.route.value.tab != (if (videoOnly) "VIDEO" else "ALBUM") || activity.windowModel.route.value.album != null || app.session.settingsOwner != null) false
            else {
                val vertical = if (horizontalGrid) action == PadAction.LEFT || action == PadAction.RIGHT else action == PadAction.UP || action == PadAction.DOWN
                if (preferredColumn < 0 || !vertical) preferredColumn = geometry[index].center
                val gridAction = if (horizontalGrid) when (action) {
                    PadAction.UP -> PadAction.LEFT; PadAction.DOWN -> PadAction.RIGHT
                    PadAction.LEFT -> PadAction.UP; PadAction.RIGHT -> PadAction.DOWN
                    else -> action
                } else action
                val target = albumGridTarget(geometry, index, gridAction, preferredColumn)
                if (target !in currentAlbumIds.indices) false
                else {
                    if (!vertical) preferredColumn = geometry[target].center
                    if (target != index && !(controllerFocusTarget == currentAlbumIds[target] && focusJob?.isActive == true)) {
                        focusJob?.cancel()
                        controllerFocusTarget = currentAlbumIds[target]
                        val layout = gridState.layoutInfo
                        val band = bandGeometry.cellBands[target]
                        if (layout.visibleItemsInfo.any { it.index == band }) {
                            focusRequests[currentAlbumIds[target]]?.requestFocus()
                        } else focusJob = focusScope.launch {
                            if (prefs.reducedMotion) gridState.scrollToItem(band) else gridState.animateScrollToItem(band)
                            androidx.compose.runtime.withFrameNanos { }
                            focusRequests[currentAlbumIds[target]]?.requestFocus()
                        }
                    }
                    true
                }
            }
        }
        activity.albumGridFocus = handler
        onDispose { if (activity.albumGridFocus === handler) activity.albumGridFocus = null }
    }
    LaunchedEffect(columns) {
        val id = focusedAlbum ?: vm.albumAnchor ?: return@LaunchedEffect
        val index = albums.indexOfFirst { it.id == id }
        if (grid && index >= 0) {
            val band = bandGeometry.cellBands[index]
            androidx.compose.runtime.withFrameNanos { }
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == band }) gridState.scrollToItem(band)
            androidx.compose.runtime.withFrameNanos { }
            if (inputMode == androidx.compose.ui.input.InputMode.Keyboard) focusRequests[id]?.requestFocus()
        }
    }
    val positions = remember { mutableMapOf<String, androidx.compose.ui.layout.LayoutCoordinates>() }
    var container by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var draggedIds by remember { mutableStateOf(emptySet<String>()) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var dragSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var dragBase by remember { mutableStateOf(emptyList<String>()) }
    var dragAlbumIds by remember { mutableStateOf(emptyList<String>()) }
    var dragLayout by remember { mutableStateOf(albumGridLayout(emptyList(), 0f, 0f)) }
    var dragBands by remember { mutableStateOf(emptyList<List<Int>>()) }
    var dragContentTop by remember { mutableFloatStateOf(0f) }
    var dragSelection by remember { mutableStateOf(emptySet<String>()) }
    var dragMoved by remember { mutableStateOf(false) }
    var dropTarget by remember { mutableStateOf<String?>(null) }
    var dropAfter by remember { mutableStateOf(false) }
    val dragDensity = androidx.compose.ui.platform.LocalDensity.current
    val edgeSize = with(dragDensity) { 64.dp.toPx() }
    val maxScrollSpeed = with(dragDensity) { 720.dp.toPx() }
    val dropDeadZone = with(dragDensity) { 8.dp.toPx() }
    fun previewOrder(next: List<String>) {
        if (next == dragOrder) return
        // LazyColumn normally follows the first item's key; a reorder must preserve the viewport instead.
        if (grid) gridState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        else listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        dragOrder = next
    }
    fun updateDropTarget() {
        val viewport = container?.takeIf { it.isAttached }?.boundsInRoot() ?: return
        val point = Offset(dragPoint.x.coerceIn(viewport.left, viewport.right), dragPoint.y.coerceIn(viewport.top, viewport.bottom))
        // Hit the original slots, never the moving/animated cards. A stationary finger cannot reorder again.
        val candidates = if (grid) {
            fun bandAt(y: Float) = dragLayout.bandOffsets.binarySearch(y).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
            val from = bandAt((if (horizontalGrid) viewport.left else viewport.top) - dragContentTop - 2 * gridUnit - gridGap)
            val to = bandAt((if (horizontalGrid) viewport.right else viewport.bottom) - dragContentTop).coerceAtMost(dragLayout.bandOffsets.lastIndex)
            (from..to).flatMap { dragBands.getOrNull(it).orEmpty() }.map { i ->
                val bounds = dragLayout.bounds[i]
                dragAlbumIds[i] to if (horizontalGrid) androidx.compose.ui.geometry.Rect(
                    dragContentTop + bounds.top, viewport.top + gridPadding + bounds.left,
                    dragContentTop + bounds.bottom, viewport.top + gridPadding + bounds.right)
                else bounds.translate(Offset(viewport.left + gridPadding, dragContentTop))
            }.filter { it.second.overlaps(viewport) }
        } else listState.layoutInfo.visibleItemsInfo.mapNotNull { slot ->
            val top = viewport.top - listState.layoutInfo.viewportStartOffset + slot.offset
            val left = viewport.left - listState.layoutInfo.viewportStartOffset + slot.offset
            dragAlbumIds.getOrNull(slot.index)?.let { id -> id to androidx.compose.ui.geometry.Rect(
                if (horizontal) left else viewport.left, if (horizontal) viewport.top else top,
                if (horizontal) left + slot.size else viewport.right, if (horizontal) viewport.bottom else top + slot.size) }
        }
        if (candidates.any { it.first in draggedIds && it.second.contains(point) }) {
            dropTarget = null; previewOrder(dragBase); return
        }
        val target = candidates.filterNot { it.first in draggedIds }.minByOrNull { (_, bounds) ->
            val dx = point.x - point.x.coerceIn(bounds.left, bounds.right)
            val dy = point.y - point.y.coerceIn(bounds.top, bounds.bottom)
            dx * dx + dy * dy
        }
        if (target == null || target.first in draggedIds) {
            dropTarget = null; previewOrder(dragBase)
            return
        }
        val (id, bounds) = target
        if (dropTarget != null && id != dropTarget && !bounds.inflate(-dropDeadZone.coerceAtMost(minOf(bounds.width, bounds.height) / 4f)).contains(point)) return
        // Dropping anywhere on a card adopts its original slot. Splitting small covers in
        // invisible before/after zones made an otherwise identical drop land a slot early.
        val after = dragBase.indexOf(id) >= dragBase.indexOfFirst { it in draggedIds }
        if (id == dropTarget && after == dropAfter) return
        dropTarget = id; dropAfter = after
        previewOrder(moveAlbumBlock(dragBase, draggedIds, id, after))
    }
    LaunchedEffect(dragged, grid, horizontal, horizontalGrid) {
        if (dragged == null) return@LaunchedEffect
        var previousFrame = android.os.SystemClock.uptimeMillis()
        while (true) {
            kotlinx.coroutines.delay(16)
            val frame = android.os.SystemClock.uptimeMillis()
            val seconds = ((frame - previousFrame) / 1000f).coerceAtMost(.05f)
            previousFrame = frame
            if (!dragMoved) continue
            val bounds = container?.takeIf { it.isAttached }?.boundsInRoot() ?: continue
            updateDropTarget()
            val start = if (horizontal || horizontalGrid) bounds.left else bounds.top
            val end = if (horizontal || horizontalGrid) bounds.right else bounds.bottom
            val point = if (horizontal || horizontalGrid) dragPoint.x else dragPoint.y
            val edge = edgeSize.coerceAtMost((end - start) / 3f)
            val speed = when {
                point < start + edge -> -((start + edge - point) / edge).coerceIn(0f, 1f)
                point > end - edge -> ((point - end + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            } * maxScrollSpeed
            if (speed != 0f) {
                if (grid) dragContentTop -= gridState.scrollBy(speed * seconds) else listState.scrollBy(speed * seconds)
            }
        }
    }
    fun stopDrag(save: Boolean) {
        try {
            if (save && dragMoved && dragOrder != dragBase) {
                dragOrder?.let { next ->
                    if (next != savedOrder) { app.preferences.reorderAlbums(next); app.sounds.play("reorder") }
                }
                vm.selectAlbums(if (draggedIds.size == 1) emptySet() else draggedIds)
            } else {
                vm.selectAlbums(dragSelection)
                if (save) dragged?.let { app.session.openAlbumOptions(it) }
            }
        } finally {
            if (!save && !grid) listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            dragged = null; draggedIds = emptySet(); dragOrder = null; dragBase = emptyList()
            dragAlbumIds = emptyList(); dragBands = emptyList()
            dropTarget = null; dragMoved = false
        }
    }
    @Composable fun card(album: AlbumEntity, placement: Offset? = null, carouselLabel: Boolean = true) {
        val animatedOffset = remember(album.id) { tileOffsets.getOrPut(album.id) { androidx.compose.animation.core.Animatable(placement ?: Offset.Zero, Offset.VectorConverter) } }
        LaunchedEffect(placement, prefs.reducedMotion) {
            if (placement != null) {
                if (prefs.reducedMotion || dragged == null) animatedOffset.snapTo(placement) else animatedOffset.animateTo(placement, tween(160))
            }
        }
        val tileFocus = remember(album.id) { focusRequests.getOrPut(album.id) { androidx.compose.ui.focus.FocusRequester() } }
        val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        LaunchedEffect(hovered) { if (hovered && dragged == null) { app.video.browsePreview(null); app.session.previewAlbum(album.id) } }
        var coordinates by remember(album.id) { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
        DisposableEffect(album.id) { onDispose { if (positions[album.id] === coordinates) positions.remove(album.id) } }
        LaunchedEffect(inputMode, coordinates, grid, anchor) {
            if (!initialFocusRequested && album.id == anchor && coordinates?.isAttached == true &&
                inputMode == androidx.compose.ui.input.InputMode.Keyboard) { initialFocusRequested = true; tileFocus.requestFocus() }
        }
        var focused by remember(album.id) { mutableStateOf(false) }
        val chosen = album.id in selected
        val active = if (dragged != null) dragged == album.id else if (focusedAlbum != null) focusedAlbum == album.id else focused || anchor == album.id
        // Translate the whole tile outside animateContentSize's clip, including its border.
        Surface(Modifier.graphicsLayer {
            alpha = if (album.id in draggedIds) .18f else 1f
            if (placement != null) { translationX = animatedOffset.value.x - placement.x; translationY = animatedOffset.value.y - placement.y }
        }.hoverable(hover).then(if (carousel) Modifier else Modifier.animateContentSize(tween(if (prefs.reducedMotion || dragged != null) 0 else 260))).fillMaxWidth().onGloballyPositioned { coordinates = it; positions[album.id] = it }.focusRequester(tileFocus).testTag("album:${album.id}").onFocusChanged { focused = it.isFocused; if (it.isFocused && (dragged == null || dragged == album.id)) { if (focusedAlbum != null && focusedAlbum != album.id) app.video.browsePreview(null); focusedAlbum = album.id; if (controllerFocusTarget != album.id) preferredColumn = geometry[currentAlbumIds.indexOf(album.id).coerceAtLeast(0)].center; controllerFocusTarget = null; val previous = app.session.previewAlbumId; app.sounds.navigation("album:${album.id}"); vm.albumAnchor = album.id; app.session.previewAlbum(album.id); if (grid) {
                    val from = geometry.getOrNull(currentAlbumIds.indexOf(previous))
                    val to = geometry.getOrNull(currentAlbumIds.indexOf(album.id))
                    app.session.gridPreviewDirection = if (from == null || to == null) androidx.compose.ui.unit.IntOffset.Zero
                        else if (from.row != to.row) androidx.compose.ui.unit.IntOffset(0, (to.row - from.row).coerceIn(-1, 1))
                        else androidx.compose.ui.unit.IntOffset((to.center - from.center).coerceIn(-1, 1), 0)
                } } }
.semantics { role = Role.Button; onClick { choose(album); true }; onLongClick { vm.selectAlbums(vm.selectedAlbums.value + album.id); true } }
            .onKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER)) {
                    if (key.action == android.view.KeyEvent.ACTION_UP) choose(album)
                    true
                } else false
            }.focusable(), shape = if (grid && columns >= 5) RoundedCornerShape(5.dp) else GalleryShape,
            border = BorderStroke(if (active && (selected.isEmpty() || dragged != null) || chosen) 3.dp else 1.dp,
                if (active && dragged != null) GalleryFocus else if (chosen) selectionColor else if (active && selected.isEmpty()) GalleryFocus else MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface) {
            AlbumTileContent(album, data, app.catalog, prefs, LocalAlbumTileGrid.current, columns, chosen, selectionColor, selected.isNotEmpty(), carouselLabel = carouselLabel) { choose(album) }
        }
    }
    val currentAlbums by rememberUpdatedState(albums)
    Box(Modifier.fillMaxSize().testTag("album-reorder-surface").onGloballyPositioned { container = it }.pointerInput(query, grid, columns, horizontal, horizontalGrid, carousel) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val rootPoint = container?.localToRoot(down.position) ?: down.position
                    val album = currentAlbums.firstOrNull { item -> positions[item.id]?.takeIf { it.isAttached }?.boundsInRoot()?.contains(rootPoint) == true } ?: return@awaitEachGesture
                    app.video.browsePreview(null)
                    focusedAlbum = album.id
                    vm.albumAnchor = album.id
                    app.session.previewAlbum(album.id)
                    val held = awaitLongPressOrCancellation(down.id)
                    if (held == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id && it.changedToUpIgnoreConsumed() }
                        if (up != null && !up.isConsumed) {
                            if (up.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) app.session.openAlbumOptions(album.id)
                            else choose(album)
                        }
                    } else {
                        val coords = positions[album.id]?.takeIf { it.isAttached }
                        if (coords != null) {
                            dragSelection = vm.selectedAlbums.value
                            dragAlbumIds = currentAlbumIds.toList()
                            dragLayout = albumGridLayout(currentGeometry, gridUnit, gridGap)
                            dragBands = dragLayout.bands
                            gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { first ->
                                val index = dragBands.getOrNull(first.index)?.firstOrNull()
                                val bounds = index?.let { positions[dragAlbumIds[it]]?.takeIf { coords -> coords.isAttached }?.boundsInRoot() }
                                dragContentTop = if (bounds != null) (if (horizontalGrid) bounds.left else bounds.top) - dragLayout.bounds[index!!].top
                                    else (container?.boundsInRoot()?.let { if (horizontalGrid) it.left else it.top } ?: 0f) - gridState.layoutInfo.viewportStartOffset + (if (horizontalGrid) first.offset.x else first.offset.y) - (gridLayout.bandOffsets.getOrNull(first.index) ?: 0f)
                            }
                            dragged = album.id; draggedIds = dragSelection + album.id; dragMoved = false
                            dragBase = (app.preferences.albumOrder.value + currentAlbumIds).distinct()
                            dragOrder = dragBase
                            dragPoint = container?.localToRoot(held.position) ?: held.position; grabOffset = dragPoint - coords.localToRoot(Offset.Zero); dragSize = coords.size
                            focusJob?.cancel(); controllerFocusTarget = null
                            focusManager.clearFocus(force = true)
                            focusedAlbum = album.id; vm.albumAnchor = album.id
                            app.video.browsePreview(null); app.session.previewAlbum(album.id)
                            val startPoint = dragPoint
                            var completed = false
                            try {
                                // Claim movement before the lazy container can start scrolling.
                                held.consume()
                                while (true) {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == held.id } ?: break
                                    if (change.isConsumed) break
                                    if (!change.pressed) { change.consume(); completed = true; break }
                                    dragPoint = container?.localToRoot(change.position) ?: change.position
                                    change.consume()
                                    if (!dragMoved && (dragPoint - startPoint).getDistance() > viewConfiguration.touchSlop) dragMoved = true
                                    if (dragMoved) updateDropTarget()
                                }
                            } finally { stopDrag(completed) }
                        }
                    }
                }
            }) {
    if (carousel && app.session.isSingleScreen) {
        val cover = (focusedAlbum ?: anchor)?.let(data.albumsById::get)?.takeUnless { data.albumLocked(it.id) }?.let(data::cover)
        if (cover != null) MediaThumbnail(cover, data, app.catalog, prefs,
            Modifier.matchParentSize().graphicsLayer { alpha = .2f }, scale = androidx.compose.ui.layout.ContentScale.Crop)
    }
    if (albums.isEmpty() && query.isNotBlank()) {
        EmptyGallery(localized(R.string.text_no_albums_found_18010c, "Nessun album trovato"), localized(R.string.text_try_another_name_or_clear_the_search_0cceaf, "Prova un altro nome o cancella la ricerca."),
            R.drawable.ph_magnifying_glass, localized(R.string.text_clear_search_3664fa, "Cancella ricerca")) { vm.search("") }
    } else GalleryGridFocus { androidx.compose.animation.Crossfade(grid, animationSpec = tween(if (prefs.reducedMotion) 0 else 260), label = "album-view") { tileGrid ->
    if (tileGrid && horizontalGrid) androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(GridCells.Fixed(1), Modifier.fillMaxSize().testTag("albums-horizontal-grid"), state = gridState,
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(if (columns >= 5) 3.dp else 10.dp)) {
        items(count = bands.size) { band ->
            BoxWithConstraints(Modifier.fillMaxHeight()) {
                val gap = if (columns >= 5) 3.dp else 10.dp
                val unit = (maxHeight - gap * (columns - 1)) / columns
                val rows = bandGeometry.bandRows[band]
                Box(Modifier.fillMaxHeight().width(unit * rows + gap * (rows - 1))) {
                    bands[band].forEach { i -> key(albums[i].id) {
                        val cell = geometry[i]
                        Box(Modifier.offset(x = (unit + gap) * (cell.row - bandGeometry.bandStarts[band]), y = (unit + gap) * (cell.start / 2))
                            .width(unit * cell.rows + gap * (cell.rows - 1))) {
                            CompositionLocalProvider(LocalAlbumTileGrid provides true, LocalAnimatedAlbumIds provides animatedAlbumIds) { card(albums[i]) }
                        }
                    } }
                }
            }
        }
    } else if (tileGrid) LazyVerticalGrid(GridCells.Fixed(1), Modifier.testTag("albums-grid"), state = gridState, contentPadding = PaddingValues(start = if (columns >= 6) 8.dp else 16.dp, end = if (columns >= 6) 8.dp else 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(if (columns >= 5) 3.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(if (columns >= 5) 3.dp else 10.dp)) {
        items(count = bands.size) { band ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = if (columns >= 5) 3.dp else 10.dp
                val unit = (maxWidth - gap * (columns - 1)) / columns
                val bandCells = bands[band]
                val rows = bandGeometry.bandRows[band]
                Box(Modifier.fillMaxWidth().height(unit * rows + gap * (rows - 1))) {
                    bandCells.forEach { i ->
                        key(albums[i].id) {
                            val cell = geometry[i]
                            Box(Modifier.offset(x = (unit + gap) * (cell.start / 2), y = (unit + gap) * (cell.row - bandGeometry.bandStarts[band]))
                                .width(unit * cell.rows + gap * (cell.rows - 1))) {
                                androidx.compose.runtime.CompositionLocalProvider(LocalAlbumTileGrid provides tileGrid, LocalAnimatedAlbumIds provides animatedAlbumIds) { card(albums[i], gridLayout.bounds.getOrNull(i)?.topLeft) }
                            }
                        }
                    }
                }
            }
        }
    } else if (carousel) BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = if (horizontal) minOf((maxHeight - 64.dp) / 1.5f, maxWidth / columns.coerceAtLeast(2))
            else minOf((maxWidth - 32.dp) / 1.5f, maxHeight / columns.coerceAtLeast(2))
        val slot = unit * 1.5f
        val content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            items(albums, key = { it.id }) { album ->
                val scale by androidx.compose.animation.core.animateFloatAsState(if (album.id == (focusedAlbum ?: anchor)) 1.5f else 1f,
                    tween(if (prefs.reducedMotion) 0 else 180), label = "carousel-focus")
                if (horizontal) Box(Modifier.width(slot).height(slot + 40.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(unit * scale)) {
                        CompositionLocalProvider(LocalAlbumTileGrid provides true, LocalAnimatedAlbumIds provides animatedAlbumIds) { card(album) }
                    }
                } else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    Box(Modifier.width(unit * scale)) {
                        CompositionLocalProvider(LocalAlbumTileGrid provides true, LocalAnimatedAlbumIds provides animatedAlbumIds) { card(album) }
                    }
                }
            }
        }
        if (horizontal) LazyRow(Modifier.fillMaxSize().testTag("albums-horizontal"), state = listState,
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
        else LazyColumn(Modifier.fillMaxSize().testTag("albums-vertical-carousel"), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start, content = content)
    } else LazyColumn(Modifier.testTag("albums-list"), state = listState, contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.id }) { album ->
            Box(Modifier.animateItem(placementSpec = if (dragged != null && !prefs.reducedMotion) tween(160) else null)) { androidx.compose.runtime.CompositionLocalProvider(LocalAlbumTileGrid provides tileGrid, LocalAnimatedAlbumIds provides animatedAlbumIds) { card(album) } }
        }
    }
    }
    }
    dragged?.let { id ->
        val album = data.albumsById[id]
        val origin = container?.takeIf { it.isAttached }?.localToRoot(Offset.Zero) ?: Offset.Zero
        val density = androidx.compose.ui.platform.LocalDensity.current
        if (album != null) Surface(Modifier.testTag("album-drag-preview").offset { androidx.compose.ui.unit.IntOffset((dragPoint.x - grabOffset.x - origin.x).toInt(), (dragPoint.y - grabOffset.y - origin.y).toInt()) }
            .size(with(density) { dragSize.width.toDp() }, with(density) { dragSize.height.toDp() }),
            shape = GalleryShape, shadowElevation = 16.dp, border = BorderStroke(3.dp, GalleryFocus)) {
            Box { AlbumTileContent(album, data, app.catalog, prefs, grid || carousel, columns, draggedIds.size > 1, selectionColor, false) {}
                if (draggedIds.size > 1) Text("${draggedIds.size}", Modifier.align(Alignment.TopEnd).padding(8.dp).background(MaterialTheme.colorScheme.surface, GalleryShape).padding(6.dp)) }
        }
    }
    }
    val command = app.session.albumCommand
    LaunchedEffect(command) {
        val (kind, frozen) = command ?: return@LaunchedEffect
        if (app.session.albumCommand !== command) return@LaunchedEffect
        app.session.albumCommand = null
        vm.albumMenu(false)
        when (kind) {
            "COVER" -> coverAlbum = frozen.singleOrNull()
            "SIZE1", "SIZE2" -> { if (!prefs.albumCarousel) { if (vm.route.value.tab == "VIDEO") app.preferences.videoAlbumSize(frozen, if (kind == "SIZE1") 2 else 4) else app.preferences.albumSize(frozen, if (kind == "SIZE1") 2 else 4) }; vm.selectAlbums(emptySet()) }
            "LOCK" -> { pinAlbums = frozen; pinMode = if (frozen.all { it in data.protectedAlbumIds }) "REMOVE" else "PROTECT" }
            "HIDE" -> hide = visible.filter { it.id in frozen }
            "MOVE", "COPY" -> {
                preparing = true
                task {
                    try {
                        val targets = app.albums.albumContents(frozen)
                        require(targets.isNotEmpty()) { localized(R.string.text_the_selected_albums_contain_no_available_media_d768a1, "Gli album selezionati non contengono media disponibili.") }
                        val snapshot = app.catalog.readSnapshot()
                        val folders = mutableMapOf<String, AlbumEntity>()
                        for (target in targets) {
                            var id: String? = target.albumId
                            while (id != null && id !in folders) {
                                val folder = snapshot.albumsById[id] ?: error(localized(R.string.text_folder_unavailable_1f6e37, "Cartella non disponibile"))
                                folders[id] = folder
                                if (id in frozen) break
                                id = folder.parentId
                            }
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { request = FileActionRequest(kind, targets, albumFolders = folders.values.toList()) }
                    } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main) { preparing = false } }
                }
            }
        }
    }

}

internal class AlbumDialogsState {
    var hide by mutableStateOf<List<AlbumEntity>?>(null)
    var request by mutableStateOf<FileActionRequest?>(null)
    var cover by mutableStateOf<String?>(null)
    var pinMode by mutableStateOf<String?>(null)
    var pinAlbums by mutableStateOf(emptySet<String>())
}

@Composable
internal fun AlbumDialogs(activity: PanelActivity, app: GalleryApp, data: CatalogSnapshot, prefs: GalleryPreferences, task: (suspend () -> Unit) -> Unit) {
    val vm = app.session.libraryModel() ?: activity.windowModel
    var hide by app.session.albumDialogs::hide
    var request by app.session.albumDialogs::request
    var coverAlbum by app.session.albumDialogs::cover
    var pinMode by app.session.albumDialogs::pinMode
    var pinAlbums by app.session.albumDialogs::pinAlbums
    coverAlbum?.let { id ->
        LocalMediaPicker(app, data, prefs, "album-cover:$id", data.albumsById[id]?.name.orEmpty(), "MEDIA", emptySet(), true,
            onSave = { app.catalog.setAlbumCover(id, it.singleOrNull()) }, done = { coverAlbum = null; vm.selectAlbums(emptySet()) }, dismiss = { coverAlbum = null }, albumId = id)
    }
    hide?.let { frozen -> GalleryAlertDialog(onDismissRequest = { hide = null }, modifier = Modifier.fillMaxWidth(.94f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { GalleryDialogTitle(R.drawable.ph_eye_slash, localized(R.string.text_hide_1_s_albums_359955, "Nascondere %1\$s album?", frozen.size)) },
        text = { Text(frozen.joinToString("\n") { it.name } + localized(R.string.text_n_nsubfolders_will_also_be_excluded_files_remain_a5552e, "\n\nAnche le sottocartelle saranno escluse. I file restano intatti.")) },
        confirmButton = { TextButton({ task { app.catalog.hideAlbums(frozen.map { it.id }, true) }; hide = null; vm.selectAlbums(emptySet()) }) { Text(localized(R.string.text_hide_12292a, "Nascondi")) } },
        dismissButton = { TextButton({ hide = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }) }
    request?.let { frozen -> FileActionDialog(frozen, data, app, prefs, activity, task,
        accepted = { request = null; vm.selectAlbums(emptySet()) }, dismiss = { request = null }) }
    pinMode?.let { mode -> AlbumPinDialog(app.catalog.protection, mode, pinAlbums, dismiss = { pinMode = null }, accepted = { pinMode = null; vm.selectAlbums(emptySet()) }) }

}

@Composable
fun AlbumChildren(activity: PanelActivity, data: CatalogSnapshot, parent: String, prefs: GalleryPreferences) {
    val currentRoute by activity.windowModel.route.collectAsStateWithLifecycle()
    val children = (if (prefs.nestedAlbums || currentRoute.tab == "VIDEO") data.childAlbums(parent, nested = true) else emptyList<AlbumEntity>()).filter { prefs.showEmptyFolders || it.id in data.albumsWithVisibleMedia }
        .sortedWith(Comparator { a, b -> MediaNames.natural.compare(a.name, b.name) })
    if (children.isEmpty()) return
    val catalog = (activity.application as GalleryApp).catalog
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(children, key = { it.id }) { album ->
            Surface(Modifier.width(220.dp).heightIn(min = 88.dp).onFocusChanged { if (it.isFocused) (activity.application as GalleryApp).session.previewAlbum(album.id) }
                .clickable { (activity.application as GalleryApp).session.previewAlbum(album.id); if (data.albumLocked(album.id)) (activity.application as GalleryApp).session.albumUnlockRequest = album.id else activity.windowModel.go(if (activity.windowModel.route.value.tab == "VIDEO") "VIDEO" else "ALBUM", album = album.id) }, shape = GalleryShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AlbumCover(album, data, catalog, prefs, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) { Text(album.name, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                        if (!album.hidden && !data.albumLocked(album.id)) Text(localized(R.string.text_folder_1_s_media_8ef7d0, "Cartella · %1\$s media", data.visibleByAlbum[album.id].orEmpty().size), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
fun HiddenScreen(data: CatalogSnapshot, catalog: Catalog, task: (suspend () -> Unit) -> Unit) {
    val hidden = data.albums.filter { it.hidden || it.systemExcluded }
    if (hidden.isEmpty()) { EmptyGallery(localized(R.string.text_no_hidden_albums_0da9d4, "Nessun album nascosto"), localized(R.string.text_hidden_albums_keep_their_favorites_and_reference_848396, "Gli album nascosti mantengono i loro Preferiti e riferimenti, senza cambiare i file."), R.drawable.ph_eye_slash); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(hidden, key = { it.id }) { album ->
            Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GalleryIcon(R.drawable.ph_eye_slash, null)
                    Column(Modifier.weight(1f)) {
                        Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(album.path, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (album.systemExcluded) Text(localized(R.string.text_system_nomedia_exclusion_remove_it_externally_to_320c3c, "Esclusione .nomedia del sistema. Rimuovila esternamente per includere la cartella."), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!album.systemExcluded) TextButton({ task { catalog.hideAlbums(listOf(album.id), false) } }) { Text(localized(R.string.text_show_again_5cb1ec, "Mostra di nuovo")) }
                }
            }
        }
    }
}

@Composable
internal fun DisplayControls(activity: PanelActivity) {
    val session = activity.session
    val others = session.screens.filter { it.key != activity.screenKey }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (session.screens.size > 1) GallerySettingSwitch(localized(R.string.text_open_on_two_screens_at_startup_7c2cb7, "Apri su due schermi all'avvio"),
            others.size == 1 && (session.startupAutomatic || session.startupPair == setOf(activity.screenKey, others.single().key)),
            Modifier.testTag("startup-dual"), enabled = others.size == 1) { enabled ->
                if (enabled) session.rememberStartupPair(activity, true) else session.single(activity)
            }
        if (session.isSingleScreen) {
            GallerySettingSwitch(localized(R.string.text_use_only_this_screen_f859c0, "Usa solo questo schermo"), true, Modifier.testTag("single-screen-active"), enabled = session.screens.size > 1) {
                others.firstOrNull()?.let { target -> session.expand(activity, target); activity.recreate() }
            }
            GallerySettingSwitch(localized(R.string.single_screen_landscape, "Forza orientamento orizzontale"), session.singleScreenLandscape, Modifier.testTag("single-screen-landscape")) { session.singleScreenLandscape(it) }
        } else GalleryButton(localized(R.string.text_use_only_this_screen_f859c0, "Usa solo questo schermo"), Modifier.fillMaxWidth()) { session.single(activity) }
    }
}

@Composable
fun DisplayScreen(activity: PanelActivity) {
    LazyColumn(Modifier.testTag("display-list"), contentPadding = PaddingValues(16.dp)) { item { DisplayControls(activity) } }
}

@Composable
fun MediaMenu(item: MediaEntity, data: CatalogSnapshot, app: GalleryApp, task: (suspend () -> Unit) -> Unit,
              compare: (MediaEntity) -> Unit, fileAction: (String) -> Unit, dismiss: () -> Unit,
              external: (ExternalAction, ShareCopyOptions?) -> Unit, editMedia: () -> Unit, insideAlbum: Boolean = false, displayOptions: () -> Unit = {}) {
    var collection by remember(item.id) { mutableStateOf(false) }
    var details by remember(item.id) { mutableStateOf(false) }
    var musicArtwork by remember(item.id) { mutableStateOf(false) }
    var videoCover by remember(item.id) { mutableStateOf(false) }
    val coverScope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    var flags by remember(item.id, item.uri) { mutableStateOf<Int?>(null) }
    var canRename by remember(item.id, item.uri) { mutableStateOf(false) }
    LaunchedEffect(item.id, item.uri) {
        if (data.usable(item)) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val documents = FileDocuments(app)
                val capabilities = documents.stat(app.catalog.uri(item, data)).flags
                val parent = data.albumsById[item.albumId]?.documentUri?.let(android.net.Uri::parse)
                val create = parent != null && documents.stat(parent).flags and android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { flags = capabilities; canRename = create }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { flags = 0 } }
        }
    }
    if (collection) {
        CollectionTargetDialog(app, data, listOf(item.id), false, done = dismiss, dismiss = { collection = false })
        return
    }
    if (videoCover) { VideoCoverDialog(app, item, data) { videoCover = false }; return }
    if (musicArtwork) { MusicArtworkDialog(app, data, prefs, ArtworkOrders.track(item.id), item.title ?: item.name, dismiss); return }
    if (details) { MediaDetailsDialog(item, data, app) { details = false }; return }
    GallerySideSheet(dismiss) {
        Text(item.title ?: item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("preview-actions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.kind in setOf("IMAGE", "GIF")) {
                GallerySettingSwitch(localized(R.string.preview_nearest, "Pixel netti (Nearest-neighbor)"), prefs.previewNearest,
                    Modifier.testTag("preview-nearest-filter")) { value -> task { app.preferences.update { it.copy(previewNearest = value) } } }
            }
            val album = data.albumsById[item.albumId]
            val canSetCover = insideAlbum && album != null && item.kind in setOf("IMAGE", "GIF", "VIDEO") &&
                data.visibleByAlbum[album.id].orEmpty().any { it.id == item.id } && !data.albumLocked(album.id)
            if (canSetCover) GalleryButton(localized(R.string.album_set_cover, "Imposta come copertina"), Modifier.fillMaxWidth(), enabled = data.usable(item)) {
                task { app.catalog.setAlbumCover(album.id, item.id); dismiss() }
            }
            GalleryButton(localized(R.string.text_add_to_a_collection_c19880, "Aggiungi a una raccolta"), Modifier.fillMaxWidth()) { collection = true }
            GalleryButton(localized(R.string.text_share_628ff2, "Condividi…"), Modifier.fillMaxWidth(), enabled = data.usable(item)) { external(ExternalAction.SHARE, null) }
            GalleryButton(localized(R.string.text_open_with_dae8f1, "Apri con…"), Modifier.fillMaxWidth(), enabled = data.usable(item)) { external(ExternalAction.OPEN, null) }
            if (item.kind == "IMAGE") GalleryButton(localized(R.string.text_set_as_wallpaper_c5056f, "Imposta come sfondo…"), Modifier.fillMaxWidth(), enabled = data.usable(item)) { external(ExternalAction.WALLPAPER, null) }
            if (item.kind == "IMAGE") GalleryButton(localized(R.string.text_edit_image_cab524, "Modifica immagine…"), Modifier.fillMaxWidth(), enabled = data.usable(item)) { editMedia() }
            if (item.kind == "GIF") GalleryButton(localized(R.string.text_export_first_frame_68b9a6, "Esporta primo fotogramma…"), Modifier.fillMaxWidth(), enabled = data.usable(item)) { editMedia() }
            val currentVideo = app.video.player?.takeIf {
                item.kind == "VIDEO" && !item.convertedGif && app.video.mediaId == item.id &&
                    app.video.liveChannel == null && app.video.surface in setOf("viewer", "pip")
            }
            if (currentVideo != null) GalleryButton(localized(R.string.video_cover_current_frame, "Imposta fotogramma copertina"),
                Modifier.fillMaxWidth().testTag("video-cover-current-frame"), enabled = data.usable(item), icon = R.drawable.ph_film_reel) {
                // Capture the position before the asynchronous menu action can advance playback.
                val position = currentVideo.currentPosition.coerceAtLeast(0).let { time -> item.duration?.let { time.coerceAtMost(it) } ?: time }
                coverScope.launch {
                    runCatching { app.catalog.setVideoCoverFrame(item, position) }
                        .onSuccess { app.sounds.play("save"); dismiss() }
                        .onFailure { app.session.fail(it.message ?: localized(R.string.text_unavailable_ce1391, "Non disponibile")) }
                }
            }
            if (item.kind == "VIDEO") GalleryButton(localized(R.string.video_cover_frame, "Scegli fotogramma copertina"), Modifier.fillMaxWidth(), enabled = data.usable(item)) { videoCover = true }
            if (item.kind == "VIDEO") GalleryButton(localized(R.string.text_edit_video_3e8c8f, "Modifica video…"), Modifier.fillMaxWidth(), enabled = data.usable(item)) { editMedia() }
            if (item.kind == "AUDIO") GalleryButton(localized(R.string.text_edit_audio_3c820b, "Modifica audio…"), Modifier.fillMaxWidth(), enabled = data.usable(item)) { editMedia() }
            if (flags != null && data.usable(item)) {
                GalleryButton(localized(R.string.text_copy_to_b8ead5, "Copia in…"), Modifier.fillMaxWidth()) { fileAction("COPY") }
                val canMove = flags!! and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
                GalleryButton(localized(R.string.text_move_to_36f208, "Sposta in…"), Modifier.fillMaxWidth(), enabled = canMove) { fileAction("MOVE") }
                if (!canMove) Text(localized(R.string.text_the_source_does_not_allow_removing_this_file_7c5fbb, "La sorgente non consente di rimuovere questo file."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (flags!! and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) {
                    if (canRename) GalleryButton(localized(R.string.text_rename_0771a2, "Rinomina…"), Modifier.fillMaxWidth()) { fileAction("RENAME") }
                    TrashActionButton(localized(R.string.text_move_to_trash_f91742, "Sposta nel cestino…"), Modifier.fillMaxWidth()) { fileAction("TRASH") }
                    if (prefs.directDeleteEnabled) GalleryButton(localized(R.string.direct_delete, "Elimina direttamente"), Modifier.fillMaxWidth()) { fileAction("DELETE_DIRECT") }
                }
            }
            if (item.kind == "AUDIO") {
                GalleryButton(localized(R.string.text_track_artwork_50bfbb, "Copertina del brano"), Modifier.fillMaxWidth()) { musicArtwork = true }
                // Queue actions are intentionally hidden until their interaction is reviewed.
                /* GalleryButton(localized(R.string.text_play_next_504580, "Riproduci dopo"), Modifier.fillMaxWidth()) { app.music.enqueue(item.id, true); dismiss() }
                GalleryButton(localized(R.string.text_add_to_queue_363178, "Aggiungi in coda"), Modifier.fillMaxWidth()) { app.music.enqueue(item.id); dismiss() } */
            }
            GalleryButton(localized(R.string.text_details_1a45b0, "Dettagli"), Modifier.fillMaxWidth()) { details = true }
            GalleryButton(localized(R.string.media_display_options, "Opzioni di visualizzazione"), Modifier.fillMaxWidth().testTag("media-display-options")) { dismiss(); displayOptions() }
        }
    }
}

@Composable
internal fun AlbumOptionsDialog(app: GalleryApp, data: CatalogSnapshot, ids: Set<String>, dismiss: () -> Unit) {
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    fun command(kind: String) { app.session.albumCommand = kind to ids.toSet(); dismiss() }
    GallerySideSheet(dismiss) {
        Text(localized(R.string.text_1_s_selected_albums_5ebbfb, "%1\$s album selezionati", ids.size), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ids.size == 1) GalleryButton(localized(R.string.album_set_cover, "Imposta come copertina"), Modifier.fillMaxWidth(), enabled = !data.albumLocked(ids.single())) { command("COVER") }
            if (!prefs.albumCarousel) {
                Text(localized(R.string.album_tile_size, "Dimensione album"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GalleryButton("1×", Modifier.weight(1f)) { command("SIZE1") }; GalleryButton("2×", Modifier.weight(1f)) { command("SIZE2") } }
            }
            val protected = ids.all { it in data.protectedAlbumIds }
            GalleryButton(localized(if (protected) R.string.album_unlock_action else R.string.album_lock_action, ""), Modifier.fillMaxWidth()) { command("LOCK") }
            GalleryButton(localized(R.string.text_hide_12292a, "Nascondi"), Modifier.fillMaxWidth()) { command("HIDE") }
            GalleryButton(localized(R.string.text_move_f44eb9, "Sposta"), Modifier.fillMaxWidth()) { command("MOVE") }
            GalleryButton(localized(R.string.text_copy_ebd162, "Copia"), Modifier.fillMaxWidth()) { command("COPY") }
        }
    }
}
