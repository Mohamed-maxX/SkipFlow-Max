package com.max.skipflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayGeometryTest {

    @Test
    fun `returns dynamic corner ratios for Portrait and Landscape orientations`() {
        val portrait = DisplayGeometry.cornerRatiosFor(1080, 2400)
        assertFalse(portrait.isLandscape)
        assertEquals(0.22f, portrait.widthRatio, 0.0001f)
        assertEquals(0.16f, portrait.heightRatio, 0.0001f)

        val landscape = DisplayGeometry.cornerRatiosFor(2400, 1080)
        assertTrue(landscape.isLandscape)
        assertEquals(0.18f, landscape.widthRatio, 0.0001f)
        assertEquals(0.26f, landscape.heightRatio, 0.0001f)
    }

    @Test
    fun `aligns stale Portrait WindowMetrics to active Landscape game window and bitmap`() {
        // Active fullscreen Landscape window overrides stale Portrait WindowMetrics
        val (w, h) = DisplayGeometry.resolveDynamicDimensions(
            windowWidth = 1080,
            windowHeight = 2400,
            activeAppBounds = BoundsRect(0, 0, 2400, 1080),
            configIsLandscape = false
        )
        assertEquals(2400, w)
        assertEquals(1080, h)

        // Bitmap alignment prevents inverted scaleX/scaleY if WindowMetrics lagged rotation
        val (alignedW, alignedH) = DisplayGeometry.alignScreenToBitmap(
            screenWidth = 1080,
            screenHeight = 2400,
            bitmapWidth = 2400,
            bitmapHeight = 1080
        )
        assertEquals(2400, alignedW)
        assertEquals(1080, alignedH)

        // Tap clamping stays strictly inside current Landscape WindowMetrics bounds
        val (tapX, tapY) = DisplayGeometry.clampTapPoint(2420f, 1090f, w, h)
        assertEquals(2399f, tapX, 0.001f)
        assertEquals(1079f, tapY, 0.001f)
    }
}
