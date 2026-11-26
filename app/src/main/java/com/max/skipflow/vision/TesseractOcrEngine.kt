package com.max.skipflow.vision

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.max.skipflow.engine.AdDetectionRules
import com.max.skipflow.engine.BoundsRect
import com.max.skipflow.engine.DisplayGeometry
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
    val hasAdContext: Boolean,
    val storeExclusionRects: List<BoundsRect> = emptyList()
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
        "> >",
        ">",
        "×",
        "✕",
        "✖"
    )

    private val adContextKeywords = listOf(
        "إعلان",
        "اعلان",
        "اعرف المزيد",
        "sponsored",
        "install",
        "learn more",
        "تخطي الإعلان",
        "تخطي الاعلان",
        "skip ad",
        "skip ads",
        "close ad",
        "إغلاق الإعلان",
        "اغلاق الاعلان"
    )

    fun findStoreRedirectExclusionRects(observations: List<OcrObservation>): List<BoundsRect> {
        if (observations.isEmpty()) return emptyList()
        val storeRects = mutableListOf<BoundsRect>()
        for (obs in observations) {
            if (obs.confidence in 1f..25f) continue
            val norm = AdDetectionRules.normalizeText(obs.text, maxLength = 72) ?: continue
            if (AdDetectionRules.hasStoreRedirectKeyword(null, norm, null)) {
                storeRects.add(
                    BoundsRect(
                        left = obs.bounds.left - 200,
                        top = obs.bounds.top - 24,
                        right = obs.bounds.right + 200,
                        bottom = obs.bounds.bottom + 24
                    )
                )
            }
        }
        return storeRects
    }

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

        val storeExclusionRects = findStoreRedirectExclusionRects(observations)
        val combinedExcludedRects = excludedRects + storeExclusionRects

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
            if (AdDetectionRules.hasStoreRedirectKeyword(null, norm, null)) continue
            if (OpenCvTemplateEvaluator.isInsideExcludedRect(
                    obs.bounds.centerX.toFloat(),
                    obs.bounds.centerY.toFloat(),
                    combinedExcludedRects
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

        // Strict verification: never trigger ANY OCR tap unless the screen is a confirmed AdActivity
        // or actively displays explicit ad text.
        val winner = if (hasAdContext) (bestHighPriority ?: bestContextual) else null
        return OcrAdScanResult(
            actionTargetBounds = winner?.first?.bounds,
            matchedKeyword = winner?.second,
            hasAdContext = hasAdContext,
            storeExclusionRects = storeExclusionRects
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

        val storeExclusionRects = findStoreRedirectExclusionRects(observations)
        val combinedExcludedRects = excludedRects + storeExclusionRects

        for (obs in observations) {
            if (obs.confidence in 1f..30f) continue
            val norm = AdDetectionRules.normalizeText(obs.text, maxLength = 32) ?: continue
            if (AdDetectionRules.isCountdownText(norm)) continue
            if (AdDetectionRules.hasExcludedCornerKeyword(null, norm, null)) continue
            if (AdDetectionRules.hasStoreRedirectKeyword(null, norm, null)) continue
            if (!isPlausibleButtonBox(obs.bounds, screenWidth, screenHeight)) continue
            if (OpenCvTemplateEvaluator.isInsideExcludedRect(
                    obs.bounds.centerX.toFloat(),
                    obs.bounds.centerY.toFloat(),
                    combinedExcludedRects
                )
            ) {
                continue
            }

            if (norm == "x") {
                return OcrAdScanResult(obs.bounds, "X", hasAdContext = true, storeExclusionRects = storeExclusionRects)
            }
            val matched = cornerDualGlyphKeywords.firstOrNull { norm.contains(it, ignoreCase = true) }
            if (matched != null) {
                return OcrAdScanResult(obs.bounds, matched, hasAdContext = true, storeExclusionRects = storeExclusionRects)
            }
        }
        return OcrAdScanResult(null, null, hasAdContext = false, storeExclusionRects = storeExclusionRects)
    }

    private fun isAdContextText(normalized: String): Boolean {
        return adContextKeywords.any { normalized.contains(it, ignoreCase = true) }
    }

    private fun isPlausibleButtonBox(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (bounds.isEmpty) return false
        if (bounds.width < 10 || bounds.height < 10) return false
        val maxHeightRatio = if (screenWidth > screenHeight) 0.28f else 0.18f
        if (bounds.width > screenWidth * 0.75f || bounds.height > screenHeight * maxHeightRatio) return false
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
            val bmpW = softwareScreenshot.width
            val bmpH = softwareScreenshot.height
            val (effectiveScreenW, effectiveScreenH) = DisplayGeometry.alignScreenToBitmap(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                bitmapWidth = bmpW,
                bitmapHeight = bmpH
            )
            val maxDim = maxOf(bmpW, bmpH)
            val targetBitmap = if (maxDim > MAX_OCR_LONG_SIDE) {
                val ratio = MAX_OCR_LONG_SIDE.toFloat() / maxDim.toFloat()
                val targetW = (bmpW * ratio).toInt().coerceAtLeast(1)
                val targetH = (bmpH * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(softwareScreenshot, targetW, targetH, true).also {
                    scaledBitmap = it
                }
            } else {
                softwareScreenshot
            }

            val scaleToScreenX = effectiveScreenW.toFloat() / targetBitmap.width.toFloat()
            val scaleToScreenY = effectiveScreenH.toFloat() / targetBitmap.height.toFloat()

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
                screenWidth = effectiveScreenW,
                screenHeight = effectiveScreenH,
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
     * Dual corner OCR pass on orientation-aware top-left and top-right crops
     * (Portrait: 22% x 16%, Landscape: 18% x 26%) to detect small `X`, `>>`, `Skip`, or `التخطي` buttons.
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
        val (effectiveScreenW, effectiveScreenH) = DisplayGeometry.alignScreenToBitmap(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            bitmapWidth = bmpW,
            bitmapHeight = bmpH
        )
        val ratios = DisplayGeometry.cornerRatiosFor(bmpW, bmpH)
        val cropW = (bmpW * ratios.widthRatio).toInt().coerceIn(32, bmpW)
        val cropH = (bmpH * ratios.heightRatio).toInt().coerceIn(32, bmpH)
        val bmpToScreenX = effectiveScreenW.toFloat() / bmpW.toFloat()
        val bmpToScreenY = effectiveScreenH.toFloat() / bmpH.toFloat()

        val corners = listOf(
            0 to 0,
            (bmpW - cropW).coerceAtLeast(0) to 0
        )
        val accumulatedStoreRects = mutableListOf<BoundsRect>()

        for ((cropX, cropY) in corners) {
            var cropped: Bitmap? = null
            var upscaled: Bitmap? = null
            try {
                cropped = Bitmap.createBitmap(softwareScreenshot, cropX, cropY, cropW, cropH)
                val factor = if (cropW < 360 || cropH < 240) 2f else 1f
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
                    screenWidth = effectiveScreenW,
                    screenHeight = effectiveScreenH,
                    excludedRects = excludedRects + accumulatedStoreRects
                )
                accumulatedStoreRects.addAll(result.storeExclusionRects)
                if (result.actionTargetBounds != null) {
                    return@withLock result.copy(storeExclusionRects = accumulatedStoreRects.toList())
                }
            } catch (_: Throwable) {
                // Continue to remaining corner
            } finally {
                upscaled?.recycle()
                cropped?.recycle()
            }
        }

        return@withLock OcrAdScanResult(
            actionTargetBounds = null,
            matchedKeyword = null,
            hasAdContext = false,
            storeExclusionRects = accumulatedStoreRects.toList()
        )
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
        private const val MAX_OCR_LONG_SIDE = 1680
        private const val MAX_OCR_ITEMS = 120
    }
}
