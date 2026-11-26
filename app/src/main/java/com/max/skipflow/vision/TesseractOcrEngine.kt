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
 * Pure JVM-testable matcher over OCR lines/words for Arabic & English ad skip controls
 * and ad-context keywords.
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
        isRealAdActivity: Boolean
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
        if (bounds.width < 12 || bounds.height < 12) return false
        if (bounds.width > screenWidth * 0.75f || bounds.height > screenHeight * 0.16f) return false
        return bounds.centerX in 2..(screenWidth - 2) && bounds.centerY in 2..(screenHeight - 2)
    }
}

/**
 * Offline Arabic + English (`ara+eng`) OCR engine powered by Tesseract4Android.
 * Used as a fallback when regular Accessibility tree inspection finds no skip/close button.
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
        isRealAdActivity: Boolean
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

            api.setImage(targetBitmap)
            // Trigger recognition before iterating bounding boxes
            api.getUTF8Text()

            val observations = mutableListOf<OcrObservation>()
            val iterator = api.resultIterator
            if (iterator != null) {
                try {
                    collectLevelObservations(
                        iterator = iterator,
                        level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE,
                        scaleX = scaleToScreenX,
                        scaleY = scaleToScreenY,
                        out = observations
                    )
                    collectLevelObservations(
                        iterator = iterator,
                        level = TessBaseAPI.PageIteratorLevel.RIL_WORD,
                        scaleX = scaleToScreenX,
                        scaleY = scaleToScreenY,
                        out = observations
                    )
                } finally {
                    iterator.delete()
                }
            }

            api.clear()

            OcrAdTextMatcher.evaluateObservations(
                observations = observations,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                isRealAdActivity = isRealAdActivity
            )
        } catch (_: Throwable) {
            OcrAdScanResult(null, null, hasAdContext = isRealAdActivity)
        } finally {
            scaledBitmap?.recycle()
        }
    }

    private fun collectLevelObservations(
        iterator: com.googlecode.tesseract.android.ResultIterator,
        level: Int,
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
                    left = (rect.left * scaleX).toInt(),
                    top = (rect.top * scaleY).toInt(),
                    right = (rect.right * scaleX).toInt(),
                    bottom = (rect.bottom * scaleY).toInt()
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
