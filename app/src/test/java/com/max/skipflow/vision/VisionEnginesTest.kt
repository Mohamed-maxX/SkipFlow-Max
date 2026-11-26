package com.max.skipflow.vision

import com.max.skipflow.engine.BoundsRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionEnginesTest {

    @Test
    fun `OpenCvShapeClassifier detects crossing X lines and double chevrons`() {
        val xLines = listOf(
            LineSegment2D(15f, 15f, 65f, 65f),
            LineSegment2D(65f, 15f, 15f, 65f)
        )
        assertEquals(
            DetectedIconShape.CROSS_X,
            OpenCvShapeClassifier.classifyLines(xLines, roiWidth = 80, roiHeight = 80)
        )

        val chevronDoubleLines = listOf(
            LineSegment2D(12f, 18f, 34f, 40f),
            LineSegment2D(12f, 62f, 34f, 40f),
            LineSegment2D(42f, 18f, 64f, 40f),
            LineSegment2D(42f, 62f, 64f, 40f)
        )
        assertEquals(
            DetectedIconShape.DOUBLE_CHEVRON,
            OpenCvShapeClassifier.classifyLines(chevronDoubleLines, roiWidth = 80, roiHeight = 80)
        )

        val horizontalOnly = listOf(
            LineSegment2D(10f, 30f, 70f, 30f),
            LineSegment2D(10f, 50f, 70f, 50f)
        )
        assertNull(OpenCvShapeClassifier.classifyLines(horizontalOnly, roiWidth = 80, roiHeight = 80))
    }

    @Test
    fun `OcrAdTextMatcher matches Arabic and English skip buttons and gates Close on ad context`() {
        val screenW = 1080
        val screenH = 2400

        // Explicit Arabic skip button
        val arabicSkip = OcrAdTextMatcher.evaluateObservations(
            observations = listOf(
                OcrObservation("التخطي", BoundsRect(820, 1750, 1020, 1840), 91f)
            ),
            screenWidth = screenW,
            screenHeight = screenH,
            isRealAdActivity = false
        )
        assertEquals(BoundsRect(820, 1750, 1020, 1840), arabicSkip.actionTargetBounds)

        // "Close" WITHOUT ad context must NOT trigger on arbitrary screens
        val closeWithoutAd = OcrAdTextMatcher.evaluateObservations(
            observations = listOf(
                OcrObservation("Close", BoundsRect(900, 100, 1020, 180), 90f)
            ),
            screenWidth = screenW,
            screenHeight = screenH,
            isRealAdActivity = false
        )
        assertNull(closeWithoutAd.actionTargetBounds)
        assertFalse(closeWithoutAd.hasAdContext)

        // "Close" WITH "اعرف المزيد" or "إعلان" ad context triggers accurately without clicking "اعرف المزيد"
        val closeWithAdContext = OcrAdTextMatcher.evaluateObservations(
            observations = listOf(
                OcrObservation("إعلان", BoundsRect(40, 80, 130, 130), 88f),
                OcrObservation("اعرف المزيد", BoundsRect(300, 2100, 780, 2220), 92f),
                OcrObservation("إغلاق", BoundsRect(920, 90, 1040, 170), 89f)
            ),
            screenWidth = screenW,
            screenHeight = screenH,
            isRealAdActivity = false
        )
        assertTrue(closeWithAdContext.hasAdContext)
        assertEquals(BoundsRect(920, 90, 1040, 170), closeWithAdContext.actionTargetBounds)

        // Countdown label must be ignored
        val countdown = OcrAdTextMatcher.evaluateObservations(
            observations = listOf(
                OcrObservation("Skip in 4", BoundsRect(820, 1750, 1020, 1840), 90f)
            ),
            screenWidth = screenW,
            screenHeight = screenH,
            isRealAdActivity = true
        )
        assertNull(countdown.actionTargetBounds)
    }
}
