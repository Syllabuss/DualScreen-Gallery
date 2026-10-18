package it.ayngallery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

internal sealed class TvFavoriteRow(val key: String, val addedAt: Long) {
    class Local(val media: MediaEntity) : TvFavoriteRow("local:${media.id}", media.favoriteAddedAt)
    class Channel(val channel: TvSavedChannel) : TvFavoriteRow("tv:${channel.id}", channel.favoriteAddedAt)
}

@Composable
internal fun TvMixedFavorites(activity: PanelActivity, data: CatalogSnapshot, items: List<MediaEntity>, prefs: GalleryPreferences,
    multi: Boolean, selected: Set<String>, activate: (MediaEntity) -> Unit, actions: (MediaEntity) -> Unit, activeScene: Boolean = true, renderedSortRevision: Long? = null) {
    val app = activity.application as GalleryApp
    val tv = app.tv
    val query by activity.windowModel.query.collectAsStateWithLifecycle()
    val descending by activity.windowModel.descending.collectAsStateWithLifecycle()
    val sort by activity.windowModel.sort.collectAsStateWithLifecycle()
    val grid by activity.windowModel.mediaGrid("FAVORITE").collectAsStateWithLifecycle()
    val columns by activity.windowModel.mediaColumns("FAVORITE").collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val smallList by app.preferences.favoriteSmallList.collectAsStateWithLifecycle()
    val requestedRevision by activity.windowModel.sortReset.collectAsStateWithLifecycle()
    val revision = renderedSortRevision ?: requestedRevision
    var appliedRevision by rememberSaveable { mutableLongStateOf(0L) }
    val sortPending = revision != appliedRevision
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val route by activity.windowModel.route.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { try { tv.store.load() } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; app.session.fail(localized(R.string.tv_error_format, "Archivio TV non disponibile")) } }
    val rows = remember(items, tv.store.channels, query, descending, sort, multi, route.kind) {
        tvFavoriteRows(items, tv.store.channels, query, sort, descending, multi, route.kind)
    }
    val gridState = rememberLazyStaggeredGridState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val active by activity.isStartedFlow.collectAsStateWithLifecycle()
    val visibleGifIds by remember(rows, grid, gridState, listState, prefs.gifLimit) { derivedStateOf {
        val cells = if (grid) gridState.layoutInfo.visibleItemsInfo.map { it.index to (it.size.width.toLong() * it.size.height) }
            else listState.layoutInfo.visibleItemsInfo.map { it.index to (it.size.toLong() * it.size) }
        animatedGifIds(cells.mapNotNull { (index, pixels) -> (rows.getOrNull(index) as? TvFavoriteRow.Local)?.media?.takeIf { it.kind == "GIF" }?.let { it.id to pixels } }, prefs.gifLimit)
    } }
    LaunchedEffect(rows, tv.store.loaded, activeScene, revision) {
        if (!activeScene || !tv.store.loaded || revision != requestedRevision) return@LaunchedEffect
        val currentVisible = rows.any { row -> when (row) {
            is TvFavoriteRow.Local -> !tv.favoritePreview && app.session.selectedMediaId == row.media.id
            is TvFavoriteRow.Channel -> tv.favoritePreview && tv.selected?.let(::tvKey) == tvKey(row.channel.entry)
        } }
        if (sortPending) {
            if (grid) gridState.scrollToItem(0) else listState.scrollToItem(0)
            appliedRevision = revision
        }
        if (sortPending || !currentVisible) when (val first = rows.firstOrNull()) {
            is TvFavoriteRow.Channel -> { tv.selected = first.channel.entry; tv.favoritePreview = true }
            is TvFavoriteRow.Local -> { tv.favoritePreview = false; app.session.selectMedia(first.media.id) }
            null -> tv.favoritePreview = false
        }
    }
    fun select(entry: TvCatalogEntry) { if (activeScene) { tv.select(entry); tv.favoritePreview = true } }
    fun selectedKey() = if (tv.favoritePreview) tv.selected?.let { "tv:${tvKey(it)}" } else app.session.selectedMediaId?.let { "local:$it" }
    fun focusRow(index: Int) {
        val row = rows.getOrNull(index) ?: return
        when (row) {
            is TvFavoriteRow.Channel -> select(row.channel.entry)
            is TvFavoriteRow.Local -> { app.session.updateMediaContext(items.map { it.id }); app.session.selectMedia(row.media.id); tv.favoritePreview = false }
        }
        focusJob?.cancel()
        focusJob = scope.launch {
            if (grid) {
                if (gridState.layoutInfo.visibleItemsInfo.none { it.key == row.key }) gridState.scrollToItem(index)
            } else if (listState.layoutInfo.visibleItemsInfo.none { it.key == row.key }) listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { requesters[row.key]?.requestFocus() }
        }
    }
    DisposableEffect(activity, rows, grid, columns, activeScene) {
        val move: (PadAction) -> Boolean = { action ->
            if (!activeScene || rows.isEmpty() || activity.settingsHeaderHasFocus || action !in setOf(PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT)) false
            else {
                val index = rows.indexOfFirst { it.key == selectedKey() }.coerceAtLeast(0)
                val step = if (grid) columns else 1
                val delta = when (action) { PadAction.UP -> -step; PadAction.DOWN -> step; PadAction.LEFT -> -1; else -> 1 }
                if (action == PadAction.UP && index < step || !grid && action in setOf(PadAction.LEFT, PadAction.RIGHT)) false
                else { focusRow((index + delta).coerceIn(rows.indices)); true }
            }
        }
        val acquire: () -> Unit = { if (activeScene && !activity.settingsHeaderHasFocus && activity.focusedMediaItemId == null) focusRow(rows.indexOfFirst { it.key == selectedKey() }.coerceAtLeast(0)) }
        if (activeScene) { activity.mediaGridFocus = move; activity.ensurePadFocus = acquire }
        onDispose {
            if (activity.mediaGridFocus === move) activity.mediaGridFocus = null
            if (activity.ensurePadFocus === acquire) activity.ensurePadFocus = null
        }
    }
    @Composable fun Cell(row: TvFavoriteRow) {
        val requester = remember(row.key) { requesters.getOrPut(row.key) { FocusRequester() } }
        DisposableEffect(row.key) { onDispose { if (requesters[row.key] === requester) requesters.remove(row.key) } }
        val cellModifier = Modifier.focusRequester(requester).focusProperties { canFocus = activeScene }
        when (row) {
            is TvFavoriteRow.Local -> MediaTile(row.media, data, app.catalog, prefs, app.session.selectedMediaId == row.media.id && !tv.favoritePreview,
                multi, row.media.id in selected, active && activeScene && !(if (grid) gridState.isScrollInProgress else listState.isScrollInProgress) && when (prefs.gifMode) { "VISIBLE" -> row.media.id in visibleGifIds; "SELECTED" -> row.media.id == app.session.selectedMediaId; else -> false }, active,
                focus = { if (activeScene) { app.session.updateMediaContext(items.map { it.id }); app.session.selectMedia(row.media.id); tv.favoritePreview = false } },
                click = { tv.favoritePreview = false; activate(row.media) }, actions = { tv.favoritePreview = false; actions(row.media) },
                modifier = cellModifier.testTag("media:${row.media.id}"), list = !grid, smallList = smallList, mosaic = grid, compactCorners = grid && columns >= 4)
            is TvFavoriteRow.Channel -> Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp,
                if (tv.favoritePreview && tv.selected?.let(::tvKey) == tvKey(row.channel.entry)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = cellModifier.testTag("favorite:${row.key}").fillMaxWidth().onFocusChanged { if (it.isFocused) select(row.channel.entry) }.clickable {
                    if (tv.favoritePreview && tv.selected?.let(::tvKey) == tvKey(row.channel.entry)) tv.play(row.channel.entry, app.session.previewActivity()?.slot ?: activity.slot)
                    else select(row.channel.entry)
                }) {
                Box {
                    Column(Modifier.padding(8.dp)) {
                        TvArtwork(app, row.channel.entry, if (grid) Modifier.fillMaxWidth().aspectRatio(1.4f) else Modifier.fillMaxWidth().height(64.dp))
                        Text(row.channel.name, Modifier.fillMaxWidth().padding(end = 36.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { tv.requestFavorite(row.channel.entry) }, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).focusProperties { canFocus = false }) { TvFavoriteStar(true, Modifier.size(24.dp)) }
                }
            }
        }
    }
    if (grid) LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize(), state = gridState,
        contentPadding = PaddingValues(8.dp), verticalItemSpacing = 6.dp, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(rows, key = { it.key }) { Cell(it) }
    } else LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(rows, key = { it.key }) { Cell(it) }
    }
}


/** Local metadata keeps the same sorting semantics as every other media view. */
internal fun tvFavoriteRows(items: List<MediaEntity>, channels: List<TvSavedChannel>, query: String,
    sort: String, descending: Boolean, multi: Boolean, kind: String): List<TvFavoriteRow> {
    val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val rows = items.map { TvFavoriteRow.Local(it) } + if (multi || kind !in setOf("ALL", "VIDEO")) emptyList() else
        channels.filter { channel -> channel.favorite && words.all { channel.name.contains(it, true) } }.map { TvFavoriteRow.Channel(it) }
    fun name(row: TvFavoriteRow) = when (row) { is TvFavoriteRow.Local -> row.media.title ?: row.media.name; is TvFavoriteRow.Channel -> row.channel.name }
    fun value(row: TvFavoriteRow): Long = when (row) {
        is TvFavoriteRow.Local -> mediaSortValue(row.media, sort)
        is TvFavoriteRow.Channel -> if (sort in setOf("DATE", "ADDED", "FAVORITE_ADDED")) row.addedAt else -1L
    }
    val comparator = (if (sort == "NAME") Comparator<TvFavoriteRow> { a, b -> MediaNames.natural.compare(name(a), name(b)) }
        else compareBy<TvFavoriteRow> { value(it) }).thenBy { it.key }
    return rows.sortedWith(if (descending) comparator.reversed() else comparator)
}
