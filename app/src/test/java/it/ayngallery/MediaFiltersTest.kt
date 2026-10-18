package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class MediaFiltersTest {
    private fun image(name: String, mime: String, kind: String = "IMAGE") = MediaEntity("image", "doc:image", "content://fixture/image", "album", name, mime, kind, 10, 1)
    @Test fun favoritesCanCombineTypesWithoutAdmittingUnknownOrDuplicateFilters() {
        assertTrue(MediaFilters.valid("IMAGE+GIF+VIDEO"))
        assertTrue(MediaFilters.matches("IMAGE+VIDEO", image("movie.mp4", "video/mp4", "VIDEO")))
        assertTrue(MediaFilters.matches("IMAGE+VIDEO", image("photo.png", "image/png")))
        assertFalse(MediaFilters.matches("IMAGE+VIDEO", image("song.mp3", "audio/mpeg", "AUDIO")))
        assertFalse(MediaFilters.valid("ALL+IMAGE")); assertFalse(MediaFilters.valid("IMAGE+IMAGE"))
        assertFalse(MediaFilters.valid("IMAGE+BROKEN"))
    }
    @Test fun canonicalMimeWinsOverNameAndGenericMimeUsesCaseInsensitiveExtensions() {
        assertTrue(MediaFilters.matches("PNG", image("wrong.jpg", "image/png")))
        assertFalse(MediaFilters.matches("JPEG", image("wrong.jpg", "image/png")))
        assertTrue(MediaFilters.matches("JPEG", image("A.JPEG", "application/octet-stream")))
        assertTrue(MediaFilters.matches("JPEG", image("unknown", "IMAGE/JPEG; charset=binary")))
        assertTrue(MediaFilters.matches("WEBP", image("B.WEBP", "")))
        assertTrue(MediaFilters.matches("OTHER_IMAGE", image("P.HEIC", "image/heic")))
        assertFalse(MediaFilters.matches("PNG", image("cover.png", "image/png", "AUDIO")))
        assertFalse(MediaFilters.matches("PNG", image("movie.png", "video/mp4", "VIDEO")))
    }
    @Test fun typeFiltersKeepGifVideoAndMusicSeparateAndEveryChoiceIsValidated() {
        val gif = image("animation.GIF", "image/gif", "GIF")
        assertTrue(MediaFilters.matches("ALL", gif)); assertTrue(MediaFilters.matches("GIF", gif))
        assertFalse(MediaFilters.matches("IMAGE", gif)); assertFalse(MediaFilters.matches("OTHER_IMAGE", gif))
        assertTrue(MediaFilters.matches("VIDEO", image("movie.mp4", "video/mp4", "VIDEO")))
        assertTrue(MediaFilters.matches("AUDIO", image("song.mp3", "audio/mpeg", "AUDIO")))
        assertTrue(MediaFilters.choices.all { MediaFilters.valid(it.first) })
        assertFalse(MediaFilters.valid("BROKEN")); assertFalse(MediaFilters.matches("BROKEN", gif))
        assertFalse(MediaFilters.choices(true).any { it.first in setOf("VIDEO", "AUDIO") })
        assertTrue(MediaFilters.choices(false).any { it.first == "VIDEO" })
    }
}
