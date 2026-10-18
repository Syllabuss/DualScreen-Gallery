package it.ayngallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun LanguageSettingsScreen(app: GalleryApp) {
    val current = AppLanguage.current(app)
    var draft by rememberSaveable(current) { mutableStateOf(current) }
    var error by remember { mutableStateOf(false) }
    SettingsSaveAction(enabled = draft != current, tag = "language-save", discard = { draft = current }) {
        error = runCatching { AppLanguage.select(app, draft) }.isFailure
    }
    LazyColumn(Modifier.fillMaxSize().testTag("language-list"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppLanguage.choices.forEach { language ->
            item(key = language) {
                OutlinedCard(Modifier.fillMaxWidth().testTag("language:${language.ifEmpty { "system" }}")
                    .selectable(draft == language, onClick = { draft = language }, role = Role.RadioButton), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(draft == language, null)
                        Text(when (language) {
                            "it" -> "Italiano"
                            "en" -> "English"
                            "ru" -> "Русский"
                            "ko" -> "한국어"
                            "zh-Hans" -> "简体中文"
                            "ja" -> "日本語"
                            "de" -> "Deutsch"
                            "fr" -> "Français"
                            "pt-BR" -> "Português (Brasil)"
                            "es" -> "Español"
                            else -> localized(R.string.language_system, "Lingua del dispositivo")
                        }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (error) item { Text(localized(R.string.language_save_error, "Lingua non salvata. Riprova."), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun AppInfoScreen(activity: PanelActivity? = null, part: String = "ALL") {
    val uriHandler = LocalUriHandler.current
    val host = androidx.activity.compose.LocalActivity.current as? PanelActivity
    val edgeFocus = remember(part) { FocusRequester() }
    DisposableEffect(host, part) {
        val request = { host?.enterPadMode?.invoke(); edgeFocus.requestFocus(); Unit }
        if (part == "HEADER") host?.session?.focusInfoHeader = request
        if (part == "DETAILS") host?.session?.focusInfoDetails = request
        onDispose {
            if (part == "HEADER") { host?.session?.focusInfoHeader = null; host?.infoHeaderBottom = false }
            if (part == "DETAILS") { host?.session?.focusInfoDetails = null; host?.session?.infoDetailsActive = false; host?.infoDetailsTop = false }
        }
    }
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApp
    if (part != "HEADER" && app.session.resetApplicationRequested) {
        var secondsRemaining by remember { mutableIntStateOf(5) }
        LaunchedEffect(Unit) {
            repeat(5) { kotlinx.coroutines.delay(1000); secondsRemaining-- }
        }
        GalleryAlertDialog(onDismissRequest = { app.session.resetApplicationRequested = false },
            icon = { GalleryIcon(R.drawable.ph_arrow_counter_clockwise, null) },
            title = { Text(localized(R.string.app_reset_title, "Ripristinare l’app?")) },
            text = { Text(localized(R.string.app_reset_description, "Verranno rimossi impostazioni, cartelle collegate e dati dell’app. I file nelle cartelle collegate non verranno eliminati. L’app si chiuderà: riaprila per ricominciare dal tutorial.")) },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    TextButton({ app.session.resetApplicationRequested = false }, Modifier.testTag("app-reset-cancel")) {
                        Text(localized(R.string.text_cancel_18c9d9, "Annulla"))
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (secondsRemaining > 0) Text("$secondsRemaining s", Modifier.testTag("app-reset-countdown"),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button({
                            if (secondsRemaining > 0) return@Button
                            app.session.resetApplicationRequested = false
                            val cleared = runCatching { app.getSystemService(android.app.ActivityManager::class.java).clearApplicationUserData() }.getOrDefault(false)
                            if (!cleared) app.session.fail(localized(R.string.app_reset_failed, "Ripristino non riuscito. Riprova dalle impostazioni Android dell’app."))
                        }, Modifier.testTag("app-reset-confirm"), enabled = secondsRemaining == 0, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                            Text(localized(R.string.app_reset_action, "Ripristina l’app"))
                        }
                    }
                }
            })
    }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    LazyColumn(Modifier.fillMaxSize().testTag("app-info"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (part != "DETAILS") item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.app_brand), null, Modifier.size(80.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(localized(R.string.info_author, "Realizzato da Syllabuss"),
                    modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri("https://t.me/syllamisu") }.testTag("info-author"),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (part != "DETAILS") item {
            Button(
                onClick = { uriHandler.openUri("https://ko-fi.com/syllabuss") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("info-support")
            ) {
                Icon(painterResource(R.drawable.ph_heart_duotone), null, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(localized(R.string.info_support, "Supporta su Ko-fi"), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (part != "DETAILS") item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Build " + androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(version) + " · " + version.versionName.orEmpty() + " · " +
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale).format(java.util.GregorianCalendar(2026, 9, 16).time),
                        Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        TextButton({ uriHandler.openUri("https://github.com/Syllabuss/DualScreen-Gallery/blob/main/THIRD_PARTY_NOTICES.md") }, Modifier.testTag("info-licenses").then(if (part == "HEADER") Modifier.focusRequester(edgeFocus).onFocusChanged { host?.infoHeaderBottom = it.isFocused } else Modifier)) {
                            GalleryIcon(R.drawable.ph_link_simple, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(localized(R.string.info_licenses, "Licenze e componenti"), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                        }
                    }
                    InfoRow(localized(R.string.info_channel, "Edizione"), localized(R.string.info_development, "Beta"))
                    InfoRow("Android", android.os.Build.VERSION.RELEASE + " · API ${android.os.Build.VERSION.SDK_INT}")
                    InfoRow(localized(R.string.info_updated, "Aggiornata"), java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale).format(java.util.Date(version.lastUpdateTime)))
                }
            }
        }
        if (part != "HEADER" && activity != null) {
            val links = listOf("SOURCES" to localized(R.string.media_folders_title, "Cartelle multimediali"),
                "COLLECTION" to localized(R.string.text_collections_and_playlists_8b30ee, "Raccolte e playlist"),
                "HIDDEN" to localized(R.string.text_hidden_albums_5ff5a5, "Album nascosti"),
                "SETTINGS_PIN" to localized(R.string.album_pin_settings, "PIN degli album"))
            links.forEach { (key, label) -> item(key = key) {
                OutlinedCard(onClick = { activity.session.settingsSection = "INFO"; activity.windowModel.go(key) },
                    modifier = Modifier.fillMaxWidth().testTag("settings-tool:$key").then(if (part == "DETAILS" && key == "SOURCES") Modifier.focusRequester(edgeFocus).onFocusChanged { host?.infoDetailsTop = it.isFocused } else Modifier)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f)); GalleryIcon(R.drawable.ph_arrow_right, null)
                    }
                }
            } }
            item {
                val app = activity.application as GalleryApp
                val operations by app.files.operations.collectAsState()
                if (operations.any { !FileOperations.terminal(it.phase) }) OutlinedCard(onClick = { activity.windowModel.go("OPERATIONS") },
                    modifier = Modifier.fillMaxWidth().testTag("settings-tool:OPERATIONS")) {
                    Text(localized(R.string.text_operations_to_finish_4f1ecf, "Operazioni da completare"), Modifier.padding(16.dp))
                }
            }
            item { PermissionControls(activity, showFolders = false) }
            item { AppMaintenanceActions(app) }
        }
        if (part != "HEADER") item {
            Column(Modifier.fillMaxWidth().focusable().testTag("info-credits").padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.info_sound_sources, R.string.info_tv_sources, R.string.info_icon_sources).forEachIndexed { index, source ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            GalleryIcon(if (index == 0) R.drawable.ph_info else R.drawable.ph_arrow_bend_down_right, null, Modifier.size(if (index == 0) 24.dp else 18.dp))
                        }
                        Text(localized(source, ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(2f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
    }
}
