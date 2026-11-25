package com.max.skipflow.updater

import org.json.JSONObject

data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val apkName: String?,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long
)

object VersionComparator {

    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val remoteParts = parseSemVer(remoteTag)
        val currentParts = parseSemVer(currentVersion)
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    fun parseSemVer(raw: String): List<Int> {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (cleaned.isBlank()) return listOf(0)
        return cleaned.split('.').map { segment ->
            segment.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}

object GitHubReleaseParser {

    fun parseLatestRelease(jsonBody: String): GitHubReleaseInfo? {
        if (jsonBody.isBlank()) return null
        val root = JSONObject(jsonBody)
        val tagName = root.optString("tag_name", "").trim()
        if (tagName.isEmpty()) return null

        val title = root.optString("name", tagName).ifBlank { tagName }
        val notes = root.optString("body", "").trim()
        val htmlUrl = root.optString("html_url", "")

        var apkName: String? = null
        var apkDownloadUrl: String? = null
        var apkSize = 0L

        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                    apkName = name
                    apkDownloadUrl = downloadUrl
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }

        return GitHubReleaseInfo(
            tagName = tagName,
            title = title,
            notes = notes,
            htmlUrl = htmlUrl,
            apkName = apkName,
            apkDownloadUrl = apkDownloadUrl,
            apkSizeBytes = apkSize
        )
    }
}
