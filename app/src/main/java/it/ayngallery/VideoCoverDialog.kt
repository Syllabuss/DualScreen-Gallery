package it.ayngallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Preview and final cover deliberately share the same extraction/cache path. */
@Composable
internal fun VideoCoverDialog(app: GalleryApp, item: MediaEntity, data: CatalogSnapshot, dismiss: () -> Unit) {
    var position by remember(item.id) { mutableLongStateOf(app.catalog.videoCoverFrame(item.id)) }
    val uri = remember(item.id) { app.catalog.uri(item, data) }
    val duration by produceState<Long?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { item.duration ?: VideoEditing.inspect(app, uri).duration }.getOrNull() }
    }
    var poster by remember(item.id) { mutableStateOf<String?>(null) }
    var shownPosition by remember(item.id) { mutableLongStateOf(-1) }
    var failed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(position) {
        delay(100)
        val loaded = app.catalog.videoThumbnail(item, uri.toString(), position, transient = true)
        poster = loaded?.first; failed = loaded == null; shownPosition = position
    }
    val filmstrip by produceState<List<String?>>(List(8) { null }, item.id, duration) {
        val length = duration ?: return@produceState
        for (index in 0..7) {
            val image = app.catalog.videoThumbnail(item, uri.toString(), length * index / 8, transient = true)?.first
            value = value.toMutableList().also { it[index] = image }
            kotlinx.coroutines.yield()
        }
    }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val runtime = remember { PadModalRuntime() }
    val seek = remember { FrameSeekSteps() }
    runtime.captureAction = { action ->
        if (focused && action in setOf(PadAction.LEFT, PadAction.RIGHT)) {
            position = (position + seek.step(if (action == PadAction.LEFT) -1 else 1, android.os.SystemClock.uptimeMillis())).coerceIn(0, duration ?: 0)
            true
        } else false
    }
    LaunchedEffect(duration) { if (duration != null) { androidx.compose.runtime.withFrameNanos { }; focus.requestFocus() } }
    val height = (LocalConfiguration.current.screenHeightDp * .4f).coerceIn(100f, 220f).dp
    GalleryAlertDialog(onDismissRequest = dismiss, padRuntime = runtime,
        title = { GalleryDialogTitle(R.drawable.ph_film_reel, localized(R.string.video_cover_frame, "Scegli fotogramma copertina")) },
        modifier = Modifier.fillMaxWidth(.94f).testTag("video-cover-dialog"),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = androidx.compose.ui.Alignment.Center) {
                poster?.let { coil3.compose.AsyncImage(it, null, Modifier.fillMaxSize().testTag("video-cover-live-preview"), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
                if (shownPosition != position) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                if (failed) Text(localized(R.string.text_preview_unreadable_9374e0, "Anteprima non leggibile"))
            }
            duration?.let { length -> Row(Modifier.fillMaxWidth().height(42.dp)) {
                repeat(8) { index ->
                    val time = length * index / 8
                    val image = filmstrip[index]
                    Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)
                        .focusProperties { canFocus = false }.clickable { position = time }) {
                        image?.let { coil3.compose.AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                    }
                }
            } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(coverTime(position), Modifier.testTag("video-cover-position"), style = MaterialTheme.typography.labelLarge)
                Text(coverTime(duration ?: 0), style = MaterialTheme.typography.labelLarge)
            }
            GallerySlider(position.toFloat(), { position = it.toLong() }, valueRange = 0f..(duration ?: 1L).coerceAtLeast(1).toFloat(),
                enabled = duration != null, modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                    .border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, GalleryShape).testTag("video-cover-seek"))
        } },
        confirmButton = { TextButton({ runCatching { app.catalog.setVideoCoverFrame(item, position) }.onSuccess { app.sounds.play("save"); dismiss() }.onFailure { app.session.fail(it.message ?: localized(R.string.text_unavailable_ce1391, "Non disponibile")) } }, enabled = poster != null && shownPosition == position && !failed) {
            Text(localized(R.string.text_save_c5997e, "Salva"))
        } }, dismissButton = { TextButton(dismiss) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}

private fun coverTime(ms: Long) = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}.${(ms % 1000 / 100).toString()}"

/** Repeated stick hits accelerate only while the same direction remains continuous. */
internal class FrameSeekSteps {
    private var direction = 0
    private var started = 0L
    private var last = Long.MIN_VALUE
    fun step(next: Int, now: Long): Long {
        require(next == -1 || next == 1)
        if (next != direction || last == Long.MIN_VALUE || now - last > 750 || now < last) { direction = next; started = now }
        last = now
        return next * when { now - started >= 8000 -> 30000L; now - started >= 3000 -> 10000L; else -> 1000L }
    }
}
