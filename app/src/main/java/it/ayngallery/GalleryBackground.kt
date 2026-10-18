package it.ayngallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.decode.BitmapFactoryDecoder
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun Modifier.galleryBackground(app: GalleryApp, data: CatalogSnapshot, prefs: GalleryPreferences): Modifier {
    val start = MaterialTheme.colorScheme.background
    val end = prefs.backgroundEnd?.let { Color(it.toColorInt()) } ?: MaterialTheme.colorScheme.primary
    val finish = androidx.compose.ui.graphics.lerp(start, end, prefs.backgroundStrength / 100f)
    val base = if (prefs.backgroundMode == "GRADIENT") background(Brush.verticalGradient(listOf(start, finish))) else background(start)
    val media = data.byId[prefs.backgroundMedia]?.takeIf { data.usable(it) && it.kind in setOf("IMAGE", "GIF") }
    if (prefs.backgroundMode != "PHOTO" || media == null) return base
    val uri = runCatching { app.catalog.uri(media, data) }.getOrNull() ?: return base
    val revision by app.catalog.cacheRevision.collectAsStateWithLifecycle()
    val request = remember(uri, media.id, media.modified, media.size, revision) {
        ImageRequest.Builder(app).data(uri).memoryCacheKey("gallery-background/${media.id}/${media.modified}/${media.size}/$revision")
            .size(1080, 1080).decoderFactory(BitmapFactoryDecoder.Factory()).build()
    }
    val painter = rememberAsyncImagePainter(request)
    return base.drawBehind {
        val intrinsic = painter.intrinsicSize
        if (intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width > 0 && intrinsic.height > 0) {
            val scale = maxOf(size.width / intrinsic.width, size.height / intrinsic.height) * prefs.backgroundZoom / 100f
            val imageSize = Size(intrinsic.width * scale, intrinsic.height * scale)
            val x = (size.width - imageSize.width) * .5f + (imageSize.width - size.width) * prefs.backgroundX / 200f
            val y = (size.height - imageSize.height) * .5f + (imageSize.height - size.height) * prefs.backgroundY / 200f
            clipRect { translate(x, y) { with(painter) { draw(imageSize, alpha = prefs.backgroundOpacity / 100f) } } }
        }
    }
}

@Composable
internal fun GalleryBackgroundSettings(app: GalleryApp, draft: GalleryPreferences, change: (GalleryPreferences) -> Unit) {
    val data by app.catalog.snapshot.collectAsStateWithLifecycle()
    var pickPhoto by remember { mutableStateOf(false) }
    var pickColor by remember { mutableStateOf(false) }
    var color by remember { mutableIntStateOf(0) }
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(localized(R.string.gallery_background_title, "Sfondo della galleria"), style = MaterialTheme.typography.titleMedium)
        Text(localized(R.string.gallery_background_description, "Personalizza solo lo sfondo della navigazione. Le anteprime e i file restano invariati."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("PLAIN" to localized(R.string.widget_effect_none, "Nessuno"),
                "GRADIENT" to localized(R.string.gallery_background_gradient, "Sfumatura"),
                "PHOTO" to localized(R.string.gallery_background_photo, "Immagine")).forEach { (mode, label) ->
                FilterChip(draft.backgroundMode == mode, { change(draft.copy(backgroundMode = mode)) }, label = { Text(label) }, modifier = Modifier.testTag("background-mode:$mode"))
            }
        }
        if (draft.backgroundMode == "GRADIENT") {
            OutlinedButton({ color = draft.backgroundEnd?.toColorInt() ?: accent; pickColor = true }, Modifier.fillMaxWidth()) {
                Text(localized(R.string.text_choose_a_color_9cf459, "Scegli il colore"), Modifier.weight(1f))
                Box(Modifier.size(40.dp).background(Color(draft.backgroundEnd?.toColorInt() ?: accent), androidx.compose.foundation.shape.CircleShape))
            }
            Text(localized(R.string.text_intensity_7a8f0c, "Intensità") + " · " + draft.backgroundStrength + "%")
            GallerySlider(draft.backgroundStrength.toFloat(), { change(draft.copy(backgroundStrength = it.toInt())) }, valueRange = 0f..100f, modifier = Modifier.testTag("background-strength"))
        }
        if (draft.backgroundMode == "PHOTO") {
            OutlinedButton({ pickPhoto = true }, Modifier.fillMaxWidth().testTag("background-photo-pick")) {
                Text(localized(R.string.gallery_background_photo, "Immagine"), Modifier.weight(1f))
                Box(Modifier.size(40.dp)) {
                    data.byId[draft.backgroundMedia]?.takeIf(data::usable)?.let { MediaThumbnail(it, data, app.catalog, draft, Modifier.fillMaxSize()) }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    @Composable fun control(title: String, value: Int, range: ClosedFloatingPointRange<Float>, tag: String, modifier: Modifier, update: (Int) -> Unit) {
                        Column(modifier) {
                            Text("$title · $value%", style = MaterialTheme.typography.bodySmall)
                            GallerySlider(value.toFloat(), { update(it.toInt()) }, valueRange = range, modifier = Modifier.height(36.dp).testTag(tag))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        control("X", draft.backgroundX, -100f..100f, "background-x", Modifier.weight(1f)) { change(draft.copy(backgroundX = it)) }
                        control("Y", draft.backgroundY, -100f..100f, "background-y", Modifier.weight(1f)) { change(draft.copy(backgroundY = it)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        control(localized(R.string.gallery_background_opacity, "Opacità"), draft.backgroundOpacity, 0f..100f, "background-opacity", Modifier.weight(1f)) { change(draft.copy(backgroundOpacity = it)) }
                        control(localized(R.string.gallery_background_zoom, "Zoom"), draft.backgroundZoom, 100f..300f, "background-zoom", Modifier.weight(1f)) { change(draft.copy(backgroundZoom = it)) }
                    }
                }
            }
        }
    }
    if (pickPhoto) LocalMediaPicker(app, data, draft, "gallery-background", localized(R.string.gallery_background_title, "Sfondo della galleria"), "MEDIA", emptySet(), true,
        onSave = { ids -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { change(draft.copy(backgroundMedia = ids.singleOrNull(), backgroundX = 0, backgroundY = 0, backgroundZoom = 100)) } },
        done = { pickPhoto = false }, dismiss = { pickPhoto = false }, titleOverride = localized(R.string.gallery_background_photo, "Immagine"), automaticLabel = localized(R.string.widget_effect_none, "Nessuno"), browseAlbums = true)
    if (pickColor) GalleryAlertDialog(onDismissRequest = { pickColor = false }, title = { Text(localized(R.string.text_choose_a_color_9cf459, "Scegli il colore")) }, text = {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HueWheel(hsv[0], hsv[1], { hue, saturation -> color = android.graphics.Color.HSVToColor(hsv.copyOf().apply { this[0] = hue; this[1] = saturation }) }, Modifier.size(180.dp))
            Text(localized(R.string.text_lightness_7d8d12, "Luminosità"))
            GallerySlider(hsv[2], { color = android.graphics.Color.HSVToColor(hsv.copyOf().apply { this[2] = it }) })
            Box(Modifier.fillMaxWidth().height(28.dp).background(Color(color), GalleryShape))
        }
    }, confirmButton = { TextButton({ change(draft.copy(backgroundEnd = "#%06X".format(java.util.Locale.ROOT, color and 0xFFFFFF))); pickColor = false }) { Text(localized(R.string.text_done_fa6730, "Fine")) } },
        dismissButton = { TextButton({ pickColor = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}
