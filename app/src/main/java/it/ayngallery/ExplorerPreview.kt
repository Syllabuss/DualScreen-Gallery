package it.ayngallery

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*

internal data class ExplorerPreview(val entry: ExplorerEntry, val file: File? = null, val uri: Uri? = null)
internal fun networkPreviewReadable(context: Context, target: IncomingFile): Boolean {
    val uri = Uri.parse(target.uri)
    if (uri.authority == "${context.packageName}.network") {
        val stream = (context.applicationContext as? GalleryApp)?.explorer?.streams?.get(uri.lastPathSegment) ?: return false
        require(stream.entry.name == target.name && stream.entry.size == target.size && stream.entry.modified == target.modified)
        return true
    }
    if (uri.authority != "${context.packageName}.share" || uri.pathSegments.firstOrNull() != "network_previews") return false
    val name = uri.lastPathSegment ?: return false
    require(name.matches(Regex("[a-f0-9]{64}\\.[a-z0-9]{1,12}")))
    val directory = File(context.filesDir, "network-preview").canonicalFile
    val file = File(directory, name).canonicalFile
    require(file.parentFile == directory && file.isFile && file.length() == target.size && file.lastModified() == target.modified)
    return true
}

@Composable
internal fun ExplorerPreviewDialog(activity: PanelActivity, preview: ExplorerPreview, dismiss: () -> Unit) {
    val app = activity.application as GalleryApp
    val entry = preview.entry
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    val uri = remember(preview) { preview.uri ?: FileProvider.getUriForFile(app, "${app.packageName}.share", checkNotNull(preview.file)) }
    val kind = MediaNames.kind(mime, entry.name)?.name
    val scope = rememberCoroutineScope()
    val token = remember(preview) { UUID.randomUUID().toString() }
    var text by remember(preview) { mutableStateOf<String?>(null) }
    var page by remember(preview) { mutableIntStateOf(0) }
    var pages by remember(preview) { mutableIntStateOf(0) }
    var bitmap by remember(preview) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(preview) { mutableStateOf(false) }
    LaunchedEffect(preview, page) {
        if (preview.file == null) return@LaunchedEffect
        try { withContext(Dispatchers.IO) {
            if (extension in setOf("txt", "md", "json", "csv", "log")) {
                require(preview.file.length() <= 1024 * 1024)
                text = preview.file.readText(Charsets.UTF_8)
            } else if (extension == "zip") {
                require(preview.file.length() <= 16L * 1024 * 1024)
                java.util.zip.ZipFile(preview.file).use { zip ->
                    require(zip.size() <= 1000)
                    val entries = zip.entries().asSequence().take(1001).toList(); require(entries.size <= 1000)
                    require(entries.all { it.name.length <= 1024 && !it.name.startsWith('/') && it.name.split('/', '\\').none { part -> part == ".." } })
                    text = entries.joinToString("\n") { it.name }
                }
            } else if (extension == "pdf") {
                android.os.ParcelFileDescriptor.open(preview.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                        pages = pdf.pageCount
                        pdf.openPage(page.coerceIn(0, pdf.pageCount - 1)).use { current ->
                            val scale = minOf(1f, 1600f / maxOf(current.width, current.height))
                            val next = android.graphics.Bitmap.createBitmap((current.width * scale).toInt().coerceAtLeast(1), (current.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                            next.eraseColor(android.graphics.Color.WHITE)
                            current.render(next, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap = next
                        }
                    }
                }
            }
        } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = true }
    }
    DisposableEffect(preview) { onDispose { preview.uri?.takeIf { it.authority == "${app.packageName}.network" }?.lastPathSegment?.let { app.explorer.streams.remove(it) }; app.music.finishPreview(token); if (app.video.surface == "received" && app.video.mediaId == token) app.video.stop(activity.slot) } }
    GalleryAlertDialog(onDismissRequest = dismiss, modifier = Modifier.fillMaxWidth(.94f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { Text(entry.name, maxLines = 1) },
        text = { Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp)) {
            when {
                error -> Text(localized(R.string.text_preview_unavailable_135044, "Anteprima non disponibile"))
                kind in setOf("IMAGE", "GIF") -> coil3.compose.AsyncImage(model = preview.file, contentDescription = entry.name, modifier = Modifier.fillMaxSize())
                kind == "VIDEO" -> Column {
                    val player = app.video.player?.takeIf { app.video.mediaId == token }
                    AndroidView(factory = { androidx.media3.ui.PlayerView(it) }, update = { it.player = player }, modifier = Modifier.weight(1f).fillMaxWidth())
                    Button({ scope.launch { try { app.video.playReceived(IncomingFile(token, uri.toString(), entry.name, mime, kind, (preview.file?.length() ?: entry.size ?: -1), (preview.file?.lastModified() ?: entry.modified ?: 0)), activity.slot, false) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = true } } }) { Text(localized(R.string.text_play_da1576, "Riproduci")) }
                }
                kind == "AUDIO" -> Button({ scope.launch { try { app.music.previewReceived(IncomingFile(token, uri.toString(), entry.name, mime, kind, (preview.file?.length() ?: entry.size ?: -1), (preview.file?.lastModified() ?: entry.modified ?: 0)), token) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = true } } }) { Text(localized(R.string.text_play_da1576, "Riproduci")) }
                bitmap != null -> Column { Image(bitmap!!.asImageBitmap(), null, Modifier.weight(1f).fillMaxWidth()); Row {
                    TextButton({ page-- }, enabled = page > 0) { Text("←") }; Text("${page + 1}/$pages"); TextButton({ page++ }, enabled = page + 1 < pages) { Text("→") }
                } }
                text != null -> Text(text!!, Modifier.verticalScroll(rememberScrollState()))
                else -> Text(localized(R.string.text_preview_unavailable_135044, "Anteprima non disponibile"))
            }
        } }, confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (extension == "zip" && !error) Button({ app.explorer.zipRequest.value = preview; dismiss() }) { Text(localized(R.string.explorer_extract, "Estrai archivio")) }; TextButton(dismiss) { Text(localized(R.string.text_close_32d407, "Chiudi")) } } })
}
