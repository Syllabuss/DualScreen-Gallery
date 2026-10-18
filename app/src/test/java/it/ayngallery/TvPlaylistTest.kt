package it.ayngallery

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class TvPlaylistTest {
    private fun parse(text: String) = TvPlaylist.parse(text.byteInputStream())

    @Test fun preservesMetadataCommasUnicodeAndSignedQueryWithoutFetching() {
        val rows = parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="news.it" tvg-name="Notizie, città" tvg-logo="https://example.com/logo.png" group-title="News",Fallback, title
            # a comment between metadata and stream
            https://example.com/live.m3u8?token=a%2Fb&expires=123
        """.trimIndent())
        assertEquals(1, rows.size)
        assertEquals("Notizie, città", rows.single().name)
        assertEquals("news.it", rows.single().channelId)
        assertEquals("News", rows.single().group)
        assertEquals("https://example.com/logo.png", rows.single().logoUrl)
        assertEquals("https://example.com/live.m3u8?token=a%2Fb&expires=123", rows.single().streamUrl)
    }

    @Test fun deduplicatesWithoutRemovingQueryAndDiscardsMalformedRecords() {
        val rows = parse("""
            #EXTM3U
            https://example.com/orphan
            #EXTINF:-1,First
            https://example.com/live?a=1
            #EXTINF:-1,Duplicate
            https://example.com/live?a=1
            #EXTINF:-1,Other token
            https://example.com/live?a=2
            #EXTINF:-1,Bad
            file:///private
            #EXTINF:-1,Bad
            https://user:password@example.com/live
            #EXTINF:-1,Bad
            http://example.com/live
            #EXTINF:-1 malformed
            https://example.com/missing-title-separator
        """.trimIndent())
        assertEquals(listOf("First", "Other token"), rows.map { it.name })
    }

    @Test fun boundsLinesBytesAndRecordsAndRejectsInvalidUtf8OrHtml() {
        assertThrows(IllegalArgumentException::class.java) { parse("<html>Not a playlist</html>") }
        assertThrows(IllegalArgumentException::class.java) { parse("#EXTM3U\n#" + "x".repeat(TvPlaylist.MAX_LINE)) }
        assertThrows(IllegalArgumentException::class.java) {
            parse("#EXTM3U\n" + "#comment\n".repeat(TvPlaylist.MAX_BYTES / 8 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("#EXTM3U\n" + "#EXTINF:-1,A\nhttps://example.com/live\n".repeat(TvPlaylist.MAX_CHANNELS + 1))
        }
        assertThrows(java.nio.charset.MalformedInputException::class.java) {
            TvPlaylist.parse(ByteArrayInputStream("#EXTM3U\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }
}
