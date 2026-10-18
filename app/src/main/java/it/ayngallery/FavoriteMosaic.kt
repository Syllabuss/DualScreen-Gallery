package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun FavoriteMosaic(activity: PanelActivity, data: CatalogSnapshot, items: List<MediaEntity>,
    prefs: GalleryPreferences, multi: Boolean, selected: Set<String>, activate: (MediaEntity) -> Unit,
    actions: (MediaEntity) -> Unit, activeScene: Boolean = true, renderedSortRevision: Long? = null) {
    val app = activity.application as GalleryApp
    val columns by activity.windowModel.mediaColumns("FAVORITE").collectAsStateWithLifecycle()
    val initialDimensionsReady by produceState(items.take(columns * 4).all { it.kind == "AUDIO" || mediaCachedAspect(it) != null }, items.take(columns * 4).map { "${it.id}/${it.modified}/${it.size}" }) {
        kotlinx.coroutines.withTimeoutOrNull(5000) { kotlinx.coroutines.coroutineScope {
            items.take(columns * 4).filter { it.kind != "AUDIO" }.map { item ->
                launch { mediaPixelResolution(item, data, app.catalog) }
            }.forEach { it.join() }
        }
        }
        value = true
    }
    if (!initialDimensionsReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    val state = rememberLazyStaggeredGridState()
    val requestedSortRevision by activity.windowModel.sortReset.collectAsStateWithLifecycle()
    val sortRevision = renderedSortRevision ?: requestedSortRevision
    var scrolledRevision by rememberSaveable { mutableLongStateOf(0L) }
    val sortPending = activeScene && sortRevision != scrolledRevision
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val active by activity.isStartedFlow.collectAsState()
    val inputMode = LocalInputModeManager.current.inputMode
    val context = remember(items) { items.map { it.id } }
    val focusId = if (sortPending) items.first().id else app.session.selectedMediaId?.takeIf { it in context } ?: items.first().id
    val visibleGifs by remember(state, items, prefs.gifLimit) { derivedStateOf {
        animatedGifIds(state.layoutInfo.visibleItemsInfo.mapNotNull { cell -> items.getOrNull(cell.index)?.takeIf { media -> media.kind == "GIF" }?.let { it.id to cell.size.width.toLong() * cell.size.height } }, prefs.gifLimit)
    } }
    LaunchedEffect(focusId, activeScene, context, sortRevision) {
        val index = context.indexOf(focusId)
        if (activeScene && index >= 0 && sortRevision == activity.windowModel.sortReset.value) {
            if (sortPending) {
                if (!activity.settingsHeaderHasFocus) focusManager.clearFocus(force = true)
                state.scrollToItem(0)
            }
            app.session.updateMediaContext(context); app.session.selectMedia(focusId)
            if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
            scrolledRevision = sortRevision
        }
    }
    OrderedLibraryFocus(activity, context, activeScene, false, sortRevision) { id ->
        focusJob?.cancel()
        app.session.updateMediaContext(context); app.session.selectMedia(id)
        focusJob = scope.launch {
            val index = context.indexOf(id)
            if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
            androidx.compose.runtime.withFrameNanos { }
            requesters[id]?.requestFocus()
        }
    }
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize().testTag("favorites-mosaic"), state,
        contentPadding = PaddingValues(if (columns >= 4) 4.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(if (columns >= 4) 3.dp else 6.dp), verticalItemSpacing = if (columns >= 4) 3.dp else 6.dp) {
        items(items, key = { it.id }) { item ->
            val requester = remember(item.id) { requesters.getOrPut(item.id) { FocusRequester() } }
            DisposableEffect(item.id) { onDispose { if (requesters[item.id] === requester) requesters.remove(item.id) } }
            var coordinates by remember(item.id) { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
            LaunchedEffect(inputMode, activeScene, focusId, sortRevision, coordinates) {
                if (activeScene && sortRevision == activity.windowModel.sortReset.value && !activity.settingsHeaderHasFocus && inputMode == InputMode.Keyboard && item.id == focusId && coordinates?.isAttached == true) requester.requestFocus()
            }
            MediaTile(item, data, app.catalog, prefs, item.id == app.session.selectedMediaId, multi, item.id in selected,
                active && activeScene && !state.isScrollInProgress && when (prefs.gifMode) { "VISIBLE" -> item.id in visibleGifs; "SELECTED" -> item.id == focusId; else -> false }, active,
                focus = { if (activeScene && sortRevision == requestedSortRevision && (!sortPending || item.id == focusId)) { if (app.session.selectedMediaId != item.id) { app.sounds.navigation("media:${item.id}"); if (item.kind != "AUDIO") app.session.previewFocusRevision += 1 }; app.session.updateMediaContext(context); app.session.selectMedia(item.id) } },
                click = { if (activeScene) { app.session.updateMediaContext(context); activate(item) } },
                actions = { if (activeScene) { app.session.updateMediaContext(context); actions(item) } },
                modifier = Modifier.onGloballyPositioned { coordinates = it }.focusRequester(requester).focusProperties { canFocus = activeScene }.testTag("media:${item.id}"), mosaic = true, compactCorners = columns >= 4)
        }
    }
}
