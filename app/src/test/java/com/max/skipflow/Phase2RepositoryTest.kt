package com.max.skipflow

import com.max.skipflow.data.KeyValueStorage
import com.max.skipflow.data.SkipDetectionMethod
import com.max.skipflow.data.SkipFlowRepository
import com.max.skipflow.engine.BoundsRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Phase2MemoryStorage : KeyValueStorage {
    private val booleans = mutableMapOf<String, Boolean>()
    private val ints = mutableMapOf<String, Int>()
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()
    private val stringSets = mutableMapOf<String, Set<String>>()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleans[key] ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = longs[key] ?: defaultValue
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getString(key: String, defaultValue: String?): String? = strings[key] ?: defaultValue
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> = stringSets[key] ?: defaultValue
    override fun putStringSet(key: String, value: Set<String>) { stringSets[key] = value }
}

class Phase2RepositoryTest {

    @Test
    fun `Phase 2 default settings match specification and persist toggles`() {
        val storage = Phase2MemoryStorage()
        val repo = SkipFlowRepository(storage = storage, todayProvider = { "2026-11-27" })

        assertFalse(repo.settings.value.adCurtainEnabled)
        assertTrue(repo.settings.value.autoSwipeSocialAdsEnabled)
        assertFalse(repo.settings.value.annoyanceKillerEnabled)

        repo.setAdCurtainEnabled(true)
        repo.setAutoSwipeSocialAdsEnabled(false)
        repo.setAnnoyanceKillerEnabled(true)

        val reloaded = SkipFlowRepository(storage = storage, todayProvider = { "2026-11-27" })
        assertTrue(reloaded.settings.value.adCurtainEnabled)
        assertFalse(reloaded.settings.value.autoSwipeSocialAdsEnabled)
        assertTrue(reloaded.settings.value.annoyanceKillerEnabled)
    }

    @Test
    fun `Smart Snooze pauses for 10 minutes, reports countdown, and expires automatically`() {
        var nowWallMs = 1_000_000L
        val storage = Phase2MemoryStorage()
        val repo = SkipFlowRepository(
            storage = storage,
            todayProvider = { "2026-11-27" },
            clockProvider = { nowWallMs }
        )

        assertFalse(repo.isSnoozed())
        assertEquals(0L, repo.getSnoozeRemainingMs())

        repo.startSmartSnooze()
        assertTrue(repo.isSnoozed())
        assertEquals(SkipFlowRepository.DEFAULT_SNOOZE_DURATION_MS, repo.getSnoozeRemainingMs())

        nowWallMs += 4 * 60 * 1000L
        assertTrue(repo.isSnoozed())
        assertEquals(6 * 60 * 1000L, repo.getSnoozeRemainingMs())

        nowWallMs += 6 * 60 * 1000L + 1L
        assertFalse(repo.isSnoozed())
        assertEquals(0L, repo.getSnoozeRemainingMs())

        repo.startSmartSnooze()
        assertTrue(repo.isSnoozed())
        repo.cancelSmartSnooze()
        assertFalse(repo.isSnoozed())
    }

    @Test
    fun `Fake-X trap stays blocked for 24 hours and expires afterward`() {
        var nowWallMs = 10_000_000L
        val storage = Phase2MemoryStorage()
        val repo = SkipFlowRepository(
            storage = storage,
            todayProvider = { "2026-11-27" },
            clockProvider = { nowWallMs }
        )

        repo.recordFakeXTrap(
            packageName = "com.example.game",
            activityName = "AdActivity",
            normCenterX = 0.92f,
            normCenterY = 0.06f,
            viewId = "com.example.game:id/fake_close"
        )

        val sameSpot = BoundsRect(960, 90, 1030, 180)
        assertTrue(
            repo.isKnownFakeXTrap(
                packageName = "com.example.game",
                activityName = "AdActivity",
                bounds = sameSpot,
                screenWidth = 1080,
                screenHeight = 2400,
                viewId = null
            )
        )
        assertEquals(
            1,
            repo.getFakeXTrapExclusionRects("com.example.game", "AdActivity", 1080, 2400).size
        )

        // Different app is NOT affected
        assertFalse(
            repo.isKnownFakeXTrap(
                packageName = "com.other.game",
                activityName = "AdActivity",
                bounds = sameSpot,
                screenWidth = 1080,
                screenHeight = 2400,
                viewId = "com.example.game:id/fake_close"
            )
        )

        // Advance past 24h TTL -> expires automatically
        nowWallMs += SkipFlowRepository.FAKE_X_TTL_MS + 1_000L
        assertFalse(
            repo.isKnownFakeXTrap(
                packageName = "com.example.game",
                activityName = "AdActivity",
                bounds = sameSpot,
                screenWidth = 1080,
                screenHeight = 2400,
                viewId = "com.example.game:id/fake_close"
            )
        )
    }

    @Test
    fun `Skip log persists newest-first capped at 50 entries and clears cleanly`() {
        var nowWallMs = 50_000L
        val storage = Phase2MemoryStorage()
        val repo = SkipFlowRepository(
            storage = storage,
            todayProvider = { "2026-11-27" },
            clockProvider = { nowWallMs }
        )

        repeat(60) { index ->
            nowWallMs += 1_000L
            repo.recordSkipLog(
                packageName = "com.example.app$index",
                appName = "App $index",
                method = SkipDetectionMethod.OPENCV,
                detail = "Close #$index"
            )
        }

        val logs = repo.skipLogs.value
        assertEquals(50, logs.size)
        assertEquals("App 59", logs.first().appName)
        assertEquals("App 10", logs.last().appName)

        // Survives repository reload
        val reloaded = SkipFlowRepository(storage = storage, todayProvider = { "2026-11-27" })
        assertEquals(50, reloaded.skipLogs.value.size)
        assertEquals("App 59", reloaded.skipLogs.value.first().appName)

        reloaded.clearSkipLogs()
        assertTrue(reloaded.skipLogs.value.isEmpty())
    }
}
