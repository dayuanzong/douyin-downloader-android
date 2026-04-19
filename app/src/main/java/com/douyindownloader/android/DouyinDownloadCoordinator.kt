package com.douyindownloader.android

import android.content.Context

class DouyinDownloadCoordinator(context: Context) {
    private val parser = DouyinShareParser()
    private val apiClient = DouyinApiClient(context)
    private val saver = MediaStoreDownloadSaver(context)
    private val appContext = context.applicationContext

    fun extractFromShareText(
        rawText: String,
        onProgress: (DownloadProgress) -> Unit,
    ): ExtractionResult {
        onProgress(DownloadProgress(message = "正在识别分享链接…"))
        val shareUrl = parser.extractShareUrl(rawText)
            ?: throw IllegalArgumentException("没有在输入内容里识别到有效链接，请粘贴完整的抖音分享文本。")
        DebugEventLogger.log(appContext, "Coordinator", "share_url_extracted", mapOf("shareUrl" to shareUrl))

        onProgress(DownloadProgress(message = "正在解析短链接跳转…"))
        val resolvedUrl = apiClient.resolveUrl(shareUrl)
        DebugEventLogger.log(appContext, "Coordinator", "share_url_resolved", mapOf("resolvedUrl" to resolvedUrl))
        val awemeId = parser.extractAwemeId(resolvedUrl)
            ?: parser.extractAwemeId(shareUrl)
            ?: throw IllegalStateException("已经拿到链接，但没有识别出作品 ID。")
        DebugEventLogger.log(appContext, "Coordinator", "aweme_id_extracted", mapOf("awemeId" to awemeId))

        onProgress(DownloadProgress(message = "正在请求作品详情…"))
        val detail = apiClient.fetchAwemeDetail(awemeId, resolvedUrl)
        DebugEventLogger.log(
            appContext,
            "Coordinator",
            "detail_loaded",
            mapOf(
                "awemeId" to detail.awemeId,
                "imageAssetCount" to detail.imageAssets.size,
                "videoCandidateCount" to detail.videoCandidates.size,
                "hasImageContent" to detail.hasImageContent,
            ),
        )
        val baseName = FileNameSanitizer.buildBaseName(detail.description, detail.awemeId)
        val imageItems = detail.imageAssets.mapIndexed { index, asset ->
            ExtractedImageItem(
                index = index,
                previewUrl = asset.motionCandidates.firstOrNull() ?: asset.imageCandidates.firstOrNull(),
                imageCandidates = asset.imageCandidates,
                motionCandidates = asset.motionCandidates,
                imageFileName = FileNameSanitizer.buildImageFileName(baseName, index + 1, asset.imageCandidates),
                motionFileName = asset.motionCandidates.takeIf { it.isNotEmpty() }?.let {
                    FileNameSanitizer.buildMotionFileName(baseName, index + 1)
                },
            )
        }
        val videoItem = if (imageItems.isEmpty() && detail.videoCandidates.isNotEmpty()) {
            ExtractedVideo(
                previewUrl = detail.videoCandidates.firstOrNull()?.url,
                candidateUrls = detail.videoCandidates.map { it.url },
                fileName = FileNameSanitizer.buildVideoFileName(detail.description, detail.awemeId),
            )
        } else {
            null
        }

        if (imageItems.isEmpty() && videoItem == null) {
            if (detail.hasImageContent) {
                throw IllegalStateException("识别到这是图文作品，但当前页面没有暴露可下载的原图地址。")
            }
            throw IllegalStateException("当前作品没有可下载的媒体地址，可能需要登录、作品已失效，或页面结构已变化。")
        }

        return ExtractionResult(
            awemeId = detail.awemeId,
            shareUrl = shareUrl,
            resolvedUrl = resolvedUrl,
            saveRelativePath = MediaStoreDownloadSaver.SAVE_RELATIVE_PATH,
            description = detail.description,
            videoItem = videoItem,
            imageItems = imageItems,
        )
    }

    fun downloadFromShareText(
        rawText: String,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        return saveAll(extractFromShareText(rawText, onProgress), onProgress)
    }

    fun saveVideo(
        extractionResult: ExtractionResult,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        val videoItem = extractionResult.videoItem
            ?: throw IllegalStateException("当前结果不是视频作品。")
        val savedAsset = saveVideoAsset(videoItem, onProgress)
        return buildDownloadResult(extractionResult, listOf(savedAsset))
    }

    fun saveImageItem(
        extractionResult: ExtractionResult,
        itemIndex: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        val imageItem = extractionResult.imageItems.getOrNull(itemIndex)
            ?: throw IllegalStateException("当前图片内容不存在。")
        val savedAssets = savePrimaryAsset(
            imageItem = imageItem,
            onProgress = onProgress,
        )
        return buildDownloadResult(extractionResult, savedAssets)
    }

    fun saveAll(
        extractionResult: ExtractionResult,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        val savedAssets = mutableListOf<SavedAsset>()

        if (extractionResult.imageItems.isNotEmpty()) {
            val totalAssets = extractionResult.imageItems.sumOf { it.downloadableAssetCount }
            var completedAssets = 0
            extractionResult.imageItems.forEach { imageItem ->
                val currentSavedAssets = saveImageAssets(
                    imageItem = imageItem,
                    completedAssets = completedAssets,
                    totalAssets = totalAssets,
                    onProgress = onProgress,
                )
                savedAssets += currentSavedAssets
                completedAssets += currentSavedAssets.size
            }
        } else if (extractionResult.videoItem != null) {
            savedAssets += saveVideoAsset(extractionResult.videoItem, onProgress)
        } else {
            throw IllegalStateException("当前结果没有可保存的媒体内容。")
        }

        return buildDownloadResult(extractionResult, savedAssets)
    }

    private fun saveImageAssets(
        imageItem: ExtractedImageItem,
        completedAssets: Int,
        totalAssets: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): List<SavedAsset> {
        val savedAssets = mutableListOf<SavedAsset>()

        if (imageItem.imageCandidates.isNotEmpty()) {
            val savedImage = saver.saveImage(
                candidateUrls = imageItem.imageCandidates,
                displayName = imageItem.imageFileName,
                mimeType = MimeTypeGuesser.guessImageMimeType(imageItem.imageFileName),
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    DownloadProgress(
                        message = "正在保存图片 ${completedAssets + 1}/$totalAssets…",
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            savedAssets += SavedAsset(
                fileName = imageItem.imageFileName,
                bytesWritten = savedImage.bytesWritten,
                kind = SavedAssetKind.IMAGE,
                relativePath = savedImage.relativePath,
            )
        }

        if (imageItem.motionCandidates.isNotEmpty()) {
            val savedMotion = saver.saveVideo(
                candidateUrls = imageItem.motionCandidates,
                displayName = imageItem.motionFileName ?: FileNameSanitizer.buildMotionFileName("douyin_media", imageItem.index + 1),
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    DownloadProgress(
                        message = "正在保存动图视频 ${completedAssets + savedAssets.size + 1}/$totalAssets…",
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            savedAssets += SavedAsset(
                fileName = imageItem.motionFileName ?: FileNameSanitizer.buildMotionFileName("douyin_media", imageItem.index + 1),
                bytesWritten = savedMotion.bytesWritten,
                kind = SavedAssetKind.MOTION,
                relativePath = savedMotion.relativePath,
            )
        }

        return savedAssets
    }

    private fun savePrimaryAsset(
        imageItem: ExtractedImageItem,
        onProgress: (DownloadProgress) -> Unit,
    ): List<SavedAsset> {
        if (imageItem.imageCandidates.isNotEmpty()) {
            val savedImage = saver.saveImage(
                candidateUrls = imageItem.imageCandidates,
                displayName = imageItem.imageFileName,
                mimeType = MimeTypeGuesser.guessImageMimeType(imageItem.imageFileName),
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    DownloadProgress(
                        message = "正在保存图片…",
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            return listOf(
                SavedAsset(
                    fileName = imageItem.imageFileName,
                    bytesWritten = savedImage.bytesWritten,
                    kind = SavedAssetKind.IMAGE,
                    relativePath = savedImage.relativePath,
                ),
            )
        }

        if (imageItem.motionCandidates.isNotEmpty()) {
            val savedMotion = saver.saveVideo(
                candidateUrls = imageItem.motionCandidates,
                displayName = imageItem.motionFileName ?: FileNameSanitizer.buildMotionFileName("douyin_media", imageItem.index + 1),
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    DownloadProgress(
                        message = "正在保存动图视频…",
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            return listOf(
                SavedAsset(
                    fileName = imageItem.motionFileName ?: FileNameSanitizer.buildMotionFileName("douyin_media", imageItem.index + 1),
                    bytesWritten = savedMotion.bytesWritten,
                    kind = SavedAssetKind.MOTION,
                    relativePath = savedMotion.relativePath,
                ),
            )
        }

        throw IllegalStateException("当前图片内容没有可保存的资源。")
    }

    private fun saveVideoAsset(
        videoItem: ExtractedVideo,
        onProgress: (DownloadProgress) -> Unit,
    ): SavedAsset {
        val savedVideo = saver.saveVideo(
            candidateUrls = videoItem.candidateUrls,
            displayName = videoItem.fileName,
        ) { downloadedBytes, totalBytes ->
            onProgress(
                DownloadProgress(
                    message = "正在保存视频…",
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                ),
            )
        }
        return SavedAsset(
            fileName = videoItem.fileName,
            bytesWritten = savedVideo.bytesWritten,
            kind = SavedAssetKind.VIDEO,
            relativePath = savedVideo.relativePath,
        )
    }

    private fun buildDownloadResult(
        extractionResult: ExtractionResult,
        savedAssets: List<SavedAsset>,
    ): DownloadResult {
        val savedRelativePath = savedAssets.firstOrNull()?.relativePath ?: extractionResult.saveRelativePath
        return DownloadResult(
            awemeId = extractionResult.awemeId,
            resolvedUrl = extractionResult.resolvedUrl,
            savedRelativePath = savedRelativePath,
            description = extractionResult.description,
            savedAssets = savedAssets,
        )
    }
}

data class DownloadProgress(
    val message: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

data class ExtractionResult(
    val awemeId: String,
    val shareUrl: String,
    val resolvedUrl: String,
    val saveRelativePath: String,
    val description: String,
    val videoItem: ExtractedVideo?,
    val imageItems: List<ExtractedImageItem>,
)

data class ExtractedVideo(
    val previewUrl: String?,
    val candidateUrls: List<String>,
    val fileName: String,
)

data class ExtractedImageItem(
    val index: Int,
    val previewUrl: String?,
    val imageCandidates: List<String>,
    val motionCandidates: List<String>,
    val imageFileName: String,
    val motionFileName: String?,
) {
    val downloadableAssetCount: Int
        get() = (if (imageCandidates.isNotEmpty()) 1 else 0) + (if (motionCandidates.isNotEmpty()) 1 else 0)
}

data class DownloadResult(
    val awemeId: String,
    val resolvedUrl: String,
    val savedRelativePath: String,
    val description: String,
    val savedAssets: List<SavedAsset>,
)

data class SavedAsset(
    val fileName: String,
    val bytesWritten: Long,
    val kind: SavedAssetKind,
    val relativePath: String,
)

enum class SavedAssetKind {
    VIDEO,
    IMAGE,
    MOTION,
}

object FileNameSanitizer {
    private val invalidChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val whitespace = Regex("\\s+")
    private val trailingExtension = Regex("\\.[A-Za-z0-9]{1,5}$")

    fun buildBaseName(description: String, awemeId: String): String {
        val cleanDescription = description
            .replace(invalidChars, " ")
            .replace(whitespace, " ")
            .trim()
            .ifEmpty { "douyin_media" }
            .take(40)
            .trim()
        return "${cleanDescription}_${awemeId}"
    }

    fun buildVideoFileName(description: String, awemeId: String): String {
        return "${buildBaseName(description, awemeId)}.mp4"
    }

    fun buildImageFileName(baseName: String, index: Int, candidateUrls: List<String>): String {
        val extension = MimeTypeGuesser.guessImageExtension(candidateUrls)
        return "${baseName}_${index.toString().padStart(2, '0')}$extension"
    }

    fun buildMotionFileName(baseName: String, index: Int): String {
        return "${baseName}_${index.toString().padStart(2, '0')}_motion.mp4"
    }

    fun ensureExtension(displayName: String, extension: String): String {
        val trimmed = displayName.trim().ifEmpty { "douyin_media" }
        if (trimmed.endsWith(extension, ignoreCase = true)) {
            return trimmed
        }
        return trimmed.replace(trailingExtension, "") + extension
    }
}

object MimeTypeGuesser {
    fun guessImageMimeType(displayName: String): String {
        return when {
            displayName.endsWith(".png", ignoreCase = true) -> "image/png"
            displayName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            displayName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            else -> "image/jpeg"
        }
    }

    fun guessImageExtension(candidateUrls: List<String>): String {
        val lowerUrls = candidateUrls.map { it.lowercase() }
        return when {
            lowerUrls.any { ".png" in it || "mime_type=image_png" in it } -> ".png"
            lowerUrls.any { ".webp" in it || "mime_type=image_webp" in it } -> ".webp"
            lowerUrls.any { ".gif" in it || "mime_type=image_gif" in it } -> ".gif"
            lowerUrls.any { ".jpeg" in it } -> ".jpeg"
            else -> ".jpg"
        }
    }
}
