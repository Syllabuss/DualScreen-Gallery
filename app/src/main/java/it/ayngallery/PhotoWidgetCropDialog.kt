package it.ayngallery

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.decode.BitmapFactoryDecoder
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoWidgetCropDialog(activity: PhotoWidgetConfigActivity, app: GalleryApp, data: CatalogSnapshot,
    prefs: GalleryPreferences, config: PhotoWidgetConfig, change: (PhotoWidgetConfig) -> Unit, dismiss: () -> Unit) {
    val original = remember { config }
    val latest by rememberUpdatedState(config)
    val update by rememberUpdatedState(change)
    val item = config.media(data).firstOrNull()
    val bitmap by produceState<android.graphics.Bitmap?>(null, item?.id, item?.modified) {
        value = withContext(Dispatchers.IO) { runCatching {
            item?.let { target ->
                val request = ImageRequest.Builder(app).data(app.catalog.uri(target, data)).allowHardware(false).size(2048, 2048)
                    .decoderFactory(BitmapFactoryDecoder.Factory()).build()
                (SingletonImageLoader.get(app).execute(request) as? SuccessResult)?.image?.toBitmap()
            }
        }.getOrNull() }
    }
    val host = remember { photoWidgetSize(app, activity.intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1)) }
    val aspect = host.width / host.height
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    fun move(x: Float, y: Float, zoom: Float) {
        val draft = latest
        update(draft.copy(selection = draft.selection.copy(fill = true), zoom = (draft.zoom + zoom).coerceIn(1f, 5f),
            panX = (draft.panX + x).coerceIn(-1f, 1f), panY = (draft.panY + y).coerceIn(-1f, 1f)))
    }
    val runtime = remember { PadModalRuntime() }
    DisposableEffect(activity, runtime) {
        activity.cropMove = ::move
        runtime.captureKey = {
            if (padButton(it.keyCode) == PadButton.A) {
                if (it.action == android.view.KeyEvent.ACTION_DOWN && it.repeatCount == 0) dismiss()
                true
            } else activity.handleCropKey(it) || runtime.input?.key(it) == true
        }
        runtime.captureMotion = { activity.handleCropMotion(it) || runtime.input?.motion(it) == true }
        onDispose { activity.cropMove = null; runtime.captureKey = null; runtime.captureMotion = null }
    }
    val cancel = { change(original); dismiss() }
    @Composable fun preview(modifier: Modifier) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val previewWidth = minOf(maxWidth, maxHeight * aspect)
            Canvas(Modifier.width(previewWidth).aspectRatio(aspect).clip(RoundedCornerShape(16.dp))
                .testTag("photo-widget-crop-preview").pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ ->
                    move(pan.x / size.width * 2, pan.y / size.height * 2, latest.zoom * (zoom - 1f))
                } }) {
                bitmap?.let { photo -> drawContext.canvas.nativeCanvas.drawBitmap(photo,
                    photoWidgetCrop(photo.width, photo.height, aspect.toDouble(), config.zoom, config.panX, config.panY),
                    android.graphics.RectF(0f, 0f, size.width, size.height), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)) }
            }
        }
    }
    @Composable fun controls() {
        GallerySlider(config.zoom, { move(0f, 0f, it - config.zoom) }, valueRange = 1f..5f)
        TextButton({ change(config.copy(zoom = 1f, panX = 0f, panY = 0f)) }) { Text(localized(R.string.widget_crop_reset, "Reset")) }
    }
    GalleryAlertDialog(cancel, padRuntime = runtime, modifier = Modifier.fillMaxWidth(.94f).widthIn(max = 1100.dp).height(windowHeight * .94f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { Text(localized(R.string.widget_crop, "Inquadra la foto")) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = windowHeight * .64f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localized(R.string.widget_crop_hint, "Pizzica per ingrandire. Trascina o usa gli stick per spostare la foto."), style = MaterialTheme.typography.bodySmall)
            if (windowWidth > windowHeight) Row(Modifier.fillMaxWidth().height(windowHeight * .52f), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                preview(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(144.dp)) { controls() }
            } else {
                preview(Modifier.fillMaxWidth().height(windowHeight * .42f))
                controls()
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text(localized(R.string.text_done_fa6730, "Fine")) } },
        dismissButton = { TextButton(cancel) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}
