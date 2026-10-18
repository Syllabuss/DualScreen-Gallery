package it.ayngallery

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MediaTimelineTest {
    private fun image(id: String, date: String?) = MediaEntity(id, "doc:$id", "content://test/$id", "album", "$id.png", "image/png", "IMAGE", 1,
        date?.let { Instant.parse(it).toEpochMilli() } ?: 0)
    @Test fun calendarGroupsUseLocalDatesKeepUnknownLastAndPreserveMediaIdentity() {
        val items = listOf(image("old", "2025-12-31T20:00:00Z"), image("new", "2026-01-01T00:30:00Z"), image("midnight", "2025-12-31T23:30:00Z"), image("unknown", null))
        val day = MediaTimeline.cells(items, "DAY", Locale.ENGLISH, "Unknown", ZoneId.of("Europe/Rome"))
        assertEquals(3, day.count { it.heading != null })
        assertEquals(listOf("new", "midnight", "old", "unknown"), day.mapNotNull { it.media?.id })
        assertEquals("date:2026-01-01", day.first().key)
        assertEquals("Unknown", day[day.size-2].heading)
        assertEquals(day.size, day.map { it.key }.toSet().size)
        assertEquals(3, MediaTimeline.cells(items, "MONTH", Locale.ITALIAN, "Unknown", ZoneId.of("Europe/Rome")).count { it.heading != null })
        assertEquals(3, MediaTimeline.cells(items, "YEAR", Locale.ITALIAN, "Unknown", ZoneId.of("Europe/Rome")).count { it.heading != null })
        assertEquals(items, MediaTimeline.cells(items, "OFF", Locale.ENGLISH, "Unknown").mapNotNull { it.media })
        val ascending = MediaTimeline.cells(items, "DAY", Locale.ENGLISH, "Unknown", ZoneId.of("Europe/Rome"), descending = false)
        assertEquals(listOf("old", "midnight", "new", "unknown"), ascending.mapNotNull { it.media?.id })
        assertEquals("date:2025-12-31", ascending.first().key)
    }
    @Test fun chronologyDoesNotApplyToMusicOrTheAlbumOverview() {
        assertFalse(MediaTimeline.supported(GalleryRoute("MUSIC")))
        assertFalse(MediaTimeline.supported(GalleryRoute("ALBUM")))
        assertTrue(MediaTimeline.supported(GalleryRoute("ALBUM", album = "album")))
        assertTrue(MediaTimeline.supported(GalleryRoute("PHOTO")))
        assertTrue(MediaTimeline.supported(GalleryRoute("VIDEO")))
    }
}
