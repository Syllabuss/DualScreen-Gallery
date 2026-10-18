package it.ayngallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun CustomThemeEditor(
    draft: GalleryPreferences,
    change: (GalleryPreferences) -> Unit,
) {
    val palette = draft.customTheme ?: CustomTheme.from(GalleryThemes.get(draft))
    var editing by remember { mutableStateOf<Int?>(null) }
    val labels = listOf(
        localized(R.string.text_background_78edb0, "Sfondo"), localized(R.string.text_panels_f2a345, "Pannelli"),
        localized(R.string.text_accent_785d03, "Accento"), localized(R.string.text_selection_ae3f45, "Selezione"),
        localized(R.string.text_text_ebb9e6, "Testo"), localized(R.string.text_borders_45b76c, "Bordi"))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(localized(R.string.text_tap_a_color_to_change_it_5cbe58, "Tocca un colore per cambiarlo. Le modifiche si applicano quando salvi."),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        palette.values().chunked(3).forEachIndexed { rowIndex, colors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEachIndexed { columnIndex, value ->
                    val index = rowIndex * 3 + columnIndex
                    val swatch = Color(android.graphics.Color.parseColor(value))
                    OutlinedCard(onClick = { editing = index }, modifier = Modifier.weight(1f).testTag("custom-color:$index"), shape = GalleryShape) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(labels[index], Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 2)
                            Box(Modifier.size(34.dp)) {
                                Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                                    .padding(2.dp).border(1.dp, Color.Black, CircleShape).padding(1.dp).background(swatch, CircleShape))
                                Icon(androidx.compose.ui.res.painterResource(R.drawable.ph_gear_six), null,
                                    Modifier.align(Alignment.BottomEnd).size(16.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp),
                                    tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { index ->
        val originalPalette = remember(index) { palette }
        val startColor = originalPalette.values()[index]
        val current = palette.values()[index]
        var hex by remember(index) { mutableStateOf(current) }
        val valid = hex.matches(Regex("#[0-9a-fA-F]{6}"))
        val context = androidx.compose.ui.platform.LocalContext.current
        var hsv by remember(index) { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(startColor), it) }) }

        fun update(value: FloatArray) {
            hsv = value
            val next = "#%06X".format(java.util.Locale.ROOT, android.graphics.Color.HSVToColor(value) and 0xFFFFFF)
            hex = next
            val colors = palette.values().toMutableList().apply { set(index, next) }
            change(draft.copy(themeId = GalleryThemes.CUSTOM, customTheme = CustomTheme(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5])))
        }
        fun cancel() {
            val colors = palette.values().toMutableList().apply { set(index, startColor) }
            change(draft.copy(customTheme = CustomTheme(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5])))
            editing = null
        }

        GalleryAlertDialog(onDismissRequest = ::cancel, title = { Text(labels[index]) },
            modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(.94f).testTag("custom-color-dialog"),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), text = {
            @Composable fun wheel(modifier: Modifier) {
                Box(modifier, contentAlignment = Alignment.Center) {
                    HueWheel(hsv[0], hsv[1], { hue, saturation -> update(hsv.copyOf().apply {
                        this[0] = hue; this[1] = saturation; if (this[2] < .15f) this[2] = .85f
                    }) }, Modifier.fillMaxSize())
                }
            }
            @Composable fun adjustments(modifier: Modifier) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localized(R.string.text_intensity_7a8f0c, "Intensità"), style = MaterialTheme.typography.labelLarge)
                    GallerySlider(hsv[1], { update(hsv.copyOf().apply { this[1] = it }) }, valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().testTag("custom-saturation"))
                    Text(localized(R.string.text_lightness_7d8d12, "Luminosità"), style = MaterialTheme.typography.labelLarge)
                    GallerySlider(hsv[2], { update(hsv.copyOf().apply { this[2] = it }) }, valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().testTag("custom-brightness"))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(44.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .background(Color(android.graphics.Color.HSVToColor(hsv)), RoundedCornerShape(12.dp)).testTag("custom-color-preview"))
                        OutlinedTextField(hex, { value ->
                            hex = value.uppercase(java.util.Locale.ROOT).take(7)
                            if (hex.matches(Regex("#[0-9A-F]{6}"))) {
                                hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), it) }
                                val colors = palette.values().toMutableList().apply { set(index, hex) }
                                change(draft.copy(themeId = GalleryThemes.CUSTOM, customTheme = CustomTheme(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5])))
                            }
                        }, label = { Text("HEX") }, singleLine = true, isError = !valid,
                            modifier = Modifier.weight(1f).testTag("custom-hex"))
                        GalleryIconButton(R.drawable.ph_copy_simple, localized(R.string.color_copy_hex, "Copia colore"),
                            Modifier.testTag("custom-copy-hex"), enabled = valid) {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("HEX", hex))
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 450.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    wheel(Modifier.size(220.dp))
                    adjustments(Modifier.weight(1f))
                } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    wheel(Modifier.size(160.dp)); adjustments(Modifier.fillMaxWidth())
                }
            }
        }, confirmButton = { TextButton({ editing = null }, enabled = valid) { Text(localized(R.string.text_done_fa6730, "Fine")) } },
            dismissButton = { TextButton(::cancel) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
}

@Composable
internal fun HueWheel(hue: Float, saturation: Float, onHue: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val currentOnHue by rememberUpdatedState(onHue)
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val colors = remember { listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red) }
    fun select(point: Offset, width: Float, height: Float) {
        val dx = point.x - width / 2f
        val dy = point.y - height / 2f
        val radius = min(width, height) * .5f - 15f * density
        currentOnHue((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f, (kotlin.math.sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f))
    }
    Canvas(modifier.testTag("custom-hue-wheel").semantics { contentDescription = localized(R.string.text_color_wheel_5d58ae, "Ruota dei colori") }
        .pointerInput(Unit) { detectTapGestures { select(it, size.width.toFloat(), size.height.toFloat()) } }
        .pointerInput(Unit) { detectDragGestures { change, _ -> select(change.position, size.width.toFloat(), size.height.toFloat()) } }) {
        val radius = min(size.width, size.height) * .5f - 15.dp.toPx()
        drawCircle(Brush.sweepGradient(colors), radius = radius)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), radius = radius), radius = radius)
        val angle = Math.toRadians(hue.toDouble())
        val center = Offset(size.width / 2f + radius * saturation * cos(angle).toFloat(), size.height / 2f + radius * saturation * sin(angle).toFloat())
        drawCircle(Color.Black.copy(alpha = .85f), 15.dp.toPx(), center)
        drawCircle(Color.White, 10.dp.toPx(), center)
    }
}

@Composable
internal fun HueWheel(hue: Float, onHue: (Float) -> Unit, modifier: Modifier = Modifier) = HueWheel(hue, 1f, { value, _ -> onHue(value) }, modifier)
