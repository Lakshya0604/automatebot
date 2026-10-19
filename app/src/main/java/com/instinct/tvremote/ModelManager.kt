package com.instinct.tvremote

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the offline Vosk speech model on first run.
 * Default: small English model (~40 MB). A Hindi or Indian-English model can
 * be swapped in later by changing MODEL_URL and the target directory.
 */
object ModelManager {

    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun modelDir(context: Context): File = File(context.filesDir, "model-en")

    fun isReady(context: Context): Boolean = File(modelDir(context), "am").exists()

    @Throws(IOException::class)
    fun ensureModel(context: Context, log: (String) -> Unit): File {
        val dir = modelDir(context)
        if (isReady(context)) return dir

        val zip = File(context.cacheDir, "vosk-model.zip")
        log("Downloading speech model (~40 MB, one time)...")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.inputStream.use { input ->
            FileOutputStream(zip).use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var nextReport = 10L * 1024 * 1024
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total >= nextReport) {
                        log("Downloaded ${total / 1024 / 1024} MB...")
                        nextReport += 10L * 1024 * 1024
                    }
                }
            }
        }
        conn.disconnect()

        log("Unpacking speech model...")
        val tmp = File(context.filesDir, "model-tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val f = File(tmp, entry.name)
                if (entry.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zip.delete()

        val inner = tmp.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw IOException("Unexpected model zip layout")
        dir.deleteRecursively()
        if (!inner.renameTo(dir)) throw IOException("Could not move model into place")
        tmp.deleteRecursively()
        log("Speech model ready")
        return dir
    }
}
