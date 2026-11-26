package com.max.skipflow.vision

import android.graphics.Bitmap
import com.max.skipflow.engine.BoundsRect
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

    const val MATCH_THRESHOLD = 0.65
    val SCALES = floatArrayOf(0.7f, 1.0f, 1.3f)

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
        excludedRects: List<BoundsRect>
    ): VisualIconDetection? {
        return candidates
            .filter { it.confidenceScore >= MATCH_THRESHOLD }
            .filterNot { isInsideExcludedRect(it.centerX, it.centerY, excludedRects) }
            .maxByOrNull { it.confidenceScore }
    }
}

/**
 * Multi-scale OpenCV Template Matching (`TM_CCOEFF_NORMED`) detector using programmatically
 * generated white-on-dark/gray `X` and `>>` templates at scales 0.7x, 1.0x, and 1.3x
 * across the top-left (22% x 15%) and top-right (22% x 15%) screen corners.
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
        preferTopLeftCorner: Boolean = false
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

        val bmpW = softwareScreenshot.width
        val bmpH = softwareScreenshot.height
        val scaleToBmpX = bmpW.toFloat() / screenWidth.toFloat()
        val scaleToBmpY = bmpH.toFloat() / screenHeight.toFloat()

        val leftWidthRatio = if (preferTopLeftCorner) TOP_LEFT_PRIORITY_WIDTH_RATIO else CORNER_WIDTH_RATIO
        val topLeftCropW = (bmpW * leftWidthRatio).toInt().coerceAtLeast(48)
        val topRightCropW = (bmpW * CORNER_WIDTH_RATIO).toInt().coerceAtLeast(48)
        val cropH = (bmpH * CORNER_HEIGHT_RATIO).toInt().coerceAtLeast(48)
        val baseSizePx = (BASE_TEMPLATE_DP * density * scaleToBmpX).coerceIn(24f, topLeftCropW * 0.75f)

        val fullMat = Mat()
        return try {
            Utils.bitmapToMat(softwareScreenshot, fullMat)

            val topLeftCrop = CvRect(0, 0, topLeftCropW.coerceAtMost(bmpW), cropH.coerceAtMost(bmpH))
            val topRightX = (bmpW - topRightCropW).coerceAtLeast(0)
            val topRightCrop = CvRect(topRightX, 0, (bmpW - topRightX).coerceAtLeast(1), cropH.coerceAtMost(bmpH))

            val topLeftDetections = mutableListOf<VisualIconDetection>()
            var highestScore = matchTemplatesInCorner(
                fullMat = fullMat,
                cropRect = topLeftCrop,
                templates = templates,
                baseSizePx = baseSizePx,
                scaleToBmpX = scaleToBmpX,
                scaleToBmpY = scaleToBmpY,
                excludedRects = excludedRects,
                out = topLeftDetections
            )

            val topLeftWinner = OpenCvTemplateEvaluator.selectBestMatch(topLeftDetections, excludedRects)
            if (preferTopLeftCorner && topLeftWinner != null) {
                return OpenCvCornerScanSummary(
                    detection = topLeftWinner,
                    maxScore = highestScore
                )
            }

            val topRightDetections = mutableListOf<VisualIconDetection>()
            highestScore = maxOf(
                highestScore,
                matchTemplatesInCorner(
                    fullMat = fullMat,
                    cropRect = topRightCrop,
                    templates = templates,
                    baseSizePx = baseSizePx,
                    scaleToBmpX = scaleToBmpX,
                    scaleToBmpY = scaleToBmpY,
                    excludedRects = excludedRects,
                    out = topRightDetections
                )
            )

            val winner = OpenCvTemplateEvaluator.selectBestMatch(
                candidates = topLeftDetections + topRightDetections,
                excludedRects = excludedRects
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
        excludedRects: List<BoundsRect>,
        out: MutableList<VisualIconDetection>
    ): Double {
        val roiMat = Mat(fullMat, cropRect)
        val cropGray = Mat()
        val cropInv = Mat()
        var bestCornerScore = 0.0

        try {
            Imgproc.cvtColor(roiMat, cropGray, Imgproc.COLOR_RGBA2GRAY)
            Core.bitwise_not(cropGray, cropInv)

            for (spec in templates) {
                val aspect = spec.mat.cols().toFloat() / spec.mat.rows().toFloat().coerceAtLeast(1f)
                for (scale in OpenCvTemplateEvaluator.SCALES) {
                    val targetH = (baseSizePx * scale).toInt()
                    val targetW = (targetH * aspect).toInt()
                    if (targetW < 14 || targetH < 14) continue
                    if (targetW >= cropGray.cols() - 2 || targetH >= cropGray.rows() - 2) continue

                    val scaledTemplate = Mat()
                    val resultNormal = Mat()
                    val resultInv = Mat()
                    try {
                        Imgproc.resize(
                            spec.mat,
                            scaledTemplate,
                            Size(targetW.toDouble(), targetH.toDouble()),
                            0.0,
                            0.0,
                            Imgproc.INTER_AREA
                        )

                        // Check white-on-dark polarity
                        Imgproc.matchTemplate(cropGray, scaledTemplate, resultNormal, Imgproc.TM_CCOEFF_NORMED)
                        val mmrNormal = Core.minMaxLoc(resultNormal)
                        bestCornerScore = maxOf(bestCornerScore, mmrNormal.maxVal)
                        if (mmrNormal.maxVal >= OpenCvTemplateEvaluator.MATCH_THRESHOLD) {
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

                        // Check dark-on-light polarity (light-theme end-card close buttons)
                        Imgproc.matchTemplate(cropInv, scaledTemplate, resultInv, Imgproc.TM_CCOEFF_NORMED)
                        val mmrInv = Core.minMaxLoc(resultInv)
                        bestCornerScore = maxOf(bestCornerScore, mmrInv.maxVal)
                        if (mmrInv.maxVal >= OpenCvTemplateEvaluator.MATCH_THRESHOLD) {
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
                    } finally {
                        resultInv.release()
                        resultNormal.release()
                        scaledTemplate.release()
                    }
                }
            }
        } finally {
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
            TemplateSpec(DetectedIconShape.CROSS_X, createPlainXTemplate()),
            TemplateSpec(DetectedIconShape.DOUBLE_CHEVRON, createCircleDoubleChevronTemplate()),
            TemplateSpec(DetectedIconShape.DOUBLE_CHEVRON, createPlainDoubleChevronTemplate())
        )
        cachedTemplates = built
        return built
    }

    private fun createCircleXTemplate(): Mat {
        val mat = Mat(64, 64, CvType.CV_8UC1, Scalar(75.0))
        Imgproc.circle(mat, Point(32.0, 32.0), 27, Scalar(28.0), -1, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(20.0, 20.0), Point(44.0, 44.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(44.0, 20.0), Point(20.0, 44.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        return mat
    }

    private fun createPlainXTemplate(): Mat {
        val mat = Mat(48, 48, CvType.CV_8UC1, Scalar(35.0))
        Imgproc.line(mat, Point(10.0, 10.0), Point(38.0, 38.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(38.0, 10.0), Point(10.0, 38.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
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

    private fun createPlainDoubleChevronTemplate(): Mat {
        val mat = Mat(44, 56, CvType.CV_8UC1, Scalar(35.0))
        Imgproc.line(mat, Point(10.0, 10.0), Point(24.0, 22.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(24.0, 22.0), Point(10.0, 34.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(29.0, 10.0), Point(43.0, 22.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        Imgproc.line(mat, Point(43.0, 22.0), Point(29.0, 34.0), Scalar(255.0), 4, Imgproc.LINE_AA, 0)
        return mat
    }

    fun release() {
        cachedTemplates?.forEach { it.mat.release() }
        cachedTemplates = null
    }

    companion object {
        const val CORNER_WIDTH_RATIO = 0.22f
        const val TOP_LEFT_PRIORITY_WIDTH_RATIO = 0.20f
        const val CORNER_HEIGHT_RATIO = 0.15f
        private const val BASE_TEMPLATE_DP = 36f
    }
}
