package com.douyindownloader.android

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
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
                if (looksLikeShortDouyinUrl(currentUrl) || looksLikeDesktopDouyinPage(currentUrl)) {
                    applyDesktopDouyinHeaders(currentUrl)
                }
                applyBrowserCookies(currentUrl)
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
        val candidateUrls = DouyinParsing.candidateWorkUrls(awemeId, pageUrl)
        val preferRenderedExtractor = looksLikeSlidesOrNotePage(pageUrl, candidateUrls)
        val pageFetchErrors = mutableListOf<Throwable>()
        debugLog("fetchAwemeDetail: candidateUrls=$candidateUrls preferRendered=$preferRenderedExtractor")

        fetchWebDetail(awemeId, pageUrl)?.let {
            debugLog("fetchAwemeDetail: web detail hit for awemeId=$awemeId")
            return it
        }

        if (!preferRenderedExtractor) {
            fetchItemInfoDetail(awemeId, pageUrl)?.let {
                debugLog("fetchAwemeDetail: iteminfo hit for awemeId=$awemeId")
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

        fetchRenderedDetail(awemeId, candidateUrls)?.let {
            debugLog("fetchAwemeDetail: rendered extractor fallback hit for awemeId=$awemeId")
            return it
        }

        if (preferRenderedExtractor) {
            fetchItemInfoDetail(awemeId, pageUrl)?.let {
                debugLog("fetchAwemeDetail: iteminfo fallback hit for awemeId=$awemeId")
                return it
            }
        }

        if (pageFetchErrors.any { it.isTransientFailure() }) {
            debugLog("fetchAwemeDetail: transient network failure for awemeId=$awemeId")
            throw IllegalStateException("褰撳墠缃戠粶杩斿洖涓嶇ǔ瀹氾紝璇风◢鍚庨噸璇曘€?")
        }

        debugLog("fetchAwemeDetail: no downloadable media for awemeId=$awemeId")
        throw IllegalStateException("褰撳墠浣滃搧娌℃湁鍙笅杞界殑濯掍綋鍦板潃锛屽彲鑳介渶瑕佺櫥褰曘€佷綔鍝佸凡澶辨晥锛屾垨椤甸潰缁撴瀯宸插彉鏇淬€?")
    }

    private fun fetchWebDetail(awemeId: String, pageUrl: String?): AwemeDetail? {
        val params = linkedMapOf(
            "device_platform" to "webapp",
            "aid" to "6383",
            "channel" to "channel_pc_web",
            "pc_client_type" to "1",
            "version_code" to "290100",
            "version_name" to "29.1.0",
            "cookie_enabled" to "true",
            "screen_width" to "1920",
            "screen_height" to "1080",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Chrome",
            "browser_version" to "130.0.0.0",
            "browser_online" to "true",
            "engine_name" to "Blink",
            "engine_version" to "130.0.0.0",
            "os_name" to "Windows",
            "os_version" to "10",
            "cpu_core_num" to "12",
            "device_memory" to "8",
            "platform" to "PC",
            "downlink" to "10",
            "effective_type" to "4g",
            "round_trip_time" to "0",
            "update_version_code" to "170400",
            "pc_libra_divert" to "Windows",
            "aweme_id" to awemeId,
            "msToken" to "",
        )
        val aBogus = runCatching { ABogusSigner.generateValue(params) }
            .onFailure { debugLog("fetchWebDetail: a_bogus failed awemeId=$awemeId error=${it.message}") }
            .getOrNull() ?: return null
        val endpoint = buildWebDetailEndpoint(params, aBogus)
        val referer = pageUrl?.takeIf { it.isNotBlank() } ?: "https://www.douyin.com/video/$awemeId"
        val cookie = prepareWebDetailCookie(pageUrl)
        val connection = openConnection(
            url = endpoint,
            connectTimeoutMs = API_CONNECT_TIMEOUT_MS,
            readTimeoutMs = API_READ_TIMEOUT_MS,
        ).apply {
            setRequestProperty("Accept", "application/json, text/plain, */*")
            applyDesktopDouyinHeaders(referer)
            if (cookie.isNotBlank()) {
                setRequestProperty("Cookie", cookie)
            }
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                debugLog("fetchWebDetail: status=$responseCode awemeId=$awemeId")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                debugLog("fetchWebDetail: blank body awemeId=$awemeId")
                return null
            }

            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (root.has("status_code") && root.optInt("status_code") != 0) {
                debugLog("fetchWebDetail: status_code=${root.optInt("status_code")} awemeId=$awemeId")
                return null
            }

            val rawDetail = root.optJSONObject("aweme_detail") ?: root.optJSONObject("aweme") ?: return null
            DouyinParsing.findUnavailableReason(rawDetail)?.let { throw IllegalStateException(it) }
            DouyinParsing.normalizeAwemeDetail(rawDetail)
                .takeIf { it.videoCandidates.isNotEmpty() || it.imageAssets.isNotEmpty() || it.hasImageContent }
        } catch (error: IOException) {
            debugLog("fetchWebDetail: network error awemeId=$awemeId error=${error.message}")
            null
        } finally {
            connection.disconnect()
        }
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
            if (looksLikeDesktopDouyinPage(url)) {
                applyDesktopDouyinHeaders(url)
            }
            applyBrowserCookies(url)
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("浣滃搧椤甸潰杩斿洖寮傚父鐘舵€佺爜: $responseCode")
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

    private fun looksLikeShortDouyinUrl(url: String): Boolean {
        val lowered = url.lowercase()
        return "v.douyin.com/" in lowered || "iesdouyin.com/share/" in lowered
    }

    private fun looksLikeDesktopDouyinPage(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrDefault("")
        return host == "www.douyin.com" || (host.endsWith(".douyin.com") && "iesdouyin" !in host)
    }

    private fun buildWebDetailEndpoint(params: LinkedHashMap<String, String>, aBogus: String): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        return "$WEB_DETAIL_ENDPOINT?$query&a_bogus=${aBogus.urlEncode()}"
    }

    private fun prepareWebDetailCookie(pageUrl: String?): String {
        val pageCookie = pageUrl?.let { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
        val webCookie = runCatching { CookieManager.getInstance().getCookie("https://www.douyin.com/") }.getOrNull()
        var merged = mergeCookies(pageCookie, webCookie)
        if (!merged.contains("ttwid=")) {
            generateTtwidCookie()?.let { merged = mergeCookies(merged, it) }
        }
        return merged
    }

    private fun generateTtwidCookie(): String? {
        val connection = (URL(TTWID_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = API_CONNECT_TIMEOUT_MS
            readTimeout = API_READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            setRequestProperty("Referer", "https://www.douyin.com/")
        }

        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(TTWID_PAYLOAD)
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                debugLog("generateTtwidCookie: status=$responseCode")
                return null
            }

            buildList {
                addAll(connection.headerFields["Set-Cookie"].orEmpty())
                connection.getHeaderField("Set-Cookie")?.let(::add)
            }
                .flatMap { it.split(';') }
                .map(String::trim)
                .firstOrNull { it.startsWith("ttwid=") }
        } catch (error: IOException) {
            debugLog("generateTtwidCookie: error=${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun mergeCookies(vararg cookieHeaders: String?): String {
        val cookiesByName = linkedMapOf<String, String>()
        cookieHeaders
            .filterNotNull()
            .flatMap { it.split(';') }
            .map(String::trim)
            .filter { '=' in it }
            .forEach { token -> cookiesByName[token.substringBefore('=')] = token }
        return cookiesByName.values.joinToString("; ")
    }

    private fun HttpURLConnection.applyDesktopDouyinHeaders(url: String) {
        setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
        setRequestProperty("Referer", url.takeIf { it.isNotBlank() } ?: "https://www.douyin.com/")
        setRequestProperty("sec-ch-ua", "\"Chromium\";v=\"142\", \"Microsoft Edge\";v=\"142\", \"Not_A Brand\";v=\"99\"")
        setRequestProperty("sec-ch-ua-mobile", "?0")
        setRequestProperty("sec-ch-ua-platform", "\"Windows\"")
    }

    private fun HttpURLConnection.applyBrowserCookies(url: String) {
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!cookie.isNullOrBlank()) {
            setRequestProperty("Cookie", cookie)
        }
    }

    private fun Throwable.isTransientFailure(): Boolean {
        return this is SocketTimeoutException ||
            this is IOException ||
            (this is IllegalStateException && message?.contains("缃戠粶杩斿洖涓嶇ǔ瀹?") == true)
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        DebugEventLogger.log(appContext, TAG, "message", mapOf("message" to message))
    }

    companion object {
        private const val TAG = "DouyinApiClient"
        private const val WEB_DETAIL_ENDPOINT = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
        private const val TTWID_ENDPOINT = "https://ttwid.bytedance.com/ttwid/union/register/"
        private const val TTWID_PAYLOAD =
            "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,\"service\":\"www.ixigua.com\"," +
                "\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},\"cbUrlProtocol\":\"https\",\"union\":true}"
        private const val MAX_REDIRECT_FOLLOWS = 6
        private const val REDIRECT_CONNECT_TIMEOUT_MS = 4_000
        private const val REDIRECT_READ_TIMEOUT_MS = 6_000
        private const val API_CONNECT_TIMEOUT_MS = 4_500
        private const val API_READ_TIMEOUT_MS = 7_000
        private const val PAGE_CONNECT_TIMEOUT_MS = 3_500
        private const val PAGE_READ_TIMEOUT_MS = 5_500
        private const val MAX_STANDARD_PAGE_FETCH_CANDIDATES = 2
        private const val MAX_SLIDES_PAGE_FETCH_CANDIDATES = 3
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S9210) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36 Edg/142.0.0.0"
    }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
