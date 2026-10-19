package it.ayngallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun AlbumTileContent(album: AlbumEntity, data: CatalogSnapshot, catalog: Catalog, prefs: GalleryPreferences,
    grid: Boolean, columns: Int, chosen: Boolean, selectionColor: Color, multi: Boolean, carouselLabel: Boolean = true, reorderScale: Float? = null, reorderSelected: Boolean = false, onClick: () -> Unit) {
    val subfolders = if (album.hidden || data.albumLocked(album.id)) 0 else data.populatedSubfolderCounts[album.id] ?: 0
    if (grid) Column(Modifier.padding(if (columns >= 5) 1.dp else 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.clip(RoundedCornerShape(if (columns >= 5) 3.dp else 17.dp))) {
            AlbumCover(album, data, catalog, prefs, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(if (columns >= 5) 3.dp else 17.dp)), reorderScale, reorderSelected)
            if (columns <= 5 && !prefs.albumCarousel) Text(album.name, Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black.copy(alpha = .85f))))
                .padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 5.dp), color = androidx.compose.ui.graphics.Color.White, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            if (subfolders > 0 && columns <= 5) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp),
                shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = .72f)) {
                Text(localized(R.string.album_subfolder_count, "%1\$d sottocartelle", subfolders),
                    Modifier.padding(horizontal = 6.dp, vertical = 3.dp).testTag("album-subfolders:${album.id}"),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
            if (chosen) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp), shape = androidx.compose.foundation.shape.CircleShape,
                color = selectionColor) { Box(contentAlignment = Alignment.Center) { Text("✓", color = MaterialTheme.colorScheme.background) } }
        }
        if (prefs.albumCarousel && carouselLabel) Text(album.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    } else Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AlbumCover(album, data, catalog, prefs, Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)), reorderScale, reorderSelected)
        Column(Modifier.weight(1f)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium)
            if (!album.hidden) Text(if (data.albumLocked(album.id)) localized(R.string.protected_album, "Album protetto") else "${data.visibleByAlbum[album.id].orEmpty().size} media", color = MaterialTheme.colorScheme.primary)
            if (subfolders > 0) Text(localized(R.string.album_subfolder_count, "%1\$d sottocartelle", subfolders),
                Modifier.testTag("album-subfolders:${album.id}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(album.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (multi) Checkbox(chosen, { onClick() }) else GalleryIcon(R.drawable.ph_arrow_right, null)
    }
}
