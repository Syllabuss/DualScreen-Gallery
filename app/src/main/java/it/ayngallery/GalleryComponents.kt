package it.ayngallery

import android.graphics.drawable.Animatable
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import coil3.gif.repeatCount

val GalleryFocus: Color @Composable get() = MaterialTheme.colorScheme.tertiary
val GalleryShape = RoundedCornerShape(20.dp)
private val LocalGalleryReducedMotion = staticCompositionLocalOf { false }

internal fun DrawScope.drawTransparencyGrid(origin: Offset, bounds: Size, base: Color) {
    val ink = if (base.luminance() > .5f) Color.Black else Color.White
    val first = lerp(base, ink, .08f); val second = lerp(base, ink, .18f)
    val cell = 24.dp.toPx()
    clipRect(origin.x, origin.y, origin.x + bounds.width, origin.y + bounds.height) {
        repeat((bounds.height / cell).toInt() + 1) { row -> repeat((bounds.width / cell).toInt() + 1) { column ->
            drawRect(if ((row + column) % 2 == 0) first else second,
                origin + Offset(column * cell, row * cell), Size(cell, cell))
        } }
    }
}

@Composable
fun GalleryDesign(preferences: GalleryPreferences, animatePalette: Boolean = false, content: @Composable () -> Unit) {
    val target = GalleryThemes.get(preferences).colors
    @Composable fun fade(color: Color): Color = androidx.compose.animation.animateColorAsState(color,
        tween(if (preferences.reducedMotion) 0 else 350), label = "tutorial-theme-color").value
    val colors = if (!animatePalette) target else target.copy(
        primary = fade(target.primary), onPrimary = fade(target.onPrimary), primaryContainer = fade(target.primaryContainer), onPrimaryContainer = fade(target.onPrimaryContainer),
        secondary = fade(target.secondary), onSecondary = fade(target.onSecondary), secondaryContainer = fade(target.secondaryContainer), onSecondaryContainer = fade(target.onSecondaryContainer),
        tertiary = fade(target.tertiary), onTertiary = fade(target.onTertiary), background = fade(target.background), onBackground = fade(target.onBackground),
        surface = fade(target.surface), onSurface = fade(target.onSurface), surfaceVariant = fade(target.surfaceVariant), onSurfaceVariant = fade(target.onSurfaceVariant),
        surfaceContainer = fade(target.surfaceContainer), surfaceContainerLow = fade(target.surfaceContainerLow), surfaceContainerHigh = fade(target.surfaceContainerHigh),
        surfaceContainerHighest = fade(target.surfaceContainerHighest), surfaceContainerLowest = fade(target.surfaceContainerLowest),
        outline = fade(target.outline), outlineVariant = fade(target.outlineVariant), surfaceTint = fade(target.surfaceTint),
        inverseSurface = fade(target.inverseSurface), inverseOnSurface = fade(target.inverseOnSurface), inversePrimary = fade(target.inversePrimary),
        tertiaryContainer = fade(target.tertiaryContainer), onTertiaryContainer = fade(target.onTertiaryContainer),
        error = fade(target.error), onError = fade(target.onError), errorContainer = fade(target.errorContainer), onErrorContainer = fade(target.onErrorContainer),
        surfaceDim = fade(target.surfaceDim), surfaceBright = fade(target.surfaceBright), scrim = fade(target.scrim))
    CompositionLocalProvider(LocalGalleryReducedMotion provides preferences.reducedMotion) {
        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onSurface) { content() }
        }
    }
}

@Composable
fun GalleryIcon(@DrawableRes resource: Int, description: String?, modifier: Modifier = Modifier) {
    Icon(painterResource(resource), description, modifier.size(24.dp))
}

@Composable
internal fun GallerySettingSwitch(label: String, value: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, change: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp, if (focused) GalleryFocus else Color.Transparent, RoundedCornerShape(12.dp))
        .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
        .clip(RoundedCornerShape(12.dp)).toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = change).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, null, enabled = enabled)
    }
}

@Composable
fun GalleryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                  primary: Boolean = false, icon: Int? = null, destructive: Boolean = false, onClick: () -> Unit) {
    var focus by remember { mutableStateOf(false) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? GalleryApp
    OutlinedButton({ if (text.startsWith(localized(R.string.text_save_c5997e, "Salva"))) app?.sounds?.play("save"); onClick() }, modifier.heightIn(min = 48.dp).onFocusChanged { focus = it.isFocused }, enabled,
        shape = RoundedCornerShape(14.dp), border = BorderStroke(if (focus) 3.dp else 1.dp,
            if (focus) GalleryFocus else if (destructive) MaterialTheme.colorScheme.error.copy(alpha = .5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (primary) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (destructive) MaterialTheme.colorScheme.error else if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
        if (icon != null || destructive) { GalleryIcon(icon ?: R.drawable.ph_trash, null); Spacer(Modifier.width(10.dp)) }
        Text(text, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
fun GalleryIconButton(@DrawableRes icon: Int, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    var focus by remember { mutableStateOf(false) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? GalleryApp
    IconButton({ if (icon == R.drawable.ph_floppy_disk) app?.sounds?.play("save") else if (icon == R.drawable.ph_gear_six) app?.sounds?.play("settings"); onClick() }, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).onFocusChanged { focus = it.isFocused }
        .border(if (focus) 3.dp else 0.dp, if (focus) GalleryFocus else Color.Transparent, RoundedCornerShape(14.dp)), enabled = enabled) {
        GalleryIcon(icon, description)
    }
}

@Composable
fun EmptyGallery(title: String, message: String, icon: Int = R.drawable.ph_images,
                 action: String? = null, featured: Boolean = false, animateGlow: Boolean = true, iconBelowTitle: Boolean = false,
                 actionIcon: Int = R.drawable.ph_folders, heroIcon: (@Composable () -> Unit)? = null, glowTint: Color? = null, onAction: () -> Unit = {}) {
    if (featured) {
        val glowColor = glowTint ?: MaterialTheme.colorScheme.primary
        val animatedGlow = if (animateGlow && !LocalGalleryReducedMotion.current) {
            val motion = rememberInfiniteTransition(label = "empty glow")
            val value by motion.animateFloat(.10f, .25f, androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(2400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse), label = "empty glow alpha")
            value
        } else .15f
        Box(Modifier.fillMaxSize().testTag("empty-gallery"), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.radialGradient(listOf(glowColor.copy(alpha = animatedGlow), Color.Transparent),
                    center = Offset(size.width * .5f, size.height * .42f), radius = size.maxDimension * .72f))
            }
            Surface(Modifier.fillMaxWidth(.78f).padding(18.dp).heightIn(min = 320.dp).clickable(enabled = action != null, role = Role.Button, onClick = onAction).testTag("empty-gallery-card"), shape = GalleryShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)),
                shadowElevation = 8.dp) {
                Column(Modifier.padding(horizontal = 28.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    if (iconBelowTitle) Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (heroIcon != null) heroIcon() else Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .13f)) {
                        Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) { GalleryIcon(icon, null, Modifier.size(34.dp)) }
                    }
                    if (!iconBelowTitle) Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (action != null) Button(onAction, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        GalleryIcon(actionIcon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(action)
                    }
                }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(16.dp).testTag("empty-gallery"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { GalleryIcon(icon, null, Modifier.size(28.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) GalleryButton(action, Modifier.widthIn(max = 144.dp), primary = true, onClick = onAction)
    }
}

@Composable
fun MediaThumbnail(item: MediaEntity?, data: CatalogSnapshot, catalog: Catalog, preferences: GalleryPreferences,
                   modifier: Modifier = Modifier, animated: Boolean = false, active: Boolean = true,
                   scale: ContentScale = ContentScale.Crop, animationPaused: Boolean = false, artwork: Boolean = false,
                   dimensions: (Int, Int) -> Unit = { _, _ -> }, crossfadeMillis: Int = 220, decoded: (Boolean?) -> Unit = {}, backgroundColor: Color? = null,
                   lockedAlbumCover: String? = null, frame: android.graphics.Bitmap? = null, filterQuality: androidx.compose.ui.graphics.FilterQuality = androidx.compose.ui.graphics.FilterQuality.Low) {
    val context = LocalContext.current
    val cacheRevision by catalog.cacheRevision.collectAsState()
    var loading by remember(item?.id, item?.modified) { mutableStateOf(true) }
    var failed by remember(item?.id, item?.modified) { mutableStateOf(false) }
    var drawable by remember(item?.id) { mutableStateOf<Animatable?>(null) }
    val coverAllowed = lockedAlbumCover?.let(data.albumsById::get)?.let { data.albumLocked(it.id) && data.lockedCover(it)?.id == item?.id } == true
    val allowed = item != null && (coverAllowed || if (artwork) data.artworkAllowed(item) else data.usable(item))
    val reference = remember(item?.id, item?.modified, item?.artworkUri, item?.artworkMediaId, data, allowed, artwork, preferences.artworkOrder, cacheRevision) {
        if (!allowed) null else if (coverAllowed) runCatching { catalog.lockedCoverUri(lockedAlbumCover!!, item!!, data).toString() to "locked-cover/${item.id}/${item.modified}" }.getOrNull()
        else if (item.kind == "AUDIO") catalog.artworkReference(item, data)
        else try { catalog.uri(item, data, artwork = artwork).toString() to "${item.id}/${item.modified}/${item.size}" } catch (_: SecurityException) { null }
    }
    var loadedReference by remember(reference, item?.id, cacheRevision) { mutableStateOf(reference.takeUnless { item?.kind == "VIDEO" }) }
    LaunchedEffect(item?.id, item?.modified, item?.size, data, allowed, active, cacheRevision) {
        if (active && allowed && item?.kind == "AUDIO") loadedReference = catalog.loadArtworkReference(item, data)
        if (active && allowed && item?.kind == "VIDEO" && reference != null) {
            loadedReference = catalog.videoThumbnail(item, reference.first) ?: reference
        }
    }
    val uri = loadedReference?.first
    val gif = item?.kind == "GIF"
    LaunchedEffect(uri, active) { if (item != null && active && uri == null) decoded(false) }
    val animation = gif && animated && !preferences.gameMode && active && frame == null
    val request = remember(loadedReference, animation, preferences.reducedMotion, active, crossfadeMillis, cacheRevision) {
        ImageRequest.Builder(context).data(uri).crossfade(if (preferences.reducedMotion) 0 else crossfadeMillis)
            .repeatCount(-1)
            .memoryCacheKey("${loadedReference?.second}/$animation/$cacheRevision/$uri")
            .apply {
                if (gif && !animation) decoderFactory(BitmapFactoryDecoder.Factory())
                if (item?.kind == "VIDEO" && loadedReference == reference) decoderFactory(VideoFrameDecoder.Factory())
                if (animation) memoryCachePolicy(CachePolicy.DISABLED)
            }.build()
    }
    DisposableEffect(drawable, animation, active, animationPaused) {
        if (animation && active && !animationPaused) drawable?.start() else drawable?.stop()
        onDispose { drawable?.stop() }
    }
    Box(if (backgroundColor != null) modifier.background(backgroundColor) else modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)))), contentAlignment = Alignment.Center) {
        if (frame != null && active) {
            SideEffect { dimensions(frame.width, frame.height); decoded(true) }
            androidx.compose.foundation.Image(frame.asImageBitmap(), contentDescription = item?.name, modifier = Modifier.fillMaxSize(), contentScale = scale, filterQuality = filterQuality)
        } else if (uri != null && active) {
            AsyncImage(request, contentDescription = item?.name, modifier = Modifier.fillMaxSize(), contentScale = scale, filterQuality = filterQuality,
                onLoading = { loading = true; failed = false; decoded(null) }, onSuccess = {
                    loading = false
                    dimensions(it.result.image.width, it.result.image.height)
                    drawable = it.result.image.asDrawable(context.resources) as? Animatable
                    decoded(true)
                }, onError = { loading = false; failed = true; decoded(false) })
            if (loading && !preferences.reducedMotion && !preferences.gameMode) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else GalleryIcon(if (item?.kind == "AUDIO") R.drawable.ph_music_notes else R.drawable.ph_images, null, Modifier.size(38.dp))
        if ((item != null && !allowed) || failed) {
            Text(if (!allowed) localized(R.string.text_unavailable_ce1391, "Non disponibile") else localized(R.string.text_preview_unreadable_9374e0, "Anteprima non leggibile"), Modifier.align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(6.dp), fontSize = 11.sp)
        }
    }
}

@Composable
private fun favoritePulse(favorite: Boolean, identity: Any?): Float {
    var previous by remember(identity) { mutableStateOf(favorite) }
    val pulse = remember(identity) { androidx.compose.animation.core.Animatable(1f) }
    val reduced = LocalGalleryReducedMotion.current
    LaunchedEffect(favorite, identity) {
        val added = favorite && !previous
        previous = favorite
        pulse.snapTo(1f)
        if (added && !reduced) { pulse.animateTo(1.18f, tween(120)); pulse.animateTo(1f, tween(220)) }
    }
    return pulse.value
}

@Composable
internal fun FavoriteHeart(favorite: Boolean, description: String?, modifier: Modifier = Modifier,
    tint: Color = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, identity: Any? = null) {
    val heartColor = if (favorite) Color(0xFFFF38AF) else tint
    val pulse = favoritePulse(favorite, identity)
    Icon(painterResource(if (favorite) R.drawable.ph_heartbeat else R.drawable.ph_heart), description,
        modifier.graphicsLayer { scaleX = pulse; scaleY = pulse }.drawBehind {
            if (favorite) drawCircle(Brush.radialGradient(listOf(heartColor.copy(alpha = .32f), Color.Transparent),
                center = center, radius = size.maxDimension * .8f), radius = size.maxDimension * .8f)
        }, tint = heartColor)
}

@Composable
internal fun TvFavoriteStar(favorite: Boolean, modifier: Modifier = Modifier, identity: Any? = null) {
    val color = if (favorite) Color(0xFFFFD54F) else LocalContentColor.current
    val pulse = favoritePulse(favorite, identity)
    Icon(painterResource(if (favorite) R.drawable.ph_star_duotone else R.drawable.ph_star), null,
        modifier.graphicsLayer { scaleX = pulse; scaleY = pulse }.drawBehind {
            if (favorite) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .32f), Color.Transparent),
                center = center, radius = size.maxDimension * .8f), radius = size.maxDimension * .8f)
        }, tint = color)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(item: MediaEntity, data: CatalogSnapshot, catalog: Catalog, preferences: GalleryPreferences,
              selected: Boolean, multi: Boolean, checked: Boolean, animated: Boolean, active: Boolean,
              focus: () -> Unit, click: () -> Unit, actions: () -> Unit, modifier: Modifier = Modifier, list: Boolean = false, mosaic: Boolean = false, smallList: Boolean = false, compactCorners: Boolean = false, showArtist: Boolean = true) {
    val panel = androidx.activity.compose.LocalActivity.current as? PanelActivity
    val tileShape = if (compactCorners) RoundedCornerShape(5.dp) else GalleryShape
    val imageShape = if (mosaic || compactCorners) tileShape else RoundedCornerShape(18.dp)
    val app = LocalContext.current.applicationContext as GalleryApp
    val playingTrack by remember(app) { app.music.state.map { it.id to it.playing }.distinctUntilChanged() }.collectAsState(null to false)
    val isPlaying = item.kind == "AUDIO" && playingTrack.first == item.id && playingTrack.second
    val tileFocus = remember(item.id) { FocusRequester() }
    val coverRotation = if (list && isPlaying && !preferences.reducedMotion) {
        val transition = rememberInfiniteTransition(label = "playing-cover")
        transition.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(10000, easing = androidx.compose.animation.core.LinearEasing)), label = "playing-cover-angle").value
    } else 0f
    DisposableEffect(isPlaying, list, preferences.reducedMotion) {
        if (isPlaying && !list && !preferences.reducedMotion) app.widgets.gridPulseEnabled(true)
        onDispose { if (isPlaying && !list && !preferences.reducedMotion) app.widgets.gridPulseEnabled(false) }
    }
    val pulse by (if (isPlaying && !list) app.widgets.pulseLevel else remember { kotlinx.coroutines.flow.MutableStateFlow(0f) }).collectAsState()
    val gridPulse by androidx.compose.animation.core.animateFloatAsState(
        if (!list && isPlaying && !preferences.reducedMotion) .88f + pulse.coerceIn(0f, 1f) * .12f else 1f,
        tween(100), label = "grid-playing-cover")
    val fallbackAspect = if (item.kind == "AUDIO") 1f else mediaCachedAspect(item) ?: if (item.kind == "VIDEO") 16f / 9f else .8f
    val mosaicAspect by produceState(fallbackAspect, item.id, item.modified, item.size, mosaic) {
        if (mosaic && item.kind != "AUDIO") {
            val dimensions = mediaPixelResolution(item, data, catalog).split(" × ")
            val width = dimensions.getOrNull(0)?.toFloatOrNull()
            val height = dimensions.getOrNull(1)?.toFloatOrNull()
            if (width != null && height != null && height > 0) value = (width / height).coerceIn(.35f, 3f)
        }
    }
    var focused by remember(item.id) { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) focus() }
    val titleScrolling = if ((focused || hovered) && !preferences.reducedMotion && !preferences.gameMode)
        Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
    Surface(modifier.focusRequester(tileFocus).hoverable(hover).then(if (mosaic) Modifier else Modifier.animateContentSize(tween(if (preferences.reducedMotion) 0 else 260))).fillMaxWidth().onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) panel?.focusedMediaItemId = item.id
        else if (panel?.focusedMediaItemId == item.id) panel.focusedMediaItemId = null
        if (it.isFocused) focus()
    }.clip(tileShape).combinedClickable(onClick = { click(); tileFocus.requestFocus() }, onLongClick = actions), shape = tileShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (multi && checked || !multi && focused) 3.dp else 2.dp, if (multi) { if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant } else if (focused) GalleryFocus else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        if (list) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaThumbnail(item, data, catalog, preferences, Modifier.size(if (item.kind == "AUDIO") 36.dp else if (smallList) 48.dp else 72.dp).graphicsLayer { rotationZ = coverRotation }.clip(GalleryShape), animated, active)
            Column(Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(item.title ?: item.name,
                modifier = Modifier.weight(1f).then(titleScrolling),
                maxLines = 1, overflow = TextOverflow.Ellipsis)

                }
                if (item.kind != "AUDIO") Text("${fileSize(item.size)} | ${item.name.substringAfterLast('.', item.mime.substringAfterLast('/')).uppercase(java.util.Locale.ROOT)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (!item.artist.isNullOrBlank()) Text(item.artist!!, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
            if (item.kind != "AUDIO") MediaListResolution(item, data, catalog)
            if (isPlaying && list) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_play, null, Modifier.size(24.dp)) }
            if (item.kind == "AUDIO") IconButton(onClick = { catalog.scope.launch { catalog.favorite(item.id) } }) {
                FavoriteHeart(item.favorite, localized(R.string.text_favorite_06bae3, "Preferito"), tint = if (item.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (multi) Checkbox(checked, { click() })
        } else Column(Modifier.padding(if (mosaic || compactCorners) 0.dp else 2.dp)) {
            Box(Modifier.clip(imageShape)) {
                MediaThumbnail(item, data, catalog, preferences, Modifier.fillMaxWidth().aspectRatio(if (mosaic) mosaicAspect else 1.2f).graphicsLayer { scaleX = gridPulse; scaleY = gridPulse }.clip(imageShape), animated, active)
                if (item.kind in setOf("AUDIO", "VIDEO") && !item.convertedGif) {
                    Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.45f)) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ph_play), localized(R.string.text_play_da1576, "Riproduci"), tint = Color.White) }
                    }
                }
                if (((item.kind == "GIF" || item.convertedGif) && !preferences.hideGifBadge) || (item.duration != null && !item.convertedGif && item.kind != "GIF")) Row(Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp)).padding(5.dp)) {
                    Text(if (item.kind == "GIF" || item.convertedGif) "GIF" else if (item.duration != null) formatTime(item.duration) else "",
                        color = Color.White, fontSize = 10.sp)
                }
                if (item.favorite) FavoriteHeart(true, localized(R.string.text_favorite_06bae3, "Preferito"), Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                if (multi && checked) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp),
                    shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.secondary) {
                    Box(contentAlignment = Alignment.Center) { Text("✓", color = MaterialTheme.colorScheme.onSecondary) }
                }
            }
            if (preferences.showMediaNames || item.kind == "AUDIO") Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title?.takeIf { it.isNotBlank() } ?: item.name, Modifier.weight(1f).then(titleScrolling),
                    maxLines = if (item.kind == "AUDIO") 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)

            }
            if (item.kind == "AUDIO" && showArtist) Text(item.artist ?: localized(R.string.text_artist_unavailable_c612a4, "Artista non disponibile"), Modifier.padding(start = 8.dp, end = 8.dp, bottom = 7.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
internal fun TrashActionButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    GalleryButton(text, modifier, enabled = enabled, destructive = true, onClick = onClick)
}

@Composable
internal fun GalleryDialogTitle(icon: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GalleryIcon(icon, null, Modifier.size(26.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
