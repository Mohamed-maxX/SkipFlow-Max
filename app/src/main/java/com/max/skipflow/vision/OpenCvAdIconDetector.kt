package com.max.skipflow.vision

import android.graphics.Bitmap
import com.max.skipflow.engine.BoundsRect
import com.max.skipflow.engine.DisplayGeometry
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

enum class DetectedIconShape {
    CROSS_X,
    DOUBLE_CHEVRON
}

data class VisualIconDetection(
    val boundsOnScreen: BoundsRect,
    val centerX: Float,
    val centerY: Float,
    val shape: DetectedIconShape,
    val confidenceScore: Double
)

data class OpenCvCornerScanSummary(
    val detection: VisualIconDetection?,
    val maxScore: Double
)

/**
 * Pure JVM-testable helper for multi-scale template matching coordinate mapping
 * and audio/info exclusion filtering.
 */
object OpenCvTemplateEvaluator {

    const val MATCH_THRESHOLD = 0.78
    const val CONFIRMED_AD_MATCH_THRESHOLD = 0.68
    val SCALES = floatArrayOf(0.7f, 0.85f, 1.0f, 1.3f)

    fun thresholdForContext(isConfirmedAdActivity: Boolean): Double {
        return if (isConfirmedAdActivity) CONFIRMED_AD_MATCH_THRESHOLD else MATCH_THRESHOLD
    }

    fun isInsideExcludedRect(
        centerX: Float,
        centerY: Float,
        excludedRects: List<BoundsRect>,
        paddingPx: Int = 14
    ): Boolean {
        for (rect in excludedRects) {
            if (centerX >= rect.left - paddingPx &&
                centerX <= rect.right + paddingPx &&
                centerY >= rect.top - paddingPx &&
                centerY <= rect.bottom + paddingPx
            ) {
                return true
            }
        }
        return false
    }

    fun selectBestMatch(
        candidates: List<VisualIconDetection>,
        excludedRects: List<BoundsRect>,
        threshold: Double = MATCH_THRESHOLD
    ): VisualIconDetection? {
        return candidates
            .filter { it.confidenceScore >= threshold }
            .filterNot { isInsideExcludedRect(it.centerX, it.centerY, excludedRects) }
            .maxByOrNull { it.confidenceScore }
    }
}

/**
 * Multi-scale OpenCV Template Matching (`TM_CCOEFF_NORMED`) detector using programmatically
 * generated white-on-dark/gray `X` and `>>` templates at scales 0.7x, 1.0x, and 1.3x
 * across dynamic top-left/top-right screen corners (Portrait: 22% x 16%, Landscape: 18% x 26%).
 */
class OpenCvAdIconDetector {

    private data class TemplateSpec(
        val shape: DetectedIconShape,
        val mat: Mat
    )

    @Volatile
    private var openCvLoaded: Boolean = false
    private var cachedTemplates: List<TemplateSpec>? = null

    fun ensureInitialized(): Boolean {
        if (openCvLoaded) return true
        val loaded = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        openCvLoaded = loaded
        return loaded
    }

    fun detectCornerCloseIcon(
        softwareScreenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        excludedRects: List<BoundsRect> = emptyList(),
        isConfirmedAdActivity: Boolean = false
    ): OpenCvCornerScanSummary {
        if (!ensureInitialized()) return OpenCvCornerScanSummary(null, 0.0)
        if (softwareScreenshot.isRecycled || softwareScreenshot.width <= 0 || softwareScreenshot.height <= 0) {
            return OpenCvCornerScanSummary(null, 0.0)
        }
        if (screenWidth <= 0 || screenHeight <= 0 || density <= 0f) {
            return OpenCvCornerScanSummary(null, 0.0)
        }

        val templates = getOrCreateTemplates()
        if (templates.isEmpty()) return OpenCvCornerScanSummary(null, 0.0)

        val threshold = OpenCvTemplateEvaluator.thresholdForContext(isConfirmedAdActivity)
        val bmpW = softwareScreenshot.width
        val bmpH = softwareScreenshot.height
        val (effectiveScreenW, effectiveScreenH) = DisplayGeometry.alignScreenToBitmap(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            bitmapWidth = bmpW,
            bitmapHeight = bmpH
        )
        val scaleToBmpX = bmpW.toFloat() / effectiveScreenW.toFloat()
        val scaleToBmpY = bmpH.toFloat() / effectiveScreenH.toFloat()

        // Crop ratios adapt dynamically to orientation directly from the captured bitmap dimensions:
        // Portrait: top 16% height x first/last 22% width; Landscape: top 26% height x first/last 18% width.
        val cornerRatios = DisplayGeometry.cornerRatiosFor(bmpW, bmpH)
        val cropW = (bmpW * cornerRatios.widthRatio).toInt().coerceIn(48, bmpW)
        val cropH = (bmpH * cornerRatios.heightRatio).toInt().coerceIn(48, bmpH)
        val baseSizePx = (BASE_TEMPLATE_DP * density * scaleToBmpX).coerceIn(24f, minOf(cropW, cropH) * 0.75f)

        val fullMat = Mat()
        return try {
            Utils.bitmapToMat(softwareScreenshot, fullMat)

            val topLeftCrop = CvRect(0, 0, cropW.coerceAtMost(bmpW), cropH.coerceAtMost(bmpH))
            val topRightX = (bmpW - cropW).coerceAtLeast(0)
            val topRightCrop = CvRect(topRightX, 0, (bmpW - topRightX).coerceAtLeast(1), cropH.coerceAtMost(bmpH))

            // Always scan BOTH top-left and top-right corners so top-right `>>` / `X` icons are detected immediately
            val detections = mutableListOf<VisualIconDetection>()
            var highestScore = matchTemplatesInCorner(
                fullMat = fullMat,
                cropRect = topLeftCrop,
                templates = templates,
                baseSizePx = baseSizePx,
                scaleToBmpX = scaleToBmpX,
                scaleToBmpY = scaleToBmpY,
                threshold = threshold,
                excludedRects = excludedRects,
                out = detections
            )

            highestScore = maxOf(
                highestScore,
                matchTemplatesInCorner(
                    fullMat = fullMat,
                    cropRect = topRightCrop,
                    templates = templates,
                    baseSizePx = baseSizePx,
                    scaleToBmpX = scaleToBmpX,
                    scaleToBmpY = scaleToBmpY,
                    threshold = threshold,
                    excludedRects = excludedRects,
                    out = detections
                )
            )

            val winner = OpenCvTemplateEvaluator.selectBestMatch(
                candidates = detections,
                excludedRects = excludedRects,
                threshold = threshold
            )
            OpenCvCornerScanSummary(
                detection = winner,
                maxScore = highestScore
            )
        } catch (_: Throwable) {
            OpenCvCornerScanSummary(null, 0.0)
        } finally {
            fullMat.release()
        }
    }

    private fun matchTemplatesInCorner(
        fullMat: Mat,
        cropRect: CvRect,
        templates: List<TemplateSpec>,
        baseSizePx: Float,
        scaleToBmpX: Float,
        scaleToBmpY: Float,
        threshold: Double,
        excludedRects: List<BoundsRect>,
        out: MutableList<VisualIconDetection>
    ): Double {
        val roiMat = Mat(fullMat, cropRect)
        val cropGray = Mat()
        val cropInv = Mat()
        val cropBlur = Mat()
        val cropAdaptive = Mat()
        val cropEdges = Mat()
        val cropEdgesSmooth = Mat()
        var bestCornerScore = 0.0

        try {
            Imgproc.cvtColor(roiMat, cropGray, Imgproc.COLOR_RGBA2GRAY)
            Core.bitwise_not(cropGray, cropInv)
            Imgproc.GaussianBlur(cropGray, cropBlur, Size(3.0, 3.0), 0.8)

            // Adaptive threshold extracts crisp white `>>` / `X` glyphs even over translucent video backgrounds
            val adaptiveBlock = (((baseSizePx * 0.6f).toInt() or 1).coerceIn(11, 31))
            Imgproc.adaptiveThreshold(
                cropBlur,
                cropAdaptive,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                adaptiveBlock,
                -10.0
            )

            // Canny edges + light 3x3 smoothing removes background brightness/transparency drag
            Imgproc.Canny(cropBlur, cropEdges, 45.0, 130.0)
            Imgproc.GaussianBlur(cropEdges, cropEdgesSmooth, Size(3.0, 3.0), 0.9)
            val hasEdgePixels = Core.countNonZero(cropEdges) >= 8

            for (spec in templates) {
                val aspect = spec.mat.cols().toFloat() / spec.mat.rows().toFloat().coerceAtLeast(1f)
                for (scale in OpenCvTemplateEvaluator.SCALES) {
                    val targetH = (baseSizePx * scale).toInt()
                    val targetW = (targetH * aspect).toInt()
                    if (targetW < 14 || targetH < 14) continue
                    if (targetW >= cropGray.cols() - 2 || targetH >= cropGray.rows() - 2) continue

                    val scaledTemplate = Mat()
                    val scaledTemplateBinary = Mat()
                    val scaledTemplateEdges = Mat()
                    val scaledTemplateEdgesSmooth = Mat()
                    val resultNormal = Mat()
                    val resultInv = Mat()
                    val resultAdaptive = Mat()
                    val resultEdges = Mat()
                    try {
                        Imgproc.resize(
                            spec.mat,
                            scaledTemplate,
                            Size(targetW.toDouble(), targetH.toDouble()),
                            0.0,
                            0.0,
                            Imgproc.INTER_AREA
                        )

                        // 1. White-on-dark polarity
                        Imgproc.matchTemplate(cropGray, scaledTemplate, resultNormal, Imgproc.TM_CCOEFF_NORMED)
                        val mmrNormal = Core.minMaxLoc(resultNormal)
                        bestCornerScore = maxOf(bestCornerScore, mmrNormal.maxVal)
                        if (mmrNormal.maxVal >= threshold) {
                            addDetectionIfNotExcluded(
                                loc = mmrNormal.maxLoc,
                                score = mmrNormal.maxVal,
                                targetW = targetW,
                                targetH = targetH,
                                cropRect = cropRect,
                                scaleToBmpX = scaleToBmpX,
                                scaleToBmpY = scaleToBmpY,
                                shape = spec.shape,
                                excludedRects = excludedRects,
                                out = out
                            )
                        }

                        // 2. Dark-on-light polarity (light-theme end-card close buttons)
                        Imgproc.matchTemplate(cropInv, scaledTemplate, resultInv, Imgproc.TM_CCOEFF_NORMED)
                        val mmrInv = Core.minMaxLoc(resultInv)
                        bestCornerScore = maxOf(bestCornerScore, mmrInv.maxVal)
                        if (mmrInv.maxVal >= threshold) {
                            addDetectionIfNotExcluded(
                                loc = mmrInv.maxLoc,
                                score = mmrInv.maxVal,
                                targetW = targetW,
                                targetH = targetH,
                                cropRect = cropRect,
                                scaleToBmpX = scaleToBmpX,
                                scaleToBmpY = scaleToBmpY,
                                shape = spec.shape,
                                excludedRects = excludedRects,
                                out = out
                            )
                        }

                        // 3. Adaptive binary match (bright white glyph inside translucent dark pill)
                        Imgproc.threshold(scaledTemplate, scaledTemplateBinary, 165.0, 255.0, Imgproc.THRESH_BINARY)
                        if (Core.countNonZero(scaledTemplateBinary) >= 6) {
                            Imgproc.matchTemplate(cropAdaptive, scaledTemplateBinary, resultAdaptive, Imgproc.TM_CCOEFF_NORMED)
                            val mmrAdaptive = Core.minMaxLoc(resultAdaptive)
                            bestCornerScore = maxOf(bestCornerScore, mmrAdaptive.maxVal)
                            if (mmrAdaptive.maxVal >= threshold) {
                                addDetectionIfNotExcluded(
                                    loc = mmrAdaptive.maxLoc,
                                    score = mmrAdaptive.maxVal,
                                    targetW = targetW,
                                    targetH = targetH,
                                    cropRect = cropRect,
                                    scaleToBmpX = scaleToBmpX,
                                    scaleToBmpY = scaleToBmpY,
                                    shape = spec.shape,
                                    excludedRects = excludedRects,
                                    out = out
                                )
                            }
                        }

                        // 4. Canny edge match (invariant to semi-transparent circle & video background brightness)
                        if (hasEdgePixels) {
                            Imgproc.Canny(scaledTemplate, scaledTemplateEdges, 45.0, 130.0)
                            if (Core.countNonZero(scaledTemplateEdges) >= 6) {
                                Imgproc.GaussianBlur(scaledTemplateEdges, scaledTemplateEdgesSmooth, Size(3.0, 3.0), 0.9)
                                Imgproc.matchTemplate(cropEdgesSmooth, scaledTemplateEdgesSmooth, resultEdges, Imgproc.TM_CCOEFF_NORMED)
                                val mmrEdges = Core.minMaxLoc(resultEdges)
                                bestCornerScore = maxOf(bestCornerScore, mmrEdges.maxVal)
                                if (mmrEdges.maxVal >= threshold) {
                                    addDetectionIfNotExcluded(
                                        loc = mmrEdges.maxLoc,
                                        score = mmrEdges.maxVal,
                                        targetW = targetW,
                                        targetH = targetH,
                                        cropRect = cropRect,
                                        scaleToBmpX = scaleToBmpX,
                                        scaleToBmpY = scaleToBmpY,
                                        shape = spec.shape,
                                        excludedRects = excludedRects,
                                        out = out
                                    )
                                }
                            }
                        }
                    } finally {
                        resultEdges.release()
                        resultAdaptive.release()
                        resultInv.release()
                        resultNormal.release()
                        scaledTemplateEdgesSmooth.release()
                        scaledTemplateEdges.release()
                        scaledTemplateBinary.release()
                        scaledTemplate.release()
                    }
                }
            }
        } finally {
            cropEdgesSmooth.release()
            cropEdges.release()
            cropAdaptive.release()
            cropBlur.release()
            cropInv.release()
            cropGray.release()
            roiMat.release()
        }
        return bestCornerScore
    }

    private fun addDetectionIfNotExcluded(
        loc: Point,
        score: Double,
        targetW: Int,
        targetH: Int,
        cropRect: CvRect,
        scaleToBmpX: Float,
        scaleToBmpY: Float,
        shape: DetectedIconShape,
        excludedRects: List<BoundsRect>,
        out: MutableList<VisualIconDetection>
    ) {
        val absLeft = ((cropRect.x + loc.x) / scaleToBmpX).toInt()
        val absTop = ((cropRect.y + loc.y) / scaleToBmpY).toInt()
        val absRight = ((cropRect.x + loc.x + targetW) / scaleToBmpX).toInt()
        val absBottom = ((cropRect.y + loc.y + targetH) / scaleToBmpY).toInt()
        val bounds = BoundsRect(absLeft, absTop, absRight, absBottom)
        val cx = bounds.centerX.toFloat()
        val cy = bounds.centerY.toFloat()

        if (!OpenCvTemplateEvaluator.isInsideExcludedRect(cx, cy, excludedRects)) {
            out.add(
                VisualIconDetection(
                    boundsOnScreen = bounds,
                    centerX = cx,
                    centerY = cy,
                    shape = shape,
                    confidenceScore = score
                )
            )
        }
    }

    @Synchronized
    private fun getOrCreateTemplates(): List<TemplateSpec> {
        cachedTemplates?.let { return it }
        val built = listOf(
            TemplateSpec(DetectedIconShape.CROSS_X, createCircleXTemplate()),
            TemplateSpec(DetectedIconShape.CROSS_X, createRoundedRectXTemplate()),
            TemplateSpec(DetectedIconShape.CROSS_X, createPlainXTemplate()),
            TemplateSpec(DetectedIconShape.CROSS_X, createThinPlainXTemplate()),
            TemplateSpec(DetectedIconShape.DOUBLE_CHEVRON, createCircleDoubleChevronTemplate()),
            TemplateSpec(DetectedIconShape.DOUBLE_CHEVRON, createTranslucentCircleSmallDoubleChevronTemplate()),
            TemplateSpec(DetectedIconShape.DOUBLE_CHEVRON, createRoundedRectDoubleChevronTemplate()),
            TemplateSpec(DetectedIconShape.DOUBLE_CHEVRON, createPlainDoubleChevronTemplate()),
            TemplateSpec(DetectedIconShape.DOUBLE_CHEVRON, createThinPlainDoubleChevronTemplate())
        )
        cachedTemplates = built
        return built
    }

    private fun fillRoundedRect(mat: Mat, left: Int, top: Int, right: Int, bottom: Int, radius: Int, color: Scalar) {
        Imgproc.rectangle(mat, Point((left + radius).toDouble(), top.toDouble()), Point((right - radius).toDouble(), bottom.toDouble()), color, -1)
        Imgproc.rectangle(mat, Point(left.toDouble(), (top + radius).toDouble()), Point(right.toDouble(), (bottom - radius).toDouble()), color, -1)
        Imgproc.circle(mat, Point((left + radius).toDouble(), (top + radius).toDouble()), radius, color, -1, Imgproc.LINE_AA, 0)
        Imgproc.circle(mat, Point((right - radius).toDouble(), (top + radius).toDouble()), radius, color, -1, Imgproc.LINE_AA, 0)
        Imgproc.circle(mat, Point((left + radius).toDouble(), (bottom - radius).toDouble()), radius, color, -1, Imgproc.LINE_AA, 0)
        Imgproc.circle(mat, Point((right - radius).toDouble(), (bottom - radius).toDouble()), radius, color, -1, Imgproc.LINE_AA, 0)
    }

    private fun createCircleXTemplate(): Mat {
        val mat = Mat(64, 64, CvType.CV_8UC1, Scalar(75.0))
        Imgproc.circle(mat, Point(32.0, 32.0), 27, Scalar(28.0), -1, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(20.0, 20.0), Point(44.0, 44.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(44.0, 20.0), Point(20.0, 44.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        return mat
    }

    private fun createRoundedRectXTemplate(): Mat {
        val mat = Mat(64, 64, CvType.CV_8UC1, Scalar(110.0))
        fillRoundedRect(mat, left = 6, top = 6, right = 58, bottom = 58, radius = 12, color = Scalar(40.0))
        Imgproc.line(mat, Point(20.0, 20.0), Point(44.0, 44.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(44.0, 20.0), Point(20.0, 44.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        return mat
    }

    private fun createPlainXTemplate(): Mat {
        val mat = Mat(48, 48, CvType.CV_8UC1, Scalar(35.0))
        Imgproc.line(mat, Point(10.0, 10.0), Point(38.0, 38.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(38.0, 10.0), Point(10.0, 38.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        return mat
    }

    private fun createThinPlainXTemplate(): Mat {
        val mat = Mat(48, 48, CvType.CV_8UC1, Scalar(42.0))
        Imgproc.line(mat, Point(12.0, 12.0), Point(36.0, 36.0), Scalar(255.0), 2, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(36.0, 12.0), Point(12.0, 36.0), Scalar(255.0), 2, Imgproc.LINE_AA, 0)
        return mat
    }

    private fun createCircleDoubleChevronTemplate(): Mat {
        val mat = Mat(64, 64, CvType.CV_8UC1, Scalar(75.0))
        Imgproc.circle(mat, Point(32.0, 32.0), 27, Scalar(28.0), -1, Imgproc.LINE_AA, 0)
        // First '>'
        Imgproc.line(mat, Point(16.0, 21.0), Point(29.0, 32.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(29.0, 32.0), Point(16.0, 43.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        // Second '>'
        Imgproc.line(mat, Point(33.0, 21.0), Point(46.0, 32.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(46.0, 32.0), Point(33.0, 43.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        return mat
    }

    /**
     * Dedicated Pangle / TikTok top-left skip template: compact crisp white `>>` chevrons
     * centered inside a dark semi-transparent circular pill with subtle outer ring stroke.
     */
    private fun createTranslucentCircleSmallDoubleChevronTemplate(): Mat {
        val mat = Mat(64, 64, CvType.CV_8UC1, Scalar(115.0))
        Imgproc.circle(mat, Point(32.0, 32.0), 26, Scalar(52.0), -1, Imgproc.LINE_AA, 0)
        Imgproc.circle(mat, Point(32.0, 32.0), 26, Scalar(165.0), 1, Imgproc.LINE_AA, 0)
        // First small '>'
        Imgproc.line(mat, Point(20.0, 23.0), Point(30.0, 32.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(30.0, 32.0), Point(20.0, 41.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        // Second small '>'
        Imgproc.line(mat, Point(33.0, 23.0), Point(43.0, 32.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(43.0, 32.0), Point(33.0, 41.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        return mat
    }

    private fun createRoundedRectDoubleChevronTemplate(): Mat {
        val mat = Mat(64, 72, CvType.CV_8UC1, Scalar(110.0))
        fillRoundedRect(mat, left = 5, top = 7, right = 67, bottom = 57, radius = 12, color = Scalar(40.0))
        // First '>'
        Imgproc.line(mat, Point(18.0, 21.0), Point(32.0, 32.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(32.0, 32.0), Point(18.0, 43.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        // Second '>'
        Imgproc.line(mat, Point(37.0, 21.0), Point(51.0, 32.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(51.0, 32.0), Point(37.0, 43.0), Scalar(255.0), 3, Imgproc.LINE_AA, 0)
        return mat
    }

    private fun createPlainDoubleChevronTemplate(): Mat {
        val mat = Mat(44, 56, CvType.CV_8UC1, Scalar(35.0))
        Imgproc.line(mat, Point(10.0, 10.0), Point(24.0, 22.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(24.0, 22.0), Point(10.0, 34.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(29.0, 10.0), Point(43.0, 22.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(43.0, 22.0), Point(29.0, 34.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        return mat
    }

    private fun createThinPlainDoubleChevronTemplate(): Mat {
        val mat = Mat(44, 56, CvType.CV_8UC1, Scalar(42.0))
        Imgproc.line(mat, Point(11.0, 12.0), Point(24.0, 22.0), Scalar(255.0), 2, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(24.0, 22.0), Point(11.0, 32.0), Scalar(255.0), 2, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(30.0, 12.0), Point(43.0, 22.0), Scalar(255.0), 2, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(43.0, 22.0), Point(30.0, 32.0), Scalar(255.0), 2, Imgproc.LINE_AA, 0)
        return mat
    }

    fun release() {
        cachedTemplates?.forEach { it.mat.release() }
        cachedTemplates = null
    }

    companion object {
        const val CORNER_WIDTH_RATIO = DisplayGeometry.PORTRAIT_CORNER_WIDTH_RATIO
        const val CORNER_HEIGHT_RATIO = DisplayGeometry.PORTRAIT_CORNER_HEIGHT_RATIO
        const val LANDSCAPE_CORNER_WIDTH_RATIO = DisplayGeometry.LANDSCAPE_CORNER_WIDTH_RATIO
        const val LANDSCAPE_CORNER_HEIGHT_RATIO = DisplayGeometry.LANDSCAPE_CORNER_HEIGHT_RATIO
        private const val BASE_TEMPLATE_DP = 36f
    }
}
