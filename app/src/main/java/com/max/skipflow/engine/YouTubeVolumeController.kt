package com.max.skipflow.engine

import android.media.AudioManager

interface MediaVolumeBackend {
    fun getMusicVolume(): Int
    fun setMusicVolume(volume: Int)
}

class AndroidMediaVolumeBackend(
    private val audioManager: AudioManager
) : MediaVolumeBackend {
    override fun getMusicVolume(): Int {
        return runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0)
    }

    override fun setMusicVolume(volume: Int) {
        runCatching {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val clamped = volume.coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        }
    }
}

/**
 * Automatically mutes STREAM_MUSIC when a YouTube ad starts and restores
 * the user's exact previous volume immediately upon skip or after ad disappearance.
 */
class YouTubeVolumeController(
    private val backend: MediaVolumeBackend,
    private val restoreGraceMs: Long = DEFAULT_RESTORE_GRACE_MS
) {
    private var savedVolume: Int? = null
    private var isMutedByEngine: Boolean = false
    private var adAbsentSinceMs: Long? = null

    fun isMuted(): Boolean = isMutedByEngine

    fun onAdVisible(autoMuteEnabled: Boolean) {
        if (!autoMuteEnabled) {
            forceRestore()
            return
        }
        adAbsentSinceMs = null
        if (!isMutedByEngine) {
            val currentVolume = backend.getMusicVolume()
            if (currentVolume > 0) {
                savedVolume = currentVolume
                isMutedByEngine = true
                backend.setMusicVolume(0)
            }
        }
    }

    fun onAdNotVisible(nowMs: Long): Boolean {
        if (!isMutedByEngine) return false
        val absentStart = adAbsentSinceMs
        if (absentStart == null) {
            adAbsentSinceMs = nowMs
            return false
        }
        if (nowMs - absentStart >= restoreGraceMs) {
            forceRestore()
            return true
        }
        return false
    }

    fun forceRestore() {
        if (isMutedByEngine) {
            val volumeToRestore = savedVolume
            isMutedByEngine = false
            savedVolume = null
            adAbsentSinceMs = null
            if (volumeToRestore != null) {
                backend.setMusicVolume(volumeToRestore)
            }
        } else {
            savedVolume = null
            adAbsentSinceMs = null
        }
    }

    companion object {
        const val DEFAULT_RESTORE_GRACE_MS = 950L
    }
}

object CallStateMonitor {
    fun isCallActive(audioManager: AudioManager?): Boolean {
        if (audioManager == null) return false
        return when (runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> true
            else -> false
        }
    }
}
