package it.ayngallery

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun sourceFolderPath(source: SourceEntity): String = runCatching {
    val tree = android.net.Uri.parse(source.treeUri)
    if (tree.authority != "com.android.externalstorage.documents") return@runCatching source.title
    val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
    val volume = id.substringBefore(':').let { if (it == "primary") localized(R.string.text_internal_storage_506cd3, "Memoria interna") else it }
    "$volume / ${id.substringAfter(':', "").replace("/", " / ")}"
}.getOrDefault(source.title)

@Composable
internal fun MediaDetailsDialog(item: MediaEntity, data: CatalogSnapshot, app: GalleryApp, dismiss: () -> Unit) {
    GallerySideSheet(dismiss) {
        val metadata by produceState<Map<String, String>?>(null, item.id, item.uri) {
            value = withContext(Dispatchers.IO) {
                val fields = linkedMapOf<String, String>()
                try {
                    val uri = app.catalog.uri(item, data)
                    if (item.kind in setOf("IMAGE", "GIF")) {
                        val dimensions = mediaPixelResolution(item, data, app.catalog)
                        if (dimensions.isNotEmpty()) fields[localized(R.string.text_dimensions_4d6b7c, "Dimensioni")] = "$dimensions px"
                    } else {
                        val reader = MediaMetadataRetriever()
                        try {
                            reader.setDataSource(app, uri)
                            if (item.kind == "VIDEO") {
                                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                if (width != null && height != null) fields[localized(R.string.text_dimensions_4d6b7c, "Dimensioni")] = "$width × $height px"
                            }
                            (item.duration ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())?.let {
                                fields[localized(R.string.text_duration_902f15, "Durata")] = formatTime(it)
                            }
                            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let {
                                fields["Bitrate"] = "${it / 1000} kb/s"
                            }
                        } finally { reader.release() }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { fields[localized(R.string.text_metadata_07289c, "Metadati")] = localized(R.string.text_unavailable_1d9817, "Non disponibili") }
                fields
            }
        }
        GalleryDialogTitle(R.drawable.ph_info, localized(R.string.text_details_1a45b0, "Dettagli"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("media-details"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val source = data.sourceFor(item)
            val fields = linkedMapOf(localized(R.string.text_name_508690, "Nome") to item.name, localized(R.string.text_format_73e180, "Formato") to item.mime, localized(R.string.text_file_size_c27dd5, "Peso") to fileSize(item.size))
            fields.putAll(metadata.orEmpty())
            item.artist?.let { fields[localized(R.string.text_artist_7e6ef2, "Artista")] = it }
            item.musicAlbum?.let { fields[localized(R.string.text_music_album_f87381, "Album musicale")] = it }
            fields[localized(R.string.text_root_folder_b2af5c, "Cartella principale")] = source?.title ?: localized(R.string.text_unavailable_ce1391, "Non disponibile")
            fields[localized(R.string.text_folder_2e95e4, "Cartella")] = data.albumsById[item.albumId]?.path ?: localized(R.string.text_unavailable_ce1391, "Non disponibile")
            source?.let { fields[localized(R.string.text_root_path_b7b245, "Percorso principale")] = sourceFolderPath(it) }
            fields[localized(R.string.text_file_path_780ddb, "Percorso del file")] = item.uri
            fields.forEach { (label, text) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (metadata == null) CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}
