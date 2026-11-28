package com.max.skipflow

import com.max.skipflow.data.KeyValueStorage
import com.max.skipflow.data.RemoteRulesConfig
import com.max.skipflow.data.RemoteRulesParser
import com.max.skipflow.data.SkipFlowRepository
import com.max.skipflow.engine.AdDetectionRules
import com.max.skipflow.engine.SponsorBlockParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Phase3InMemoryStorage : KeyValueStorage {
    private val map = mutableMapOf<String, Any?>()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        map[key] as? Boolean ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        map[key] as? Int ?: defaultValue

    override fun putInt(key: String, value: Int) {
        map[key] = value
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        map[key] as? Long ?: defaultValue

    override fun putLong(key: String, value: Long) {
        map[key] = value
    }

    override fun getString(key: String, defaultValue: String?): String? =
        map[key] as? String ?: defaultValue

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        (map[key] as? Set<String>) ?: defaultValue

    override fun putStringSet(key: String, value: Set<String>) {
        map[key] = value.toSet()
    }
}

class Phase3FeaturesTest {

    @After
    fun tearDown() {
        AdDetectionRules.resetRemoteRulesToBaseline()
    }

    @Test
    fun `remote rules parser extracts trimmed distinct lists and rejects empty payloads`() {
        val json = """
            {
              "version": 4,
              "adActivities": ["  CustomAdNetworkActivity ", "CustomAdNetworkActivity", "MBridge"],
              "skipTexts": ["تخطي سريع", "Skip Sponsor"],
              "excludedPackages": ["com.example.wallet"]
            }
        """.trimIndent()

        val config = RemoteRulesParser.parse(json)
        assertNotNull(config)
        assertEquals(4, config!!.version)
        assertEquals(listOf("CustomAdNetworkActivity", "MBridge"), config.adActivities)
        assertEquals(listOf("تخطي سريع", "Skip Sponsor"), config.skipTexts)
        assertEquals(listOf("com.example.wallet"), config.excludedPackages)

        assertNull(RemoteRulesParser.parse("{}"))
        assertNull(RemoteRulesParser.parse("not-json"))
    }

    @Test
    fun `remote rules hot-reload merges with baseline and resets cleanly`() {
        assertFalse(AdDetectionRules.isStrictlyExcludedPackage("com.example.bank"))
        assertFalse(AdDetectionRules.isRealAdActivity("com.example.CustomNetworkVideoOverlay"))

        AdDetectionRules.applyRemoteRules(
            RemoteRulesConfig(
                version = 2,
                adActivities = listOf("CustomNetworkVideoOverlay"),
                skipTexts = listOf("bypass promo card"),
                excludedPackages = listOf("com.example.bank")
            )
        )

        // Baseline must remain intact
        assertTrue(AdDetectionRules.isStrictlyExcludedPackage("com.android.vending"))
        assertTrue(AdDetectionRules.isRealAdActivity("com.bytedance.sdk.openadsdk.activity.TTFullScreenExpressVideoActivity"))

        // Remote additions must be active immediately
        assertTrue(AdDetectionRules.isStrictlyExcludedPackage("com.example.bank"))
        assertTrue(AdDetectionRules.isRealAdActivity("com.example.CustomNetworkVideoOverlay"))
        assertNotNull(
            AdDetectionRules.matchCandidate(
                packageName = "com.example.game",
                viewIdResourceName = null,
                text = "bypass promo card",
                contentDescription = null,
                isClickableOrHasClickableParent = true,
                isAdActivityContext = true
            )
        )

        // Reset restores baseline only
        AdDetectionRules.resetRemoteRulesToBaseline()
        assertFalse(AdDetectionRules.isStrictlyExcludedPackage("com.example.bank"))
        assertFalse(AdDetectionRules.isRealAdActivity("com.example.CustomNetworkVideoOverlay"))
        assertNull(
            AdDetectionRules.matchCandidate(
                packageName = "com.example.game",
                viewIdResourceName = null,
                text = "bypass promo card",
                contentDescription = null,
                isClickableOrHasClickableParent = true,
                isAdActivityContext = true
            )
        )
    }

    @Test
    fun `sponsorblock parser hashes videoId prefix, parses urls, timestamps and segments`() {
        val prefix = SponsorBlockParser.sha256HexPrefix("dQw4w9WgXcQ", 4)
        assertEquals(4, prefix.length)
        assertTrue(prefix.all { it in '0'..'9' || it in 'a'..'f' })

        assertEquals(
            "dQw4w9WgXcQ",
            SponsorBlockParser.extractVideoIdFromText("Check https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s now")
        )
        assertEquals("abc123_-XYZ", SponsorBlockParser.extractVideoIdFromText("https://youtu.be/abc123_-XYZ"))
        assertEquals("sH0Rt5_Vid1", SponsorBlockParser.extractVideoIdFromText("https://youtube.com/shorts/sH0Rt5_Vid1"))
        assertNull(SponsorBlockParser.extractVideoIdFromText("Just plain video title"))

        assertEquals(134.0, SponsorBlockParser.parseTimestampToSeconds("2:14")!!, 0.01)
        assertEquals(3723.0, SponsorBlockParser.parseTimestampToSeconds("1:02:03")!!, 0.01)
        assertEquals(
            134.0,
            SponsorBlockParser.parseTimestampToSeconds("2 minutes 14 seconds of 10 minutes")!!,
            0.01
        )
        assertNull(SponsorBlockParser.parseTimestampToSeconds("Play video"))

        val apiResponse = """
            [
              {
                "videoID": "otherVideo1",
                "segments": [
                  {"category": "sponsor", "segment": [10.0, 20.0], "UUID": "u-other"}
                ]
              },
              {
                "videoID": "dQw4w9WgXcQ",
                "segments": [
                  {"category": "intro", "segment": [0.0, 12.0], "UUID": "u-intro"},
                  {"category": "sponsor", "segment": [45.0, 75.0], "UUID": "u-sponsor-1"},
                  {"category": "selfpromo", "segment": [120.0, 140.0], "UUID": "u-self-1"},
                  {"category": "sponsor", "segment": [150.0, 150.5], "UUID": "u-too-short"}
                ]
              }
            ]
        """.trimIndent()

        val segments = SponsorBlockParser.parseSkipSegmentsJson(apiResponse, "dQw4w9WgXcQ")
        assertEquals(2, segments.size)
        assertEquals("u-sponsor-1", segments[0].uuid)
        assertEquals("u-self-1", segments[1].uuid)

        assertNull(SponsorBlockParser.findActiveSegment(30.0, segments, emptySet()))

        val active = SponsorBlockParser.findActiveSegment(47.0, segments, emptySet())
        assertNotNull(active)
        assertEquals("u-sponsor-1", active!!.uuid)
        assertEquals(28.0, active.jumpSeconds, 0.01)
        assertEquals(4, SponsorBlockParser.doubleTapSeriesCountForJump(active.jumpSeconds))

        assertNull(SponsorBlockParser.findActiveSegment(47.0, segments, setOf("u-sponsor-1")))
    }

    @Test
    fun `repository phase 3 defaults and ota sync timestamps behave as specified`() {
        var nowMs = 1_700_000_000_000L
        val storage = Phase3InMemoryStorage()
        val repo = SkipFlowRepository(
            storage = storage,
            todayProvider = { "2026-11-28" },
            clockProvider = { nowMs }
        )

        // Phase 3 production defaults
        assertFalse(repo.settings.value.liveDebugOverlayEnabled)
        assertTrue(repo.settings.value.sponsorBlockEnabled)
        assertFalse(repo.settings.value.shizukuTurboModeEnabled)

        // Toggle persistence
        repo.setLiveDebugOverlayEnabled(true)
        repo.setSponsorBlockEnabled(false)
        repo.setShizukuTurboModeEnabled(true)

        val reloaded = SkipFlowRepository(storage = storage, clockProvider = { nowMs })
        assertTrue(reloaded.settings.value.liveDebugOverlayEnabled)
        assertFalse(reloaded.settings.value.sponsorBlockEnabled)
        assertTrue(reloaded.settings.value.shizukuTurboModeEnabled)

        // Daily OTA check gating
        assertTrue(reloaded.shouldRunDailyOtaCheck(nowMs))
        reloaded.recordOtaCheckedNow(nowMs)
        assertFalse(reloaded.shouldRunDailyOtaCheck(nowMs + 3_600_000L))
        assertTrue(reloaded.shouldRunDailyOtaCheck(nowMs + 86_400_000L))

        // Rules sync timestamp
        reloaded.recordRulesSynced(nowMs + 5_000L)
        assertEquals(nowMs + 5_000L, reloaded.lastRulesSyncEpochMs.value)
    }
}
