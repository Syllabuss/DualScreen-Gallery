package it.ayngallery

import android.content.res.Resources
import android.content.Context
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Application resources work in UI, services and workers without retaining a panel Activity. */
internal object GalleryText {
    @Volatile var resources: Resources? = null
    var revision by mutableIntStateOf(0)
}

/** Android owns app locales on API 33+. Earlier versions store only the language override. */
internal object AppLanguage {
    val choices = listOf("", "it", "en", "ru", "ko", "zh-Hans", "ja", "de", "fr", "pt-BR", "es")
    fun current(context: Context): String {
        GalleryText.revision // Observe external configuration changes in Compose.
        val language = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',')
        else context.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE).getString("app_language", "").orEmpty()
        return language.takeIf { it in choices } ?: when {
            language.startsWith("zh") && !language.contains("Hant") && !language.endsWith("TW") && !language.endsWith("HK") -> "zh-Hans"
            language.startsWith("pt-BR", ignoreCase = true) -> "pt-BR"
            else -> language.substringBefore('-').takeIf { it in choices }.orEmpty()
        }
    }
    fun resources(context: Context): Resources {
        val language = current(context)
        if (language.isEmpty()) return context.resources
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(language))
        return context.createConfigurationContext(configuration).resources
    }
    fun select(app: GalleryApp, language: String) {
        require(language in choices)
        if (current(app) == language) return
        if (Build.VERSION.SDK_INT >= 33)
            app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        else check(app.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE).edit().putString("app_language", language).commit())
        GalleryText.resources = resources(app)
        GalleryText.revision++
        app.widgets.refresh()
        app.photoWidgets.refresh()
    }
}

/** The Italian fallback also keeps validation usable before Android Application attachment. */
internal fun localized(@StringRes id: Int, fallback: String, vararg arguments: Any?): String {
    GalleryText.revision
    val resources = GalleryText.resources
    val text = resources?.getString(id) ?: fallback
    return if (arguments.isEmpty()) text else String.format(
        resources?.configuration?.locales?.get(0) ?: Locale.ITALIAN, text, *arguments)
}
