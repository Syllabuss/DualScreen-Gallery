package it.ayngallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect

internal fun avoidPreviewNavigator(position: Offset, size: IntSize, navigator: Rect?, margin: Float, width: Float, fixedEdge: Boolean = false): Offset {
    if (navigator == null || size == IntSize.Zero) return position
    val occupied = Rect(navigator.left - margin, navigator.top - margin, navigator.right + margin, navigator.bottom + margin)
    if (!Rect(position, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())).overlaps(occupied)) return position
    if (fixedEdge) return Offset(position.x, (occupied.top - size.height).coerceAtLeast(margin))
    val left = occupied.left - size.width
    return if (left >= margin) Offset(left, position.y) else Offset(position.x.coerceIn(margin, (width - size.width - margin).coerceAtLeast(margin)), (occupied.top - size.height).coerceAtLeast(margin))
}

internal class PreviewDockState {
    var horizontal by mutableFloatStateOf(0f)
    var vertical by mutableFloatStateOf(1f)
    var edge by mutableIntStateOf(0)
    var previousParked: Boolean? = null
}
internal val LocalPreviewDockPark = compositionLocalOf<(Int) -> Unit> { {} }

internal fun previewDockSide(x: Float, width: Float, screenWidth: Float): Int = when {
    x < -minOf(width * .3f, 24f) -> -1
    x > screenWidth - width + minOf(width * .3f, 24f) -> 1
    else -> 0
}

/** Drag only the overlay, park it at an edge, then tap or swipe its bubble inward. */
@Composable
internal fun PreviewDock(tag: String, icon: Int, visible: Boolean, bottomReserve: Dp = 0.dp, parked: Boolean = false, onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier, avoidNavigator: Boolean = true, fixed: Boolean = false,
    content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as GalleryApp
        val state = remember(tag) { app.session.previewDocks.getOrPut(tag) { PreviewDockState() } }
        var horizontal by state::horizontal
        var vertical by state::vertical
        var edge by state::edge
        LaunchedEffect(parked) {
            if (parked && state.previousParked == false) {
                edge = -1
                val occupied = app.session.previewDocks.filterKeys { it != tag }.values.filter { it.edge == -1 }.map { it.vertical }
                vertical = listOf(1f, .82f, .64f, .46f, .28f, .1f).firstOrNull { y -> occupied.none { kotlin.math.abs(it - y) < .15f } } ?: .1f
            }
            state.previousParked = parked
        }
        var size by remember { mutableStateOf(IntSize.Zero) }
        var dragging by remember { mutableStateOf(false) }
        var drag by remember { mutableStateOf(Offset.Zero) }
        var origin by remember { mutableStateOf(Offset.Zero) }
        val navigator = if (avoidNavigator) app.session.previewNavigatorBounds?.translate(-origin) else null
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val bubble = with(density) { 26.dp.toPx() }
        val tabHeight = with(density) { 64.dp.toPx() }
        val rangeX = (width - size.width - margin * 2).coerceAtLeast(0f)
        val rangeY = (height - size.height - margin * 2 - with(density) { bottomReserve.toPx() }).coerceAtLeast(0f)
        val x = margin + (if (fixed) 0f else horizontal) * rangeX
        val y = margin + (if (fixed) 1f else vertical) * rangeY
        val strip = app.session.previewGifStripBounds?.takeIf { tag != "gif-strip" }?.translate(-origin)
        fun safePosition(base: Offset, measure: IntSize, fixedEdge: Boolean = false): Offset {
            val first = avoidPreviewNavigator(base, measure, navigator, margin, width, fixedEdge)
            return avoidPreviewNavigator(first, measure, strip, margin, width, fixedEdge)
        }
        fun recordBounds(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
            if (tag == "gif-strip") app.session.previewGifStripBounds = coordinates.boundsInRoot()
        }
        DisposableEffect(visible) { if (!visible && tag == "gif-strip") app.session.previewGifStripBounds = null
            onDispose { if (tag == "gif-strip") app.session.previewGifStripBounds = null } }

        fun finish(cancel: Boolean = false) {
            if (!cancel) {
                edge = previewDockSide(drag.x, size.width.toFloat(), width)
                if (rangeX > 0) horizontal = ((drag.x - margin) / rangeX).coerceIn(0f, 1f)
                if (rangeY > 0) vertical = ((drag.y - margin) / rangeY).coerceIn(0f, 1f)
            }
            dragging = false
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
                if (edge == 0) Box(Modifier.offset {
                    val base = Offset(if (dragging) drag.x.coerceIn(margin, margin + rangeX) else x, if (dragging) drag.y.coerceIn(margin, margin + rangeY) else y)
                    val safe = safePosition(base, size)
                    IntOffset(safe.x.roundToInt(), safe.y.roundToInt()) }
                    .onSizeChanged { size = it }.onGloballyPositioned(::recordBounds)) {
                    CompositionLocalProvider(LocalPreviewDockPark provides { side -> edge = side }) { content(if (fixed) Modifier else Modifier.pointerInput(tag) {
                        if (tag == "preview-music") detectTapGestures(onDoubleTap = { position -> if (position.x > size.width / 2f) edge = 1 })
                    }.pointerInput(width, height, rangeX, rangeY) {
                        detectDragGestures(onDragStart = {
                            drag = safePosition(Offset(margin + state.horizontal * rangeX, margin + state.vertical * rangeY), size)
                            dragging = true
                        },
                            onDragEnd = { finish() }, onDragCancel = { finish(true) }) { change, amount ->
                            change.consume()
                            drag = Offset((drag.x + amount.x).coerceIn(-size.width + bubble, (width - bubble).coerceAtLeast(0f)),
                                (drag.y + amount.y).coerceIn(margin, (height - size.height - margin - with(density) { bottomReserve.toPx() }).coerceAtLeast(margin)))
                        }
                    }) }
                } else IconButton({ edge = 0 }, Modifier
                    .offset {
                        val safe = safePosition(Offset((if (edge < 0) 0f else width - bubble).coerceAtLeast(0f), y.coerceIn(0f, (height - tabHeight).coerceAtLeast(0f))),
                            IntSize(bubble.roundToInt(), tabHeight.roundToInt()), fixedEdge = true)
                        IntOffset(safe.x.roundToInt(), safe.y.roundToInt()) }
                    .onGloballyPositioned(::recordBounds).width(26.dp).height(64.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .92f), RoundedCornerShape(6.dp))
                    .testTag("$tag-dock").pointerInput(edge) {
                        var distance = 0f
                        detectDragGestures(onDragStart = { distance = 0f }, onDragEnd = { if (distance * edge < -margin) edge = 0 }) { change, amount ->
                            change.consume(); distance += amount.x
                            if (!fixed && rangeY > 0f) vertical = (vertical + amount.y / rangeY).coerceIn(0f, 1f)
                        }
                    }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (onClose != null) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
                            IconButton(onClose, Modifier.size(24.dp).testTag("$tag-dock-close")) { GalleryIcon(R.drawable.ph_x, localized(R.string.text_close_32d407, "Chiudi"), Modifier.size(14.dp)) }
                        }
                        GalleryIcon(icon, localized(R.string.preview_restore_overlay, "Mostra di nuovo"), Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
