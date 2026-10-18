package it.ayngallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ThemeChoice(theme: GalleryTheme, selected: Boolean, applied: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, choose: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val c = theme.colors
    val focus = theme.focus
    Surface(modifier.testTag("theme:${theme.id}").onFocusChanged { focused = it.isFocused }
        .selectable(selected, role = Role.RadioButton, onClick = choose)
        .semantics { stateDescription = if (applied) localized(R.string.text_active_theme_40e4ef, "Tema attivo") else if (selected) localized(R.string.text_selected_not_saved_yet_0b8923, "Selezionato, da salvare") else localized(R.string.text_not_selected_f6efb4, "Non selezionato") },
        shape = GalleryShape, color = c.background, contentColor = c.onSurface,
        border = BorderStroke(if (focused || selected) 3.dp else 1.dp, if (focused) focus else if (selected) c.primary else c.outlineVariant)) {
        Column(Modifier.padding(if (compact) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(theme.name, Modifier.weight(1f), style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = if (compact) 2 else 1)
                RadioButton(selected, null, Modifier.size(if (compact) 20.dp else 24.dp), colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.outline))
            }
            Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 48.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant).padding(if (compact) 5.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(if (compact) 24.dp else 32.dp).clip(RoundedCornerShape(8.dp))
                    .background(c.primary), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ph_images), null, Modifier.size(if (compact) 15.dp else 20.dp), tint = c.onPrimary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth(.75f).height(4.dp).background(c.onSurface, RoundedCornerShape(2.dp)))
                    Box(Modifier.fillMaxWidth(.45f).height(3.dp).background(c.onSurfaceVariant, RoundedCornerShape(2.dp)))
                }
                theme.swatches.forEach { color -> Box(Modifier.size(if (compact) 8.dp else 10.dp).background(color, RoundedCornerShape(5.dp))) }
            }
            if (!compact && theme.description.isNotBlank()) Text(theme.description, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Text(if (applied) localized(R.string.text_active_39df52, "Attivo") else if (selected) localized(R.string.text_not_saved_yet_a9ff08, "Da salvare") else if (theme.dark) localized(R.string.text_dark_316022, "Scuro") else localized(R.string.text_light_8f1b4e, "Chiaro"),
                style = MaterialTheme.typography.labelSmall, color = c.primary, maxLines = 1)
        }
    }
}
