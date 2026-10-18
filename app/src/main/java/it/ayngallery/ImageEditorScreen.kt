package it.ayngallery

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.math.min

@Composable
fun ImageEditorScreen(activity: PanelActivity, encoded: String, data: CatalogSnapshot, prefs: GalleryPreferences,
    dismiss: () -> Unit, accepted: () -> Unit, updateRecipe: (String) -> Unit = {}) {
    val target = remember(encoded) { ExternalRequest.decode(encoded).targets.single() }
    val firstGifFrame = target.kind == "GIF"
    val input = if (firstGifFrame) "GIF_FIRST_FRAME" else "STATIC"
    val app = activity.application as GalleryApp
    var raw by rememberSaveable(encoded) { mutableStateOf(ImageRecipe(input = input).encode()) }
    val paired = app.session.canSwap() && app.session.hasOtherPanel(activity)
    LaunchedEffect(app.session.mediaEditor?.recipe) { app.session.mediaEditor?.recipe?.let { if (it != raw) raw = it } }
    val recipe = remember(raw) { ImageRecipe.decode(raw) }
    LaunchedEffect(raw) { updateRecipe(raw) }
    var baseName by rememberSaveable(encoded) { mutableStateOf(target.name.substringBeforeLast('.', target.name).take(130) + if (firstGifFrame) localized(R.string.text_frame_a6b9c6, "-fotogramma") else localized(R.string.text_edited_7e7cdc, "-modificata")) }
    var result by rememberSaveable(encoded) { mutableStateOf(false) }
    LaunchedEffect(result) { app.session.updateMediaEditorPreview(result = result) }
    var preview by remember(encoded) { mutableStateOf<ImagePreview?>(null) }
    var error by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sourceReady = recipe.input == input && data.byId[target.id]?.let { data.usable(it) && target.matches(it) && recipe.accepts(it.kind) } == true
    fun change(value: ImageRecipe) { if (!busy) { raw = value.encode(); error = null } }
    LaunchedEffect(encoded, recipe.gifFrame) { withContext(Dispatchers.Main.immediate) {
        var allocated: ImagePreview? = null
        try {
            withContext(Dispatchers.IO) {
                val fresh = app.catalog.readMediaSnapshot(target.id)
                val item = fresh.byId[target.id]?.takeIf { fresh.usable(it) && target.matches(it) && recipe.accepts(it.kind) }
                    ?: throw IllegalStateException(localized(R.string.text_the_image_is_no_longer_available_47ae20, "L'immagine non è più disponibile"))
                allocated = ImageEditing.preview(app.contentResolver, app.catalog.uri(item, fresh), input, recipe.gifFrame)
            }
            ensureActive(); preview = allocated
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: localized(R.string.text_image_unreadable_dffbcd, "Immagine non leggibile") }
        finally { preview = null; allocated?.bitmap?.recycle() }
    } }
    var pendingRecipe by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var pendingName by rememberSaveable(encoded) { mutableStateOf("") }
    val pickDestination = rememberEditorDestinationPicker(activity, data, target, SourceRole.VISUAL,
        busy = { busy = it }, error = { error = it }) { album ->
        app.files.enqueueImageExport(target, ImageRecipe.decode(requireNotNull(pendingRecipe)), album, pendingName)
        accepted()
    }
    fun submit(allowGame: Boolean = false) {
        val frozen = recipe.copy(allowGame = allowGame)
        pendingRecipe = frozen.encode(); pendingName = "$baseName.${recipe.extension}"
        pickDestination()
    }
    MediaEditorPage(onDismissRequest = { if (!busy) dismiss() }, title = { GalleryDialogTitle(if (firstGifFrame) R.drawable.ph_film_strip else R.drawable.ph_image, if (firstGifFrame) localized(R.string.text_export_gif_frame_a7c5cd, "Esporta fotogramma GIF") else localized(R.string.text_edit_image_da6264, "Modifica immagine")) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(target.name, Modifier.testTag("image-editor-target"), style = MaterialTheme.typography.titleSmall)
            Text(localized(R.string.text_the_original_stays_intact_save_creates_a_new_cop_1584a7, "L'originale rimane intatto. Salva crea una nuova copia e lascia i Preferiti sull'originale."), style = MaterialTheme.typography.bodySmall)
            if (firstGifFrame) {
                val count by produceState<Int?>(null, target.id) {
                    value = withContext(Dispatchers.IO) { runCatching { GifPlayback.header(ImageEditing.gifBytes(app.contentResolver, app.catalog.uri(data.byId.getValue(target.id), data)))?.numFrames }.getOrNull() }
                }
                count?.let { total ->
                    Text("${recipe.gifFrame + 1} / $total", Modifier.testTag("gif-export-frame"))
                    GallerySlider(recipe.gifFrame.toFloat(), { change(recipe.copy(gifFrame = it.toInt().coerceIn(0, total - 1))) }, valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(), enabled = !busy && total > 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!result, { result = false }, label = { Text(localized(R.string.text_crop_300dba, "Ritaglio")) }, modifier = Modifier.testTag("image-crop-mode"))
                FilterChip(result, { result = true }, label = { Text(localized(R.string.text_result_d28636, "Risultato")) }, modifier = Modifier.testTag("image-result-mode"))
            }
            val loaded = preview
            if (loaded != null) {
                if (!paired) CropCanvas(loaded, recipe, result, Modifier.fillMaxWidth().height(220.dp).testTag("image-crop-canvas"), filter = recipe.filter) { change(it) }
                val crop = recipe.rect(loaded.original.width, loaded.original.height)
                Text(localized(R.string.text_source_1_s_2_s_crop_3_s_4_s_rotation_5_s_8a16ea, "Origine %1\$s×%2\$s · ritaglio %3\$s×%4\$s · rotazione %5\$s°", loaded.original.width, loaded.original.height, crop.width(), crop.height(), recipe.turns * 90),
                    Modifier.testTag("image-recipe-summary"), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GalleryButton(localized(R.string.text_full_4b19a8, "Intera"), enabled = !busy) { change(recipe.copy(left = 0f, top = 0f, right = 1f, bottom = 1f)) }
                    listOf("1:1" to 1f, "4:3" to 4f / 3, "16:9" to 16f / 9).forEach { (label, ratio) ->
                        GalleryButton(label, enabled = !busy) { change(recipe.ratio(loaded.original.width, loaded.original.height, ratio)) }
                    }
                }
                val upper = app.session.thorPair?.second ?: app.session.screens.firstOrNull { it.id == android.view.Display.DEFAULT_DISPLAY }
                val lower = app.session.thorPair?.first ?: app.session.screens.firstOrNull { it.id != upper?.id }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.crop_upper_screen to upper, R.string.crop_lower_screen to lower).forEach { (label, screen) ->
                        GalleryButton(localized(label, if (label == R.string.crop_upper_screen) "Schermo superiore" else "Schermo inferiore"),
                            Modifier.weight(1f), enabled = !busy && screen != null && screen.width > 0 && screen.height > 0) {
                            val display = activity.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(screen!!.id)
                            val size = android.graphics.Point()
                            @Suppress("DEPRECATION")
                            display?.getRealSize(size)
                            if (size.x <= 0 || size.y <= 0) return@GalleryButton
                            val ratio = size.x.toFloat() / size.y
                            change(recipe.ratio(loaded.original.width, loaded.original.height, if (recipe.turns % 2 == 0) ratio else 1f / ratio))
                        }
                    }
                }
                Text(localized(R.string.text_drag_the_corners_or_frame_with_a_controller_adju_023237, "Trascina gli angoli o il riquadro. Per il controller, regola i bordi qui sotto."), style = MaterialTheme.typography.bodySmall)
                listOf(localized(R.string.text_left_af36a4, "Sinistra") to recipe.left, localized(R.string.text_top_85e2ad, "Alto") to recipe.top, localized(R.string.text_right_ddcff9, "Destra") to recipe.right, localized(R.string.text_bottom_e201c6, "Basso") to recipe.bottom).forEachIndexed { index, (label, value) ->
                    Text("$label · ${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    val gx = min(0.005f, recipe.right - recipe.left); val gy = min(0.005f, recipe.bottom - recipe.top)
                    val range = when (index) { 0 -> 0f..(recipe.right - gx); 1 -> 0f..(recipe.bottom - gy)
                        2 -> (recipe.left + gx)..1f; else -> (recipe.top + gy)..1f }
                    GallerySlider(value.coerceIn(range), { v -> change(when (index) { 0 -> recipe.copy(left = v); 1 -> recipe.copy(top = v); 2 -> recipe.copy(right = v); else -> recipe.copy(bottom = v) }) },
                        enabled = !busy, valueRange = range, modifier = Modifier.testTag("image-edge:$index").semantics { contentDescription = localized(R.string.text_1_s_edge_c30958, "Bordo %1\$s", label) })
                }
            } else if (error == null) { if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(localized(R.string.text_preparing_preview_cda2d9, "Preparazione dell'anteprima…")) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryIconButton(R.drawable.ph_arrow_clockwise, localized(R.string.text_rotate_90_9746ed, "Ruota 90°"), enabled = !busy, modifier = Modifier.testTag("image-rotate")) { change(recipe.copy(turns = (recipe.turns + 1) % 4)); result = true }
                GalleryIconButton(R.drawable.ph_flip_horizontal, if (recipe.mirror) localized(R.string.text_mirror_horizontal_on_b74c1f, "Specchio orizzontale attivo") else localized(R.string.text_mirror_horizontal_2c4e75, "Specchia in orizzontale"), enabled = !busy,
                    modifier = Modifier.testTag("image-mirror").background(if (recipe.mirror) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, GalleryShape)) { change(recipe.copy(mirror = !recipe.mirror)); result = true }
                GalleryIconButton(R.drawable.ph_flip_vertical, if (recipe.mirrorVertical) localized(R.string.text_mirror_vertical_on_9c28ef, "Specchio verticale attivo") else localized(R.string.text_mirror_vertical_629eb8, "Specchia in verticale"), enabled = !busy,
                    modifier = Modifier.testTag("image-mirror-vertical").background(if (recipe.mirrorVertical) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, GalleryShape)) { change(recipe.copy(mirrorVertical = !recipe.mirrorVertical)); result = true }
            }
            Text(localized(R.string.text_filters_54fc01, "Filtri"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ORIGINAL" to R.string.text_filter_original_18c7dc, "MONO" to R.string.text_filter_black_and_white_7c204d,
                    "WARM" to R.string.text_filter_warm_ef28da, "COOL" to R.string.text_filter_cool_5a1d5d).forEach { (filter, label) ->
                    FilterChip(recipe.filter == filter, { change(recipe.copy(filter = filter)); result = true }, enabled = !busy,
                        label = { Text(localized(label, filter)) }, modifier = Modifier.testTag("image-filter:$filter"))
                }
            }
            Text(localized(R.string.text_format_73e180, "Formato"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PNG", "JPEG").forEach { format -> FilterChip(recipe.format == format, { change(recipe.copy(format = format)) }, enabled = !busy,
                    label = { Text(format) }, modifier = Modifier.testTag("image-format:$format")) }
            }
            if (recipe.format == "JPEG") {
                Text(localized(R.string.text_jpeg_transparency_becomes_white_quality_1_s_654e7d, "JPEG: la trasparenza diventa uno sfondo bianco. Qualità %1\$s%%.", recipe.quality), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                GallerySlider(recipe.quality.toFloat(), { change(recipe.copy(quality = it.toInt())) }, valueRange = 1f..100f, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = localized(R.string.text_jpeg_quality_2a0749, "Qualità JPEG") })
            } else Text(localized(R.string.text_png_preserves_transparency_96ed6e, "PNG conserva la trasparenza."), style = MaterialTheme.typography.bodySmall)
            Text(localized(R.string.text_dimensions_before_crop_4622f5, "Dimensioni prima del ritaglio"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 4096, 2048, 1024).forEach { limit -> FilterChip(recipe.longest == limit, { change(recipe.copy(longest = limit)) }, enabled = !busy,
                    label = { Text(if (limit == 0) localized(R.string.text_originals_fa1fbe, "Originali") else "$limit px") }) }
            }
            Text(localized(R.string.text_the_image_is_oriented_correctly_cropped_then_mir_64c8b2, "L'immagine è orientata correttamente, ritagliata, poi specchiata e ruotata. Colori sRGB; EXIF, posizione e altri metadati non vengono copiati."), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(baseName, { if (it.length <= 170) { baseName = it; error = null } else error = localized(R.string.text_use_a_name_up_to_170_characters_9d3f2a, "Usa un nome fino a 170 caratteri") }, singleLine = true, enabled = !busy, label = { Text(localized(R.string.text_new_copy_name_1_s_cfecd3, "Nome della nuova copia · .%1\$s", recipe.extension)) },
                modifier = Modifier.fillMaxWidth().testTag("image-export-name"))
            Text(localized(R.string.text_if_the_name_exists_the_copy_receives_a_suffix_th_b80221, "Se il nome esiste già, la copia riceve un suffisso. La coda mostra verifica, risultato e annullamento."), style = MaterialTheme.typography.bodySmall)
            if (!sourceReady) Text(localized(R.string.text_the_source_has_changed_or_is_unavailable_reopen_43f906, "La sorgente è cambiata o non è disponibile. Riapri l'editor."), color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, Modifier.testTag("image-editor-error"), color = MaterialTheme.colorScheme.error) }
            if (busy) { if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(localized(R.string.text_preparing_request_76bb17, "Preparazione della richiesta…"), style = MaterialTheme.typography.labelSmall) }
        }
    }, dismissButton = { GalleryIconButton(R.drawable.ph_x, localized(R.string.text_cancel_18c9d9, "Annulla"), Modifier.testTag("image-editor-cancel"), enabled = !busy, onClick = dismiss) }, confirmButton = {
        IconButton(enabled = preview != null && sourceReady && baseName.isNotBlank() && !busy, modifier = Modifier.testTag("image-export-confirm"), onClick = {
            if (prefs.gameMode) consent = true else submit()
        }) { GalleryIcon(R.drawable.ph_floppy_disk, localized(R.string.text_save_a_copy_0680bd, "Salva una copia")) }
    })
    if (consent) GalleryAlertDialog(onDismissRequest = { consent = false }, title = { Text(localized(R.string.text_export_while_gaming_a6b51c, "Esportare mentre giochi?")) },
        text = { Text(localized(R.string.text_encoding_may_use_memory_and_slow_the_game_you_ca_8acfd0, "La codifica può usare memoria e rallentare il gioco. Puoi annullare e salvare più tardi.")) },
        dismissButton = { TextButton({ consent = false }) { Text(localized(R.string.text_later_b81861, "Più tardi")) } }, confirmButton = { TextButton({ consent = false; submit(true) }) { Text(localized(R.string.text_export_now_4bb86b, "Esporta ora")) } })
}

@Composable
internal fun CropCanvas(preview: ImagePreview, recipe: ImageRecipe, result: Boolean, modifier: Modifier, editable: Boolean = true,
    filter: String = "ORIGINAL", guideGrid: Boolean = false, change: (ImageRecipe) -> Unit) {
    val image = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    val area = remember { CropArea() }
    val current by rememberUpdatedState(recipe)
    Canvas(modifier.background(MaterialTheme.colorScheme.surfaceVariant, GalleryShape).pointerInput(preview, result, editable) {
        if (!editable) return@pointerInput
        if (result) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val position = down.position
            val bounds = area.bounds
            if (bounds.width <= 0 || bounds.height <= 0) return@awaitEachGesture
            val initial = current
            val corners = listOf(Offset(initial.left, initial.top), Offset(initial.right, initial.top), Offset(initial.right, initial.bottom), Offset(initial.left, initial.bottom))
                .map { Offset(bounds.left + it.x * bounds.width, bounds.top + it.y * bounds.height) }
            // Pick the handle from the initial press, before a fast drag crosses touch slop.
            val mode = corners.indices.minBy { (corners[it] - position).getDistance() }.takeIf { (corners[it] - position).getDistance() <= 28.dp.toPx() }
                ?: if (position.x in corners[0].x..corners[2].x && position.y in corners[0].y..corners[2].y) 4 else -1
            if (mode < 0) return@awaitEachGesture
            fun move(delta: Offset) {
                val r = current; val dx = delta.x / bounds.width; val dy = delta.y / bounds.height
                val next = if (mode == 4) {
                    val x = dx.coerceIn(-r.left, 1f - r.right); val y = dy.coerceIn(-r.top, 1f - r.bottom)
                    r.copy(left = r.left + x, right = r.right + x, top = r.top + y, bottom = r.bottom + y)
                } else {
                    val gx = min(0.005f, r.right - r.left); val gy = min(0.005f, r.bottom - r.top)
                    r.copy(left = if (mode in setOf(0, 3)) (r.left + dx).coerceIn(0f, r.right - gx) else r.left,
                        right = if (mode in setOf(1, 2)) (r.right + dx).coerceIn(r.left + gx, 1f) else r.right,
                        top = if (mode in setOf(0, 1)) (r.top + dy).coerceIn(0f, r.bottom - gy) else r.top,
                        bottom = if (mode in setOf(2, 3)) (r.bottom + dy).coerceIn(r.top + gy, 1f) else r.bottom)
                }
                change(next)
            }
            val started = awaitTouchSlopOrCancellation(down.id) { pointer, delta -> pointer.consume(); move(delta) }
            if (started != null) drag(started.id) { pointer -> move(pointer.positionChange()); pointer.consume() }
        }
    }) {
        val rect = if (result) recipe.rect(image.width, image.height) else android.graphics.Rect(0, 0, image.width, image.height)
        val rotated = result && recipe.turns % 2 == 1
        val w = if (rotated) rect.height() else rect.width(); val h = if (rotated) rect.width() else rect.height()
        val scale = min(size.width / w, size.height / h)
        val fitted = Size(w * scale, h * scale); val origin = Offset((size.width - fitted.width) / 2, (size.height - fitted.height) / 2)
        drawTransparencyGrid(origin, fitted, background)
        if (result && recipe.format == "JPEG") drawRect(Color.White, origin, fitted)
        val colorFilter = if (filter == "ORIGINAL") null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix(ImageEditing.filterMatrix(filter)))
        withTransform({ translate(size.width / 2, size.height / 2); if (result) rotate(recipe.turns * 90f, Offset.Zero)
            scale(if (result && recipe.mirror) -scale else scale, if (result && recipe.mirrorVertical) -scale else scale, Offset.Zero)
            translate(-rect.width() / 2f, -rect.height() / 2f) }) {
            drawImage(image, IntOffset(rect.left, rect.top), IntSize(rect.width(), rect.height()), IntOffset.Zero, IntSize(rect.width(), rect.height()), colorFilter = colorFilter)
        }
        if (result && guideGrid) for (fraction in listOf(1f / 3f, 2f / 3f)) {
            val x = origin.x + fitted.width * fraction; val y = origin.y + fitted.height * fraction
            drawLine(accent.copy(alpha = .75f), Offset(x, origin.y), Offset(x, origin.y + fitted.height), 1.dp.toPx())
            drawLine(accent.copy(alpha = .75f), Offset(origin.x, y), Offset(origin.x + fitted.width, y), 1.dp.toPx())
        }
        if (!result) {
            area.bounds = androidx.compose.ui.geometry.Rect(origin, fitted)
            val left = origin.x + recipe.left * fitted.width; val top = origin.y + recipe.top * fitted.height
            val right = origin.x + recipe.right * fitted.width; val bottom = origin.y + recipe.bottom * fitted.height
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, origin, Size(fitted.width, top - origin.y)); drawRect(shade, Offset(origin.x, bottom), Size(fitted.width, origin.y + fitted.height - bottom))
            drawRect(shade, Offset(origin.x, top), Size(left - origin.x, bottom - top)); drawRect(shade, Offset(right, top), Size(origin.x + fitted.width - right, bottom - top))
            drawRect(accent, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
            for (fraction in listOf(1f / 3f, 2f / 3f)) {
                val x = left + (right - left) * fraction
                val y = top + (bottom - top) * fraction
                drawLine(accent.copy(alpha = .75f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                drawLine(accent.copy(alpha = .75f), Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            listOf(Offset(left, top), Offset(right, top), Offset(right, bottom), Offset(left, bottom)).forEach { drawCircle(accent, 6.dp.toPx(), it) }
        }
    }
}

// Draw geometry is read by gestures, never observable composition state written during drawing.
private class CropArea { var bounds = androidx.compose.ui.geometry.Rect.Zero }
