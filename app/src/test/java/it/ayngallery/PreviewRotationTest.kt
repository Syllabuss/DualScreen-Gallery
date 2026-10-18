package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class PreviewRotationTest {
    @Test fun rotatedPortraitUsesAvailableWidthAndLandscapeFitsWithoutCropping() {
        assertEquals(1f, PreviewRotation.fit(800f, 600f, 200f, 600f, 0f), .001f)
        assertEquals(800f/600f, PreviewRotation.fit(800f, 600f, 200f, 600f, 90f), .001f)
        assertEquals(.75f, PreviewRotation.fit(800f, 600f, 800f, 450f, 90f), .001f)
        assertEquals(1f, PreviewRotation.fit(800f, 600f, 800f, 450f, 360f), .001f)
    }
}
