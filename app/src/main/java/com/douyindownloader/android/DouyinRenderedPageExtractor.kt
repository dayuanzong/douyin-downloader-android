package com.douyindownloader.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DouyinRenderedPageExtractor(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun extractAwemeDetail(awemeId: String, candidateUrls: List<String>): AwemeDetail? {
        val urls = candidateUrls.filter { it.startsWith("http") }.distinct()
        if (urls.isEmpty()) {
            return null
        }

        val resultRef = AtomicReference<AwemeDetail?>()
        val errorRef = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)

        mainHandler.post {
            Session(awemeId, urls) { result, error ->
                resultRef.set(result)
                errorRef.set(error)
                latch.countDown()
            }.start()
        }

        latch.await(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        errorRef.get()?.let { throw it }
        return resultRef.get()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class Session(
        private val awemeId: String,
        private val urls: List<String>,
        private val onComplete: (AwemeDetail?, Throwable?) -> Unit,
    ) {
        private var currentIndex = 0
        private var pollCount = 0
        private var slideAdvanceAttempts = 0
        private var finished = false
        private val requestLock = Any()
        private val requestUrls = linkedSetOf<String>()
        private val slidesInfoDetailRef = AtomicReference<AwemeDetail?>()
        @Volatile
        private var sawSlidesInfoRequest = false
        @Volatile
        private var slidesInfoCaptureAttempted = false

        private val webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = USER_AGENT
        }

        fun start() {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return request?.url?.let(::shouldBlockNavigation) ?: false
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val parsed = runCatching { Uri.parse(url) }.getOrNull()
                    return parsed?.let(::shouldBlockNavigation) ?: false
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    request?.url?.toString()?.takeIf { it.startsWith("http") }?.let(::rememberRequestUrl)
                    interceptApiResponse(request)?.let { return it }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (finished) return
                    url?.takeIf { it.startsWith("http") }?.let(::rememberRequestUrl)
                    debugLog("onPageFinished awemeId=$awemeId url=$url requests=${requestUrls.size}")
                    pollCount = 0
                    slideAdvanceAttempts = 0
                    schedulePoll(POLL_DELAY_MS)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (finished || request?.isForMainFrame != true) return
                    loadNext()
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.cancel()
                    loadNext()
                }
            }
            loadCurrent()
        }

        private fun rememberRequestUrl(url: String) {
            if (url.contains("/aweme/slidesinfo/")) {
                sawSlidesInfoRequest = true
            }
            val requestSnapshot = synchronized(requestLock) {
                if (requestUrls.size >= MAX_CAPTURED_REQUESTS && url !in requestUrls) {
                    return
                }
                if (!requestUrls.add(url)) {
                    return
                }
                requestUrls.toList()
            }
            DebugSnapshotStore.saveRequests(context, awemeId, urls.getOrNull(currentIndex), requestSnapshot)
        }

        private fun interceptApiResponse(request: WebResourceRequest?): WebResourceResponse? {
            val safeRequest = request ?: return null
            val requestUrl = safeRequest.url.toString()
            if (!requestUrl.contains("/aweme/slidesinfo/")) {
                return null
            }

            return runCatching {
                slidesInfoCaptureAttempted = true
                val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = safeRequest.method
                    connectTimeout = 8_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    safeRequest.requestHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
                    setRequestProperty("User-Agent", userAgentFor(urls.getOrNull(currentIndex)))
                    setRequestProperty("Referer", urls.getOrNull(currentIndex) ?: "https://www.douyin.com/")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                }

                try {
                    val statusCode = connection.responseCode
                    val responseBytes = readAllBytes(
                        if (statusCode in 200..299) connection.inputStream else connection.errorStream,
                    )
                    val charset = extractCharset(connection.contentType) ?: Charsets.UTF_8
                    val responseBody = responseBytes.toString(charset)
                    DebugSnapshotStore.saveApiResponse(context, awemeId, requestUrl, responseBody)
                    DouyinParsing.extractAwemeDetailFromSlidesInfo(responseBody, awemeId)?.let { rawDetail ->
                        slidesInfoDetailRef.set(DouyinParsing.normalizeAwemeDetail(rawDetail))
                    }
                    DebugEventLogger.log(
                        context,
                        TAG,
                        "slidesinfo_response_captured",
                        mapOf(
                            "url" to requestUrl,
                            "statusCode" to statusCode,
                            "bytes" to responseBytes.size,
                            "detailParsed" to (slidesInfoDetailRef.get() != null),
                        ),
                    )

                    WebResourceResponse(
                        extractMimeType(connection.contentType),
                        charset.name(),
                        statusCode,
                        connection.responseMessage ?: "OK",
                        connection.headerFields.filterKeys { it != null }.mapValues { it.value?.joinToString("; ").orEmpty() },
                        ByteArrayInputStream(responseBytes),
                    )
                } finally {
                    connection.disconnect()
                }
            }.onFailure { error ->
                DebugEventLogger.log(
                    context,
                    TAG,
                    "slidesinfo_response_failed",
                    mapOf("url" to requestUrl, "error" to (error.message ?: error.javaClass.simpleName)),
                )
            }.getOrNull()
        }

        private fun shouldBlockNavigation(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase().orEmpty()
            val shouldBlock = scheme != "http" && scheme != "https"
            if (shouldBlock) {
                uri.toString().takeIf { it.isNotBlank() }?.let(::rememberRequestUrl)
                debugLog("blocked external navigation awemeId=$awemeId uri=$uri")
            }
            return shouldBlock
        }

        private fun loadCurrent() {
            if (currentIndex >= urls.size) {
                finish(null, null)
                return
            }
            val targetUrl = urls[currentIndex]
            webView.settings.userAgentString = userAgentFor(targetUrl)
            webView.loadUrl(
                targetUrl,
                mapOf(
                    "Referer" to "https://www.douyin.com/",
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                ),
            )
        }

        private fun loadNext() {
            if (finished) return
            currentIndex += 1
            pollCount = 0
            loadCurrent()
        }

        private fun schedulePoll(delayMs: Long) {
            mainHandler.postDelayed({ if (!finished) poll() }, delayMs)
        }

        private fun poll() {
            pollCount += 1
            webView.evaluateJavascript(buildSnapshotScript(awemeId)) { raw ->
                if (finished) return@evaluateJavascript

                val snapshot = parseSnapshot(raw, snapshotRequestUrls())
                slidesInfoDetailRef.get()?.let { detail ->
                    snapshot?.let { currentSnapshot ->
                        DebugSnapshotStore.saveSnapshot(context, awemeId, currentSnapshot, detail)
                    }
                    finish(detail, null)
                    return@evaluateJavascript
                }
                snapshot?.let {
                    val interestingRequests = it.requests.filter { url ->
                        "douyinpic" in url || "mime_type=image_" in url || ".webp" in url || ".jpg" in url || ".jpeg" in url
                    }
                    debugLog(
                        TAG,
                        "poll awemeId=$awemeId count=$pollCount scripts=${it.scripts.size} images=${it.images.size} " +
                            "videos=${it.videos.size} requests=${it.requests.size}",
                    )
                    debugLog(
                        "poll snapshot images awemeId=$awemeId urls=${it.images.map { node -> node.src }}",
                    )
                    debugLog(
                        "poll snapshot requests awemeId=$awemeId urls=$interestingRequests",
                    )
                }
                val result = runCatching { snapshot?.let { DouyinParsing.buildDetailFromRenderedSnapshot(it, awemeId) } }
                result.exceptionOrNull()?.let {
                    snapshot?.let { currentSnapshot ->
                        DebugSnapshotStore.saveSnapshot(context, awemeId, currentSnapshot, null)
                    }
                    finish(null, it)
                    return@evaluateJavascript
                }

                val detail = result.getOrNull()
                if (shouldAdvanceSlides(detail, snapshot)) {
                    slideAdvanceAttempts += 1
                    advanceSlides()
                    schedulePoll(SLIDE_ADVANCE_DELAY_MS)
                    return@evaluateJavascript
                }
                snapshot?.let { currentSnapshot ->
                    DebugSnapshotStore.saveSnapshot(context, awemeId, currentSnapshot, detail)
                }
                if (shouldWaitForSlidesInfo(detail)) {
                    schedulePoll(POLL_DELAY_MS)
                    return@evaluateJavascript
                }
                if (detail != null) {
                    debugLog(
                        "poll hit awemeId=$awemeId images=${detail.imageAssets.size} videos=${detail.videoCandidates.size} hasImage=${detail.hasImageContent}",
                    )
                    debugLog(
                        "poll image previews awemeId=$awemeId previews=${detail.imageAssets.map { it.imageCandidates.firstOrNull().orEmpty() }} motions=${detail.imageAssets.map { it.motionCandidates.firstOrNull().orEmpty() }}",
                    )
                    finish(detail, null)
                } else if (pollCount < MAX_POLL_COUNT) {
                    schedulePoll(POLL_DELAY_MS)
                } else {
                    debugLog("poll miss awemeId=$awemeId recentRequests=${snapshotRequestUrls().takeLast(20)}")
                    loadNext()
                }
            }
        }

        private fun snapshotRequestUrls(): List<String> = synchronized(requestLock) { requestUrls.toList() }

        private fun finish(result: AwemeDetail?, error: Throwable?) {
            if (finished) return
            finished = true
            webView.stopLoading()
            webView.destroy()
            onComplete(result, error)
        }

        private fun shouldAdvanceSlides(detail: AwemeDetail?, snapshot: RenderedPageSnapshot?): Boolean {
            if (slideAdvanceAttempts >= MAX_SLIDE_ADVANCE_ATTEMPTS) {
                return false
            }
            val isSlidesPage = isSlidesPage()
            if (!isSlidesPage) {
                return false
            }
            if (detail?.hasImageContent == true) {
                return true
            }
            return snapshot?.images?.isNotEmpty() == true || snapshot?.videos?.isNotEmpty() == true
        }

        private fun shouldWaitForSlidesInfo(detail: AwemeDetail?): Boolean {
            if (detail == null || !detail.hasImageContent || !isSlidesPage()) {
                return false
            }
            if (slidesInfoDetailRef.get() != null) {
                return false
            }
            if (pollCount < MIN_SLIDESINFO_WAIT_POLLS) {
                debugLog("poll wait slidesinfo awemeId=$awemeId count=$pollCount reason=minimum_wait")
                return true
            }
            if (sawSlidesInfoRequest && slidesInfoCaptureAttempted && pollCount < MAX_POLL_COUNT) {
                debugLog("poll wait slidesinfo awemeId=$awemeId count=$pollCount reason=request_seen")
                return true
            }
            return false
        }

        private fun isSlidesPage(): Boolean {
            val currentUrl = urls.getOrNull(currentIndex).orEmpty().lowercase()
            return "/slides/" in currentUrl || "is_slides=1" in currentUrl || "/note/" in currentUrl
        }

        private fun advanceSlides() {
            webView.evaluateJavascript(buildAdvanceSlidesScript()) { raw ->
                debugLog("advanceSlides awemeId=$awemeId attempt=$slideAdvanceAttempts result=$raw")
            }
        }

        private fun userAgentFor(url: String?): String {
            val lowered = url.orEmpty().lowercase()
            return if ("www.douyin.com" in lowered) DESKTOP_USER_AGENT else USER_AGENT
        }
        private fun readAllBytes(inputStream: InputStream?): ByteArray {
            return inputStream?.use { it.readBytes() } ?: ByteArray(0)
        }

        private fun extractCharset(contentType: String?): Charset? {
            val charsetName = contentType
                ?.substringAfter("charset=", "")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return runCatching { Charset.forName(charsetName) }.getOrNull()
        }

        private fun extractMimeType(contentType: String?): String {
            return contentType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "application/json"
        }
    }

    private fun parseSnapshot(rawValue: String?, interceptedRequests: List<String>): RenderedPageSnapshot? {
        val decoded = decodeJsValue(rawValue) ?: return null
        val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        val scripts = mutableListOf<String>()
        val images = mutableListOf<RenderedImageNode>()
        val videos = mutableListOf<String>()
        val resources = mutableListOf<String>()

        root.optJSONArray("scripts")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(scripts::add)
            }
        }
        root.optJSONArray("images")?.let { array ->
            for (index in 0 until array.length()) {
                val image = array.optJSONObject(index) ?: continue
                images += RenderedImageNode(
                    src = image.optString("src"),
                    width = image.optInt("width"),
                    height = image.optInt("height"),
                    className = image.optString("className"),
                    alt = image.optString("alt"),
                )
            }
        }
        root.optJSONArray("videos")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.startsWith("http") }?.let(videos::add)
            }
        }
        root.optJSONArray("resources")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.startsWith("http") }?.let(resources::add)
            }
        }

        return RenderedPageSnapshot(
            title = root.optString("title"),
            scripts = scripts,
            images = images,
            videos = videos,
            requests = (interceptedRequests + resources).distinct(),
        )
    }

    private fun decodeJsValue(rawValue: String?): String? {
        if (rawValue.isNullOrBlank() || rawValue == "null") {
            return null
        }
        return when (val value = runCatching { JSONTokener(rawValue).nextValue() }.getOrNull()) {
            is String -> value
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            else -> value?.toString()
        }
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        DebugEventLogger.log(context, TAG, "message", mapOf("message" to message))
    }

    private fun debugLog(tag: String, message: String) {
        Log.d(tag, message)
        DebugEventLogger.log(context, tag, "message", mapOf("message" to message))
    }

    private fun buildSnapshotScript(awemeId: String): String {
        return """
            (function() {
              const targetAwemeId = ${JSONObject.quote(awemeId)};
              const scripts = Array.from(document.scripts || [])
                .map(function(script) { return script && script.textContent ? script.textContent : ""; })
                .filter(function(text) {
                  return text &&
                    (text.indexOf(targetAwemeId) >= 0 ||
                     text.indexOf("self.__pace_f.push") >= 0 ||
                     text.indexOf("videoDetail") >= 0 ||
                     text.indexOf("noteDetail") >= 0);
                })
                .slice(0, 8);

              const images = Array.from(document.images || [])
                .map(function(img) {
                  return {
                    src: img.currentSrc || img.src || "",
                    width: img.naturalWidth || img.width || 0,
                    height: img.naturalHeight || img.height || 0,
                    className: typeof img.className === "string" ? img.className : "",
                    alt: img.alt || ""
                  };
                })
                .filter(function(item) { return item.src && item.src.indexOf("http") === 0; });

              const videos = Array.from(document.querySelectorAll("video, source") || [])
                .map(function(node) { return node.currentSrc || node.src || ""; })
                .filter(function(src) { return src && src.indexOf("http") === 0; });

              const resources = (typeof performance !== "undefined" && performance.getEntriesByType)
                ? performance.getEntriesByType("resource")
                    .map(function(entry) { return entry && entry.name ? entry.name : ""; })
                    .filter(function(name) { return name && name.indexOf("http") === 0; })
                    .slice(-200)
                : [];

              return JSON.stringify({
                title: document.title || "",
                scripts: scripts,
                images: images,
                videos: videos,
                resources: resources
              });
            })();
        """.trimIndent()
    }

    private fun buildAdvanceSlidesScript(): String {
        return """
            (function() {
              const nextButton = Array.from(document.querySelectorAll('button, div, span')).find(function(node) {
                const text = (node.innerText || node.textContent || '').trim();
                return text === '下一张' || text === '下一页';
              });
              if (nextButton && typeof nextButton.click === 'function') {
                nextButton.click();
                return 'button';
              }

              const target =
                document.querySelector('[class*="swiper"]') ||
                document.querySelector('[class*="carousel"]') ||
                document.querySelector('[data-e2e*="slide"]') ||
                document.elementFromPoint(window.innerWidth * 0.5, window.innerHeight * 0.55) ||
                document.body;

              const startX = Math.floor(window.innerWidth * 0.82);
              const endX = Math.floor(window.innerWidth * 0.18);
              const y = Math.floor(window.innerHeight * 0.55);
              const dispatch = function(type, x, y) {
                try {
                  const touchInit = {
                    identifier: Date.now(),
                    target: target,
                    clientX: x,
                    clientY: y,
                    pageX: x + window.scrollX,
                    pageY: y + window.scrollY,
                    screenX: x,
                    screenY: y
                  };
                  target.dispatchEvent(new TouchEvent(type, {
                    bubbles: true,
                    cancelable: true,
                    touches: type === 'touchend' ? [] : [touchInit],
                    targetTouches: type === 'touchend' ? [] : [touchInit],
                    changedTouches: [touchInit]
                  }));
                } catch (error) {
                  const fallbackType = type === 'touchstart' ? 'mousedown' : (type === 'touchmove' ? 'mousemove' : 'mouseup');
                  target.dispatchEvent(new MouseEvent(fallbackType, {
                    bubbles: true,
                    cancelable: true,
                    clientX: x,
                    clientY: y
                  }));
                }
              };

              dispatch('touchstart', startX, y);
              dispatch('touchmove', Math.floor((startX + endX) / 2), y);
              dispatch('touchmove', endX, y);
              dispatch('touchend', endX, y);
              return 'swipe';
            })();
        """.trimIndent()
    }

    companion object {
        private const val TAG = "DouyinRendered"
        private const val TOTAL_TIMEOUT_SECONDS = 45L
        private const val MAX_POLL_COUNT = 12
        private const val POLL_DELAY_MS = 900L
        private const val SLIDE_ADVANCE_DELAY_MS = 1200L
        private const val MAX_SLIDE_ADVANCE_ATTEMPTS = 2
        private const val MIN_SLIDESINFO_WAIT_POLLS = 3
        private const val MAX_CAPTURED_REQUESTS = 400
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S9210) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36 Edg/142.0.0.0"
    }
}

data class RenderedPageSnapshot(
    val title: String,
    val scripts: List<String>,
    val images: List<RenderedImageNode>,
    val videos: List<String>,
    val requests: List<String>,
)

data class RenderedImageNode(
    val src: String,
    val width: Int,
    val height: Int,
    val className: String,
    val alt: String,
)
