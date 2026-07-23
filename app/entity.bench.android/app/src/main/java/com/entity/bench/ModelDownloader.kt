package com.entity.bench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

// Model download: resumable, cancellable, no third-party HTTP client.
//
// A multi-GB download over a phone connection gets interrupted, so bytes land in a
// ".part" file and a retry continues with a Range request instead of starting over.
// The file only takes its real name once its length matches the catalog's expected
// size, so a truncated download can never be mistaken for a loadable model.
object ModelDownloader {

    /** Bytes done and total, both in bytes; total is the catalog size. */
    data class Progress(val done: Long, val total: Long) {
        val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    class DownloadException(message: String) : Exception(message)

    fun partFileFor(dir: File, e: ModelCatalog.Entry) = File(dir, e.fileName + ".part")

    /**
     * Downloads [e] into [dir], resuming any existing ".part". Returns the finished file.
     * Cancelling the calling coroutine stops the transfer and leaves the ".part" in place
     * for a later resume.
     */
    suspend fun download(
        e: ModelCatalog.Entry,
        dir: File,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, e.fileName)
        if (target.exists() && target.length() == e.sizeBytes) return@withContext target

        val part = partFileFor(dir, e)
        var have = if (part.exists()) part.length() else 0L
        if (have > e.sizeBytes) {           // a stale/corrupt part cannot be resumed
            part.delete()
            have = 0L
        }

        val conn = (URL(e.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }

        try {
            conn.connect()
            val code = conn.responseCode
            // 206 continues the part; 200 means the host ignored the Range, so restart.
            val appending = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !appending) {
                throw DownloadException("Server returned HTTP $code")
            }
            if (!appending) have = 0L

            onProgress(Progress(have, e.sizeBytes))

            FileOutputStream(part, appending).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var done = have
                    var lastPct = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (e.sizeBytes > 0) ((done * 100) / e.sizeBytes).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(Progress(done, e.sizeBytes))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (part.length() != e.sizeBytes) {
            throw DownloadException(
                "Incomplete download: got ${part.length()} of ${e.sizeBytes} bytes. Tap again to resume."
            )
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw DownloadException("Could not finalise ${e.fileName}")
        target
    }
}
