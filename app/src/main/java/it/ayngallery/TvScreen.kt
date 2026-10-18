package it.ayngallery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal fun tvError(failure: Throwable): String = localized(when ((failure as? TvNetworkFailure)?.reason) {
    "BLOCKED" -> R.string.tv_error_blocked
    "FORBIDDEN" -> R.string.tv_error_forbidden
    "NOT_FOUND" -> R.string.tv_error_not_found
    "LARGE" -> R.string.tv_error_large
    else -> if (failure is IllegalArgumentException || failure is java.nio.charset.CharacterCodingException) R.string.tv_error_format else R.string.tv_error_network
}, "Canale non disponibile. Riprova.")

@Composable
internal fun TvArtwork(app: GalleryApp, entry: TvCatalogEntry, modifier: Modifier, overrideArtwork: String? = null, color: ((androidx.compose.ui.graphics.Color) -> Unit)? = null, loadRemote: Boolean = true, maxSide: Int = 512, logoOnly: Boolean = false, crop: Boolean = false, placeholder: Boolean = true, imageReady: ((Boolean) -> Unit)? = null) {
    val cacheRevision by app.catalog.cacheRevision.collectAsStateWithLifecycle()
    val image by produceState<android.graphics.Bitmap?>(null, entry.logoUrl, tvKey(entry), app.tv.revision, cacheRevision, overrideArtwork, loadRemote, maxSide, logoOnly) {
        value = app.tv.image(entry, overrideArtwork, loadRemote, maxSide, logoOnly)
    }
    LaunchedEffect(image) {
        imageReady?.invoke(image != null)
        if (color != null) image?.let { bitmap ->
            val pixels = IntArray(32 * 32) { index -> bitmap.getPixel((index % 32 * bitmap.width / 32).coerceAtMost(bitmap.width - 1), (index / 32 * bitmap.height / 32).coerceAtMost(bitmap.height - 1)) }
            color.invoke(androidx.compose.ui.graphics.Color(musicArtworkColors(pixels, MusicColors(0xff252530.toInt(), 0xff888888.toInt())).accent))
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        else if (placeholder) GalleryIcon(R.drawable.ph_television_simple, null, Modifier.size(40.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvScreen(activity: PanelActivity, preview: Boolean) {
    val app = activity.application as GalleryApp
    val tv = app.tv
    if (!tv.store.acknowledged || !tv.store.sourceAcknowledged) {
        LaunchedEffect(Unit) { tv.noticeVisible = true }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_television_simple, null, Modifier.size(64.dp)) }
        return
    }
    LaunchedEffect(tv.store) { try {
        tv.store.load()
        if (tv.selected == null) tv.selected = tv.store.channels.firstOrNull()?.entry
    } catch (failure: Exception) { if (failure is CancellationException) throw failure; tv.failure = tvError(failure) } }
    androidx.activity.compose.BackHandler(enabled = !preview) {
        if (!tv.backNavigation()) activity.windowModel.go("VIDEO")
    }
    val selected = tv.selected
    val play: (TvCatalogEntry) -> Unit = { entry ->
        val panel = app.session.previewActivity() ?: activity
        tv.play(entry, panel.slot)
    }
    if (!preview && !app.session.canSwap() && app.video.liveChannel != null && app.video.player != null && !app.video.floating && !app.video.pipOpening) {
        TvVideoPreview(activity)
        return
    }
    if (preview) {
        if (app.video.liveChannel != null && app.video.player != null && (!(app.video.floating || app.video.pipOpening) || selected?.let(::tvKey) == app.video.liveChannel?.let(::tvKey))) TvVideoPreview(activity)
        else if (selected != null) Box(Modifier.fillMaxSize()) {
            TvArtwork(app, selected, Modifier.fillMaxSize(), crop = true)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = .78f)))
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            TvArtwork(app, selected, Modifier.size(144.dp), logoOnly = true, placeholder = false)
            Text(tv.store.channels.firstOrNull { it.matches(selected) }?.name ?: selected.name, style = MaterialTheme.typography.headlineSmall)
            selected.group?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GalleryButton(localized(R.string.tv_watch, "Guarda ora"), enabled = app.video.liveLoadingChannel == null, primary = true, icon = R.drawable.ph_play) { play(selected) }
                IconButton(onClick = { tv.requestFavorite(selected) }) {
                    TvFavoriteStar(tv.store.channels.any { it.matches(selected) && it.favorite }, Modifier.size(24.dp), identity = tvKey(selected))
                }
                GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.tv_edit, "Modifica canale")) { tv.editSelected() }
            }
            }
            TvChannelBanner(app)
        } else EmptyGallery(localized(R.string.tv_my_channels, "I miei canali"), localized(R.string.tv_empty_help, "Sfoglia il catalogo o aggiungi un singolo canale."), R.drawable.ph_television_simple, localized(R.string.tv_browse, "Sfoglia canali"), featured = true, actionIcon = R.drawable.ph_magnifying_glass) { tv.browsing = true }
    } else Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!tv.browsing && tv.store.channels.isNotEmpty()) Row(Modifier.fillMaxWidth().onFocusChanged { activity.settingsHeaderHasFocus = it.hasFocus }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
            Text(localized(if (tv.browsing) R.string.tv_browse else R.string.tv_my_channels, "I miei canali"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (!tv.browsing) TextButton({ tv.browsing = true; tv.query = "" }) { Text(localized(R.string.tv_browse, "Sfoglia canali")) }
            GalleryIconButton(if (tv.store.grid) R.drawable.ph_rows else R.drawable.ph_grid_nine, localized(R.string.tools_view, "Vista"), Modifier.testTag("tv-view")) { tv.store.grid(!tv.store.grid) }
            GalleryIconButton(R.drawable.ph_plus_circle, localized(R.string.tv_add, "Aggiungi canale"), Modifier.testTag("tv-add")) { tv.adding = true }
        }

        if (!tv.browsing && tv.store.loaded && tv.store.channels.isEmpty()) {
            EmptyFolderButton(localized(R.string.tv_browse, "Sfoglia canali")) { tv.browsing = true; tv.query = "" }
        } else {
            var rows by remember { mutableStateOf<List<TvCatalogEntry>>(emptyList()) }
            var catalogs by remember { mutableStateOf<List<TvCatalog>>(emptyList()) }
            var busy by remember { mutableStateOf(false) }
            var retry by remember { mutableIntStateOf(0) }
            LaunchedEffect(tv.browsing, tv.catalog, tv.query.isNotBlank(), tv.store.sources, retry) {
                if (tv.browsing) {
                    rows = emptyList(); catalogs = emptyList(); busy = true; tv.failure = null
                    try {
                        if (tv.query.isNotBlank()) { kotlinx.coroutines.delay(300); rows = tv.searchSources() }
                        else if (tv.catalog != null) rows = tv.entries(tv.catalog!!) else catalogs = tv.catalogs()
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { tv.failure = tvError(failure); tv.retryAction = { retry++ } }
                    finally { busy = false }
                }
            }
            if (tv.browsing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(tv.query, { tv.query = it }, Modifier.weight(1f).testTag("tv-search"), singleLine = true,
                        label = { Text(localized(R.string.tv_search, "Cerca canali")) })
                    GalleryIconButton(R.drawable.ph_plus_circle, localized(R.string.tv_personal_source_add, "Aggiungi playlist"), Modifier.testTag("tv-source-add")) { tv.addingSource = true }
                    GalleryIconButton(if (tv.store.grid) R.drawable.ph_rows else R.drawable.ph_grid_nine,
                        localized(R.string.tools_view, "Vista"), Modifier.testTag("tv-view")) { tv.store.grid(!tv.store.grid) }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tv.browsing && catalogs.isEmpty() && rows.isEmpty() && !busy && tv.store.sources.isEmpty())
                GalleryButton(localized(R.string.tv_personal_source_add, "Aggiungi playlist"), Modifier.fillMaxWidth(), icon = R.drawable.ph_plus_circle) { tv.addingSource = true }

            val entries = if (tv.browsing) rows else tv.store.channels.map { it.entry }
            val visibleEntries = remember(entries, tv.query) { entries.filter { it.name.contains(tv.query, true) } }
            val visibleCatalogs = remember(catalogs, tv.query) { catalogs.filter { it.name.contains(tv.query, true) } }
            if (tv.browsing && tv.catalog == null && tv.query.isBlank()) LazyVerticalGrid(GridCells.Fixed(if (tv.store.grid) 4 else 1), Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visibleCatalogs, key = { it.id }) { catalog ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (tv.store.grid) Modifier.aspectRatio(1.4f) else Modifier)
                            .clickable { tv.abandonTransientActions(); tv.catalog = catalog; tv.query = "" }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(catalog.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            GalleryIconButton(R.drawable.ph_trash, localized(R.string.text_delete_permanently_8a62e5, "Elimina")) { tv.removingSource = catalog }
                        }
                    }
                }
            } else TvChannelCollection(activity, visibleEntries, reorderable = !tv.browsing && tv.query.isBlank(), Modifier.weight(1f), play)
            if (!app.session.canSwap() && selected != null) GalleryButton(localized(R.string.tv_watch, "Guarda ora"), enabled = app.video.liveLoadingChannel == null, icon = R.drawable.ph_play) { play(selected) }
        }
    }
}

@Composable
internal fun TvChannelDialog(activity: PanelActivity) {
    val app = activity.application as GalleryApp
    val tv = app.tv
    val original = tv.selected.takeIf { tv.editing }
    tv.beginDraft(original)
    var name by tv::draftName
    var url by tv::draftUrl
    var displayTitle by tv::draftTitle
    var artwork by tv::draftArtwork
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var allowUnverified by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    GalleryAlertDialog(onDismissRequest = { if (!busy && !tv.artworkPickerPending) tv.closeDraft() },
        modifier = Modifier.fillMaxWidth(.9f).heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .86f).dp).testTag("tv-channel-dialog"),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        spreadButtons = true,
        title = { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryIcon(R.drawable.ph_television_simple, null, Modifier.size(28.dp))
            Text(localized(if (original == null) R.string.tv_add else R.string.tv_edit, "Canale TV"))
        } },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 270.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(512) }, Modifier.fillMaxWidth().testTag("tv-channel-name"), label = { Text(localized(R.string.text_name_508690, "Nome")) }, singleLine = true)
            if (original == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GalleryIcon(R.drawable.ph_info, null, Modifier.size(20.dp))
                    Text(localized(R.string.tv_network_notice, ""), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(url, { url = it.take(8192); allowUnverified = false; error = null }, Modifier.fillMaxWidth(), label = { Text(localized(R.string.tv_url, "URL HTTPS dello stream")) }, singleLine = true, isError = error != null)
                Text(localized(R.string.tv_unverified, "Un solo stream HLS. Il collegamento verrà verificato senza avviare la riproduzione."), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (artwork == null) Button({ activity.chooseTvArtwork() }, enabled = !busy && !tv.artworkPickerPending,
                    modifier = Modifier.testTag("tv-choose-artwork")) { Text(localized(R.string.tv_artwork, "Scegli miniatura")) }
                else Box(Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy && !tv.artworkPickerPending) { activity.chooseTvArtwork() }
                    .semantics { contentDescription = localized(R.string.tv_artwork, "Scegli miniatura") }.testTag("tv-edit-artwork")) {
                    TvArtwork(app, original ?: TvCatalogEntry("https://example.com/new.m3u8", name, null, null, null), Modifier.fillMaxSize(), overrideArtwork = artwork, crop = true)
                    Surface(Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp), shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Box(contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_pencil, null, Modifier.size(18.dp)) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (original != null) TextButton({ name = original.name; displayTitle = ""; tv.restoreDraftArtwork() }, enabled = !busy && !tv.artworkPickerPending) {
                    Text(localized(R.string.tv_restore, "Ripristina dati originali"))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        } }, confirmButton = { TextButton(enabled = !busy && !tv.artworkPickerPending && (original != null || TvNetwork.validUrl(url.trim())), onClick = {
            busy = true
            scope.launch {
                try {
                    val entry = original ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (!allowUnverified) TvLiveSource.verify(url.trim())
                        TvCatalogEntry(url.trim(), name.trim().ifBlank { java.net.URI(url.trim()).host }, null, null, null)
                    }
                    tv.store.customize(entry, name.ifBlank { null }, artwork, displayTitle)
                    tv.selected = entry; tv.revision++; tv.closeDraft()
                    app.sounds.play("save")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    allowUnverified = original == null && (failure is java.net.UnknownHostException || failure is java.net.ConnectException || failure is java.net.SocketTimeoutException ||
                        failure is TvNetworkFailure && failure.reason in setOf("NETWORK", "FORBIDDEN", "NOT_FOUND"))
                    error = tvError(failure) + if (allowUnverified) "\n" + localized(R.string.tv_save_unverified, "Premi di nuovo Salva per conservare il canale non verificato.") else ""
                }
                finally { busy = false }
            }
        }) { Text(localized(R.string.text_save_c5997e, "Salva")) } }, dismissButton = {
            TextButton(enabled = !busy && !tv.artworkPickerPending, onClick = { tv.closeDraft() }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvNetworkInfo() {
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = tooltip,
        tooltip = { PlainTooltip { Column {
            Text(localized(R.string.tv_network_notice, ""))
        } } }) {
        GalleryIconButton(R.drawable.ph_info, localized(R.string.tv_network_notice, ""), Modifier.testTag("tv-network-info")) {
            scope.launch { tooltip.show() }
        }
    }
}
