package com.max.skipflow.vision

import android.graphics.Bitmap
import com.max.skipflow.engine.BoundsRect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect as CvRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

enum class DetectedIconShape {
    CROSS_X,
    DOUBLE_CHEVRON
}

data class LineSegment2D(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

data class VisualIconDetection(
    val boundsOnScreen: BoundsRect,
    val centerX: Float,
    val centerY: Float,
    val shape: DetectedIconShape,
    val confidenceScore: Int
)

/**
 * Pure JVM-testable geometric classifier that identifies crossing diagonal lines (`X`)
 * or double-arrow chevrons (`>>`) inside a small candidate button ROI.
 */
object OpenCvShapeClassifier {

    fun classifyLines(
        segments: List<LineSegment2D>,
        roiWidth: Int,
        roiHeight: Int
    ): DetectedIconShape? {
        if (segments.size < 2 || roiWidth <= 0 || roiHeight <= 0) return null

        val minLength = minOf(roiWidth, roiHeight) * 0.16f
        val positiveDiagonals = mutableListOf<LineSegment2D>()
        val negativeDiagonals = mutableListOf<LineSegment2D>()

        for (seg in segments) {
            val dx = seg.x2 - seg.x1
            val dy = seg.y2 - seg.y1
            val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len < minLength) continue

            var angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angleDeg > 90f) angleDeg -= 180f
            if (angleDeg < -90f) angleDeg += 180f

            // Diagonal slopes around 22..72 degrees (covers X and > / >> chevrons)
            if (angleDeg in 22f..72f) {
                positiveDiagonals.add(seg)
            } else if (angleDeg in -72f..-22f) {
                negativeDiagonals.add(seg)
            }
        }

        if (positiveDiagonals.isEmpty() || negativeDiagonals.isEmpty()) {
            return null
        }

        // Check for crossing diagonals near center -> `X` icon
        val centerX = roiWidth / 2f
        val centerY = roiHeight / 2f
        val centerTolX = roiWidth * 0.35f
        val centerTolY = roiHeight * 0.35f

        for (pos in positiveDiagonals) {
            for (neg in negativeDiagonals) {
                if (segmentsIntersectNear(pos, neg, centerX, centerY, centerTolX, centerTolY)) {
                    return DetectedIconShape.CROSS_X
                }
            }
        }

        // Check for double chevrons (`>>`) arranged horizontally across left and right halves
        if (positiveDiagonals.size >= 2 && negativeDiagonals.size >= 2) {
            val leftHalfHasChevron = hasChevronPairInXRange(positiveDiagonals, negativeDiagonals, 0f, roiWidth * 0.58f)
            val rightHalfHasChevron = hasChevronPairInXRange(positiveDiagonals, negativeDiagonals, roiWidth * 0.42f, roiWidth.toFloat())
            if (leftHalfHasChevron && rightHalfHasChevron) {
                return DetectedIconShape.DOUBLE_CHEVRON
            }
        }

        return null
    }

    private fun hasChevronPairInXRange(
        posList: List<LineSegment2D>,
        negList: List<LineSegment2D>,
        minX: Float,
        maxX: Float
    ): Boolean {
        val posInBand = posList.any { ((it.x1 + it.x2) / 2f) in minX..maxX }
        val negInBand = negList.any { ((it.x1 + it.x2) / 2f) in minX..maxX }
        return posInBand && negInBand
    }

    private fun segmentsIntersectNear(
        a: LineSegment2D,
        b: LineSegment2D,
        targetX: Float,
        targetY: Float,
        tolX: Float,
        tolY: Float
    ): Boolean {
        val denom = (a.x1 - a.x2) * (b.y1 - b.y2) - (a.y1 - a.y2) * (b.x1 - b.x2)
        if (abs(denom) < 1e-3f) return false

        val t = ((a.x1 - b.x1) * (b.y1 - b.y2) - (a.y1 - b.y1) * (b.x1 - b.x2)) / denom
        val u = -((a.x1 - a.x2) * (a.y1 - b.y1) - (a.y1 - a.y2) * (a.x1 - b.x1)) / denom
        // True crossing X lines intersect in the interior of both segments (not at chevron endpoints)
        if (t !in 0.20f..0.80f || u !in 0.20f..0.80f) return false

        val ix = a.x1 + t * (a.x2 - a.x1)
        val iy = a.y1 + t * (a.y2 - a.y1)
        return abs(ix - targetX) <= tolX && abs(iy - targetY) <= tolY
    }
}

/**
 * Uses OpenCV (Canny Edge Detection + findContours + HoughCircles + HoughLinesP)
 * on the top-left (20% x 15%) and top-right (20% x 15%) crops of a screenshot
 * to locate small circular or square `X` / `>>` close buttons (20dp to 75dp).
 */
class OpenCvAdIconDetector {

    @Volatile
    private var openCvLoaded: Boolean = false

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
        density: Float
    ): VisualIconDetection? {
        if (!ensureInitialized()) return null
        if (softwareScreenshot.isRecycled || softwareScreenshot.width <= 0 || softwareScreenshot.height <= 0) {
            return null
        }
        if (screenWidth <= 0 || screenHeight <= 0 || density <= 0f) return null

        val bmpW = softwareScreenshot.width
        val bmpH = softwareScreenshot.height
        val scaleX = bmpW.toFloat() / screenWidth.toFloat()
        val scaleY = bmpH.toFloat() / screenHeight.toFloat()

        val cropW = (bmpW * CORNER_WIDTH_RATIO).toInt().coerceAtLeast(32)
        val cropH = (bmpH * CORNER_HEIGHT_RATIO).toInt().coerceAtLeast(32)

        val minSizePx = (MIN_BUTTON_DP * density * scaleX).toInt().coerceAtLeast(16)
        val maxSizePx = (MAX_BUTTON_DP * density * scaleX).toInt().coerceAtLeast(minSizePx + 8)

        val fullMat = Mat()
        return try {
            Utils.bitmapToMat(softwareScreenshot, fullMat)

            val topLeftCrop = CvRect(0, 0, cropW.coerceAtMost(bmpW), cropH.coerceAtMost(bmpH))
            val topRightX = (bmpW - cropW).coerceAtLeast(0)
            val topRightCrop = CvRect(topRightX, 0, (bmpW - topRightX).coerceAtLeast(1), cropH.coerceAtMost(bmpH))

            val candidates = mutableListOf<VisualIconDetection>()
            inspectCornerCrop(
                fullMat = fullMat,
                cropRect = topLeftCrop,
                minSizePx = minSizePx,
                maxSizePx = maxSizePx,
                scaleX = scaleX,
                scaleY = scaleY,
                out = candidates
            )
            inspectCornerCrop(
                fullMat = fullMat,
                cropRect = topRightCrop,
                minSizePx = minSizePx,
                maxSizePx = maxSizePx,
                scaleX = scaleX,
                scaleY = scaleY,
                out = candidates
            )

            candidates.maxByOrNull { it.confidenceScore }
        } catch (_: Throwable) {
            null
        } finally {
            fullMat.release()
        }
    }

    private fun inspectCornerCrop(
        fullMat: Mat,
        cropRect: CvRect,
        minSizePx: Int,
        maxSizePx: Int,
        scaleX: Float,
        scaleY: Float,
        out: MutableList<VisualIconDetection>
    ) {
        val roiMat = Mat(fullMat, cropRect)
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val circles = Mat()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(blurred, edges, 55.0, 155.0)

            val candidateBoxes = mutableListOf<Pair<CvRect, Int>>()

            // 1. Detect circular close/skip badges via HoughCircles
            val minRadius = (minSizePx / 2).coerceAtLeast(8)
            val maxRadius = (maxSizePx / 2).coerceAtLeast(minRadius + 4)
            Imgproc.HoughCircles(
                blurred,
                circles,
                Imgproc.HOUGH_GRADIENT,
                1.0,
                minSizePx.toDouble(),
                120.0,
                26.0,
                minRadius,
                maxRadius
            )
            for (i in 0 until circles.cols()) {
                val data = circles.get(0, i) ?: continue
                if (data.size < 3) continue
                val cx = data[0].toInt()
                val cy = data[1].toInt()
                val r = data[2].toInt()
                val left = (cx - r).coerceAtLeast(0)
                val top = (cy - r).coerceAtLeast(0)
                val right = (cx + r).coerceAtMost(edges.cols())
                val bottom = (cy + r).coerceAtMost(edges.rows())
                val w = right - left
                val h = bottom - top
                if (w in minSizePx..maxSizePx && h in minSizePx..maxSizePx) {
                    candidateBoxes.add(CvRect(left, top, w, h) to 20)
                }
            }

            // 2. Detect square / rounded-rect close/skip buttons via findContours
            Imgproc.findContours(
                edges.clone(),
                contours,
                hierarchy,
                Imgproc.RETR_TREE,
                Imgproc.CHAIN_APPROX_SIMPLE
            )
            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                if (rect.width in minSizePx..maxSizePx && rect.height in minSizePx..maxSizePx) {
                    val ratio = rect.width.toFloat() / rect.height.toFloat().coerceAtLeast(1f)
                    if (ratio in 0.65f..1.65f) {
                        candidateBoxes.add(rect to 10)
                    }
                }
            }

            // 3. Verify inner glyph (`X` crossing lines or `>>` double chevrons) inside each candidate box
            for ((box, baseScore) in candidateBoxes) {
                val insetX = (box.width * 0.14f).toInt().coerceAtLeast(1)
                val insetY = (box.height * 0.14f).toInt().coerceAtLeast(1)
                val innerLeft = (box.x + insetX).coerceIn(0, edges.cols() - 1)
                val innerTop = (box.y + insetY).coerceIn(0, edges.rows() - 1)
                val innerRight = (box.x + box.width - insetX).coerceIn(innerLeft + 1, edges.cols())
                val innerBottom = (box.y + box.height - insetY).coerceIn(innerTop + 1, edges.rows())
                val innerRect = CvRect(innerLeft, innerTop, innerRight - innerLeft, innerBottom - innerTop)
                if (innerRect.width < 8 || innerRect.height < 8) continue

                val detectedShape = classifyButtonInnerGlyph(edges, innerRect) ?: continue

                val absLeft = ((cropRect.x + box.x) / scaleX).toInt()
                val absTop = ((cropRect.y + box.y) / scaleY).toInt()
                val absRight = ((cropRect.x + box.x + box.width) / scaleX).toInt()
                val absBottom = ((cropRect.y + box.y + box.height) / scaleY).toInt()
                val screenBounds = BoundsRect(absLeft, absTop, absRight, absBottom)
                val shapeBonus = if (detectedShape == DetectedIconShape.CROSS_X) 15 else 12

                out.add(
                    VisualIconDetection(
                        boundsOnScreen = screenBounds,
                        centerX = screenBounds.centerX.toFloat(),
                        centerY = screenBounds.centerY.toFloat(),
                        shape = detectedShape,
                        confidenceScore = baseScore + shapeBonus
                    )
                )
            }
        } finally {
            contours.forEach { it.release() }
            circles.release()
            hierarchy.release()
            edges.release()
            blurred.release()
            gray.release()
            roiMat.release()
        }
    }

    private fun classifyButtonInnerGlyph(edges: Mat, innerRect: CvRect): DetectedIconShape? {
        val innerMat = Mat(edges, innerRect)
        val linesMat = Mat()
        return try {
            val minLen = (minOf(innerRect.width, innerRect.height) * 0.22).coerceAtLeast(4.0)
            val threshold = (minOf(innerRect.width, innerRect.height) * 0.18).toInt().coerceAtLeast(6)
            Imgproc.HoughLinesP(
                innerMat,
                linesMat,
                1.0,
                Math.PI / 180.0,
                threshold,
                minLen,
                4.0
            )
            val segments = ArrayList<LineSegment2D>(linesMat.rows())
            for (i in 0 until linesMat.rows()) {
                val vec = linesMat.get(i, 0) ?: continue
                if (vec.size >= 4) {
                    segments.add(
                        LineSegment2D(
                            x1 = vec[0].toFloat(),
                            y1 = vec[1].toFloat(),
                            x2 = vec[2].toFloat(),
                            y2 = vec[3].toFloat()
                        )
                    )
                }
            }
            OpenCvShapeClassifier.classifyLines(segments, innerRect.width, innerRect.height)
        } finally {
            linesMat.release()
            innerMat.release()
        }
    }

    companion object {
        private const val CORNER_WIDTH_RATIO = 0.20f
        private const val CORNER_HEIGHT_RATIO = 0.15f
        private const val MIN_BUTTON_DP = 20f
        private const val MAX_BUTTON_DP = 75f
    }
}
