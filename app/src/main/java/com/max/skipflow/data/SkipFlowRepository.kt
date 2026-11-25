package com.max.skipflow.data

import android.content.Context
import android.content.SharedPreferences
import com.max.skipflow.engine.AdDetectionRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

interface KeyValueStorage {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, defaultValue: Long): Long
    fun putLong(key: String, value: Long)
    fun getString(key: String, defaultValue: String?): String?
    fun putString(key: String, value: String)
    fun getStringSet(key: String, defaultValue: Set<String>): Set<String>
    fun putStringSet(key: String, value: Set<String>)
}

class SharedPreferencesStorage(
    private val prefs: SharedPreferences
) : KeyValueStorage {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        prefs.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        prefs.getLong(key, defaultValue)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getString(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        prefs.getStringSet(key, defaultValue)?.toSet() ?: defaultValue

    override fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value.toSet()).apply()
    }
}

class SkipFlowRepository(
    private val storage: KeyValueStorage,
    private val todayProvider: () -> String = { LocalDate.now().toString() }
) {
    constructor(context: Context) : this(
        storage = SharedPreferencesStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    private val lock = Any()

    private val _settings = MutableStateFlow(loadInitialSettings())
    val settings: StateFlow<SkipFlowSettings> = _settings.asStateFlow()

    // Blacklist mode: all non-system external apps & games are enabled by default unless listed here.
    private val _disabledPackages = MutableStateFlow(loadInitialDisabledPackages())
    val disabledPackages: StateFlow<Set<String>> = _disabledPackages.asStateFlow()

    private val _stats = MutableStateFlow(loadStatsForToday())
    val stats: StateFlow<DailyStats> = _stats.asStateFlow()

    private fun loadInitialSettings(): SkipFlowSettings {
        return SkipFlowSettings(
            engineEnabled = storage.getBoolean(KEY_ENGINE_ENABLED, true),
            autoMuteYouTubeAds = storage.getBoolean(KEY_AUTO_MUTE_YT, true),
            vibrateOnSkip = storage.getBoolean(KEY_VIBRATE_ON_SKIP, true),
            toastOnSkip = storage.getBoolean(KEY_TOAST_ON_SKIP, true),
            pauseDuringCalls = storage.getBoolean(KEY_PAUSE_DURING_CALLS, true)
        )
    }

    private fun loadInitialDisabledPackages(): Set<String> {
        val initialized = storage.getBoolean(KEY_BLACKLIST_INITIALIZED, false)
        if (!initialized) {
            val defaults = emptySet<String>()
            storage.putStringSet(KEY_DISABLED_PACKAGES, defaults)
            storage.putBoolean(KEY_BLACKLIST_INITIALIZED, true)
            return defaults
        }
        return storage.getStringSet(KEY_DISABLED_PACKAGES, emptySet())
    }

    private fun loadStatsForToday(): DailyStats {
        val today = todayProvider()
        val storedDate = storage.getString(KEY_STATS_DATE, "")
        if (storedDate != today) {
            storage.putString(KEY_STATS_DATE, today)
            storage.putInt(KEY_SKIPPED_TODAY, 0)
            storage.putLong(KEY_SAVED_SECONDS_TODAY, 0L)
            return DailyStats(dateKey = today, skippedToday = 0, savedSecondsToday = 0L)
        }
        return DailyStats(
            dateKey = today,
            skippedToday = storage.getInt(KEY_SKIPPED_TODAY, 0),
            savedSecondsToday = storage.getLong(KEY_SAVED_SECONDS_TODAY, 0L)
        )
    }

    fun setEngineEnabled(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_ENGINE_ENABLED, enabled)
            _settings.value = _settings.value.copy(engineEnabled = enabled)
        }
    }

    fun setAutoMuteYouTubeAds(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_AUTO_MUTE_YT, enabled)
            _settings.value = _settings.value.copy(autoMuteYouTubeAds = enabled)
        }
    }

    fun setVibrateOnSkip(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_VIBRATE_ON_SKIP, enabled)
            _settings.value = _settings.value.copy(vibrateOnSkip = enabled)
        }
    }

    fun setToastOnSkip(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_TOAST_ON_SKIP, enabled)
            _settings.value = _settings.value.copy(toastOnSkip = enabled)
        }
    }

    fun setPauseDuringCalls(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_PAUSE_DURING_CALLS, enabled)
            _settings.value = _settings.value.copy(pauseDuringCalls = enabled)
        }
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        synchronized(lock) {
            val updated = _disabledPackages.value.toMutableSet()
            if (enabled) {
                updated.remove(packageName)
            } else {
                updated.add(packageName)
            }
            storage.putStringSet(KEY_DISABLED_PACKAGES, updated)
            _disabledPackages.value = updated.toSet()
        }
    }

    fun isPackageEnabled(packageName: String): Boolean {
        if (AdDetectionRules.isSystemPackage(packageName) || AdDetectionRules.isInputMethodPackage(packageName)) {
            return false
        }
        return packageName !in _disabledPackages.value
    }

    fun refreshDateRolloverIfNeeded() {
        synchronized(lock) {
            val today = todayProvider()
            if (_stats.value.dateKey != today) {
                storage.putString(KEY_STATS_DATE, today)
                storage.putInt(KEY_SKIPPED_TODAY, 0)
                storage.putLong(KEY_SAVED_SECONDS_TODAY, 0L)
                _stats.value = DailyStats(dateKey = today, skippedToday = 0, savedSecondsToday = 0L)
            }
        }
    }

    fun recordAdSkipped(savedSeconds: Long) {
        synchronized(lock) {
            val today = todayProvider()
            val current = if (_stats.value.dateKey == today) {
                _stats.value
            } else {
                DailyStats(dateKey = today, skippedToday = 0, savedSecondsToday = 0L)
            }
            val updated = DailyStats(
                dateKey = today,
                skippedToday = current.skippedToday + 1,
                savedSecondsToday = current.savedSecondsToday + savedSeconds.coerceAtLeast(0L)
            )
            storage.putString(KEY_STATS_DATE, today)
            storage.putInt(KEY_SKIPPED_TODAY, updated.skippedToday)
            storage.putLong(KEY_SAVED_SECONDS_TODAY, updated.savedSecondsToday)
            _stats.value = updated
        }
    }

    companion object {
        private const val PREFS_NAME = "skipflow_prefs"
        private const val KEY_ENGINE_ENABLED = "engine_enabled"
        private const val KEY_AUTO_MUTE_YT = "auto_mute_yt_ads"
        private const val KEY_VIBRATE_ON_SKIP = "vibrate_on_skip"
        private const val KEY_TOAST_ON_SKIP = "toast_on_skip"
        private const val KEY_PAUSE_DURING_CALLS = "pause_during_calls"
        private const val KEY_BLACKLIST_INITIALIZED = "blacklist_mode_initialized"
        private const val KEY_DISABLED_PACKAGES = "disabled_packages"
        private const val KEY_STATS_DATE = "stats_date"
        private const val KEY_SKIPPED_TODAY = "skipped_today"
        private const val KEY_SAVED_SECONDS_TODAY = "saved_seconds_today"
    }
}
