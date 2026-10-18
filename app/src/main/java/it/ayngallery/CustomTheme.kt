package it.ayngallery

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

data class CustomTheme(val background: String, val surface: String, val accent: String,
    val focus: String, val ink: String, val border: String) {
    fun validate() { require(values().all { it.matches(Regex("#[0-9a-fA-F]{6}")) }) { localized(R.string.text_invalid_colors_124716, "Colori non validi") } }
    fun values() = listOf(background, surface, accent, focus, ink, border)
    fun theme(): GalleryTheme {
        validate()
        val colors = values().map { Color(android.graphics.Color.parseColor(it)) }
        return GalleryTheme(GalleryThemes.CUSTOM, { "Custom theme" }, { "" }, "CUSTOM", colors[0].luminance() < .35f,
            colors[0], colors[2], colors[3], surfaceColor = colors[1], inkColor = colors[4], borderColor = colors[5])
    }
    fun encode(): String = JSONObject().put("background", background).put("surface", surface).put("accent", accent)
        .put("focus", focus).put("ink", ink).put("border", border).toString()
    companion object {
        fun from(theme: GalleryTheme): CustomTheme {
            fun hex(color: Color) = "#%06X".format(java.util.Locale.ROOT, color.toArgb() and 0xFFFFFF)
            return CustomTheme(hex(theme.background), hex(theme.colors.surface), hex(theme.accent), hex(theme.focus),
                hex(theme.colors.onSurface), hex(theme.colors.outlineVariant))
        }
        fun decode(raw: String): CustomTheme {
            val p = JSONObject(raw)
            require(p.keys().asSequence().toSet() == setOf("background", "surface", "accent", "focus", "ink", "border"))
            fun text(key: String) = p.get(key) as? String ?: error(localized(R.string.text_invalid_color_afaf16, "Colore non valido"))
            return CustomTheme(text("background"), text("surface"), text("accent"), text("focus"), text("ink"), text("border")).also { it.validate() }
        }
    }
}
