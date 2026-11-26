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
    fun `OpenCvTemplateEvaluator enforces 0_78 threshold and skips audio icon rects`() {
        val audioIconRect = BoundsRect(30, 80, 120, 170)
        val muteMatch = VisualIconDetection(
            boundsOnScreen = BoundsRect(35, 85, 115, 165),
            centerX = 75f,
            centerY = 125f,
            shape = DetectedIconShape.CROSS_X,
            confidenceScore = 0.91
        )
        val closeChevronMatch = VisualIconDetection(
            boundsOnScreen = BoundsRect(930, 85, 1020, 175),
            centerX = 975f,
            centerY = 130f,
            shape = DetectedIconShape.DOUBLE_CHEVRON,
            confidenceScore = 0.84
        )
        val belowThresholdStrict = VisualIconDetection(
            boundsOnScreen = BoundsRect(930, 200, 1010, 280),
            centerX = 970f,
            centerY = 240f,
            shape = DetectedIconShape.CROSS_X,
            confidenceScore = 0.72
        )

        val chosen = OpenCvTemplateEvaluator.selectBestMatch(
            candidates = listOf(muteMatch, belowThresholdStrict, closeChevronMatch),
            excludedRects = listOf(audioIconRect),
            threshold = OpenCvTemplateEvaluator.MATCH_THRESHOLD
        )
        assertEquals(closeChevronMatch, chosen)

        // Rejected at 0.78 strict threshold
        assertNull(
            OpenCvTemplateEvaluator.selectBestMatch(
                candidates = listOf(belowThresholdStrict),
                excludedRects = emptyList(),
                threshold = OpenCvTemplateEvaluator.thresholdForContext(isConfirmedAdActivity = false)
            )
        )

        // Accepted at 0.68 threshold ONLY inside confirmed Ad Activities
        assertEquals(
            belowThresholdStrict,
            OpenCvTemplateEvaluator.selectBestMatch(
                candidates = listOf(belowThresholdStrict),
                excludedRects = emptyList(),
                threshold = OpenCvTemplateEvaluator.thresholdForContext(isConfirmedAdActivity = true)
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
    fun `OcrAdTextMatcher matches full-screen Arabic and English skip buttons and gates all taps on ad context`() {
        val screenW = 1080
        val screenH = 2400

        // Even "Skip" in a normal game screen must NOT tap unless ad context is confirmed
        val skipWithoutAd = OcrAdTextMatcher.evaluateObservations(
            observations = listOf(
                OcrObservation("Skip", BoundsRect(900, 100, 1020, 180), 90f),
                OcrObservation("Close", BoundsRect(40, 100, 140, 180), 90f)
            ),
            screenWidth = screenW,
            screenHeight = screenH,
            isRealAdActivity = false
        )
        assertNull(skipWithoutAd.actionTargetBounds)
        assertFalse(skipWithoutAd.hasAdContext)

        // Random broken word "AD" should not falsely verify gameplay
        val strayAdWord = OcrAdTextMatcher.evaluateObservations(
            observations = listOf(
                OcrObservation("AD", BoundsRect(200, 300, 280, 350), 80f),
                OcrObservation("Close", BoundsRect(900, 100, 1020, 180), 90f)
            ),
            screenWidth = screenW,
            screenHeight = screenH,
            isRealAdActivity = false
        )
        assertNull(strayAdWord.actionTargetBounds)
        assertFalse(strayAdWord.hasAdContext)

        // "Install" + "Close" confirms active ad context
        val closeWithInstallContext = OcrAdTextMatcher.evaluateObservations(
            observations = listOf(
                OcrObservation("Install", BoundsRect(300, 2000, 780, 2120), 92f),
                OcrObservation("Close", BoundsRect(920, 90, 1040, 170), 89f)
            ),
            screenWidth = screenW,
            screenHeight = screenH,
            isRealAdActivity = false
        )
        assertTrue(closeWithInstallContext.hasAdContext)
        assertEquals(BoundsRect(920, 90, 1040, 170), closeWithInstallContext.actionTargetBounds)

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

    @Test
    fun `OcrAdTextMatcher accepts Landscape corner icons within 18pct x 26pct and maps full bounds`() {
        val landW = 2400
        val landH = 1080

        val landscapeCornerSkip = OcrAdTextMatcher.evaluateCornerObservations(
            observations = listOf(OcrObservation(">>", BoundsRect(120, 180, 220, 260), 86f)),
            screenWidth = landW,
            screenHeight = landH
        )
        assertEquals(BoundsRect(120, 180, 220, 260), landscapeCornerSkip.actionTargetBounds)

        val landscapeFullAdClose = OcrAdTextMatcher.evaluateObservations(
            observations = listOf(
                OcrObservation("Sponsored", BoundsRect(80, 920, 280, 980), 90f),
                OcrObservation("Close", BoundsRect(2200, 170, 2340, 260), 88f)
            ),
            screenWidth = landW,
            screenHeight = landH,
            isRealAdActivity = false
        )
        assertTrue(landscapeFullAdClose.hasAdContext)
        assertEquals(BoundsRect(2200, 170, 2340, 260), landscapeFullAdClose.actionTargetBounds)
    }

    @Test
    fun `OcrAdTextMatcher rejects Google Play chevron pills and exports store exclusion rects`() {
        val screenW = 1080
        val screenH = 2400

        // Single observation "Google Play >>" must never tap
        val googlePlayPill = OcrAdTextMatcher.evaluateCornerObservations(
            observations = listOf(OcrObservation("Google Play >>", BoundsRect(700, 90, 1040, 160), 90f)),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertNull(googlePlayPill.actionTargetBounds)
        assertTrue(googlePlayPill.storeExclusionRects.isNotEmpty())

        // Split words "Google Play" + nearby ">>" in same pill must ignore ">>"
        val splitPillResult = OcrAdTextMatcher.evaluateCornerObservations(
            observations = listOf(
                OcrObservation("Google Play", BoundsRect(700, 95, 920, 155), 90f),
                OcrObservation(">>", BoundsRect(940, 95, 1030, 155), 88f)
            ),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertNull(splitPillResult.actionTargetBounds)
    }
}
