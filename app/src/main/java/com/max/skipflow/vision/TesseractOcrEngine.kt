package com.max.skipflow.vision

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.max.skipflow.engine.AdDetectionRules
import com.max.skipflow.engine.BoundsRect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OcrObservation(
    val text: String,
    val bounds: BoundsRect,
    val confidence: Float
)

data class OcrAdScanResult(
    val actionTargetBounds: BoundsRect?,
    val matchedKeyword: String?,
    val hasAdContext: Boolean
)

/**
 * Pure JVM-testable matcher over OCR lines/words for Arabic & English ad skip controls,
 * corner glyph text (`X`, `>>`, `Skip`, `التخطي`), and ad-context keywords.
 */
object OcrAdTextMatcher {

    private val highPriorityActionKeywords = listOf(
        "التخطي",
        "تخطي الإعلان",
        "تخطي الاعلان",
        "تخطي",
        "skip ad",
        "skip ads",
        "skip"
    )

    private val contextualActionKeywords = listOf(
        "إغلاق",
        "اغلاق",
        "close",
        "continue"
    )

    private val cornerDualGlyphKeywords = listOf(
        "التخطي",
        "تخطي",
        "إغلاق",
        "اغلاق",
        "skip",
        "close",
        ">>",
        "×",
        "✕",
        "✖"
    )

    private val adContextKeywords = listOf(
        "إعلان",
        "اعلان",
        "اعرف المزيد",
        "sponsored",
        "learn more",
        "تخطي الإعلان",
        "skip ad"
    )

    private val adWordRegex = Regex("""(?i)\bad\b""")

    fun evaluateObservations(
        observations: List<OcrObservation>,
        screenWidth: Int,
        screenHeight: Int,
        isRealAdActivity: Boolean,
        excludedRects: List<BoundsRect> = emptyList()
    ): OcrAdScanResult {
        if (observations.isEmpty() || screenWidth <= 0 || screenHeight <= 0) {
            return OcrAdScanResult(null, null, hasAdContext = isRealAdActivity)
        }

        var hasAdContext = isRealAdActivity
        var bestHighPriority: Pair<OcrObservation, String>? = null
        var bestContextual: Pair<OcrObservation, String>? = null

        for (obs in observations) {
            if (obs.confidence in 1f..34f) continue
            val norm = AdDetectionRules.normalizeText(obs.text, maxLength = 72) ?: continue

            if (!hasAdContext && isAdContextText(norm)) {
                hasAdContext = true
            }

            if (AdDetectionRules.isCountdownText(norm)) continue
            if (!isPlausibleButtonBox(obs.bounds, screenWidth, screenHeight)) continue
            if (AdDetectionRules.hasExcludedCornerKeyword(null, norm, null)) continue
            if (OpenCvTemplateEvaluator.isInsideExcludedRect(
                    obs.bounds.centerX.toFloat(),
                    obs.bounds.centerY.toFloat(),
                    excludedRects
                )
            ) {
                continue
            }

            val highMatch = highPriorityActionKeywords.firstOrNull { norm.contains(it, ignoreCase = true) }
            if (highMatch != null && bestHighPriority == null) {
                bestHighPriority = obs to highMatch
                continue
            }

            val contextualMatch = contextualActionKeywords.firstOrNull { norm.contains(it, ignoreCase = true) }
            if (contextualMatch != null && bestContextual == null) {
                bestContextual = obs to contextualMatch
            }
        }

        val winner = bestHighPriority ?: if (hasAdContext) bestContextual else null
        return OcrAdScanResult(
            actionTargetBounds = winner?.first?.bounds,
            matchedKeyword = winner?.second,
            hasAdContext = hasAdContext
        )
    }

    fun evaluateCornerObservations(
        observations: List<OcrObservation>,
        screenWidth: Int,
        screenHeight: Int,
        excludedRects: List<BoundsRect> = emptyList()
    ): OcrAdScanResult {
        if (observations.isEmpty() || screenWidth <= 0 || screenHeight <= 0) {
            return OcrAdScanResult(null, null, hasAdContext = false)
        }

        for (obs in observations) {
            if (obs.confidence in 1f..30f) continue
            val norm = AdDetectionRules.normalizeText(obs.text, maxLength = 32) ?: continue
            if (AdDetectionRules.isCountdownText(norm)) continue
            if (AdDetectionRules.hasExcludedCornerKeyword(null, norm, null)) continue
            if (!isPlausibleButtonBox(obs.bounds, screenWidth, screenHeight)) continue
            if (OpenCvTemplateEvaluator.isInsideExcludedRect(
                    obs.bounds.centerX.toFloat(),
                    obs.bounds.centerY.toFloat(),
                    excludedRects
                )
            ) {
                continue
            }

            if (norm == "x") {
                return OcrAdScanResult(obs.bounds, "X", hasAdContext = true)
            }
            val matched = cornerDualGlyphKeywords.firstOrNull { norm.contains(it, ignoreCase = true) }
            if (matched != null) {
                return OcrAdScanResult(obs.bounds, matched, hasAdContext = true)
            }
        }
        return OcrAdScanResult(null, null, hasAdContext = false)
    }

    private fun isAdContextText(normalized: String): Boolean {
        if (adContextKeywords.any { normalized.contains(it, ignoreCase = true) }) {
            return true
        }
        return adWordRegex.containsMatchIn(normalized)
    }

    private fun isPlausibleButtonBox(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (bounds.isEmpty) return false
        if (bounds.width < 10 || bounds.height < 10) return false
        if (bounds.width > screenWidth * 0.75f || bounds.height > screenHeight * 0.16f) return false
        return bounds.centerX in 2..(screenWidth - 2) && bounds.centerY in 2..(screenHeight - 2)
    }
}

/**
 * Offline Arabic + English (`ara+eng`) OCR engine powered by Tesseract4Android.
 * Scans both full screenshots and zoomed top-corner crops (`X`, `>>`, `Skip`, `التخطي`).
 */
class TesseractOcrEngine(
    private val tessDataManager: TessDataManager
) {
    private val tessMutex = Mutex()
    private var tessApi: TessBaseAPI? = null
    private var initialized: Boolean = false

    suspend fun recognizeAdControls(
        softwareScreenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        isRealAdActivity: Boolean,
        excludedRects: List<BoundsRect> = emptyList()
    ): OcrAdScanResult = tessMutex.withLock {
        if (softwareScreenshot.isRecycled || softwareScreenshot.width <= 0 || softwareScreenshot.height <= 0) {
            return@withLock OcrAdScanResult(null, null, hasAdContext = isRealAdActivity)
        }

        val ready = ensureInitializedLocked()
        if (!ready) {
            return@withLock OcrAdScanResult(null, null, hasAdContext = isRealAdActivity)
        }

        val api = tessApi ?: return@withLock OcrAdScanResult(null, null, hasAdContext = isRealAdActivity)
        var scaledBitmap: Bitmap? = null

        return@withLock try {
            val targetBitmap = if (softwareScreenshot.width > MAX_OCR_WIDTH) {
                val ratio = MAX_OCR_WIDTH.toFloat() / softwareScreenshot.width.toFloat()
                val targetH = (softwareScreenshot.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(softwareScreenshot, MAX_OCR_WIDTH, targetH, true).also {
                    scaledBitmap = it
                }
            } else {
                softwareScreenshot
            }

            val scaleToScreenX = screenWidth.toFloat() / targetBitmap.width.toFloat()
            val scaleToScreenY = screenHeight.toFloat() / targetBitmap.height.toFloat()

            val observations = runOcrPass(
                api = api,
                bitmap = targetBitmap,
                offsetX = 0f,
                offsetY = 0f,
                scaleX = scaleToScreenX,
                scaleY = scaleToScreenY
            )

            OcrAdTextMatcher.evaluateObservations(
                observations = observations,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                isRealAdActivity = isRealAdActivity,
                excludedRects = excludedRects
            )
        } catch (_: Throwable) {
            OcrAdScanResult(null, null, hasAdContext = isRealAdActivity)
        } finally {
            scaledBitmap?.recycle()
        }
    }

    /**
     * Dual corner OCR pass on cropped top-left and top-right regions (`22% x 15%`)
     * to detect small `X`, `>>`, `Skip`, or `التخطي` buttons missed on full-screen downscales.
     */
    suspend fun recognizeCornerControls(
        softwareScreenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        excludedRects: List<BoundsRect> = emptyList()
    ): OcrAdScanResult = tessMutex.withLock {
        if (softwareScreenshot.isRecycled || softwareScreenshot.width <= 0 || softwareScreenshot.height <= 0) {
            return@withLock OcrAdScanResult(null, null, hasAdContext = false)
        }

        val ready = ensureInitializedLocked()
        if (!ready) {
            return@withLock OcrAdScanResult(null, null, hasAdContext = false)
        }

        val api = tessApi ?: return@withLock OcrAdScanResult(null, null, hasAdContext = false)

        val bmpW = softwareScreenshot.width
        val bmpH = softwareScreenshot.height
        val cropW = (bmpW * OpenCvAdIconDetector.CORNER_WIDTH_RATIO).toInt().coerceIn(32, bmpW)
        val cropH = (bmpH * OpenCvAdIconDetector.CORNER_HEIGHT_RATIO).toInt().coerceIn(32, bmpH)
        val bmpToScreenX = screenWidth.toFloat() / bmpW.toFloat()
        val bmpToScreenY = screenHeight.toFloat() / bmpH.toFloat()

        val corners = listOf(
            0 to 0,
            (bmpW - cropW).coerceAtLeast(0) to 0
        )

        for ((cropX, cropY) in corners) {
            var cropped: Bitmap? = null
            var upscaled: Bitmap? = null
            try {
                cropped = Bitmap.createBitmap(softwareScreenshot, cropX, cropY, cropW, cropH)
                val factor = if (cropW < 360) 2f else 1f
                val ocrBitmap = if (factor > 1f) {
                    Bitmap.createScaledBitmap(
                        cropped,
                        (cropW * factor).toInt(),
                        (cropH * factor).toInt(),
                        true
                    ).also { upscaled = it }
                } else {
                    cropped
                }

                val observations = runOcrPass(
                    api = api,
                    bitmap = ocrBitmap,
                    offsetX = cropX * bmpToScreenX,
                    offsetY = cropY * bmpToScreenY,
                    scaleX = bmpToScreenX / factor,
                    scaleY = bmpToScreenY / factor
                )

                val result = OcrAdTextMatcher.evaluateCornerObservations(
                    observations = observations,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    excludedRects = excludedRects
                )
                if (result.actionTargetBounds != null) {
                    return@withLock result
                }
            } catch (_: Throwable) {
                // Continue to remaining corner
            } finally {
                upscaled?.recycle()
                cropped?.recycle()
            }
        }

        return@withLock OcrAdScanResult(null, null, hasAdContext = false)
    }

    private fun runOcrPass(
        api: TessBaseAPI,
        bitmap: Bitmap,
        offsetX: Float,
        offsetY: Float,
        scaleX: Float,
        scaleY: Float
    ): List<OcrObservation> {
        api.setImage(bitmap)
        api.getUTF8Text()

        val observations = mutableListOf<OcrObservation>()
        val iterator = api.resultIterator
        if (iterator != null) {
            try {
                collectLevelObservations(
                    iterator = iterator,
                    level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    out = observations
                )
                collectLevelObservations(
                    iterator = iterator,
                    level = TessBaseAPI.PageIteratorLevel.RIL_WORD,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    out = observations
                )
            } finally {
                iterator.delete()
            }
        }
        api.clear()
        return observations
    }

    private fun collectLevelObservations(
        iterator: com.googlecode.tesseract.android.ResultIterator,
        level: Int,
        offsetX: Float,
        offsetY: Float,
        scaleX: Float,
        scaleY: Float,
        out: MutableList<OcrObservation>
    ) {
        iterator.begin()
        var count = 0
        do {
            val text = iterator.getUTF8Text(level)
            val rect = iterator.getBoundingRect(level)
            val conf = iterator.confidence(level)
            if (!text.isNullOrBlank() && rect != null) {
                val mapped = BoundsRect(
                    left = (offsetX + rect.left * scaleX).toInt(),
                    top = (offsetY + rect.top * scaleY).toInt(),
                    right = (offsetX + rect.right * scaleX).toInt(),
                    bottom = (offsetY + rect.bottom * scaleY).toInt()
                )
                out.add(OcrObservation(text = text, bounds = mapped, confidence = conf))
            }
            count++
        } while (count < MAX_OCR_ITEMS && iterator.next(level))
    }

    private suspend fun ensureInitializedLocked(): Boolean {
        if (initialized && tessApi != null) return true
        val dataReady = tessDataManager.ensureTessDataReady()
        if (!dataReady) return false

        val api = TessBaseAPI()
        val ok = runCatching {
            api.init(tessDataManager.getTesseractDataRootPath(), OCR_LANGUAGES)
        }.getOrDefault(false)

        if (!ok) {
            runCatching { api.recycle() }
            return false
        }

        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        tessApi = api
        initialized = true
        return true
    }

    fun release() {
        runCatching { tessApi?.recycle() }
        tessApi = null
        initialized = false
    }

    companion object {
        private const val OCR_LANGUAGES = "ara+eng"
        private const val MAX_OCR_WIDTH = 760
        private const val MAX_OCR_ITEMS = 120
    }
}
