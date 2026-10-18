package it.ayngallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

@Composable
internal fun GifFrameStrip(frames: GifPlayback, maxWidth: Dp = 520.dp, dragModifier: Modifier = Modifier) {
    val session = (androidx.compose.ui.platform.LocalContext.current.applicationContext as GalleryApp).session
    val park = LocalPreviewDockPark.current
    LaunchedEffect(frames) { frames.stripWidth = session.gifStripWidth }
    val list = rememberLazyListState()
    val viewportWidth by remember { derivedStateOf { list.layoutInfo.viewportSize.width } }
    val density = LocalDensity.current
    val width = frames.stripWidth.coerceIn(84f, maxWidth.value.coerceAtLeast(84f)).dp
    var cursor by remember(frames) { mutableIntStateOf(frames.count + frames.index) }
    var previous by remember(frames) { mutableIntStateOf(frames.index) }
    var scrubbing by remember(frames) { mutableStateOf(false) }
    var manualFrame by remember(frames) { mutableStateOf<Int?>(null) }
    val coordinates = remember(frames) { mutableStateMapOf<Int, LayoutCoordinates>() }
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    suspend fun center(virtual: Int) {
        val viewport = list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset
        list.scrollToItem(virtual, (with(density) { 72.dp.roundToPx() } - viewport) / 2)
    }
    fun selectCenter() {
        val visible = list.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
        val middle = viewport?.takeIf { it.isAttached }?.boundsInRoot()?.center?.x ?: return
        val virtual = coordinates.filter { it.key in visible && it.value.isAttached }
            .minByOrNull { kotlin.math.abs(it.value.boundsInRoot().center.x - middle) }?.key ?: return
        cursor = virtual; previous = virtual % frames.count
        if (frames.selected != previous) frames.select(previous)
    }
    LaunchedEffect(list, frames) {
        list.interactionSource.interactions.collect { event ->
            if (event is DragInteraction.Start) {
                scrubbing = true; frames.pause()
                manualFrame?.let { frame ->
                    val near = list.firstVisibleItemIndex
                    val virtual = (0 until frames.count * 3).filter { it % frames.count == frame }.minBy { kotlin.math.abs(it - near) }
                    center(virtual)
                }
                manualFrame = null
            }
        }
    }
    LaunchedEffect(list, frames) {
        snapshotFlow {
            listOf(if (scrubbing) 1 else 0, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.layoutInfo.viewportSize.width)
        }.distinctUntilChanged().collectLatest {
            if (scrubbing) { withFrameNanos { }; selectCenter() }
        }
    }
    LaunchedEffect(list.isScrollInProgress) {
        if (scrubbing && !list.isScrollInProgress) {
            withFrameNanos { }; selectCenter()
            val first = list.firstVisibleItemIndex
            if (first < frames.count || first >= frames.count * 2) {
                val offset = list.firstVisibleItemScrollOffset
                val shift = frames.count + first % frames.count - first
                cursor += shift
                list.scrollToItem(first + shift, offset)
                withFrameNanos { }; selectCenter()
            }
        }
    }
    LaunchedEffect(frames.index, frames.paused, width, viewportWidth) {
        if (scrubbing || manualFrame != null) return@LaunchedEffect
        if (frames.index != previous) {
            cursor = if (!frames.paused && frames.index == (previous + 1) % frames.count) cursor + 1 else frames.count + frames.index
            previous = frames.index
        }
        if (cursor >= frames.count * 3 - 2) cursor = frames.count + frames.index
        center(cursor)
    }
    Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), modifier = Modifier.width(width).testTag("gif-frame-strip")) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            GalleryIconButton(if (frames.paused) R.drawable.ph_play else R.drawable.ph_pause,
                localized(if (frames.paused) R.string.text_play_da1576 else R.string.text_pause_9bd4d1, if (frames.paused) "Riproduci" else "Pausa"), dragModifier) {
                scrubbing = false; manualFrame = null
                if (frames.paused) {
                    session.gifStripPauseWidth?.let { frames.stripWidth = it; session.gifStripWidth = it }; session.gifStripPauseWidth = null
                    frames.resume()
                } else {
                    session.gifStripPauseWidth = frames.stripWidth.takeIf { it > 110f }
                    frames.stripWidth = 84f; session.gifStripWidth = 84f
                    frames.pause()
                }
            }
            if (width > 110.dp) LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f).height(64.dp).onGloballyPositioned { viewport = it }.testTag("gif-strip-frames")) {
                items(count = frames.count * 3, key = { it }) { virtual ->
                    val frame = virtual % frames.count
                    DisposableEffect(virtual) { onDispose { coordinates.remove(virtual) } }
                    val bitmap by produceState<android.graphics.Bitmap?>(frames.cachedThumbnail(frame), frames, frame) {
                        try { value = frames.thumbnail(frame) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { value = null }
                    }
                    Box(Modifier.width(72.dp).fillMaxHeight().clip(GalleryShape)
                        .onGloballyPositioned { coordinates[virtual] = it }
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(if (frames.selected == frame) 2.dp else 1.dp,
                            if (frames.selected == frame) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, GalleryShape)
                        .clickable { scrubbing = false; manualFrame = frame; cursor = virtual; previous = frame; frames.select(frame) }.semantics { selected = frames.selected == frame }.testTag(if (virtual == cursor + ((frame - frames.index + frames.count + frames.count / 2) % frames.count - frames.count / 2)) "gif-frame:$frame" else "gif-copy:$virtual")) {
                        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize()) }
                        Text("${frame + 1}", Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .8f)).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val handleColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.width(24.dp).height(64.dp).testTag("gif-strip-resize").pointerInput(maxWidth, density) {
                var overflow = 0f
                detectHorizontalDragGestures(onDragStart = { overflow = 0f }) { change, amount ->
                    change.consume()
                    session.gifStripPauseWidth = null
                    if (frames.stripWidth <= 84f && amount < 0f) { overflow -= amount; if (overflow > with(density) { 10.dp.toPx() }) park(-1) }
                    frames.stripWidth = (frames.stripWidth + with(density) { amount.toDp().value }).coerceIn(84f, maxWidth.value.coerceAtLeast(84f))
                    session.gifStripWidth = frames.stripWidth
                }
            }) {
                drawLine(handleColor, Offset(size.width * .35f, size.height * .35f), Offset(size.width * .65f, size.height * .5f), 2.dp.toPx())
                drawLine(handleColor, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .65f), 2.dp.toPx())
            }

        }
    }
}
