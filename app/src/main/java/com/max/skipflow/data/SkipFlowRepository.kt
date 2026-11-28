package com.max.skipflow.data

import android.content.Context
import android.content.SharedPreferences
import com.max.skipflow.engine.AdDetectionRules
import com.max.skipflow.engine.BoundsRect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import kotlin.math.abs

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
    private val todayProvider: () -> String = { LocalDate.now().toString() },
    private val clockProvider: () -> Long = { System.currentTimeMillis() }
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

    private val _snoozeUntilEpochMs = MutableStateFlow(loadInitialSnoozeUntil())
    val snoozeUntilEpochMs: StateFlow<Long> = _snoozeUntilEpochMs.asStateFlow()

    private val _skipLogs = MutableStateFlow(loadInitialSkipLogs())
    val skipLogs: StateFlow<List<SkipLogEntry>> = _skipLogs.asStateFlow()

    private val _lastRulesSyncEpochMs = MutableStateFlow(storage.getLong(KEY_LAST_RULES_SYNC_MS, 0L))
    val lastRulesSyncEpochMs: StateFlow<Long> = _lastRulesSyncEpochMs.asStateFlow()

    private val _lastOtaCheckEpochMs = MutableStateFlow(storage.getLong(KEY_LAST_OTA_CHECK_MS, 0L))
    val lastOtaCheckEpochMs: StateFlow<Long> = _lastOtaCheckEpochMs.asStateFlow()

    private var fakeXTraps: MutableList<FakeXTrapRecord> = loadInitialFakeXTraps().toMutableList()

    private fun loadInitialSettings(): SkipFlowSettings {
        return SkipFlowSettings(
            engineEnabled = storage.getBoolean(KEY_ENGINE_ENABLED, true),
            autoMuteYouTubeAds = storage.getBoolean(KEY_AUTO_MUTE_YT, true),
            vibrateOnSkip = storage.getBoolean(KEY_VIBRATE_ON_SKIP, true),
            toastOnSkip = storage.getBoolean(KEY_TOAST_ON_SKIP, true),
            pauseDuringCalls = storage.getBoolean(KEY_PAUSE_DURING_CALLS, true),
            bounceBackFromPlayStore = storage.getBoolean(KEY_BOUNCE_BACK_PLAY_STORE, true),
            liveDebugOverlayEnabled = storage.getBoolean(KEY_LIVE_DEBUG_OVERLAY, false),
            adCurtainEnabled = storage.getBoolean(KEY_AD_CURTAIN_ENABLED, false),
            autoSwipeSocialAdsEnabled = storage.getBoolean(KEY_AUTO_SWIPE_SOCIAL_ADS, true),
            annoyanceKillerEnabled = storage.getBoolean(KEY_ANNOYANCE_KILLER_ENABLED, false),
            sponsorBlockEnabled = storage.getBoolean(KEY_SPONSOR_BLOCK_ENABLED, true),
            shizukuTurboModeEnabled = storage.getBoolean(KEY_SHIZUKU_TURBO_MODE, false)
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

    private fun loadInitialSnoozeUntil(): Long {
        val stored = storage.getLong(KEY_SNOOZE_UNTIL_EPOCH_MS, 0L)
        return if (stored > clockProvider()) stored else {
            if (stored != 0L) storage.putLong(KEY_SNOOZE_UNTIL_EPOCH_MS, 0L)
            0L
        }
    }

    private fun loadInitialSkipLogs(): List<SkipLogEntry> {
        val raw = storage.getString(KEY_SKIP_LOGS_JSON, null) ?: return emptyList()
        if (raw.isBlank() || raw == "[]") return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .take(MAX_SKIP_LOGS)
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 6) return@mapNotNull null
                val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                val pkg = decodeField(parts[1]).trim()
                if (pkg.isEmpty()) return@mapNotNull null
                val appName = decodeField(parts[2]).ifBlank { pkg }
                val ts = parts[3].toLongOrNull() ?: 0L
                val method = runCatching { SkipDetectionMethod.valueOf(parts[4]) }
                    .getOrDefault(SkipDetectionMethod.ACCESSIBILITY)
                val detail = decodeField(parts[5])
                SkipLogEntry(
                    id = id,
                    packageName = pkg,
                    appName = appName,
                    timestampMs = ts,
                    method = method,
                    detail = detail
                )
            }
            .toList()
    }

    private fun loadInitialFakeXTraps(): List<FakeXTrapRecord> {
        val raw = storage.getString(KEY_FAKE_X_TRAPS_JSON, null) ?: return emptyList()
        if (raw.isBlank() || raw == "[]") return emptyList()
        val cutoff = clockProvider() - FAKE_X_TTL_MS
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .take(MAX_FAKE_X_TRAPS)
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 6) return@mapNotNull null
                val pkg = decodeField(parts[0]).trim()
                if (pkg.isEmpty()) return@mapNotNull null
                val recordedAt = parts[5].toLongOrNull() ?: return@mapNotNull null
                if (recordedAt < cutoff) return@mapNotNull null
                val nx = parts[2].toFloatOrNull() ?: return@mapNotNull null
                val ny = parts[3].toFloatOrNull() ?: return@mapNotNull null
                FakeXTrapRecord(
                    packageName = pkg,
                    activityName = decodeField(parts[1]),
                    normCenterX = nx,
                    normCenterY = ny,
                    viewId = decodeField(parts[4]).takeIf { it.isNotBlank() },
                    recordedAtWallMs = recordedAt
                )
            }
            .toList()
    }

    fun setEngineEnabled(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_ENGINE_ENABLED, enabled)
            _settings.value = _settings.value.copy(engineEnabled = enabled)
            if (!enabled) {
                storage.putLong(KEY_SNOOZE_UNTIL_EPOCH_MS, 0L)
                _snoozeUntilEpochMs.value = 0L
            }
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

    fun setBounceBackFromPlayStore(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_BOUNCE_BACK_PLAY_STORE, enabled)
            _settings.value = _settings.value.copy(bounceBackFromPlayStore = enabled)
        }
    }

    fun setLiveDebugOverlayEnabled(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_LIVE_DEBUG_OVERLAY, enabled)
            _settings.value = _settings.value.copy(liveDebugOverlayEnabled = enabled)
        }
    }

    fun setAdCurtainEnabled(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_AD_CURTAIN_ENABLED, enabled)
            _settings.value = _settings.value.copy(adCurtainEnabled = enabled)
        }
    }

    fun setAutoSwipeSocialAdsEnabled(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_AUTO_SWIPE_SOCIAL_ADS, enabled)
            _settings.value = _settings.value.copy(autoSwipeSocialAdsEnabled = enabled)
        }
    }

    fun setAnnoyanceKillerEnabled(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_ANNOYANCE_KILLER_ENABLED, enabled)
            _settings.value = _settings.value.copy(annoyanceKillerEnabled = enabled)
        }
    }

    fun setSponsorBlockEnabled(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_SPONSOR_BLOCK_ENABLED, enabled)
            _settings.value = _settings.value.copy(sponsorBlockEnabled = enabled)
        }
    }

    fun setShizukuTurboModeEnabled(enabled: Boolean) {
        synchronized(lock) {
            storage.putBoolean(KEY_SHIZUKU_TURBO_MODE, enabled)
            _settings.value = _settings.value.copy(shizukuTurboModeEnabled = enabled)
        }
    }

    fun recordRulesSynced(nowMs: Long = clockProvider()) {
        synchronized(lock) {
            storage.putLong(KEY_LAST_RULES_SYNC_MS, nowMs)
            _lastRulesSyncEpochMs.value = nowMs
        }
    }

    fun shouldRunDailyOtaCheck(nowMs: Long = clockProvider()): Boolean {
        val last = _lastOtaCheckEpochMs.value
        if (last <= 0L) return true
        return (nowMs - last) >= OTA_DAILY_CHECK_INTERVAL_MS
    }

    fun recordOtaCheckedNow(nowMs: Long = clockProvider()) {
        synchronized(lock) {
            storage.putLong(KEY_LAST_OTA_CHECK_MS, nowMs)
            _lastOtaCheckEpochMs.value = nowMs
        }
    }

    fun startSmartSnooze(durationMs: Long = DEFAULT_SNOOZE_DURATION_MS): Long {
        synchronized(lock) {
            val until = clockProvider() + durationMs.coerceAtLeast(1_000L)
            storage.putLong(KEY_SNOOZE_UNTIL_EPOCH_MS, until)
            _snoozeUntilEpochMs.value = until
            return until
        }
    }

    fun cancelSmartSnooze() {
        synchronized(lock) {
            storage.putLong(KEY_SNOOZE_UNTIL_EPOCH_MS, 0L)
            _snoozeUntilEpochMs.value = 0L
        }
    }

    fun getSnoozeRemainingMs(): Long {
        val until = _snoozeUntilEpochMs.value
        if (until <= 0L) return 0L
        val remaining = until - clockProvider()
        if (remaining <= 0L) {
            cancelSmartSnooze()
            return 0L
        }
        return remaining
    }

    fun isSnoozed(): Boolean = getSnoozeRemainingMs() > 0L

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
        if (AdDetectionRules.isStrictlyExcludedPackage(packageName) ||
            AdDetectionRules.isInputMethodPackage(packageName)
        ) {
            return false
        }
        return packageName !in _disabledPackages.value
    }

    fun recordSkipLog(
        packageName: String,
        appName: String,
        method: SkipDetectionMethod,
        detail: String
    ) {
        synchronized(lock) {
            val now = clockProvider()
            val entry = SkipLogEntry(
                id = now * 31L + (_skipLogs.value.size + 1),
                packageName = packageName,
                appName = appName.ifBlank { packageName },
                timestampMs = now,
                method = method,
                detail = detail
            )
            val updated = (listOf(entry) + _skipLogs.value).take(MAX_SKIP_LOGS)
            _skipLogs.value = updated
            persistSkipLogsLocked(updated)
        }
    }

    fun clearSkipLogs() {
        synchronized(lock) {
            _skipLogs.value = emptyList()
            storage.putString(KEY_SKIP_LOGS_JSON, "")
        }
    }

    private fun persistSkipLogsLocked(logs: List<SkipLogEntry>) {
        val encoded = logs.take(MAX_SKIP_LOGS).joinToString("\n") { entry ->
            listOf(
                entry.id.toString(),
                encodeField(entry.packageName),
                encodeField(entry.appName),
                entry.timestampMs.toString(),
                entry.method.name,
                encodeField(entry.detail)
            ).joinToString("|")
        }
        storage.putString(KEY_SKIP_LOGS_JSON, encoded)
    }

    fun recordFakeXTrap(
        packageName: String,
        activityName: String?,
        normCenterX: Float,
        normCenterY: Float,
        viewId: String?
    ) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            val now = clockProvider()
            pruneExpiredTrapsLocked(now)
            val normalizedAct = activityName?.trim().orEmpty()
            val normalizedVid = viewId?.trim()?.takeIf { it.isNotBlank() }

            fakeXTraps.removeAll { existing ->
                existing.packageName == packageName &&
                    existing.activityName == normalizedAct &&
                    ((normalizedVid != null && existing.viewId == normalizedVid) ||
                        (abs(existing.normCenterX - normCenterX) <= FAKE_X_NORM_TOLERANCE &&
                            abs(existing.normCenterY - normCenterY) <= FAKE_X_NORM_TOLERANCE))
            }

            fakeXTraps.add(
                0,
                FakeXTrapRecord(
                    packageName = packageName,
                    activityName = normalizedAct,
                    normCenterX = normCenterX.coerceIn(0f, 1f),
                    normCenterY = normCenterY.coerceIn(0f, 1f),
                    viewId = normalizedVid,
                    recordedAtWallMs = now
                )
            )
            if (fakeXTraps.size > MAX_FAKE_X_TRAPS) {
                fakeXTraps = fakeXTraps.take(MAX_FAKE_X_TRAPS).toMutableList()
            }
            persistFakeXTrapsLocked()
        }
    }

    fun isKnownFakeXTrap(
        packageName: String,
        activityName: String?,
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int,
        viewId: String?
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val normX = bounds.centerX.toFloat() / screenWidth.toFloat()
        val normY = bounds.centerY.toFloat() / screenHeight.toFloat()
        val normalizedAct = activityName?.trim().orEmpty()
        val normalizedVid = viewId?.trim()?.takeIf { it.isNotBlank() }

        synchronized(lock) {
            val now = clockProvider()
            if (pruneExpiredTrapsLocked(now)) {
                persistFakeXTrapsLocked()
            }
            return fakeXTraps.any { trap ->
                trap.packageName == packageName &&
                    (trap.activityName.isEmpty() || normalizedAct.isEmpty() || trap.activityName == normalizedAct) &&
                    ((normalizedVid != null && trap.viewId == normalizedVid) ||
                        (abs(trap.normCenterX - normX) <= FAKE_X_NORM_TOLERANCE &&
                            abs(trap.normCenterY - normY) <= FAKE_X_NORM_TOLERANCE))
            }
        }
    }

    fun getFakeXTrapExclusionRects(
        packageName: String,
        activityName: String?,
        screenWidth: Int,
        screenHeight: Int
    ): List<BoundsRect> {
        if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
        val normalizedAct = activityName?.trim().orEmpty()
        val halfBoxW = (screenWidth * FAKE_X_NORM_TOLERANCE).toInt().coerceAtLeast(36)
        val halfBoxH = (screenHeight * FAKE_X_NORM_TOLERANCE).toInt().coerceAtLeast(36)

        synchronized(lock) {
            val now = clockProvider()
            if (pruneExpiredTrapsLocked(now)) {
                persistFakeXTrapsLocked()
            }
            return fakeXTraps.filter { trap ->
                trap.packageName == packageName &&
                    (trap.activityName.isEmpty() || normalizedAct.isEmpty() || trap.activityName == normalizedAct)
            }.map { trap ->
                val cx = (trap.normCenterX * screenWidth).toInt()
                val cy = (trap.normCenterY * screenHeight).toInt()
                BoundsRect(
                    left = (cx - halfBoxW).coerceAtLeast(0),
                    top = (cy - halfBoxH).coerceAtLeast(0),
                    right = (cx + halfBoxW).coerceAtMost(screenWidth),
                    bottom = (cy + halfBoxH).coerceAtMost(screenHeight)
                )
            }
        }
    }

    private fun pruneExpiredTrapsLocked(nowMs: Long): Boolean {
        val cutoff = nowMs - FAKE_X_TTL_MS
        val before = fakeXTraps.size
        fakeXTraps.removeAll { it.recordedAtWallMs < cutoff }
        return fakeXTraps.size != before
    }

    private fun persistFakeXTrapsLocked() {
        val encoded = fakeXTraps.take(MAX_FAKE_X_TRAPS).joinToString("\n") { trap ->
            listOf(
                encodeField(trap.packageName),
                encodeField(trap.activityName),
                trap.normCenterX.toString(),
                trap.normCenterY.toString(),
                encodeField(trap.viewId.orEmpty()),
                trap.recordedAtWallMs.toString()
            ).joinToString("|")
        }
        storage.putString(KEY_FAKE_X_TRAPS_JSON, encoded)
    }

    private fun encodeField(raw: String): String {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8.name())
    }

    private fun decodeField(encoded: String): String {
        return runCatching {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        }.getOrDefault(encoded)
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
        private const val KEY_BOUNCE_BACK_PLAY_STORE = "bounce_back_play_store"
        private const val KEY_LIVE_DEBUG_OVERLAY = "live_debug_overlay_enabled"
        private const val KEY_AD_CURTAIN_ENABLED = "ad_curtain_enabled"
        private const val KEY_AUTO_SWIPE_SOCIAL_ADS = "auto_swipe_social_ads_enabled"
        private const val KEY_ANNOYANCE_KILLER_ENABLED = "annoyance_killer_enabled"
        private const val KEY_SPONSOR_BLOCK_ENABLED = "sponsor_block_enabled"
        private const val KEY_SHIZUKU_TURBO_MODE = "shizuku_turbo_mode_enabled"
        private const val KEY_LAST_RULES_SYNC_MS = "last_rules_sync_epoch_ms"
        private const val KEY_LAST_OTA_CHECK_MS = "last_ota_check_epoch_ms"
        private const val KEY_SNOOZE_UNTIL_EPOCH_MS = "snooze_until_epoch_ms"
        private const val KEY_SKIP_LOGS_JSON = "skip_logs_json"
        private const val KEY_FAKE_X_TRAPS_JSON = "fake_x_traps_json"
        private const val KEY_BLACKLIST_INITIALIZED = "blacklist_mode_initialized"
        private const val KEY_DISABLED_PACKAGES = "disabled_packages"
        private const val KEY_STATS_DATE = "stats_date"
        private const val KEY_SKIPPED_TODAY = "skipped_today"
        private const val KEY_SAVED_SECONDS_TODAY = "saved_seconds_today"

        const val DEFAULT_SNOOZE_DURATION_MS = 10 * 60 * 1000L
        const val OTA_DAILY_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val MAX_SKIP_LOGS = 50
        const val MAX_FAKE_X_TRAPS = 100
        const val FAKE_X_TTL_MS = 24 * 60 * 60 * 1000L
        const val FAKE_X_NORM_TOLERANCE = 0.06f
    }
}
