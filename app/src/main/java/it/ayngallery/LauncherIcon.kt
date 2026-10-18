package it.ayngallery

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

internal object LauncherIcon {
    fun current(context: Context): String = context.getSharedPreferences("launcher_icon", Context.MODE_PRIVATE).getString("style", "LIGHT") ?: "LIGHT"
    fun select(context: Context, style: String) {
        require(style in setOf("LIGHT", "DARK"))
        val pm = context.packageManager
        // Enable the replacement first so the launcher always has an entry.
        listOf(style, if (style == "DARK") "LIGHT" else "DARK").forEach { key ->
            pm.setComponentEnabledSetting(ComponentName(context, "${context.packageName}.Launcher${if (key == "DARK") "Dark" else "Light"}"),
                if (key == style) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
        check(context.getSharedPreferences("launcher_icon", Context.MODE_PRIVATE).edit().putString("style", style).commit())
        (context.applicationContext as? GalleryApp)?.let { app -> context.mainExecutor.execute { app.session.refreshTaskIcons() } }
    }
    fun applyTaskIcon(activity: android.app.Activity) {
        val drawable = androidx.core.content.ContextCompat.getDrawable(activity,
            if (current(activity) == "DARK") R.drawable.app_icon_dark else R.drawable.app_icon_light) ?: return
        activity.setTaskDescription(android.app.ActivityManager.TaskDescription(activity.getString(R.string.app_name), drawable.toBitmap(96, 96)))
    }
}

@Composable
internal fun LauncherIconChoice(selected: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(localized(R.string.launcher_icon_title, "Icona dell’app"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        listOf("LIGHT" to R.drawable.app_icon_light, "DARK" to R.drawable.app_icon_dark).forEach { (key, icon) ->
            Surface(onClick = { change(key) }, modifier = Modifier.testTag("launcher-icon:$key"), color = androidx.compose.ui.graphics.Color.Transparent) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == key, null)
                    Image(painterResource(icon), localized(if (key == "DARK") R.string.widget_dark else R.string.widget_light, if (key == "DARK") "Scuro" else "Chiaro"), Modifier.size(48.dp))
                }
            }
        }
    }
}
