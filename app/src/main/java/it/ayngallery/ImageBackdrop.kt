package it.ayngallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt

internal object ImageBackdrops {
    val MODES = setOf("GRID", "TINT", "SOLID", "LINEAR", "RADIAL")
}

internal fun DrawScope.drawImageBackdrop(prefs: GalleryPreferences, base: Color) {
    val first = Color(prefs.imageBackdropColor.toColorInt())
    val second = Color(prefs.imageBackdropEnd.toColorInt())
    when (prefs.imageBackdrop) {
        "TINT" -> { drawTransparencyGrid(Offset.Zero, size, base); drawRect(first.copy(alpha = prefs.imageBackdropOpacity / 100f)) }
        "SOLID" -> drawRect(first)
        "LINEAR" -> drawRect(Brush.verticalGradient(listOf(first, second)))
        "RADIAL" -> drawRect(Brush.radialGradient(listOf(first, second), radius = size.maxDimension * .7f))
        else -> drawTransparencyGrid(Offset.Zero, size, base)
    }
}

@Composable
internal fun ImageBackdropSettings(draft: GalleryPreferences, change: (GalleryPreferences) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val labels = mapOf("GRID" to localized(R.string.image_backdrop_grid, "Scacchiera"),
        "TINT" to localized(R.string.image_backdrop_tint, "Scacchiera colorata"),
        "SOLID" to localized(R.string.image_backdrop_solid, "Colore uniforme"),
        "LINEAR" to localized(R.string.image_backdrop_linear, "Sfumatura verticale"),
        "RADIAL" to localized(R.string.image_backdrop_radial, "Sfumatura dal centro"))
    val base = MaterialTheme.colorScheme.surface
    OutlinedCard(Modifier.fillMaxWidth().testTag("image-backdrop-settings")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localized(R.string.image_backdrop_title, "Sfondo delle immagini trasparenti"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Box {
                    TextButton({ expanded = true }, Modifier.testTag("image-backdrop-mode")) { Text(labels.getValue(draft.imageBackdrop)) }
                    GalleryDropdownMenu(expanded, { expanded = false }) {
                        labels.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { change(draft.copy(imageBackdrop = id)); expanded = false }) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(72.dp)) {
                Canvas(Modifier.fillMaxSize().testTag("image-backdrop-example")) { drawImageBackdrop(draft, base) }
                GalleryIcon(R.drawable.ph_image, null, Modifier.align(Alignment.Center).size(32.dp))
            }
            if (draft.imageBackdrop != "GRID") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(0, 1.takeIf { draft.imageBackdrop in setOf("LINEAR", "RADIAL") }).forEach { index ->
                    OutlinedButton({ editing = index }, Modifier.weight(1f).testTag("image-backdrop-color:$index")) {
                        Text(localized(R.string.text_choose_a_color_9cf459, "Scegli il colore") + if (draft.imageBackdrop in setOf("LINEAR", "RADIAL")) " ${index + 1}" else "", Modifier.weight(1f))
                        Box(Modifier.size(28.dp).background(Color((if (index == 0) draft.imageBackdropColor else draft.imageBackdropEnd).toColorInt()), RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)))
                    }
                }
            }
            if (draft.imageBackdrop == "TINT") {
                Text(localized(R.string.gallery_background_opacity, "Opacità") + " · ${draft.imageBackdropOpacity}%", style = MaterialTheme.typography.bodySmall)
                GallerySlider(draft.imageBackdropOpacity.toFloat(), { change(draft.copy(imageBackdropOpacity = it.toInt())) }, valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth().testTag("image-backdrop-opacity"))
            }
        }
    }
    editing?.let { index ->
        BackdropColorDialog(if (index == 0) draft.imageBackdropColor else draft.imageBackdropEnd,
            dismiss = { editing = null }, save = { color ->
                change(if (index == 0) draft.copy(imageBackdropColor = color) else draft.copy(imageBackdropEnd = color)); editing = null
            })
    }
}

@Composable
internal fun BackdropColorDialog(initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var hsv by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toColorInt(), it) }) }
    var hex by remember { mutableStateOf(initial) }
    fun update(values: FloatArray) { hsv = values; hex = "#%06X".format(java.util.Locale.ROOT, android.graphics.Color.HSVToColor(values) and 0xFFFFFF) }
    val valid = hex.matches(Regex("#[0-9a-fA-F]{6}"))
    GalleryAlertDialog(onDismissRequest = dismiss, title = { Text(localized(R.string.text_choose_a_color_9cf459, "Scegli il colore")) },
        modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(.94f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HueWheel(hsv[0], hsv[1], { hue, saturation -> update(hsv.copyOf().apply { this[0] = hue; this[1] = saturation }) }, Modifier.size(180.dp))
                Column(Modifier.weight(1f)) {
                    Text(localized(R.string.text_intensity_7a8f0c, "Intensità"))
                    GallerySlider(hsv[1], { update(hsv.copyOf().apply { this[1] = it }) })
                    Text(localized(R.string.text_lightness_7d8d12, "Luminosità"))
                    GallerySlider(hsv[2], { update(hsv.copyOf().apply { this[2] = it }) })
                    OutlinedTextField(hex, { value -> hex = value.take(7); if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(hex.toColorInt(), it) } },
                        singleLine = true, isError = !valid, leadingIcon = { Box(Modifier.size(24.dp).background(Color(android.graphics.Color.HSVToColor(hsv)), RoundedCornerShape(5.dp))) }, label = { Text("HEX") })
                }
            }
        }, confirmButton = { TextButton({ save(hex.uppercase(java.util.Locale.ROOT)) }, enabled = valid) { Text(localized(R.string.text_done_fa6730, "Fine")) } },
        dismissButton = { TextButton(dismiss) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}
