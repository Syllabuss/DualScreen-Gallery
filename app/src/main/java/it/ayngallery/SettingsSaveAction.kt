package it.ayngallery

import androidx.compose.runtime.*

internal data class SettingsSave(val enabled: Boolean, val tag: String, val save: () -> Unit, val dirty: Boolean = enabled, val busy: Boolean = false, val discard: () -> Unit = {})
internal val LocalSettingsSave = staticCompositionLocalOf<MutableState<SettingsSave?>?> { null }

@Composable
internal fun SettingsSaveAction(enabled: Boolean, tag: String = "settings-save", dirty: Boolean = enabled, busy: Boolean = false, discard: () -> Unit = {}, save: () -> Unit) {
    val holder = LocalSettingsSave.current
    val latest by rememberUpdatedState(save)
    val latestDiscard by rememberUpdatedState(discard)
    if (holder == null) {
        GalleryIconButton(R.drawable.ph_floppy_disk, localized(R.string.text_save_c5997e, "Salva"), enabled = enabled) { latest() }
    } else DisposableEffect(holder, enabled, tag, dirty, busy) {
        val action = SettingsSave(enabled, tag, { latest() }, dirty, busy, { latestDiscard() })
        holder.value = action
        onDispose { if (holder.value === action) holder.value = null }
    }
}
