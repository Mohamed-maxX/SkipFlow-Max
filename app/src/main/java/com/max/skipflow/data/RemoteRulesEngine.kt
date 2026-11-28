package com.max.skipflow.data

import android.content.Context
import com.max.skipflow.engine.AdDetectionRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class RemoteRulesConfig(
    val version: Int = 1,
    val adActivities: List<String> = emptyList(),
    val skipTexts: List<String> = emptyList(),
    val excludedPackages: List<String> = emptyList()
)

data class RemoteRuleCounts(
    val rulesVersion: Int = 1,
    val adActivitiesCount: Int = 0,
    val skipTextsCount: Int = 0,
    val excludedPackagesCount: Int = 0
)

sealed interface RemoteRulesSyncResult {
    data class Success(val config: RemoteRulesConfig, val syncedAtMs: Long) : RemoteRulesSyncResult
    data class Error(val message: String) : RemoteRulesSyncResult
}

/**
 * Pure Kotlin zero-dependency JSON parser so rules and API JSON parse identically
 * in Android Runtime AND local JVM unit tests without `org.json` stub issues.
 */
object MiniJsonParser {

    fun parse(json: String): Any? {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return null
        val cursor = Cursor(trimmed)
        val result = parseValue(cursor)
        cursor.skipWhitespace()
        return result
    }

    private class Cursor(val src: String) {
        var pos: Int = 0

        fun hasNext(): Boolean = pos < src.length
        fun peek(): Char = src[pos]
        fun next(): Char = src[pos++]

        fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }
    }

    private fun parseValue(c: Cursor): Any? {
        c.skipWhitespace()
        if (!c.hasNext()) return null
        return when (c.peek()) {
            '{' -> parseObject(c)
            '[' -> parseArray(c)
            '"' -> parseString(c)
            't', 'f' -> parseBoolean(c)
            'n' -> parseNull(c)
            '-', in '0'..'9' -> parseNumber(c)
            else -> throw IllegalArgumentException("Unexpected JSON char '${c.peek()}' at ${c.pos}")
        }
    }

    private fun parseObject(c: Cursor): Map<String, Any?> {
        c.next() // '{'
        val map = linkedMapOf<String, Any?>()
        c.skipWhitespace()
        if (c.hasNext() && c.peek() == '}') {
            c.next()
            return map
        }
        while (c.hasNext()) {
            c.skipWhitespace()
            if (c.peek() == '}') {
                c.next()
                break
            }
            val key = parseString(c)
            c.skipWhitespace()
            if (!c.hasNext() || c.next() != ':') {
                throw IllegalArgumentException("Expected ':' after key '$key'")
            }
            val value = parseValue(c)
            map[key] = value
            c.skipWhitespace()
            if (c.hasNext() && c.peek() == ',') {
                c.next()
            } else if (c.hasNext() && c.peek() == '}') {
                c.next()
                break
            } else {
                break
            }
        }
        return map
    }

    private fun parseArray(c: Cursor): List<Any?> {
        c.next() // '['
        val list = mutableListOf<Any?>()
        c.skipWhitespace()
        if (c.hasNext() && c.peek() == ']') {
            c.next()
            return list
        }
        while (c.hasNext()) {
            c.skipWhitespace()
            if (c.peek() == ']') {
                c.next()
                break
            }
            list.add(parseValue(c))
            c.skipWhitespace()
            if (c.hasNext() && c.peek() == ',') {
                c.next()
            } else if (c.hasNext() && c.peek() == ']') {
                c.next()
                break
            } else {
                break
            }
        }
        return list
    }

    private fun parseString(c: Cursor): String {
        if (!c.hasNext() || c.next() != '"') {
            throw IllegalArgumentException("Expected opening quote at ${c.pos}")
        }
        val sb = StringBuilder()
        while (c.hasNext()) {
            val ch = c.next()
            if (ch == '"') return sb.toString()
            if (ch == '\\' && c.hasNext()) {
                when (val esc = c.next()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (c.pos + 4 <= c.src.length) {
                            val hex = c.src.substring(c.pos, c.pos + 4)
                            c.pos += 4
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        }
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseBoolean(c: Cursor): Boolean {
        return if (c.src.startsWith("true", c.pos)) {
            c.pos += 4
            true
        } else if (c.src.startsWith("false", c.pos)) {
            c.pos += 5
            false
        } else {
            throw IllegalArgumentException("Invalid boolean at ${c.pos}")
        }
    }

    private fun parseNull(c: Cursor): Any? {
        if (c.src.startsWith("null", c.pos)) {
            c.pos += 4
            return null
        }
        throw IllegalArgumentException("Invalid null at ${c.pos}")
    }

    private fun parseNumber(c: Cursor): Number {
        val start = c.pos
        while (c.hasNext() && c.peek() in "0123456789+-.eE") {
            c.pos++
        }
        val token = c.src.substring(start, c.pos)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: 0.0
    }
}

object RemoteRulesParser {

    fun parse(jsonText: String): RemoteRulesConfig? {
        val root = runCatching { MiniJsonParser.parse(jsonText) as? Map<*, *> }.getOrNull() ?: return null
        val version = (root["version"] as? Number)?.toInt() ?: 1
        val adActivities = extractStringList(root["adActivities"])
        val skipTexts = extractStringList(root["skipTexts"])
        val excludedPackages = extractStringList(root["excludedPackages"])
        if (adActivities.isEmpty() && skipTexts.isEmpty() && excludedPackages.isEmpty()) {
            return null
        }
        return RemoteRulesConfig(
            version = version,
            adActivities = adActivities,
            skipTexts = skipTexts,
            excludedPackages = excludedPackages
        )
    }

    private fun extractStringList(raw: Any?): List<String> {
        val items = raw as? List<*> ?: return emptyList()
        return items.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .distinct()
    }
}

/**
 * Loads bundled/cached `rules.json`, fetches live updates from GitHub, caches them locally,
 * and hot-reloads them straight into [AdDetectionRules].
 */
class RemoteRulesManager(
    context: Context,
    private val repository: SkipFlowRepository
) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, CACHE_FILE_NAME)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _ruleCounts = MutableStateFlow(RemoteRuleCounts())
    val ruleCounts: StateFlow<RemoteRuleCounts> = _ruleCounts.asStateFlow()

    fun loadAndApplyCachedOrBundledRules() {
        val cachedConfig = if (cacheFile.exists()) {
            runCatching { RemoteRulesParser.parse(cacheFile.readText()) }.getOrNull()
        } else {
            null
        }
        val bundledConfig = runCatching {
            appContext.assets.open(BUNDLED_ASSET_NAME).bufferedReader().use {
                RemoteRulesParser.parse(it.readText())
            }
        }.getOrNull()

        val chosen = when {
            cachedConfig != null && bundledConfig != null -> {
                if (cachedConfig.version >= bundledConfig.version) cachedConfig else bundledConfig
            }
            cachedConfig != null -> cachedConfig
            else -> bundledConfig
        }

        if (chosen != null) {
            applyConfig(chosen)
        }
    }

    suspend fun syncFromRemoteNow(): RemoteRulesSyncResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(REMOTE_RULES_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "SkipFlow-Max-RulesSync")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RemoteRulesSyncResult.Error(
                        "تعذر تحميل القواعد (رمز الحالة: ${response.code})"
                    )
                }
                val body = response.body?.string().orEmpty()
                val parsed = RemoteRulesParser.parse(body)
                    ?: return@withContext RemoteRulesSyncResult.Error("تنسيق ملف rules.json غير صالح.")

                runCatching { cacheFile.writeText(body) }
                applyConfig(parsed)
                val now = System.currentTimeMillis()
                repository.recordRulesSynced(now)
                RemoteRulesSyncResult.Success(parsed, now)
            }
        } catch (_: Exception) {
            RemoteRulesSyncResult.Error("تعذر الاتصال بالإنترنت لتحديث القواعد.")
        }
    }

    private fun applyConfig(config: RemoteRulesConfig) {
        AdDetectionRules.applyRemoteRules(config)
        _ruleCounts.value = RemoteRuleCounts(
            rulesVersion = config.version,
            adActivitiesCount = config.adActivities.size,
            skipTextsCount = config.skipTexts.size,
            excludedPackagesCount = config.excludedPackages.size
        )
    }

    companion object {
        const val REMOTE_RULES_URL =
            "https://raw.githubusercontent.com/Mohamed-maxX/SkipFlow-Max/main/rules.json"
        private const val CACHE_FILE_NAME = "rules_cache.json"
        private const val BUNDLED_ASSET_NAME = "rules.json"
    }
}
