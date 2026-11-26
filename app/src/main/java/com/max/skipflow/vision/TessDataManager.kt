package com.max.skipflow.vision

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ensures bundled `ara.traineddata` and `eng.traineddata` models in `assets/tessdata/`
 * are copied once to app internal storage so Tesseract4Android can read them offline.
 */
class TessDataManager(private val context: Context) {

    private val copyMutex = Mutex()
    private val rootDir: File = File(context.filesDir, "tesseract")
    private val tessDataDir: File = File(rootDir, "tessdata")

    fun getTesseractDataRootPath(): String = rootDir.absolutePath

    suspend fun ensureTessDataReady(): Boolean = withContext(Dispatchers.IO) {
        copyMutex.withLock {
            runCatching {
                if (!tessDataDir.exists()) {
                    tessDataDir.mkdirs()
                }
                for (fileName in REQUIRED_TRAINED_DATA_FILES) {
                    val destFile = File(tessDataDir, fileName)
                    if (!destFile.exists() || destFile.length() < MIN_VALID_SIZE_BYTES) {
                        copyAssetToFile("tessdata/$fileName", destFile)
                    }
                }
                REQUIRED_TRAINED_DATA_FILES.all {
                    val f = File(tessDataDir, it)
                    f.exists() && f.length() >= MIN_VALID_SIZE_BYTES
                }
            }.getOrDefault(false)
        }
    }

    private fun copyAssetToFile(assetPath: String, destFile: File) {
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        context.assets.open(assetPath).use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 16_384)
            }
        }
        if (destFile.exists()) {
            destFile.delete()
        }
        tempFile.renameTo(destFile)
    }

    companion object {
        private val REQUIRED_TRAINED_DATA_FILES = listOf(
            "ara.traineddata",
            "eng.traineddata"
        )
        private const val MIN_VALID_SIZE_BYTES = 100_000L
    }
}
