package it.ayngallery

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val dimensionsCache = android.util.LruCache<String, String>(256)
private val dimensionsReads = Semaphore(2)

internal fun mediaCachedAspect(item: MediaEntity): Float? {
    val parts = dimensionsCache.get("${item.uri}/${item.id}/${item.modified}/${item.size}")?.split(" × ") ?: return null
    val width = parts.getOrNull(0)?.toFloatOrNull() ?: return null
    val height = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it > 0 } ?: return null
    return (width / height).coerceIn(.35f, 3f)
}

@Composable
internal fun MediaListResolution(item: MediaEntity, data: CatalogSnapshot, catalog: Catalog) {
    val key = "${item.uri}/${item.id}/${item.modified}/${item.size}"
    val resolution by produceState(dimensionsCache.get(key).orEmpty(), key, data.usable(item)) {
        if (!data.usable(item)) { value = ""; return@produceState }
        value = mediaPixelResolution(item, data, catalog)
    }
    Text(resolution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal suspend fun mediaPixelResolution(item: MediaEntity, data: CatalogSnapshot, catalog: Catalog): String {
    if (!data.usable(item)) return ""
    val key = "${item.uri}/${item.id}/${item.modified}/${item.size}"
    return withContext(Dispatchers.IO) { dimensionsReads.withPermit {
            dimensionsCache.get(key) ?: runCatching {
                val uri = catalog.uri(item, data)
                if (item.kind == "VIDEO") {
                    val reader = MediaMetadataRetriever()
                    try {
                    reader.setDataSource(catalog.context, uri)
                    val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    if (width > 0 && height > 0) "$width × $height" else ""
                    } finally { reader.release() }
                } else catalog.context.contentResolver.openInputStream(uri)?.use { input ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, options)
                    if (options.outWidth > 0 && options.outHeight > 0) "${options.outWidth} × ${options.outHeight}" else ""
                }.orEmpty()
            }.getOrDefault("").also { dimensionsCache.put(key, it) }
        } }
}
