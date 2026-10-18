package it.ayngallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Keep focused tiles 10 dp above the navbar within the same native focus scroll. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GalleryGridFocus(content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 10.dp.toPx() }
    val spec = remember(gap) { object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
            offset < gap -> offset - gap
            size > containerSize - gap -> 0f
            offset + size > containerSize - gap -> offset + size - containerSize + gap
            else -> 0f
        }
    } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Lists navigate by their displayed order, rather than a possibly stale native focus rectangle. */
@Composable
internal fun OrderedLibraryFocus(activity: PanelActivity, ids: List<String>, active: Boolean,
    singleColumn: Boolean, revision: Long, focus: (String) -> Unit) {
    val currentFocus by rememberUpdatedState(focus)
    val app = activity.application as GalleryApp
    DisposableEffect(activity, ids, active, singleColumn, revision) {
        val handler: (PadAction) -> Boolean = { action ->
            val header = activity.settingsHeaderHasFocus
            if (!active || ids.isEmpty() || action !in listOf(PadAction.UP, PadAction.DOWN) ||
                header && action != PadAction.DOWN || !singleColumn && !header) false
            else if (revision != activity.windowModel.sortReset.value) true
            else {
                val index = ids.indexOf(app.session.selectedMediaId).coerceAtLeast(0)
                if (!header && action == PadAction.UP && index == 0) false
                else {
                    val target = if (header) 0 else (index + if (action == PadAction.DOWN) 1 else -1).coerceIn(ids.indices)
                    if (ids[target] != app.session.selectedMediaId) {
                        app.sounds.navigation("media:${ids[target]}")
                        if (app.catalog.snapshot.value.byId[ids[target]]?.kind != "AUDIO") app.session.previewFocusRevision += 1
                    }
                    currentFocus(ids[target])
                    true
                }
            }
        }
        if (active) activity.mediaGridFocus = handler
        onDispose { if (activity.mediaGridFocus === handler) activity.mediaGridFocus = null }
    }
}
