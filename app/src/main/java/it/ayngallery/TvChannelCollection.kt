package it.ayngallery

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One keyed layout keeps channel focus and drag targets consistent between list and grid. */
@Composable
internal fun TvChannelCollection(activity: PanelActivity, entries: List<TvCatalogEntry>, reorderable: Boolean,
    modifier: Modifier = Modifier, play: (TvCatalogEntry) -> Unit) {
    val app = activity.application as GalleryApp
    val tv = app.tv
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    val grid = tv.store.grid
    val columns = if (grid) { if (tv.browsing) 4 else 5 } else 1
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var order by remember(entries) { mutableStateOf(entries) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var point by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var lastTarget by remember { mutableStateOf<String?>(null) }
    var dragBase by remember { mutableStateOf(emptyList<TvCatalogEntry>()) }
    var dragMoved by remember { mutableStateOf(false) }
    var dragSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var channelFocused by remember { mutableStateOf(false) }
    // Selection belongs to touch/hover and controller navigation, not automatic focus restoration.
    val requesters = remember(entries) { entries.associate { tvKey(it) to FocusRequester() } }
    val currentOrder by rememberUpdatedState(order)
    fun focus(index: Int) {
        val entry = currentOrder.getOrNull(index) ?: return
        tv.select(entry)
        scope.launch {
            if (state.layoutInfo.visibleItemsInfo.none { it.key == tvKey(entry) }) state.scrollToItem(index)
            withFrameNanos { }
            runCatching { requesters[tvKey(entry)]?.requestFocus() }
        }
    }
    LaunchedEffect(tv.selected?.let(::tvKey), entries, grid) {
        val index = currentOrder.indexOfFirst { tvKey(it) == tv.selected?.let(::tvKey) }
        if (index >= 0 && dragging == null) {
            if (state.layoutInfo.visibleItemsInfo.none { it.key == tv.selected?.let(::tvKey) }) state.animateScrollToItem(index)

        }
    }
    DisposableEffect(activity, requesters, columns) {
        val ensure: () -> Unit = {
            if (!channelFocused && !activity.settingsHeaderHasFocus) focus(currentOrder.indexOfFirst { tvKey(it) == tv.selected?.let(::tvKey) }.coerceAtLeast(0))
        }
        val move: (PadAction) -> Boolean = { action ->
            val index = currentOrder.indexOfFirst { tvKey(it) == tv.selected?.let(::tvKey) }.coerceAtLeast(0)
            val delta = when (action) { PadAction.UP -> -columns; PadAction.DOWN -> columns; PadAction.LEFT -> -1; PadAction.RIGHT -> 1; else -> 0 }
            val edge = columns > 1 && (action == PadAction.LEFT && index % columns == 0 ||
                action == PadAction.RIGHT && (index % columns == columns - 1 || index == currentOrder.lastIndex) ||
                action == PadAction.DOWN && index / columns == currentOrder.lastIndex.coerceAtLeast(0) / columns)
            if (activity.settingsHeaderHasFocus && action == PadAction.DOWN) { focus(0); true }
            else if (activity.settingsHeaderHasFocus) false
            else if (edge) true
            else if (delta == 0 || columns == 1 && action in setOf(PadAction.LEFT, PadAction.RIGHT) || index < columns && action == PadAction.UP || activity.settingsHeaderHasFocus && action != PadAction.DOWN) false
            else { focus((index + delta).coerceIn(0, (currentOrder.size - 1).coerceAtLeast(0))); true }
        }
        activity.ensurePadFocus = ensure
        activity.mediaGridFocus = move
        onDispose {
            if (activity.ensurePadFocus === ensure) activity.ensurePadFocus = null
            if (activity.mediaGridFocus === move) activity.mediaGridFocus = null
        }
    }
    fun updateDragTarget() {
        val id = dragging ?: return
        val target = state.layoutInfo.visibleItemsInfo.firstOrNull {
            point.x >= it.offset.x && point.x < it.offset.x + it.size.width && point.y >= it.offset.y && point.y < it.offset.y + it.size.height
        } ?: return
        val targetEntry = dragBase.getOrNull(target.index) ?: return
        val targetId = tvKey(targetEntry)
        if (targetId == lastTarget) return
        val from = dragBase.indexOfFirst { tvKey(it) == id }
        val to = target.index
        if (from >= 0 && to in dragBase.indices) {
            lastTarget = targetId
            val next = dragBase.toMutableList().apply { add(to, removeAt(from)) }
            if (order != next) {
                state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                order = next
            }
        }
    }
    LaunchedEffect(dragging) {
        val edge = with(density) { 64.dp.toPx() }
        val maxSpeed = with(density) { 720.dp.toPx() }
        var previous = android.os.SystemClock.uptimeMillis()
        while (dragging != null) {
            kotlinx.coroutines.delay(16)
            val now = android.os.SystemClock.uptimeMillis()
            val seconds = ((now - previous) / 1000f).coerceIn(.016f, .05f)
            previous = now
            if (!dragMoved) continue
            val viewport = state.layoutInfo.viewportSize.height
            val step = when {
                point.y < edge -> -((edge - point.y) / edge).coerceIn(0f, 1f)
                point.y > viewport - edge -> ((point.y - viewport + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            } * maxSpeed * seconds
            if (step != 0f) { state.scroll { scrollBy(step) }; withFrameNanos { }; updateDragTarget() }
        }
    }
    fun finishDrag(save: Boolean) {
        if (dragging == null) return
        if (save) updateDragTarget()
        dragging = null; lastTarget = null; dragMoved = false
        if (save) scope.launch {
            try { tv.store.reorder(order.map { entry -> tv.store.channels.first { it.matches(entry) }.id }) }
            catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; tv.failure = tvError(failure); order = entries }
        } else order = entries
    }
    // Capture on the stable viewport: lazy items may leave composition while the finger is held still.
    val dragGesture = if (!reorderable) Modifier else Modifier.pointerInput(reorderable, entries) {
        detectDragGesturesAfterLongPress(onDragStart = { local ->
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { local.x >= it.offset.x && local.x < it.offset.x + it.size.width && local.y >= it.offset.y && local.y < it.offset.y + it.size.height }
            val entry = item?.let { target -> order.firstOrNull { tvKey(it) == target.key } }
            if (item != null && entry != null) {
                dragBase = order.toList(); dragSize = item.size
                dragging = tvKey(entry); tv.select(entry); lastTarget = tvKey(entry)
                grab = local - Offset(item.offset.x.toFloat(), item.offset.y.toFloat()); point = local
            }
        }, onDragCancel = { finishDrag(false) }, onDragEnd = { finishDrag(true) }) { change, amount ->
            if (dragging != null) { change.consume(); point += amount; if (amount != Offset.Zero) dragMoved = true; updateDragTarget() }
        }
    }
    Box(modifier.then(dragGesture)) {
    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("tv-channel-layout"), state, userScrollEnabled = dragging == null, contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(order, key = ::tvKey) { entry ->
            val id = tvKey(entry)
            val selected = tv.selected?.let(::tvKey) == id
            LaunchedEffect(selected) {
                if (selected && dragging == null) { withFrameNanos { }; runCatching { requesters[id]?.requestFocus() } }
            }
            var accent by remember(id, tv.revision) { mutableStateOf<Color?>(null) }
            val interaction = remember(id) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            LaunchedEffect(hovered) { if (hovered && dragging == null) tv.select(entry) }
            val shape = RoundedCornerShape(12.dp)
            Surface(shape = shape, color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f).compositeOver(MaterialTheme.colorScheme.surface)
                else accent?.copy(alpha = .10f)?.compositeOver(MaterialTheme.colorScheme.surface) ?: MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else accent ?: MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().animateItem(placementSpec = if (dragging != null && dragging != id && !prefs.reducedMotion) androidx.compose.animation.core.spring() else null).testTag("tv-channel:$id").clip(shape).focusRequester(requesters.getValue(id))
                    .onFocusChanged { channelFocused = it.isFocused }
                    .hoverable(interaction).graphicsLayer { alpha = if (dragging == id) .18f else 1f }
                    .clickable { if (tv.selected?.let(::tvKey) == id) play(entry) else tv.select(entry) }) {
                TvChannelContents(app, entry, grid, selected, prefs.reducedMotion) { accent = it }

            }
        }
    }
    dragging?.let { id -> dragBase.firstOrNull { tvKey(it) == id }?.let { entry ->
        Surface(Modifier.offset { IntOffset((point.x - grab.x).roundToInt(), (point.y - grab.y).roundToInt()) }
            .size(with(density) { dragSize.width.toDp() }, with(density) { dragSize.height.toDp() })
            .zIndex(2f).graphicsLayer { scaleX = 1.03f; scaleY = 1.03f }.testTag("tv-drag-preview"),
            shape = RoundedCornerShape(12.dp), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shadowElevation = 8.dp) {
            TvChannelContents(app, entry, grid, true, prefs.reducedMotion, interactive = false)
        }
    } }
    }

}

@Composable
private fun TvChannelContents(app: GalleryApp, entry: TvCatalogEntry, grid: Boolean, selected: Boolean,
    reducedMotion: Boolean, interactive: Boolean = true, color: ((Color) -> Unit)? = null) {
    val tv = app.tv
    val id = tvKey(entry)
    val saved = tv.store.channels.firstOrNull { it.matches(entry) }
    var logoAvailable by remember(id) { mutableStateOf(false) }
    val hasArtwork = saved?.artwork != null || !entry.logoUrl.isNullOrBlank() || logoAvailable
    Column(Modifier.padding(6.dp)) {
        if (grid) Box(Modifier.fillMaxWidth().aspectRatio(1.55f).clip(RoundedCornerShape(8.dp))) {
            if (saved?.artwork != null) TvArtwork(app, entry, Modifier.fillMaxSize(), color = color, loadRemote = false, maxSide = 256, crop = true, placeholder = false)
            TvArtwork(app, entry,
                Modifier.align(Alignment.Center).fillMaxWidth(.78f).fillMaxHeight(.7f), logoOnly = true, loadRemote = selected && interactive, maxSide = 256, placeholder = false, color = if (saved?.artwork == null) color else null)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
            if (!grid) Box(
                Modifier.matchParentSize().padding(end = 40.dp), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.fillMaxWidth(.5f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).then(if (hasArtwork) Modifier.testTag("tv-list-artwork:$id") else Modifier)) {
                    if (saved?.artwork != null) TvArtwork(app, entry, Modifier.fillMaxSize().testTag("tv-list-thumb:$id").graphicsLayer { alpha = .35f }, loadRemote = false, maxSide = 256, crop = true, placeholder = false)
                    TvArtwork(app, entry, Modifier.fillMaxSize().then(if (!entry.logoUrl.isNullOrBlank() || logoAvailable) Modifier.testTag("tv-list-logo:$id") else Modifier).padding(horizontal = 4.dp),
                        logoOnly = true, loadRemote = selected && interactive, maxSide = 256, placeholder = false, imageReady = { logoAvailable = it })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text(saved?.name ?: entry.name, Modifier.weight(1f).then(if (selected && !reducedMotion)
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                if (!grid && hasArtwork) Spacer(Modifier.weight(1f))
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    IconButton(onClick = { tv.toggleSaved(entry) }, enabled = interactive, modifier = Modifier.size(36.dp).focusProperties { canFocus = false }
                        .then(if (interactive) Modifier.testTag("tv-star:$id") else Modifier).semantics { contentDescription = localized(R.string.tv_my_channels, "I miei canali") }) {
                        CompositionLocalProvider(LocalContentColor provides if (saved != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) { GalleryIcon(if (saved != null) R.drawable.ph_push_pin_simple else R.drawable.ph_push_pin, null, Modifier.size(24.dp)) }
                    }
                }
            }
        }
    }
}
