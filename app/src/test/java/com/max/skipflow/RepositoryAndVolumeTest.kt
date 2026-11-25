package com.max.skipflow.engine

import com.max.skipflow.data.KeyValueStorage
import com.max.skipflow.data.SkipFlowRepository
import com.max.skipflow.updater.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryKeyValueStorage : KeyValueStorage {
    private val map = mutableMapOf<String, Any>()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        (map[key] as? Int) ?: defaultValue

    override fun putInt(key: String, value: Int) {
        map[key] = value
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        (map[key] as? Long) ?: defaultValue

    override fun putLong(key: String, value: Long) {
        map[key] = value
    }

    override fun getString(key: String, defaultValue: String?): String? =
        (map[key] as? String) ?: defaultValue

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        (map[key] as? Set<String>)?.toSet() ?: defaultValue

    override fun putStringSet(key: String, value: Set<String>) {
        map[key] = value.toSet()
    }
}

private class FakeVolumeBackend(var volume: Int) : MediaVolumeBackend {
    override fun getMusicVolume(): Int = volume
    override fun setMusicVolume(volume: Int) {
        this.volume = volume
    }
}

class RepositoryAndVolumeTest {

    @Test
    fun `YouTube is enabled by default and stats reset cleanly on day rollover`() {
        var currentDay = "2026-11-25"
        val repo = SkipFlowRepository(
            storage = InMemoryKeyValueStorage(),
            todayProvider = { currentDay }
        )

        assertTrue(repo.isPackageEnabled(AdDetectionRules.YOUTUBE_PACKAGE))
        assertFalse(repo.isPackageEnabled("com.example.game"))

        repo.recordAdSkipped(15L)
        assertEquals(1, repo.stats.value.skippedToday)
        assertEquals(15L, repo.stats.value.savedSecondsToday)

        currentDay = "2026-11-26"
        repo.refreshDateRolloverIfNeeded()
        assertEquals(0, repo.stats.value.skippedToday)
        assertEquals(0L, repo.stats.value.savedSecondsToday)
    }

    @Test
    fun `YouTubeVolumeController mutes on ad and restores original level`() {
        val backend = FakeVolumeBackend(volume = 11)
        val controller = YouTubeVolumeController(backend, restoreGraceMs = 900L)

        controller.onAdVisible(autoMuteEnabled = true)
        assertEquals(0, backend.volume)
        assertTrue(controller.isMuted())

        // Repeated visible checks do not lose original volume
        controller.onAdVisible(autoMuteEnabled = true)
        assertEquals(0, backend.volume)

        // Immediate restore brings back 11
        controller.forceRestore()
        assertEquals(11, backend.volume)
        assertFalse(controller.isMuted())
    }

    @Test
    fun `VersionComparator compares GitHub release tags accurately`() {
        assertTrue(VersionComparator.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(VersionComparator.isNewerVersion("v1.10.0", "1.2.0"))
        assertFalse(VersionComparator.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewerVersion("v0.9.9", "1.0.0"))
    }
}
