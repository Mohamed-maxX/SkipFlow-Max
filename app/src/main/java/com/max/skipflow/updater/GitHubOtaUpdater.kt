package com.max.skipflow.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.max.skipflow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed interface OtaUpdateState {
    data object Idle : OtaUpdateState
    data object Checking : OtaUpdateState
    data class UpToDate(val currentVersion: String) : OtaUpdateState
    data class NoReleasesYet(val currentVersion: String) : OtaUpdateState
    data class UpdateAvailable(
        val release: GitHubReleaseInfo,
        val currentVersion: String
    ) : OtaUpdateState

    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : OtaUpdateState

    data class ReadyToInstall(
        val apkFile: File,
        val release: GitHubReleaseInfo
    ) : OtaUpdateState

    data class Error(val message: String) : OtaUpdateState
}

class GitHubOtaUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _state = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle)
    val state: StateFlow<OtaUpdateState> = _state.asStateFlow()

    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        _state.value = OtaUpdateState.Checking
        val currentVersion = BuildConfig.VERSION_NAME

        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SkipFlow-Max/$currentVersion")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    _state.value = OtaUpdateState.NoReleasesYet(currentVersion)
                    return@withContext
                }
                if (!response.isSuccessful) {
                    _state.value = OtaUpdateState.Error(
                        "تعذر الاتصال بـ GitHub (رمز الحالة: ${response.code})"
                    )
                    return@withContext
                }

                val body = response.body?.string().orEmpty()
                val release = GitHubReleaseParser.parseLatestRelease(body)
                if (release == null) {
                    _state.value = OtaUpdateState.NoReleasesYet(currentVersion)
                    return@withContext
                }

                if (VersionComparator.isNewerVersion(release.tagName, currentVersion)) {
                    _state.value = OtaUpdateState.UpdateAvailable(
                        release = release,
                        currentVersion = currentVersion
                    )
                } else {
                    _state.value = OtaUpdateState.UpToDate(currentVersion)
                }
            }
        } catch (e: Exception) {
            _state.value = OtaUpdateState.Error(
                "فشل التحقق من التحديثات. تأكد من الاتصال بالإنترنت."
            )
        }
    }

    suspend fun downloadAndPrepareInstall(release: GitHubReleaseInfo) = withContext(Dispatchers.IO) {
        val downloadUrl = release.apkDownloadUrl
        if (downloadUrl.isNullOrBlank()) {
            _state.value = OtaUpdateState.Error("هذا الإصدار لا يحتوي على ملف APK للتثبيت المباشر.")
            return@withContext
        }

        _state.value = OtaUpdateState.Downloading(0, 0L, release.apkSizeBytes)

        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { oldFile ->
                if (oldFile.name.endsWith(".apk")) {
                    oldFile.delete()
                }
            }

            val safeName = (release.apkName ?: "SkipFlowMax-${release.tagName}.apk")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val targetFile = File(updatesDir, safeName)

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "SkipFlow-Max/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = OtaUpdateState.Error(
                        "فشل تنزيل التحديث (رمز الحالة: ${response.code})"
                    )
                    return@withContext
                }

                val body = response.body
                if (body == null) {
                    _state.value = OtaUpdateState.Error("ملف التحديث فارغ أو غير صالح.")
                    return@withContext
                }

                val totalBytes = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                var downloaded = 0L
                val buffer = ByteArray(8192)
                var lastReportedPercent = -1

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                _state.value = OtaUpdateState.Downloading(
                                    progressPercent = percent,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes
                                )
                            }
                        }
                    }
                }

                if (!targetFile.exists() || targetFile.length() == 0L) {
                    _state.value = OtaUpdateState.Error("تعذر حفظ ملف APK على الجهاز.")
                    return@withContext
                }

                _state.value = OtaUpdateState.ReadyToInstall(targetFile, release)
            }
        } catch (e: Exception) {
            _state.value = OtaUpdateState.Error("حدث خطأ أثناء تنزيل ملف التحديث.")
        }
    }

    fun launchApkInstaller(launcherContext: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false

        if (!context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launcherContext.startActivity(permissionIntent)
            return false
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launcherContext.startActivity(installIntent)
        return true
    }

    fun openReleasePage(launcherContext: Context, url: String) {
        val targetUrl = url.ifBlank { RELEASES_PAGE_URL }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launcherContext.startActivity(intent)
    }

    companion object {
        const val GITHUB_REPO = "Mohamed-maxX/SkipFlow-Max"
        private const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        private const val RELEASES_PAGE_URL =
            "https://github.com/$GITHUB_REPO/releases"
    }
}
