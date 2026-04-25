package com.douyindownloader.android

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object DouyinParsing {
    private const val MIN_CONTENT_IMAGE_GROUP_SCORE = 20
    private val pacePushPattern = Regex("""self\.__pace_f\.push\(\[1,"(.*?)"\]\)""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val renderScriptPattern = Regex(
        """<script[^>]*id=["'](?:RENDER_DATA|_ROUTER_DATA)["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun extractAwemeDetailFromItemInfo(body: String): JSONObject? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return root.optJSONArray("item_list")?.optJSONObject(0)
    }

    fun extractAwemeDetailFromSlidesInfo(body: String, awemeId: String): JSONObject? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val details = root.optJSONArray("aweme_details") ?: return null
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            val candidateId = detail.optString("aweme_id").ifBlank {
                detail.optString("awemeId").ifBlank { detail.optString("group_id") }
            }
            if (candidateId == awemeId || awemeId.isBlank()) {
                return detail
            }
        }
        return details.optJSONObject(0)
    }

    fun candidateWorkUrls(awemeId: String, pageUrl: String?): List<String> {
        val cleaned = pageUrl?.trim().orEmpty()
        val candidates = mutableListOf<String>()
        if ("/note/" in cleaned || "/slides/" in cleaned) {
            candidates += "https://www.douyin.com/note/$awemeId?previous_page=app_code_link"
            if (cleaned.startsWith("http")) {
                candidates += cleaned
            }
            candidates += "https://www.iesdouyin.com/share/slides/$awemeId/?from_ssr=1"
            candidates += "https://www.iesdouyin.com/share/note/$awemeId/"
        } else {
            candidates += "https://www.douyin.com/video/$awemeId?previous_page=app_code_link"
            if (cleaned.startsWith("http")) {
                candidates += cleaned
            }
            candidates += "https://www.iesdouyin.com/share/video/$awemeId/"
            candidates += "https://www.douyin.com/note/$awemeId?previous_page=app_code_link"
        }

        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        candidates.forEach { url ->
            if (url.startsWith("http") && seen.add(url.substringBefore('#'))) {
                result += url
            }
        }
        return result
    }

    fun extractAwemeDetailFromPageContent(pageContent: String, awemeId: String): JSONObject? {
        val scripts = mutableListOf<String>()
        renderScriptPattern.findAll(pageContent).forEach { scripts += it.groupValues[1] }
        pacePushPattern.findAll(pageContent).forEach { scripts += """self.__pace_f.push([1,"${it.groupValues[1]}"])""" }
        return extractAwemeDetailFromScriptTexts(scripts, awemeId)
    }

    fun extractAwemeDetailFromScriptTexts(scriptTexts: List<String>, awemeId: String): JSONObject? {
        scriptTexts.forEach { scriptText ->
            decodeRenderPayload(scriptText, awemeId)?.let { payload ->
                findRenderedAwemeDetail(payload, awemeId)?.let { return it }
            }
        }
        return null
    }

    private fun decodeRenderPayload(payload: String, awemeId: String): Any? {
        val trimmed = payload.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val candidates = mutableListOf(trimmed)
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            val decodedString = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
            if (decodedString is String) {
                candidates += decodedString
            }
        }

        candidates.distinct().forEach { candidate ->
            if (!candidate.contains(awemeId) && !candidate.contains("videoDetail") && !candidate.contains("noteDetail")) {
                return@forEach
            }

            val decoded = runCatching {
                URLDecoder.decode(candidate, StandardCharsets.UTF_8.name())
            }.getOrDefault(candidate)

            listOf(candidate, decoded).distinct().forEach { value ->
                val stripped = value.trim()
                if (stripped.startsWith("{") || stripped.startsWith("[")) {
                    runCatching { JSONTokener(stripped).nextValue() }.getOrNull()?.let { return it }
                }
                if (':' in stripped) {
                    val body = stripped.substringAfter(':').trim()
                    if (body.startsWith("{") || body.startsWith("[")) {
                        runCatching { JSONTokener(body).nextValue() }.getOrNull()?.let { return it }
                    }
                }
            }

            pacePushPattern.findAll(candidate).forEach { match ->
                decodeRenderPayload(match.groupValues[1], awemeId)?.let { return it }
            }
        }
        return null
    }

    private fun findRenderedAwemeDetail(node: Any?, awemeId: String): JSONObject? {
        return when (node) {
            is JSONObject -> {
                listOf("videoDetail", "noteDetail").forEach { key ->
                    node.optJSONObject(key)?.let { candidate ->
                        if (matchesAwemeId(candidate, awemeId)) {
                            return candidate
                        }
                    }
                }
                if (matchesAwemeId(node, awemeId) && looksLikeAwemeDetail(node)) {
                    return node
                }
                val aweme = node.optJSONObject("aweme")
                if (matchesAwemeId(node, awemeId) && aweme != null) {
                    aweme.optJSONObject("detail")?.let { return it }
                }
                node.optJSONObject("detail")?.let { detail ->
                    if (matchesAwemeId(detail, awemeId)) {
                        return detail
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findRenderedAwemeDetail(node.opt(keys.next()), awemeId)?.let { return it }
                }
                null
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    findRenderedAwemeDetail(node.opt(index), awemeId)?.let { return it }
                }
                null
            }

            else -> null
        }
    }

    fun normalizeAwemeDetail(item: JSONObject): AwemeDetail {
        val normalized = if (item.has("aweme_id")) item else normalizeRenderedAwemeDetail(item)
        val awemeId = normalized.optString("aweme_id").ifBlank {
            normalized.optString("awemeId").ifBlank { item.optString("awemeId") }
        }
        val description = repairMojibakeText(
            normalized.optString("desc").ifBlank {
                item.optString("desc").ifBlank { item.optString("itemTitle") }
            },
        )
        val imageAssets = extractImageAssets(normalized)
        val hasImageContent = detectImageContent(normalized) || imageAssets.isNotEmpty()
        val videoCandidates = if (hasImageContent && imageAssets.isNotEmpty()) emptyList() else extractVideoCandidates(normalized.optJSONObject("video"))
        return AwemeDetail(awemeId, description, videoCandidates, imageAssets, hasImageContent)
    }

    fun normalizeRenderedAwemeDetail(detail: JSONObject): JSONObject {
        if (detail.has("aweme_id")) {
            return detail
        }
        val authorInfo = detail.optJSONObject("authorInfo") ?: JSONObject()
        val normalized = JSONObject().apply {
            put("aweme_id", detail.optString("awemeId"))
            put("aweme_type", detail.opt("awemeType"))
            put("desc", repairMojibakeText(detail.optString("desc").ifBlank { detail.optString("itemTitle") }))
            put(
                "author",
                JSONObject().apply {
                    put("uid", authorInfo.optString("uid").ifBlank { detail.optString("authorUserId") })
                    put("sec_uid", authorInfo.optString("secUid").ifBlank { authorInfo.optString("sec_uid") })
                    put("nickname", repairMojibakeText(authorInfo.optString("nickname")))
                },
            )
        }

        normalizeRenderedVideo(detail.optJSONObject("video"))?.let { normalized.put("video", it) }

        val images = detail.optJSONArray("images")
        if (images != null) {
            val normalizedImages = JSONArray()
            for (index in 0 until images.length()) {
                normalizeRenderedImageNode(images.optJSONObject(index))?.let(normalizedImages::put)
            }
            if (normalizedImages.length() > 0) {
                normalized.put("images", normalizedImages)
                normalized.put("image_post_info", JSONObject().put("images", normalizedImages))
            }
        }

        return normalized
    }

    fun detectUnavailableAwemePage(pageContent: String): String? {
        if (pageContent.isBlank()) {
            return null
        }
        val markers = listOf("作品不存在", "内容不存在", "视频不见了", "该内容已删除", "无法查看该内容", "页面不存在", "status_audit_self_see")
        return if (markers.any { it in pageContent }) "作品不存在" else null
    }

    fun findUnavailableReason(item: JSONObject): String? {
        val queue = ArrayDeque<Any>()
        queue += item
        while (queue.isNotEmpty()) {
            when (val current = queue.removeFirst()) {
                is JSONObject -> {
                    if (current.optString("filter_reason") == "status_audit_self_see") {
                        return "作品不存在"
                    }
                    if (current.optString("status_msg").contains("不存在")) {
                        return "作品不存在"
                    }
                        val keys = current.keys()
                        while (keys.hasNext()) {
                            current.opt(keys.next())?.let { queue += it }
                        }
                }

                is JSONArray -> {
                        for (index in 0 until current.length()) {
                            current.opt(index)?.let { queue += it }
                        }
                }
            }
        }
        return null
    }

    fun buildDetailFromRenderedSnapshot(snapshot: RenderedPageSnapshot, awemeId: String): AwemeDetail? {
        extractAwemeDetailFromScriptTexts(snapshot.scripts, awemeId)?.let { rawDetail ->
            findUnavailableReason(rawDetail)?.let { throw IllegalStateException(it) }
            return normalizeAwemeDetail(rawDetail)
        }

        val imageGroups = snapshot.images
            .filter { looksLikeContentImage(it.src, it.width, it.height, it.className, it.alt) }
            .map { buildPreferredImageCandidates(it.src) }
            .distinct()
        val requestImageGroups = snapshot.requests
            .filter(::looksLikeRequestedImageUrl)
            .map(::buildPreferredImageCandidates)
            .distinct()
        val motionUrls = (snapshot.videos + snapshot.requests)
            .filter(::looksLikeRealVideoUrl)
            .distinct()
        val allImageGroups = (imageGroups + requestImageGroups)
            .distinct()
            .let { groups ->
                if (motionUrls.isNotEmpty()) {
                    groups.sortedByDescending(::scoreRenderedImageGroup)
                } else {
                    groups
                }
            }
            .let { groups -> filterLikelyCoverGroups(groups, motionUrls.isNotEmpty()) }
            .let { groups -> filterLikelyContentGroups(groups, motionUrls.isNotEmpty()) }

        if (allImageGroups.isNotEmpty()) {
            val assets = allImageGroups.mapIndexed { index, urls ->
                ImageAsset(urls, motionUrls.getOrNull(index)?.let(::listOf).orEmpty())
            }
            return AwemeDetail(awemeId, sanitizeBrowserTitle(snapshot.title), emptyList(), assets, true)
        }

        if (motionUrls.isNotEmpty()) {
            return AwemeDetail(
                awemeId = awemeId,
                description = sanitizeBrowserTitle(snapshot.title),
                videoCandidates = motionUrls.map { VideoCandidate(url = it, sourcePriority = 80) },
                imageAssets = emptyList(),
                hasImageContent = false,
            )
        }

        return null
    }

    fun sanitizeBrowserTitle(title: String): String {
        val cleanTitle = title.trim()
        return if (cleanTitle.endsWith(" - 抖音")) repairMojibakeText(cleanTitle.removeSuffix(" - 抖音").trim()) else repairMojibakeText(cleanTitle)
    }

    fun looksLikeRealVideoUrl(url: String): Boolean {
        if (!url.startsWith("http")) {
            return false
        }
        val lowered = url.lowercase()
        if ("douyin-pc-web/uuu_265.mp4" in lowered) {
            return false
        }
        if (".mp3" in lowered || "mime_type=audio" in lowered || "video_id=http" in lowered) {
            return false
        }
        return ".mp4" in lowered || "mime_type=video_mp4" in lowered || "__vid=" in lowered || "/play/" in lowered
    }

    fun repairMojibakeText(value: String): String {
        if (value.isBlank()) {
            return ""
        }
        if (value.any { it in '\u4e00'..'\u9fff' }) {
            return value
        }
        return try {
            val repaired = value.toByteArray(StandardCharsets.ISO_8859_1).toString(StandardCharsets.UTF_8)
            if (repaired.any { it in '\u4e00'..'\u9fff' }) repaired else value
        } catch (_: Exception) {
            value
        }
    }

    private fun matchesAwemeId(node: JSONObject, awemeId: String): Boolean {
        val direct = node.optString("awemeId").ifBlank { node.optString("aweme_id") }
        return direct == awemeId
    }

    private fun looksLikeAwemeDetail(node: JSONObject): Boolean {
        return node.has("desc") || node.has("video") || node.has("images") || node.has("awemeType") || node.has("aweme_type")
    }

    private fun detectImageContent(item: JSONObject): Boolean {
        if (item.optInt("aweme_type") in setOf(68, 150)) {
            return true
        }
        return listOf("images", "image_list", "image_infos", "origin_images").any { item.optJSONArray(it)?.length() ?: 0 > 0 } ||
            (item.optJSONObject("image_post_info")?.optJSONArray("images")?.length() ?: 0) > 0
    }

    private fun extractImageAssets(item: JSONObject): List<ImageAsset> {
        val nodes = mutableListOf<JSONObject>()
        listOf("images", "image_list", "image_infos", "origin_images").forEach { key ->
            val array = item.optJSONArray(key) ?: return@forEach
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(nodes::add)
        }
        item.optJSONObject("image_post_info")?.let { info ->
            listOf("images", "image_list").forEach { key ->
                val array = info.optJSONArray(key) ?: return@forEach
                for (index in 0 until array.length()) array.optJSONObject(index)?.let(nodes::add)
            }
        }

        val assets = mutableListOf<ImageAsset>()
        val seen = mutableSetOf<Pair<List<String>, List<String>>>()
        nodes.forEach { node ->
            val images = collectImageCandidateUrls(node)
            val motions = collectMotionCandidateUrls(node)
            if ((images.isNotEmpty() || motions.isNotEmpty()) && seen.add(images to motions)) {
                assets += ImageAsset(images, motions)
            }
        }
        return assets
    }

    private fun collectImageCandidateUrls(imageNode: JSONObject): List<String> {
        val urls = mutableListOf<String>()
        listOf("display_image", "origin_image", "image_url", "large_image", "download_image", "thumbnail", "owner_watermark_image").forEach { key ->
            urls += collectUrls(imageNode.opt(key), listOf("watermark_free_download_url_list", "origin_url_list", "url_list", "download_url_list", "download_url", "url"))
        }
        urls += collectUrls(imageNode, listOf("watermark_free_download_url_list", "origin_url_list", "url_list", "download_url_list", "download_url", "url"))
        return urls.flatMap(::buildPreferredImageCandidates).distinct()
    }

    private fun collectMotionCandidateUrls(imageNode: JSONObject): List<String> {
        val urls = mutableListOf<String>()
        listOf("live_photo_video", "video", "motion_photo", "motion_video").forEach { key ->
            urls += collectUrls(imageNode.opt(key), listOf("play_addr_h264", "play_addr", "url_list", "download_url_list", "url"))
        }
        return urls.filter(::looksLikeRealVideoUrl).distinct()
    }

    private fun extractVideoCandidates(video: JSONObject?): List<VideoCandidate> {
        if (video == null) return emptyList()
        val candidates = mutableListOf<VideoCandidate>()
        val bitRates = video.optJSONArray("bit_rate")
        if (bitRates != null) {
            val variants = mutableListOf<JSONObject>()
            for (index in 0 until bitRates.length()) bitRates.optJSONObject(index)?.let(variants::add)
            variants.sortedWith(compareByDescending<JSONObject> { it.optInt("bit_rate") }.thenByDescending { it.optInt("height") }).forEach { variant ->
                collectUrls(variant, listOf("play_addr_h264", "play_addr", "url_list", "download_url_list", "url")).forEach { url ->
                    candidates += VideoCandidate(normalizeVideoCandidate(url), scoreVideoUrl(url, 40), variant.optInt("bit_rate"), variant.optInt("height"), variant.optInt("width"))
                }
            }
        }
        listOf("play_addr_h264", "play_addr", "download_addr").forEach { key ->
            collectUrls(video.opt(key)).forEach { url ->
                val base = if (key == "play_addr_h264") 30 else if (key == "play_addr") 20 else 10
                candidates += VideoCandidate(normalizeVideoCandidate(url), scoreVideoUrl(url, base))
            }
        }
        buildNoWatermarkCandidates(video).forEach { candidates += VideoCandidate(it, 60) }
        return candidates.filter { looksLikeRealVideoUrl(it.url) }.distinctBy { it.url }.sortedWith(compareByDescending<VideoCandidate> { it.sourcePriority }.thenByDescending { it.bitrate }.thenByDescending { it.height })
    }

    private fun buildNoWatermarkCandidates(video: JSONObject): List<String> {
        val videoIds = mutableSetOf<String>()
        listOf("play_addr", "play_addr_h264", "download_addr").forEach { key -> videoIds += collectUris(video.opt(key)) }
        return videoIds.filter { it.isNotBlank() }.flatMap { id ->
            listOf(
                "https://aweme.snssdk.com/aweme/v1/play/?video_id=$id&ratio=1080p&line=0",
                "https://aweme.snssdk.com/aweme/v1/play/?video_id=$id&ratio=720p&line=0",
            )
        }
    }

    private fun collectUris(node: Any?): List<String> {
        return when (node) {
            is JSONArray -> buildList { for (index in 0 until node.length()) addAll(collectUris(node.opt(index))) }
            is JSONObject -> buildList {
                node.optString("uri").takeIf { it.isNotBlank() }?.let(::add)
                val keys = node.keys()
                while (keys.hasNext()) addAll(collectUris(node.opt(keys.next())))
            }
            else -> emptyList()
        }.distinct()
    }

    private fun collectUrls(node: Any?, preferredKeys: List<String> = listOf("play_addr_h264", "play_addr", "url_list", "download_url_list", "url", "download_url")): List<String> {
        return when (node) {
            is String -> if (node.startsWith("http")) listOf(node) else emptyList()
            is JSONArray -> buildList { for (index in 0 until node.length()) addAll(collectUrls(node.opt(index), preferredKeys)) }
            is JSONObject -> buildList { preferredKeys.forEach { if (node.has(it)) addAll(collectUrls(node.opt(it), preferredKeys)) } }
            else -> emptyList()
        }.distinct()
    }

    private fun normalizeVideoCandidate(url: String): String = if ("/playwm/" in url) url.replace("/playwm/", "/play/") else url

    private fun scoreVideoUrl(url: String, base: Int): Int = when {
        "/playwm/" in url -> base - 10
        "ratio=1080p" in url || "1080" in url -> base + 5
        else -> base
    }

    private fun normalizeRenderedImageNode(imageNode: JSONObject?): JSONObject? {
        if (imageNode == null) return null
        val normalized = JSONObject()
        coerceStringList(imageNode.opt("urlList") ?: imageNode.opt("url_list"))?.let { normalized.put("url_list", it) }
        coerceStringList(imageNode.opt("downloadUrlList") ?: imageNode.opt("download_url_list"))?.let { normalized.put("download_url_list", it) }
        normalizeRenderedVideo(imageNode.optJSONObject("video") ?: imageNode.optJSONObject("live_photo_video") ?: imageNode.optJSONObject("motion_photo") ?: imageNode.optJSONObject("motion_video"))?.let {
            normalized.put("video", it)
        }
        return if (normalized.length() > 0) normalized else null
    }

    private fun normalizeRenderedVideo(video: JSONObject?): JSONObject? {
        if (video == null) return null
        val normalized = JSONObject()
        coercePlayUrls(video.opt("playAddr") ?: video.opt("play_addr"))?.let { normalized.put("play_addr", JSONObject().put("url_list", it)) }
        coercePlayUrls(video.opt("playAddrH265") ?: video.opt("play_addr_h265"))?.let { normalized.put("play_addr_h265", JSONObject().put("url_list", it)) }
        val playApi = video.optString("playApi").ifBlank { video.optString("play_api") }
        val downloadApi = video.optString("downloadAddr").ifBlank { video.optString("download_addr") }
        val downloadUrl = playApi.ifBlank { downloadApi }
        if (downloadUrl.startsWith("http")) normalized.put("download_addr", JSONObject().put("url_list", JSONArray().put(downloadUrl)))

        val bitRates = video.optJSONArray("bitRateList") ?: video.optJSONArray("bit_rate")
        if (bitRates != null) {
            val normalizedBitRates = JSONArray()
            for (index in 0 until bitRates.length()) {
                val variant = bitRates.optJSONObject(index) ?: continue
                val variantJson = JSONObject().apply {
                    put("bit_rate", variant.optInt("bitRate", variant.optInt("bit_rate")))
                    put("gear_name", variant.optString("gearName").ifBlank { variant.optString("gear_name") })
                    put("width", variant.optInt("width"))
                    put("height", variant.optInt("height"))
                }
                coercePlayUrls(variant.opt("playAddr") ?: variant.opt("play_addr"))?.let {
                    variantJson.put("play_addr", JSONObject().put("url_list", it))
                }
                coercePlayUrls(variant.opt("playAddrH265") ?: variant.opt("play_addr_h265"))?.let {
                    variantJson.put("play_addr_h265", JSONObject().put("url_list", it))
                }
                if (variantJson.has("play_addr") || variantJson.has("play_addr_h265")) {
                    normalizedBitRates.put(variantJson)
                }
            }
            if (normalizedBitRates.length() > 0) {
                normalized.put("bit_rate", normalizedBitRates)
            }
        }

        return if (normalized.length() > 0) normalized else null
    }

    private fun coerceStringList(node: Any?): JSONArray? {
        val values = mutableListOf<String>()
        when (node) {
            is String -> if (node.startsWith("http")) values += node
            is JSONArray -> for (index in 0 until node.length()) node.optString(index).takeIf { it.startsWith("http") }?.let(values::add)
        }
        return values.distinct().takeIf { it.isNotEmpty() }?.let { list -> JSONArray().apply { list.forEach(::put) } }
    }

    private fun coercePlayUrls(node: Any?): JSONArray? {
        val values = mutableListOf<String>()
        when (node) {
            is String -> if (node.startsWith("http")) values += node
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    when (val value = node.opt(index)) {
                        is String -> if (value.startsWith("http")) values += value
                        is JSONObject -> value.optString("src").takeIf { it.startsWith("http") }?.let(values::add)
                    }
                }
            }
            is JSONObject -> {
                node.optString("src").takeIf { it.startsWith("http") }?.let(values::add)
                coercePlayUrls(node.opt("url_list"))?.let { array -> for (index in 0 until array.length()) values += array.optString(index) }
                coercePlayUrls(node.opt("urlList"))?.let { array -> for (index in 0 until array.length()) values += array.optString(index) }
            }
        }
        return values.distinct().takeIf { it.isNotEmpty() }?.let { list -> JSONArray().apply { list.forEach(::put) } }
    }

    private fun looksLikeContentImage(url: String, width: Int, height: Int, className: String, alt: String): Boolean {
        if (!url.startsWith("http") || width in 1..199 || height in 1..199) return false
        val lowerUrl = url.lowercase()
        val lowerClass = className.lowercase()
        val lowerAlt = alt.lowercase()
        if (
            "avatar" in lowerUrl ||
            "avatar" in lowerClass ||
            "icon" in lowerUrl ||
            "icon" in lowerClass ||
            "logo" in lowerUrl ||
            "emoji" in lowerUrl ||
            looksLikeStaticAssetImageUrl(lowerUrl)
        ) {
            return false
        }
        return lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".webp") || lowerUrl.endsWith(".gif") || "mime_type=image_" in lowerUrl || "douyinpic.com" in lowerUrl || "zjcdn.com" in lowerUrl || "picture" in lowerAlt
    }

    private fun looksLikeRequestedImageUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val lowerUrl = url.lowercase()
        if (
            lowerUrl.endsWith(".js") ||
            lowerUrl.endsWith(".css") ||
            lowerUrl.endsWith(".json") ||
            lowerUrl.endsWith(".svg") ||
            looksLikeStaticAssetImageUrl(lowerUrl)
        ) {
            return false
        }
        val looksLikeImageResource =
            lowerUrl.endsWith(".jpg") ||
            lowerUrl.endsWith(".jpeg") ||
            lowerUrl.endsWith(".png") ||
            lowerUrl.endsWith(".webp") ||
            lowerUrl.endsWith(".gif") ||
            "mime_type=image_" in lowerUrl ||
            "douyinpic.com" in lowerUrl ||
            "p3-pc-sign.douyinpic.com" in lowerUrl ||
            "zjcdn.com/img/" in lowerUrl
        if (!looksLikeImageResource) {
            return false
        }
        return hasStrongAwemeImageSignal(lowerUrl) || "douyinpic.com" in lowerUrl
    }

    private fun buildPreferredImageCandidates(url: String): List<String> {
        return buildList {
            buildNoWatermarkImageUrl(url)?.let(::add)
            add(url)
        }.distinct()
    }

    private fun buildNoWatermarkImageUrl(url: String): String? {
        val marker = "~tplv-dy-water-v2"
        val markerIndex = url.indexOf(marker)
        if (markerIndex < 0) {
            return null
        }

        val queryStart = url.indexOf('?', markerIndex).let { if (it >= 0) it else url.length }
        val extensionIndex = url.lastIndexOf('.', queryStart).takeIf { it > markerIndex } ?: return null
        return url.removeRange(markerIndex, extensionIndex)
    }

    private fun scoreRenderedImageGroup(urls: List<String>): Int {
        val loweredUrls = urls.map { it.lowercase() }
        var score = 0
        if (loweredUrls.any { ".webp" in it || "mime_type=image_webp" in it }) score += 50
        if (loweredUrls.any { ".gif" in it || "mime_type=image_gif" in it }) score += 45
        if (loweredUrls.any { "water-v2" in it || "live_photo" in it || "biz_tag=aweme_images" in it }) score += 20
        if (loweredUrls.any { ".jpg" in it || ".jpeg" in it || ".png" in it }) score += 10
        if (loweredUrls.any { "sign.douyinpic.com/obj/" in it }) score -= 15
        return score
    }

    private fun filterLikelyCoverGroups(groups: List<List<String>>, hasMotionContent: Boolean): List<List<String>> {
        if (groups.size <= 1) {
            return groups
        }

        val coverGroups = groups.filter(::looksLikeRenderedCoverGroup)
        if (coverGroups.isEmpty()) {
            return groups
        }

        val contentGroups = groups.filterNot(::looksLikeRenderedCoverGroup)
        return when {
            hasMotionContent && contentGroups.size >= 2 -> contentGroups
            contentGroups.size > coverGroups.size -> contentGroups
            else -> groups
        }
    }

    private fun filterLikelyContentGroups(groups: List<List<String>>, hasMotionContent: Boolean): List<List<String>> {
        if (groups.size <= 1) {
            return groups
        }

        val candidateGroups = groups.filterNot(::looksLikeNonContentImageGroup)
        if (candidateGroups.isEmpty()) {
            return groups
        }

        val strongGroups = candidateGroups.filter { urls ->
            scoreRenderedImageGroup(urls) >= MIN_CONTENT_IMAGE_GROUP_SCORE ||
                urls.any { hasStrongAwemeImageSignal(it.lowercase()) }
        }
        if (strongGroups.isNotEmpty()) {
            return strongGroups
        }

        if (hasMotionContent) {
            return candidateGroups.filter { scoreRenderedImageGroup(it) > 0 }.ifEmpty { candidateGroups }
        }

        return candidateGroups
    }

    private fun looksLikeRenderedCoverGroup(urls: List<String>): Boolean {
        val loweredUrls = urls.map { it.lowercase() }
        if (loweredUrls.any { "biz_tag=aweme_images" in it || "~tplv-dy-water-v2" in it || "/tos-cn-i-" in it }) {
            return false
        }
        return loweredUrls.any { "/obj/tos-cn-p-" in it || "sign.douyinpic.com/obj/" in it }
    }

    private fun looksLikeNonContentImageGroup(urls: List<String>): Boolean {
        return urls.all { looksLikeStaticAssetImageUrl(it.lowercase()) }
    }

    private fun hasStrongAwemeImageSignal(lowerUrl: String): Boolean {
        return "biz_tag=aweme_images" in lowerUrl ||
            "tplv-dy-aweme-images" in lowerUrl ||
            "/tos-cn-i-" in lowerUrl ||
            "mime_type=image_" in lowerUrl ||
            "live_photo" in lowerUrl
    }

    private fun looksLikeStaticAssetImageUrl(lowerUrl: String): Boolean {
        return "avatar" in lowerUrl ||
            "emoji" in lowerUrl ||
            "icon" in lowerUrl ||
            "favicon" in lowerUrl ||
            "logo" in lowerUrl ||
            "emblem" in lowerUrl ||
            "blackbg" in lowerUrl ||
            "douyindefault" in lowerUrl ||
            "nav_" in lowerUrl ||
            "pcweb_cover" in lowerUrl ||
            "aweme-avatar" in lowerUrl ||
            "douyinstatic.com/obj/douyin-pc-web" in lowerUrl ||
            "byteimg.com/tos-cn-i-9r5gewecjs" in lowerUrl ||
            "/obj/tos-cn-i-tsj2vxp0zn/" in lowerUrl
    }
}

data class AwemeDetail(
    val awemeId: String,
    val description: String,
    val videoCandidates: List<VideoCandidate>,
    val imageAssets: List<ImageAsset>,
    val hasImageContent: Boolean,
)

data class VideoCandidate(
    val url: String,
    val sourcePriority: Int = 0,
    val bitrate: Int = 0,
    val height: Int = 0,
    val width: Int = 0,
)

data class ImageAsset(
    val imageCandidates: List<String>,
    val motionCandidates: List<String> = emptyList(),
)
