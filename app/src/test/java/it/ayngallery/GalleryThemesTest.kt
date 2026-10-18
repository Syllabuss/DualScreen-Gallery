package it.ayngallery

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class GalleryThemesTest {
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance(); val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
    @Test fun allPalettesKeepTextAndControllerFocusReadable() {
        assertEquals(20, GalleryThemes.all.map { it.id }.distinct().size)
        assertEquals(4, GalleryThemes.all.count { it.family == "AYN" })
        assertEquals(setOf("cyber_citrus", "ultraviolet_drive"), GalleryThemes.all.filter { it.family == "DARK" && it.id in setOf("cyber_citrus", "ultraviolet_drive") }.map { it.id }.toSet())
        assertEquals(setOf("sorbet_pop", "lagoon_candy"), GalleryThemes.all.filter { it.family == "PASTEL" && it.id in setOf("sorbet_pop", "lagoon_candy") }.map { it.id }.toSet())
        val failures = mutableListOf<String>()
        GalleryThemes.all.forEach { theme ->
            val c = theme.colors
            val textPairs = listOf("body" to (c.onSurface to c.surface), "muted" to (c.onSurfaceVariant to c.surfaceVariant),
                "background" to (c.onBackground to c.background), "button" to (c.onPrimary to c.primary),
                "primary label" to (c.primary to c.background), "preview label" to (c.primary to c.surfaceVariant),
                "secondary" to (c.onSecondary to c.secondary), "selection" to (c.onPrimaryContainer to c.primaryContainer),
                "navigation" to (c.onSecondaryContainer to c.secondaryContainer), "error" to (c.onErrorContainer to c.errorContainer))
            textPairs.forEach { (role, pair) ->
                if (contrast(pair.first, pair.second) < 4.5f) failures += "${theme.id}: $role contrast ${contrast(pair.first, pair.second)}"
            }
            listOf(c.background, c.surface, c.surfaceVariant).forEach { surface ->
                if (contrast(theme.focus, surface) < 3f) failures += "${theme.id}: controller focus"
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
        assertEquals(Color.Black, GalleryThemes.get(GalleryThemes.BLACK).background)
        assertFalse(GalleryThemes.get("ayn_rainbow").dark)
    }
}
