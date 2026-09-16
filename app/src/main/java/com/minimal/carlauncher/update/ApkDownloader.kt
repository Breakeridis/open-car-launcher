package com.minimal.carlauncher.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed interface DownloadProgress {
    /** [total] is -1 when the server sent no Content-Length. */
    data class Running(val bytes: Long, val total: Long) : DownloadProgress
    data class Done(val file: File) : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}

/**
 * Streams the release APK to the private cache dir with byte-level progress.
 *
 * DownloadManager was rejected: it offers no progress callback at all (you would poll a
 * Cursor on a timer), it is a system app that stripped head-unit ROMs sometimes omit, it
 * posts its own notification, and it wants a public destination rather than the private
 * cache dir sitting behind our FileProvider.
 */
class ApkDownloader {

    fun download(url: String, dest: File): Flow<DownloadProgress> = flow {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".tmp")
        if (tmp.exists()) tmp.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(url)
            if (connection == null) {
                emit(DownloadProgress.Failed("Too many redirects"))
                return@flow
            }
            if (connection.responseCode !in 200..299) {
                emit(DownloadProgress.Failed("HTTP ${connection.responseCode}"))
                return@flow
            }

            val total = connection.contentLengthLong
            var written = 0L
            var lastPercent = -1
            var lastEmitMs = 0L

            emit(DownloadProgress.Running(0L, total))

            connection.inputStream.buffered(BUFFER).use { input ->
                tmp.outputStream().buffered(BUFFER).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read

                        // Throttle emissions: one per whole percent, or every 250ms when the
                        // total size is unknown. Otherwise this floods the main thread.
                        val percent = if (total > 0) ((written * 100L) / total).toInt() else -1
                        val now = System.currentTimeMillis()
                        if (percent != lastPercent || now - lastEmitMs >= 250L) {
                            lastPercent = percent
                            lastEmitMs = now
                            emit(DownloadProgress.Running(written, total))
                        }
                    }
                    output.flush()
                }
            }

            if (!looksLikeApk(tmp)) {
                tmp.delete()
                emit(DownloadProgress.Failed("Downloaded file is not a valid APK"))
                return@flow
            }

            if (dest.exists()) dest.delete()
            // Rename only on success - never hand a truncated APK to the package installer.
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                emit(DownloadProgress.Failed("Could not finalise the download"))
                return@flow
            }

            emit(DownloadProgress.Running(written, total))
            emit(DownloadProgress.Done(dest))
        } catch (e: Exception) {
            emit(DownloadProgress.Failed(e.message ?: "Download failed"))
        } finally {
            connection?.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * browser_download_url 302s to objects.githubusercontent.com. HttpURLConnection follows
     * same-protocol hops itself but silently refuses an http <-> https change, so follow by hand.
     */
    private fun openFollowingRedirects(startUrl: String): HttpURLConnection? {
        var current = startUrl
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "MinimalCarLauncher")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) return null
                current = URL(URL(current), location).toString()
            } else {
                return connection
            }
        }
        return null
    }

    /** Every APK is a zip, so it starts with the local-file-header magic "PK". */
    private fun looksLikeApk(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 &&
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        }
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val BUFFER = 16 * 1024
        const val MAX_REDIRECTS = 5
    }
}
