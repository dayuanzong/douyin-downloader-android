package com.douyindownloader.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MediaStoreDownloadSaver(private val context: Context) {
    fun saveVideo(
        candidateUrls: List<String>,
        displayName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): SavedMedia {
        return saveMedia(
            candidateUrls = candidateUrls,
            displayName = FileNameSanitizer.ensureExtension(displayName, ".mp4"),
            mimeType = "video/mp4",
            onProgress = onProgress,
        )
    }

    fun saveImage(
        candidateUrls: List<String>,
        displayName: String,
        mimeType: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): SavedMedia {
        val extension = when (mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            else -> ".jpg"
        }
        return saveMedia(
            candidateUrls = candidateUrls,
            displayName = FileNameSanitizer.ensureExtension(displayName, extension),
            mimeType = mimeType,
            onProgress = onProgress,
        )
    }

    private fun saveMedia(
        candidateUrls: List<String>,
        displayName: String,
        mimeType: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): SavedMedia {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, SAVE_RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在 Download/抖音 创建目标文件。")

        try {
            val cleanCandidates = candidateUrls
                .filter { it.startsWith("http") }
                .distinct()
            if (cleanCandidates.isEmpty()) {
                throw IOException("当前媒体没有可用的下载地址。")
            }

            var lastError: Exception? = null
            for (candidateUrl in cleanCandidates) {
                repeat(MAX_ATTEMPTS_PER_URL) {
                    try {
                        val bytesWritten = resolver.openOutputStream(itemUri, "w")?.use { outputStream ->
                            val connection = openConnection(candidateUrl)
                            try {
                                val code = connection.responseCode
                                if (code !in 200..299) {
                                    throw IOException("下载地址返回异常状态码：$code")
                                }

                                val totalBytes = connection.contentLengthLong
                                connection.inputStream.use { input ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var written = 0L
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) {
                                            break
                                        }
                                        outputStream.write(buffer, 0, read)
                                        written += read
                                        onProgress(written, totalBytes)
                                    }
                                    outputStream.flush()
                                    written
                                }
                            } finally {
                                connection.disconnect()
                            }
                        } ?: throw IOException("无法打开目标文件输出流。")

                        resolver.update(
                            itemUri,
                            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                            null,
                            null,
                        )

                        return SavedMedia(
                            uri = itemUri,
                            relativePath = SAVE_RELATIVE_PATH,
                            bytesWritten = bytesWritten,
                        )
                    } catch (error: Exception) {
                        lastError = error
                    }
                }
            }

            throw lastError ?: IOException("所有候选下载地址都失败了。")
        } catch (error: Exception) {
            resolver.delete(itemUri, null, null)
            throw error
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", "https://www.douyin.com/")
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    companion object {
        const val SAVE_RELATIVE_PATH = "Download/抖音"
        private const val MAX_ATTEMPTS_PER_URL = 3

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S9210) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

data class SavedMedia(
    val uri: Uri,
    val relativePath: String,
    val bytesWritten: Long,
)
