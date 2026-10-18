package it.ayngallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal val musicColorModes = setOf("THEME", "ARTWORK", "CUSTOM")
internal data class MusicColors(val background: Int, val accent: Int)

/** A bounded histogram of a 64px thumbnail, including black and white in the dominant color. */
internal fun musicArtworkColors(pixels: IntArray, fallback: MusicColors): MusicColors {
    val bins = IntArray(4096)
    for (pixel in pixels) if (android.graphics.Color.alpha(pixel) >= 128) {
        val key = ((pixel shr 12) and 0xF00) or ((pixel shr 8) and 0xF0) or ((pixel shr 4) and 0xF)
        bins[key]++
    }
    val dominant = bins.indices.maxByOrNull { bins[it] } ?: return fallback
    if (bins[dominant] == 0) return fallback
    fun color(key: Int) = android.graphics.Color.rgb(((key shr 8) and 15) * 17, ((key shr 4) and 15) * 17, (key and 15) * 17)
    val base = color(dominant)
    val hsv = FloatArray(3)
    val candidates = bins.indices.filter { bins[it] > 0 && ColorUtils.calculateContrast(color(it), base) >= 1.15 }
    // Combine nearby shades by hue: a blue cover should not lose to a single grey pixel bucket.
    val weights = DoubleArray(24)
    val red = DoubleArray(24); val green = DoubleArray(24); val blue = DoubleArray(24)
    for (key in bins.indices) if (bins[key] > 0) {
        val pixel = color(key)
        android.graphics.Color.colorToHSV(pixel, hsv)
        if (hsv[1] < .3f || hsv[2] < .2f) continue
        val bucket = (hsv[0] / 15).toInt().coerceIn(weights.indices)
        val weight = bins[key] * hsv[1].toDouble() * hsv[1] * kotlin.math.sqrt(hsv[2].toDouble())
        weights[bucket] += weight
        red[bucket] += android.graphics.Color.red(pixel) * weight
        green[bucket] += android.graphics.Color.green(pixel) * weight
        blue[bucket] += android.graphics.Color.blue(pixel) * weight
    }
    val baseHsv = FloatArray(3).also { android.graphics.Color.colorToHSV(base, it) }
    fun hueColor(i: Int) = android.graphics.Color.rgb((red[i] / weights[i]).roundToInt(),
        (green[i] / weights[i]).roundToInt(), (blue[i] / weights[i]).roundToInt())
    val strongest = weights.max()
    val contrasting = weights.indices.filter { i ->
        if (weights[i] < strongest * .04 || weights[i] == 0.0 || baseHsv[1] < .3f) false
        else {
            val difference = kotlin.math.abs((i + .5f) * 15f - baseHsv[0])
            minOf(difference, 360f - difference) >= 30f && ColorUtils.calculateContrast(hueColor(i), base) >= 1.8
        }
    }
    val hue = (contrasting.ifEmpty { weights.indices.toList() }).maxBy { weights[it] }
    val accent = if (weights[hue] > 0) android.graphics.Color.rgb((red[hue] / weights[hue]).roundToInt(),
        (green[hue] / weights[hue]).roundToInt(), (blue[hue] / weights[hue]).roundToInt())
        else candidates.maxByOrNull { bins[it] }?.let(::color) ?: fallback.accent
    return MusicColors(base, accent)
}

internal data class MusicPlayerPalette(val background: Color, val edge: Color, val accent: Color, val ink: Color, val dark: Boolean,
    val artwork: String? = null)
private data class MusicArtworkPalette(val uri: String, val colors: MusicColors)

internal fun customMusicPalette(background: String, accent: String): MusicPlayerPalette {
    val base = Color(background.toColorInt())
    val dark = ColorUtils.calculateContrast(android.graphics.Color.WHITE, base.toArgb()) >=
        ColorUtils.calculateContrast(android.graphics.Color.BLACK, base.toArgb())
    return MusicPlayerPalette(base, base, Color(accent.toColorInt()), if (dark) Color.White else Color.Black, dark)
}

@Composable
internal fun musicPlayerPalette(app: GalleryApp, track: MediaEntity, data: CatalogSnapshot, prefs: GalleryPreferences): MusicPlayerPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = GalleryThemes.get(prefs).dark
    val fallback = MusicColors(scheme.background.toArgb(), scheme.primary.toArgb())
    val revision by app.catalog.cacheRevision.collectAsState()
    val extracted by produceState<MusicArtworkPalette?>(null, track.id, track.modified, track.size, track.artworkUri, data.artworkChoices, prefs.artworkOrder, prefs.musicColorMode, revision, fallback.accent) {
        if (prefs.musicColorMode != "ARTWORK") value = null
        else value = withContext(Dispatchers.IO) {
            try { withTimeoutOrNull(2500) {
                val reference = app.catalog.loadArtworkReference(track, data) ?: return@withTimeoutOrNull null
                val result = SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(reference.first)
                    .size(64, 64).allowHardware(false).decoderFactory(coil3.decode.BitmapFactoryDecoder.Factory())
                    .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build()) as? SuccessResult
                result?.image?.toBitmap()?.let { bitmap ->
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    MusicArtworkPalette(reference.first, musicArtworkColors(pixels, fallback))
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    val colors = when (prefs.musicColorMode) {
        "ARTWORK" -> extracted?.colors ?: fallback
        "CUSTOM" -> MusicColors(prefs.musicBackgroundColor.toColorInt(), prefs.musicBarsColor.toColorInt())
        else -> fallback
    }
    // Custom colors are literal. Only the text adapts; no theme tint or gradient is added.
    val custom = if (prefs.musicColorMode == "CUSTOM") customMusicPalette(prefs.musicBackgroundColor, prefs.musicBarsColor) else null
    val hasArtwork = prefs.musicColorMode == "ARTWORK" && extracted != null
    val background = custom?.background ?: if (hasArtwork) lerp(scheme.background, Color(colors.background), .18f) else scheme.background
    val ink = if (dark) Color.White else Color.Black
    val edge = custom?.background ?: scheme.background
    var accent = Color(colors.accent)
    if (hasArtwork) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(colors.accent, it) }
        if (hsv[1] >= .3f) {
            hsv[1] = hsv[1].coerceAtLeast(.65f)
            hsv[2] = if (dark) hsv[2].coerceAtLeast(.9f) else hsv[2].coerceAtMost(.55f)
            accent = Color(android.graphics.Color.HSVToColor(hsv))
        }
        repeat(8) {
            if (ColorUtils.calculateContrast(accent.toArgb(), background.toArgb()) < 3.0)
                accent = lerp(accent, ink, .12f)
        }
    }
    val duration = if (prefs.reducedMotion) 0 else 650
    val animatedBackground by androidx.compose.animation.animateColorAsState(background, androidx.compose.animation.core.tween(duration), label = "music background")
    val animatedAccent by androidx.compose.animation.animateColorAsState(accent, androidx.compose.animation.core.tween(duration), label = "music bars")
    return MusicPlayerPalette(animatedBackground, edge, animatedAccent, custom?.ink ?: scheme.onBackground,
        custom?.dark ?: dark, extracted?.uri?.takeIf { hasArtwork })
}

@Composable
internal fun MusicPlayerBackdrop(palette: MusicPlayerPalette, prefs: GalleryPreferences) {
    Box(Modifier.fillMaxSize().background(palette.background)) {
        palette.artwork?.let { uri ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val request = remember(uri, prefs.reducedMotion) { ImageRequest.Builder(context).data(uri).size(1024, 1024)
                .decoderFactory(coil3.decode.BitmapFactoryDecoder.Factory()).crossfade(if (prefs.reducedMotion) 0 else 650).build() }
            coil3.compose.AsyncImage(request, null, Modifier.fillMaxSize().blur(12.dp).testTag("music-artwork-backdrop"), contentScale = ContentScale.Crop)
            val alpha = if (palette.dark) .64f else .76f
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(palette.edge.copy(alpha = alpha), palette.background.copy(alpha = alpha - .08f), palette.edge.copy(alpha = alpha + .1f)))))
        }
    }
}

@Composable
internal fun MusicColorSettings(draft: GalleryPreferences, change: (GalleryPreferences) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val modes = musicColorModes.toList()
    PlaybackChoice(localized(R.string.music_colors_title, "Colori del player"), modes.indexOf(draft.musicColorMode),
        mapOf(0 to localized(R.string.music_colors_theme, "Tema"), 1 to localized(R.string.music_colors_artwork, "Dalla copertina"),
            2 to localized(R.string.music_colors_custom, "Personalizzati")), "music-color-mode") { change(draft.copy(musicColorMode = modes[it])) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
        Text(localized(R.string.music_colors_description, "Usa il tema, la copertina a tutto schermo con colori coordinati, oppure colori personalizzati. Senza copertina resta lo sfondo del tema. I colori personalizzati vengono usati esattamente come li scegli, senza sfumature aggiunte."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
    if (draft.musicColorMode == "CUSTOM") Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(draft.musicBackgroundColor to localized(R.string.music_colors_background, "Sfondo"), draft.musicBarsColor to localized(R.string.music_colors_bars, "Barre")).forEachIndexed { index, (color, label) ->
            OutlinedButton({ editing = index }, Modifier.weight(1f)) {
                Text(label, Modifier.weight(1f))
                Box(Modifier.size(28.dp).background(Color(color.toColorInt()), GalleryShape).border(1.dp, MaterialTheme.colorScheme.outline, GalleryShape))
            }
        }
    }
    editing?.let { index -> BackdropColorDialog(if (index == 0) draft.musicBackgroundColor else draft.musicBarsColor,
        { editing = null }, { value -> change(if (index == 0) draft.copy(musicBackgroundColor = value) else draft.copy(musicBarsColor = value)); editing = null }) }
}
