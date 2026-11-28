package com.max.skipflow.engine

import com.max.skipflow.data.MiniJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class SponsorSegment(
    val uuid: String,
    val category: String,
    val startSec: Double,
    val endSec: Double
)

data class SponsorSkipAction(
    val uuid: String,
    val category: String,
    val startSec: Double,
    val endSec: Double,
    val jumpSeconds: Double
)

/**
 * Pure JVM-testable SponsorBlock helper:
 * - Privacy-preserving SHA-256 4-hex prefix hashing
 * - Video ID & playback timestamp extraction
 * - Sponsor & selfpromo segment matching
 */
object SponsorBlockParser {

    val SUPPORTED_CATEGORIES = setOf("sponsor", "selfpromo")

    private val videoIdUrlRegex = Regex(
        pattern = """(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})"""
    )

    private val colonTimestampRegex = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val englishUnitTimestampRegex = Regex(
        """(?i)(?:(\d+)\s*hours?)?\s*(?:(\d+)\s*minutes?)?\s*(?:(\d+)\s*seconds?)?"""
    )
    private val htmlVideoIdRegex = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")

    fun sha256HexPrefix(videoId: String, prefixLength: Int = 4): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(StandardCharsets.UTF_8))
        val fullHex = buildString(digest.size * 2) {
            for (b in digest) {
                append(String.format("%02x", b.toInt() and 0xFF))
            }
        }
        return fullHex.take(prefixLength.coerceIn(4, 16))
    }

    fun extractVideoIdFromText(raw: CharSequence?): String? {
        if (raw.isNullOrBlank()) return null
        return videoIdUrlRegex.find(raw.toString())?.groupValues?.getOrNull(1)
    }

    fun extractFirstVideoIdFromHtml(html: String): String? {
        return htmlVideoIdRegex.find(html)?.groupValues?.getOrNull(1)
    }

    fun parseTimestampToSeconds(raw: CharSequence?): Double? {
        val text = raw?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val colonMatch = colonTimestampRegex.find(text)
        if (colonMatch != null) {
            val first = colonMatch.groupValues[1].toIntOrNull() ?: return null
            val second = colonMatch.groupValues[2].toIntOrNull() ?: return null
            val third = colonMatch.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            return if (third != null) {
                (first * 3600 + second * 60 + third).toDouble()
            } else {
                (first * 60 + second).toDouble()
            }
        }

        // Parse spoken accessibility time phrases like "2 minutes 14 seconds" (e.g., before "of 10 minutes")
        val headPart = text.substringBefore(" of ").substringBefore(" من ")
        val unitMatch = englishUnitTimestampRegex.find(headPart)
        if (unitMatch != null) {
            val h = unitMatch.groupValues[1].toIntOrNull() ?: 0
            val m = unitMatch.groupValues[2].toIntOrNull() ?: 0
            val s = unitMatch.groupValues[3].toIntOrNull() ?: 0
            if (h > 0 || m > 0 || s > 0) {
                return (h * 3600 + m * 60 + s).toDouble()
            }
        }

        return null
    }

    fun parseSkipSegmentsJson(jsonBody: String, targetVideoId: String): List<SponsorSegment> {
        val parsed = runCatching { MiniJsonParser.parse(jsonBody) as? List<*> }.getOrNull() ?: return emptyList()
        val result = mutableListOf<SponsorSegment>()

        for (item in parsed) {
            val obj = item as? Map<*, *> ?: continue
            val nestedVideoId = (obj["videoID"] as? String)?.trim()
            val nestedSegments = obj["segments"] as? List<*>
            if (nestedSegments != null) {
                if (nestedVideoId != null && nestedVideoId != targetVideoId) continue
                for (segItem in nestedSegments) {
                    parseSingleSegmentObject(segItem as? Map<*, *>)?.let { result.add(it) }
                }
            } else {
                if (nestedVideoId != null && nestedVideoId != targetVideoId) continue
                parseSingleSegmentObject(obj)?.let { result.add(it) }
            }
        }
        return result.sortedBy { it.startSec }
    }

    private fun parseSingleSegmentObject(obj: Map<*, *>?): SponsorSegment? {
        if (obj == null) return null
        val category = (obj["category"] as? String)?.trim()?.lowercase() ?: return null
        if (category !in SUPPORTED_CATEGORIES) return null
        val uuid = (obj["UUID"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tuple = obj["segment"] as? List<*> ?: return null
        if (tuple.size < 2) return null
        val start = (tuple[0] as? Number)?.toDouble() ?: return null
        val end = (tuple[1] as? Number)?.toDouble() ?: return null
        if (end <= start + 1.0) return null
        return SponsorSegment(uuid = uuid, category = category, startSec = start, endSec = end)
    }

    fun findActiveSegment(
        currentSec: Double,
        segments: List<SponsorSegment>,
        skippedUuids: Set<String>
    ): SponsorSkipAction? {
        if (currentSec < 0.0 || segments.isEmpty()) return null
        for (seg in segments) {
            if (seg.uuid in skippedUuids) continue
            if (currentSec >= seg.startSec + 0.25 && currentSec < seg.endSec - 1.5) {
                val jumpSec = (seg.endSec - currentSec).coerceAtLeast(1.0)
                return SponsorSkipAction(
                    uuid = seg.uuid,
                    category = seg.category,
                    startSec = seg.startSec,
                    endSec = seg.endSec,
                    jumpSeconds = jumpSec
                )
            }
        }
        return null
    }

    /**
     * Number of fast-forward right-edge taps in YouTube Android (`2` = double-tap +10s, `+1` per extra 10s).
     */
    fun doubleTapSeriesCountForJump(jumpSeconds: Double): Int {
        val steps10s = ceil((jumpSeconds - 1.0) / 10.0).toInt().coerceIn(1, 6)
        return steps10s + 1
    }
}

/**
 * Queries SponsorBlock (`https://sponsor.ajay.app/api/skipSegments/{hashPrefix}`) silently for YouTube videos,
 * tracks playback position, and cleanly signals when playback enters a `sponsor` or `selfpromo` segment.
 * Network errors or unknown video IDs fail silently with zero impact on YouTube ad skipping.
 */
class YouTubeSponsorBlockManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val titleToVideoIdCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 64
    }
    private val segmentsCache = object : LinkedHashMap<String, List<SponsorSegment>>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<SponsorSegment>>?
        ): Boolean = size > 32
    }
    private val failedKeys = mutableSetOf<String>()

    @Volatile
    private var activeVideoKey: String? = null

    @Volatile
    private var activeVideoId: String? = null

    @Volatile
    private var activeSegments: List<SponsorSegment> = emptyList()

    @Volatile
    private var isFetchingVideo: Boolean = false

    private val skippedUuidsForCurrentVideo = mutableSetOf<String>()
    private var lastObservedPlaybackSec: Double? = null
    private var lastObservedAtElapsedMs: Long = 0L
    private var isVideoPlaying: Boolean = true
    private var lastSkipDispatchedAtMs: Long = 0L

    fun onLeftYouTubeOrAdStarted() {
        lastObservedPlaybackSec = null
        lastObservedAtElapsedMs = 0L
    }

    fun updatePlaybackState(
        directVideoId: String?,
        videoTitle: String?,
        channelName: String?,
        observedPlaybackSec: Double?,
        isPaused: Boolean,
        nowElapsedMs: Long
    ) {
        isVideoPlaying = !isPaused

        if (observedPlaybackSec != null) {
            lastObservedPlaybackSec = observedPlaybackSec
            lastObservedAtElapsedMs = nowElapsedMs
        }

        val resolvedKey = when {
            !directVideoId.isNullOrBlank() -> "id:$directVideoId"
            !videoTitle.isNullOrBlank() -> "meta:${videoTitle.trim()}|${channelName?.trim().orEmpty()}"
            else -> null
        } ?: return

        if (resolvedKey != activeVideoKey) {
            activeVideoKey = resolvedKey
            activeVideoId = directVideoId ?: titleToVideoIdCache[resolvedKey]
            activeSegments = activeVideoId?.let { segmentsCache[it] }.orEmpty()
            skippedUuidsForCurrentVideo.clear()
            if (observedPlaybackSec == null) {
                lastObservedPlaybackSec = null
                lastObservedAtElapsedMs = 0L
            }
        }
    }

    fun needsNetworkLookup(): Boolean {
        val key = activeVideoKey ?: return false
        if (isFetchingVideo) return false
        val id = activeVideoId
        if (id != null && segmentsCache.containsKey(id)) return false
        return key !in failedKeys
    }

    suspend fun fetchMissingSegmentsIfNeeded() = withContext(Dispatchers.IO) {
        val key = activeVideoKey ?: return@withContext
        if (isFetchingVideo || key in failedKeys) return@withContext
        isFetchingVideo = true
        try {
            var vid = activeVideoId ?: titleToVideoIdCache[key]
            if (vid == null && key.startsWith("meta:")) {
                val query = key.removePrefix("meta:").replace("|", " ").trim()
                if (query.length >= 4) {
                    vid = resolveVideoIdViaSearch(query)
                    if (vid != null) {
                        titleToVideoIdCache[key] = vid
                        if (activeVideoKey == key) {
                            activeVideoId = vid
                        }
                    }
                }
            }

            if (vid == null) {
                failedKeys.add(key)
                return@withContext
            }

            val cached = segmentsCache[vid]
            if (cached != null) {
                if (activeVideoKey == key) {
                    activeSegments = cached
                }
                return@withContext
            }

            val segments = fetchSegmentsByHashPrefix(vid)
            segmentsCache[vid] = segments
            if (activeVideoKey == key) {
                activeSegments = segments
            }
        } catch (_: Throwable) {
            // Silent failure if offline or rate-limited
        } finally {
            isFetchingVideo = false
        }
    }

    fun evaluateSkipCandidate(nowElapsedMs: Long): SponsorSkipAction? {
        if (!isVideoPlaying) return null
        if (nowElapsedMs - lastSkipDispatchedAtMs < SKIP_COOLDOWN_MS) return null
        val currentSec = estimateCurrentPlaybackSeconds(nowElapsedMs) ?: return null
        return SponsorBlockParser.findActiveSegment(
            currentSec = currentSec,
            segments = activeSegments,
            skippedUuids = skippedUuidsForCurrentVideo
        )
    }

    fun markSegmentSkipped(action: SponsorSkipAction, nowElapsedMs: Long) {
        skippedUuidsForCurrentVideo.add(action.uuid)
        lastSkipDispatchedAtMs = nowElapsedMs
        lastObservedPlaybackSec = action.endSec
        lastObservedAtElapsedMs = nowElapsedMs
    }

    private fun estimateCurrentPlaybackSeconds(nowElapsedMs: Long): Double? {
        val base = lastObservedPlaybackSec ?: return null
        if (lastObservedAtElapsedMs <= 0L) return null
        val elapsedSec = ((nowElapsedMs - lastObservedAtElapsedMs).coerceAtLeast(0L)) / 1000.0
        if (elapsedSec > MAX_BLIND_ESTIMATE_SEC) return null
        return base + elapsedSec
    }

    private fun resolveVideoIdViaSearch(query: String): String? {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val req = Request.Builder()
            .url("https://www.youtube.com/results?search_query=$encoded")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string().orEmpty()
                SponsorBlockParser.extractFirstVideoIdFromHtml(html)
            }
        }.getOrNull()
    }

    private fun fetchSegmentsByHashPrefix(videoId: String): List<SponsorSegment> {
        val prefix = SponsorBlockParser.sha256HexPrefix(videoId, 4)
        val url = "https://sponsor.ajay.app/api/skipSegments/$prefix?service=YouTube&categories=%5B%22sponsor%22%2C%22selfpromo%22%5D"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "SkipFlow-Max/3.0.0")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404 || !resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                SponsorBlockParser.parseSkipSegmentsJson(body, videoId)
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val SKIP_COOLDOWN_MS = 3_500L
        private const val MAX_BLIND_ESTIMATE_SEC = 180.0
    }
}
