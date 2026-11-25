package com.max.skipflow.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStoreRedirectGuardTest {

    @Test
    fun `bounces back once when Play Store opens within 3 seconds of game skip attempt`() {
        val guard = PlayStoreRedirectGuard()
        guard.recordGameOrAdSkipAttempt("com.example.game", nowMs = 10_000L)

        // Play Store opens 1.2s after tap -> bounce back ONCE
        assertTrue(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = true, nowMs = 11_200L))
        // Second event from Play Store for same attempt must NOT loop
        assertFalse(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = true, nowMs = 11_500L))
    }

    @Test
    fun `does not bounce back after 3 seconds or when user disables toggle or exits via launcher`() {
        val guard = PlayStoreRedirectGuard()
        guard.recordGameOrAdSkipAttempt("com.example.game", nowMs = 10_000L)

        // Disabled in settings
        assertFalse(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = false, nowMs = 10_500L))

        // Past 3000ms window
        assertFalse(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = true, nowMs = 13_500L))

        // Launcher reset clears pending redirect guard
        guard.recordGameOrAdSkipAttempt("com.example.game", nowMs = 20_000L)
        guard.clearOnLauncherOrUserExit()
        assertFalse(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = true, nowMs = 20_500L))
    }
}
