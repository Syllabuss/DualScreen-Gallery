package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class AlbumPreviewEdgesTest {
    @Test fun cornersDoNotDoubleDarknessAndImageCentreStaysClear() {
        assertEquals(.75f, albumEdgeOpacity(0f, 0f, "ALL"), .001f)
        assertEquals(.75f, albumEdgeOpacity(0f, 1f, "ALL"), .001f)
        assertEquals(0f, albumEdgeOpacity(.5f, .5f, "ALL"), .001f)
        assertEquals(0f, albumEdgeOpacity(0f, 0f, "NONE"), .001f)
        assertTrue(albumEdgeOpacity(.5f, .01f, "TOP") > albumEdgeOpacity(.5f, .1f, "TOP"))
        assertEquals(0f, albumEdgeOpacity(.5f, .95f, "TOP"), .001f)
    }
}
