package com.max.skipflow.vision

import com.max.skipflow.engine.BoundsRect
import com.max.skipflow.engine.DebugStatusFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionEnginesTest {

    @Test
    fun `OpenCvTemplateEvaluator enforces 0_65 threshold and skips audio icon rects`() {
        val audioIconRect = BoundsRect(30, 80, 120, 170)
        val muteMatch = VisualIconDetection(
            boundsOnScreen = BoundsRect(35, 85, 115, 165),
            centerX = 75f,
            centerY = 125f,
            shape = DetectedIconShape.CROSS_X,
            confidenceScore = 0.89
        )
        val closeChevronMatch = VisualIconDetection(
            boundsOnScreen = BoundsRect(930, 85, 1020, 175),
            centerX = 975f,
            centerY = 130f,
            shape = DetectedIconShape.DOUBLE_CHEVRON,
            confidenceScore = 0.74
        )
        val weakMatch = VisualIconDetection(
            boundsOnScreen = BoundsRect(930, 200, 1010, 280),
            centerX = 970f,
            centerY = 240f,
            shape = DetectedIconShape.CROSS_X,
            confidenceScore = 0.58
        )

        val chosen = OpenCvTemplateEvaluator.selectBestMatch(
            candidates = listOf(muteMatch, weakMatch, closeChevronMatch),
            excludedRects = listOf(audioIconRect)
        )
        assertEquals(closeChevronMatch, chosen)

        assertNull(
            OpenCvTemplateEvaluator.selectBestMatch(
                candidates = listOf(weakMatch),
                excludedRects = emptyList()
            )
        )
    }

    @Test
    fun `OcrAdTextMatcher detects corner glyphs X, double chevron, Skip, and Arabic skip`() {
        val screenW = 1080
        val screenH = 2400

        val cornerX = OcrAdTextMatcher.evaluateCornerObservations(
            observations = listOf(OcrObservation("X", BoundsRect(960, 90, 1020, 150), 82f)),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertEquals(BoundsRect(960, 90, 1020, 150), cornerX.actionTargetBounds)

        val cornerChevron = OcrAdTextMatcher.evaluateCornerObservations(
            observations = listOf(OcrObservation(">>", BoundsRect(940, 90, 1030, 150), 78f)),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertEquals(BoundsRect(940, 90, 1030, 150), cornerChevron.actionTargetBounds)

        val cornerArabic = OcrAdTextMatcher.evaluateCornerObservations(
            observations = listOf(OcrObservation("التخطي", BoundsRect(880, 90, 1030, 150), 86f)),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertEquals(BoundsRect(880, 90, 1030, 150), cornerArabic.actionTargetBounds)

        // Audio label in corner must be ignored
        val cornerMute = OcrAdTextMatcher.evaluateCornerObservations(
            observations = listOf(OcrObservation("Mute", BoundsRect(30, 90, 120, 150), 90f)),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertNull(cornerMute.actionTargetBounds)
    }

    @Test
    fun `OcrAdTextMatcher matches full-screen Arabic and English skip buttons and gates Close on ad context`() {
        val screenW = 1080
        val screenH = 2400

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
    }

    @Test
    fun `DebugStatusFormatter formats short Activity and single-line diagnostic pill`() {
        val shortName = DebugStatusFormatter.shortActivityName(
            rawClassName = "com.bytedance.sdk.openadsdk.activity.TTRewardVideoActivity",
            fallbackPackage = "com.game.play"
        )
        assertEquals("TTRewardVideoActivity", shortName)

        val line = DebugStatusFormatter.formatLine(
            activityShortName = shortName,
            isAdScreen = true,
            screenshotStatus = "OK",
            detail = "OCV CROSS_X (0.82)"
        )
        assertEquals("TTRewardVideoActivity | إعلان: نعم | لقطة: OK | OCV CROSS_X (0.82)", line)
    }
}
