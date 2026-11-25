package com.max.skipflow.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.max.skipflow.engine.AdDetectionRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AppCatalogRepository(private val context: Context) {

    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    @SuppressLint("QueryPackagesPermission")
    suspend fun loadInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPkg = context.packageName

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }

        val appsByPkg = LinkedHashMap<String, InstalledAppInfo>()

        for (resolveInfo in resolved) {
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: continue
            val pkg = appInfo.packageName ?: continue
            if (pkg == selfPkg || AdDetectionRules.isSystemPackage(pkg)) continue
            if (appsByPkg.containsKey(pkg)) continue

            val label = resolveInfo.loadLabel(pm)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: pm.getApplicationLabel(appInfo).toString()

            appsByPkg[pkg] = InstalledAppInfo(
                packageName = pkg,
                appName = label,
                isGame = isGameApplication(appInfo),
                isYouTube = pkg == AdDetectionRules.YOUTUBE_PACKAGE
            )
        }

        // Ensure YouTube appears if installed even if OEM launcher hides it
        if (!appsByPkg.containsKey(AdDetectionRules.YOUTUBE_PACKAGE)) {
            runCatching {
                val ytInfo = pm.getApplicationInfo(AdDetectionRules.YOUTUBE_PACKAGE, 0)
                appsByPkg[AdDetectionRules.YOUTUBE_PACKAGE] = InstalledAppInfo(
                    packageName = AdDetectionRules.YOUTUBE_PACKAGE,
                    appName = pm.getApplicationLabel(ytInfo).toString(),
                    isGame = false,
                    isYouTube = true
                )
            }
        }

        appsByPkg.values.sortedWith(
            compareByDescending<InstalledAppInfo> { it.isYouTube }
                .thenBy { it.appName.lowercase() }
        )
    }

    suspend fun loadAppIcon(packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        iconCache[packageName]?.let { return@withContext it }
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable, sizePx = 96).asImageBitmap()
            iconCache[packageName] = bitmap
            bitmap
        }.getOrNull()
    }

    private fun isGameApplication(appInfo: ApplicationInfo): Boolean {
        @Suppress("DEPRECATION")
        val legacyGameFlag = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        val categoryGame = appInfo.category == ApplicationInfo.CATEGORY_GAME
        return legacyGameFlag || categoryGame
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
