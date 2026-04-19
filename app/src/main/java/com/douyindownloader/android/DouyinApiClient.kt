package com.douyindownloader.android

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class DouyinApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val renderedExtractor = DouyinRenderedPageExtractor(context)

    fun resolveUrl(url: String): String {
        var currentUrl = url.trim()
        repeat(MAX_REDIRECT_FOLLOWS) {
            val connection = openConnection(
                url = currentUrl,
                connectTimeoutMs = REDIRECT_CONNECT_TIMEOUT_MS,
                readTimeoutMs = REDIRECT_READ_TIMEOUT_MS,
            ).apply {
                instanceFollowRedirects = false
            }

            try {
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    currentUrl = URL(URL(currentUrl), location).toString()
                    debugLog("resolveUrl redirect code=$code currentUrl=$currentUrl")
                    return@repeat
                }

                drainConnection(connection)
                val finalUrl = connection.url?.toString().orEmpty()
                if (finalUrl.isNotBlank()) {
                    currentUrl = finalUrl
                }
                return currentUrl
            } finally {
                connection.disconnect()
            }
        }
        return currentUrl
    }

    fun fetchAwemeDetail(awemeId: String, pageUrl: String? = null): AwemeDetail {
        fetchItemInfoDetail(awemeId, pageUrl)?.let {
            debugLog("fetchAwemeDetail: iteminfo hit for awemeId=$awemeId")
            return it
        }

        val candidateUrls = DouyinParsing.candidateWorkUrls(awemeId, pageUrl)
        val preferRenderedExtractor = looksLikeSlidesOrNotePage(pageUrl, candidateUrls)
        val pageFetchErrors = mutableListOf<Throwable>()
        debugLog("fetchAwemeDetail: candidateUrls=$candidateUrls preferRendered=$preferRenderedExtractor")

        if (preferRenderedExtractor) {
            fetchRenderedDetail(awemeId, candidateUrls)?.let {
                debugLog("fetchAwemeDetail: rendered extractor hit first for awemeId=$awemeId")
                return it
            }
        }

        candidateUrls
            .take(if (preferRenderedExtractor) MAX_SLIDES_PAGE_FETCH_CANDIDATES else MAX_STANDARD_PAGE_FETCH_CANDIDATES)
            .forEach { candidateUrl ->
                try {
                    val pageContent = fetchPageContent(candidateUrl)
                    DouyinParsing.detectUnavailableAwemePage(pageContent)?.let { throw IllegalStateException(it) }
                    DouyinParsing.extractAwemeDetailFromPageContent(pageContent, awemeId)?.let { rawDetail ->
                        DouyinParsing.findUnavailableReason(rawDetail)?.let { throw IllegalStateException(it) }
                        debugLog("fetchAwemeDetail: page content hit for awemeId=$awemeId url=$candidateUrl")
                        return DouyinParsing.normalizeAwemeDetail(rawDetail)
                    }
                    debugLog("fetchAwemeDetail: page content miss for awemeId=$awemeId url=$candidateUrl bodyLength=${pageContent.length}")
                } catch (error: Exception) {
                    pageFetchErrors += error
                    debugLog(
                        "fetchAwemeDetail: page content error for awemeId=$awemeId url=$candidateUrl error=${error.message ?: error.javaClass.simpleName}",
                    )
                    if (error is IllegalStateException && !error.isTransientFailure()) {
                        throw error
                    }
                }
            }

        if (!preferRenderedExtractor) {
            fetchRenderedDetail(awemeId, candidateUrls)?.let {
                debugLog("fetchAwemeDetail: rendered extractor fallback hit for awemeId=$awemeId")
                return it
            }
        }

        if (pageFetchErrors.any { it.isTransientFailure() }) {
            debugLog("fetchAwemeDetail: transient network failure for awemeId=$awemeId")
            throw IllegalStateException("当前网络返回不稳定，请稍后重试。")
        }

        debugLog("fetchAwemeDetail: no downloadable media for awemeId=$awemeId")
        throw IllegalStateException("当前作品没有可下载的媒体地址，可能需要登录、作品已失效，或页面结构已变化。")
    }

    private fun fetchRenderedDetail(awemeId: String, candidateUrls: List<String>): AwemeDetail? {
        val detail = renderedExtractor.extractAwemeDetail(awemeId, candidateUrls) ?: return null
        return detail.takeIf { it.videoCandidates.isNotEmpty() || it.imageAssets.isNotEmpty() || it.hasImageContent }
    }

    private fun fetchItemInfoDetail(awemeId: String, pageUrl: String?): AwemeDetail? {
        val endpoint = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$awemeId"
        val referer = pageUrl?.takeIf { it.isNotBlank() } ?: "https://www.iesdouyin.com/share/video/$awemeId/"
        val connection = openConnection(
            url = endpoint,
            connectTimeoutMs = API_CONNECT_TIMEOUT_MS,
            readTimeoutMs = API_READ_TIMEOUT_MS,
        ).apply {
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                debugLog("fetchItemInfoDetail: status=$responseCode awemeId=$awemeId")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                debugLog("fetchItemInfoDetail: blank body awemeId=$awemeId")
                return null
            }

            val rawDetail = DouyinParsing.extractAwemeDetailFromItemInfo(body) ?: return null
            DouyinParsing.findUnavailableReason(rawDetail)?.let { throw IllegalStateException(it) }
            return DouyinParsing.normalizeAwemeDetail(rawDetail)
        } catch (error: IOException) {
            debugLog("fetchItemInfoDetail: network error awemeId=$awemeId error=${error.message}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchPageContent(url: String): String {
        val connection = openConnection(
            url = url,
            connectTimeoutMs = PAGE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = PAGE_READ_TIMEOUT_MS,
        ).apply {
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Referer", url)
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("作品页面返回异常状态码: $responseCode")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://www.douyin.com/")
        }
    }

    private fun drainConnection(connection: HttpURLConnection) {
        val stream = try {
            connection.inputStream
        } catch (_: IOException) {
            connection.errorStream
        } ?: return

        stream.use { input ->
            BufferedInputStream(input).use { buffered ->
                val bytes = ByteArray(512)
                while (buffered.read(bytes) != -1) {
                    // Drain the stream so redirect URLs are finalized before close.
                }
            }
        }
    }

    private fun looksLikeSlidesOrNotePage(pageUrl: String?, candidateUrls: List<String>): Boolean {
        val lowered = buildList {
            pageUrl?.let(::add)
            addAll(candidateUrls)
        }.map { it.lowercase() }
        return lowered.any { "/slides/" in it || "/note/" in it || "is_slides=1" in it || "contains_video_type_clip=1" in it }
    }

    private fun Throwable.isTransientFailure(): Boolean {
        return this is SocketTimeoutException || this is IOException || (this is IllegalStateException && message == "当前网络返回不稳定，请稍后重试。")
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        DebugEventLogger.log(appContext, TAG, "message", mapOf("message" to message))
    }

    companion object {
        private const val TAG = "DouyinApiClient"
        private const val MAX_REDIRECT_FOLLOWS = 6
        private const val REDIRECT_CONNECT_TIMEOUT_MS = 4_000
        private const val REDIRECT_READ_TIMEOUT_MS = 6_000
        private const val API_CONNECT_TIMEOUT_MS = 4_500
        private const val API_READ_TIMEOUT_MS = 7_000
        private const val PAGE_CONNECT_TIMEOUT_MS = 3_500
        private const val PAGE_READ_TIMEOUT_MS = 5_500
        private const val MAX_STANDARD_PAGE_FETCH_CANDIDATES = 2
        private const val MAX_SLIDES_PAGE_FETCH_CANDIDATES = 1
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S9210) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
