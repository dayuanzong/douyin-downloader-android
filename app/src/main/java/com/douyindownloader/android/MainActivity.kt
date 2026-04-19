package com.douyindownloader.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.webkit.WebView
import android.content.pm.ApplicationInfo
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var shareInputLayout: TextInputLayout
    private lateinit var shareEditText: TextInputEditText
    private lateinit var pasteButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var extractButton: MaterialButton
    private lateinit var actionProgressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var detailTextView: TextView
    private lateinit var resultCard: View
    private lateinit var resultSummaryTextView: TextView
    private lateinit var resultTabLayout: TabLayout
    private lateinit var previewImageView: ImageView
    private lateinit var previewVideoView: VideoView
    private lateinit var previewPlaceholderTextView: TextView
    private lateinit var previewLoadingBar: ProgressBar
    private lateinit var titleContentTextView: TextView
    private lateinit var imageControlsContainer: View
    private lateinit var previousImageButton: MaterialButton
    private lateinit var nextImageButton: MaterialButton
    private lateinit var imageIndicatorTextView: TextView
    private lateinit var resultHintTextView: TextView
    private lateinit var copyLinkButton: MaterialButton
    private lateinit var savePrimaryButton: MaterialButton
    private lateinit var saveAllButton: MaterialButton

    private val backgroundExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coordinator by lazy { DouyinDownloadCoordinator(applicationContext) }

    private var currentExtraction: ExtractionResult? = null
    private var currentTab: ResultTab = ResultTab.IMAGE
    private var currentImageIndex: Int = 0
    private var previewRequestToken: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)
        setContentView(R.layout.activity_main)

        bindViews()
        setupTabs()
        setupListeners()

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        previewRequestToken += 1
        previewVideoView.stopPlayback()
        backgroundExecutor.shutdownNow()
    }

    private fun bindViews() {
        shareInputLayout = findViewById(R.id.shareInputLayout)
        shareEditText = findViewById(R.id.shareEditText)
        pasteButton = findViewById(R.id.pasteButton)
        clearButton = findViewById(R.id.clearButton)
        extractButton = findViewById(R.id.extractButton)
        actionProgressBar = findViewById(R.id.actionProgressBar)
        statusTextView = findViewById(R.id.statusTextView)
        detailTextView = findViewById(R.id.detailTextView)
        resultCard = findViewById(R.id.resultCard)
        resultSummaryTextView = findViewById(R.id.resultSummaryTextView)
        resultTabLayout = findViewById(R.id.resultTabLayout)
        previewImageView = findViewById(R.id.previewImageView)
        previewVideoView = findViewById(R.id.previewVideoView)
        previewPlaceholderTextView = findViewById(R.id.previewPlaceholderTextView)
        previewLoadingBar = findViewById(R.id.previewLoadingBar)
        titleContentTextView = findViewById(R.id.titleContentTextView)
        imageControlsContainer = findViewById(R.id.imageControlsContainer)
        previousImageButton = findViewById(R.id.previousImageButton)
        nextImageButton = findViewById(R.id.nextImageButton)
        imageIndicatorTextView = findViewById(R.id.imageIndicatorTextView)
        resultHintTextView = findViewById(R.id.resultHintTextView)
        copyLinkButton = findViewById(R.id.copyLinkButton)
        savePrimaryButton = findViewById(R.id.savePrimaryButton)
        saveAllButton = findViewById(R.id.saveAllButton)
    }

    private fun setupTabs() {
        if (resultTabLayout.tabCount == 0) {
            resultTabLayout.addTab(resultTabLayout.newTab().setText(R.string.tab_video))
            resultTabLayout.addTab(resultTabLayout.newTab().setText(R.string.tab_image))
            resultTabLayout.addTab(resultTabLayout.newTab().setText(R.string.tab_title))
        }
        resultTabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    currentTab = ResultTab.fromPosition(tab.position)
                    renderCurrentTab()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
    }

    private fun setupListeners() {
        shareEditText.doAfterTextChanged { shareInputLayout.error = null }
        pasteButton.setOnClickListener { pasteFromClipboard() }
        clearButton.setOnClickListener { clearShareText() }
        extractButton.setOnClickListener { startExtraction() }
        previousImageButton.setOnClickListener { showRelativeImage(-1) }
        nextImageButton.setOnClickListener { showRelativeImage(1) }
        copyLinkButton.setOnClickListener { copyResolvedLink() }
        savePrimaryButton.setOnClickListener { handlePrimaryAction() }
        saveAllButton.setOnClickListener { saveAllMedia() }
    }

    private fun handleIntent(intent: Intent?) {
        val sharedText = intent
            ?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrBlank()) {
            DebugEventLogger.log(
                applicationContext,
                source = "MainActivity",
                event = "share_text_received",
                details = mapOf("text" to sharedText),
            )
            shareEditText.setText(sharedText)
            setIdleStatus(
                title = getString(R.string.status_idle),
                detail = getString(R.string.status_shared_text_received),
            )
        } else if (detailTextView.text.isNullOrBlank()) {
            setIdleStatus(
                title = getString(R.string.status_idle),
                detail = getString(R.string.result_waiting_hint),
            )
        }
    }

    private fun pasteFromClipboard() {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()

        if (clipText.isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        shareEditText.setText(clipText)
        shareEditText.setSelection(shareEditText.text?.length ?: 0)
    }

    private fun clearShareText() {
        shareInputLayout.error = null
        shareEditText.setText("")
        currentExtraction = null
        resultCard.isVisible = false
        previewRequestToken += 1
        previewVideoView.stopPlayback()
        setIdleStatus(
            title = getString(R.string.status_idle),
            detail = getString(R.string.result_waiting_hint),
        )
    }

    private fun startExtraction() {
        val rawText = shareEditText.text?.toString().orEmpty().trim()
        if (rawText.isEmpty()) {
            shareInputLayout.error = getString(R.string.error_empty_share_text)
            return
        }

        DebugEventLogger.reset(applicationContext)
        DebugEventLogger.log(
            applicationContext,
            source = "MainActivity",
            event = "extraction_started",
            details = mapOf("rawText" to rawText),
        )

        currentExtraction = null
        resultCard.isVisible = false
        setBusyState(
            isBusy = true,
            title = getString(R.string.status_extracting),
            detail = getString(R.string.status_network_hint),
        )

        backgroundExecutor.execute {
            try {
                val extraction = coordinator.extractFromShareText(rawText) { progress ->
                    mainHandler.post { renderProgress(progress) }
                }
                mainHandler.post {
                    DebugEventLogger.log(
                        applicationContext,
                        source = "MainActivity",
                        event = "extraction_succeeded",
                        details = mapOf(
                            "awemeId" to extraction.awemeId,
                            "imageCount" to extraction.imageItems.size,
                            "videoPresent" to (extraction.videoItem != null),
                        ),
                    )
                    currentExtraction = extraction
                    currentImageIndex = 0
                    resultCard.isVisible = true
                    resultSummaryTextView.text = buildResultSummary(extraction)
                    setIdleStatus(
                        title = getString(R.string.status_success),
                        detail = getString(R.string.status_ready_to_preview_save),
                    )
                    selectDefaultTab(extraction)
                    renderCurrentTab()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    DebugEventLogger.log(
                        applicationContext,
                        source = "MainActivity",
                        event = "extraction_failed",
                        details = mapOf("message" to (error.message ?: error::class.java.simpleName)),
                    )
                    currentExtraction = null
                    resultCard.isVisible = false
                    setIdleStatus(
                        title = getString(R.string.status_failed),
                        detail = error.message ?: getString(R.string.status_failed),
                    )
                }
            }
        }
    }

    private fun selectDefaultTab(extraction: ExtractionResult) {
        currentTab = when {
            extraction.imageItems.isNotEmpty() -> ResultTab.IMAGE
            extraction.videoItem != null -> ResultTab.VIDEO
            else -> ResultTab.TITLE
        }
        resultTabLayout.getTabAt(currentTab.position)?.select()
    }

    private fun renderCurrentTab() {
        val extraction = currentExtraction ?: return
        when (currentTab) {
            ResultTab.VIDEO -> renderVideoTab(extraction)
            ResultTab.IMAGE -> renderImageTab(extraction)
            ResultTab.TITLE -> renderTitleTab(extraction)
        }
    }

    private fun renderVideoTab(extraction: ExtractionResult) {
        val videoItem = extraction.videoItem
        titleContentTextView.isVisible = false
        imageControlsContainer.isVisible = false
        saveAllButton.isVisible = false
        savePrimaryButton.text = getString(R.string.label_save_video)
        savePrimaryButton.isEnabled = videoItem != null
        copyLinkButton.isEnabled = true

        if (videoItem == null) {
            showPlaceholder(getString(R.string.placeholder_video_missing))
            resultHintTextView.text = getString(R.string.result_title_summary)
            return
        }

        previewImageView.isVisible = false
        previewVideoView.isVisible = true
        previewPlaceholderTextView.isVisible = true
        previewPlaceholderTextView.text = getString(R.string.placeholder_video_preview)
        previewLoadingBar.isVisible = true
        resultHintTextView.text = getString(R.string.hint_video_save)
        playVideoPreview(videoItem.previewUrl)
    }

    private fun renderImageTab(extraction: ExtractionResult) {
        val imageItem = extraction.imageItems.getOrNull(currentImageIndex)
        titleContentTextView.isVisible = false
        imageControlsContainer.isVisible = extraction.imageItems.isNotEmpty()
        saveAllButton.isVisible = extraction.imageItems.isNotEmpty()
        saveAllButton.isEnabled = extraction.imageItems.isNotEmpty()
        savePrimaryButton.text = getString(R.string.label_save_image, currentImageIndex + 1)
        savePrimaryButton.isEnabled = imageItem != null
        copyLinkButton.isEnabled = true
        previewVideoView.stopPlayback()
        previewVideoView.isVisible = false

        if (imageItem == null) {
            showPlaceholder(getString(R.string.placeholder_image_missing))
            imageIndicatorTextView.text = getString(R.string.label_page_indicator, 0, 0)
            previousImageButton.isEnabled = false
            nextImageButton.isEnabled = false
            resultHintTextView.text = getString(R.string.result_title_summary)
            return
        }

        imageIndicatorTextView.text =
            getString(R.string.label_page_indicator, currentImageIndex + 1, extraction.imageItems.size)
        previousImageButton.isEnabled = currentImageIndex > 0
        nextImageButton.isEnabled = currentImageIndex < extraction.imageItems.lastIndex
        resultHintTextView.text =
            if (imageItem.motionCandidates.isNotEmpty()) {
                getString(R.string.hint_image_motion)
            } else {
                getString(R.string.hint_image_static)
            }
        if (imageItem.motionCandidates.isNotEmpty()) {
            playVideoPreview(imageItem.motionCandidates.firstOrNull())
        } else {
            loadImagePreview(imageItem.previewUrl)
        }
    }

    private fun renderTitleTab(extraction: ExtractionResult) {
        previewRequestToken += 1
        previewVideoView.stopPlayback()
        previewImageView.isVisible = false
        previewVideoView.isVisible = false
        previewLoadingBar.isVisible = false
        previewPlaceholderTextView.isVisible = false
        imageControlsContainer.isVisible = false
        titleContentTextView.isVisible = true
        titleContentTextView.text = extraction.description.ifBlank { getString(R.string.label_title_empty) }
        savePrimaryButton.text = getString(R.string.label_copy_title)
        savePrimaryButton.isEnabled = extraction.description.isNotBlank()
        saveAllButton.isVisible = extraction.videoItem != null || extraction.imageItems.isNotEmpty()
        saveAllButton.isEnabled = saveAllButton.isVisible
        resultHintTextView.text = getString(R.string.hint_title_copy)
    }

    private fun playVideoPreview(previewUrl: String?) {
        if (previewUrl.isNullOrBlank()) {
            showPlaceholder(getString(R.string.placeholder_no_preview))
            return
        }

        previewRequestToken += 1
        previewImageView.isVisible = false
        previewVideoView.isVisible = true
        previewLoadingBar.isVisible = true
        previewPlaceholderTextView.isVisible = true
        previewPlaceholderTextView.text = getString(R.string.placeholder_video_preview)

        previewVideoView.setOnPreparedListener { mediaPlayer ->
            previewLoadingBar.isVisible = false
            previewPlaceholderTextView.isVisible = false
            configurePreparedPlayer(mediaPlayer)
            previewVideoView.start()
        }
        previewVideoView.setOnErrorListener { _, _, _ ->
            showPlaceholder(getString(R.string.placeholder_no_preview))
            true
        }
        previewVideoView.setVideoPath(previewUrl)
        previewVideoView.requestFocus()
    }

    private fun configurePreparedPlayer(mediaPlayer: MediaPlayer) {
        mediaPlayer.isLooping = true
        mediaPlayer.setVolume(0f, 0f)
    }

    private fun loadImagePreview(previewUrl: String?) {
        if (previewUrl.isNullOrBlank()) {
            showPlaceholder(getString(R.string.placeholder_no_preview))
            return
        }

        val requestToken = ++previewRequestToken
        previewVideoView.stopPlayback()
        previewVideoView.isVisible = false
        previewImageView.isVisible = true
        previewImageView.setImageDrawable(null)
        previewPlaceholderTextView.isVisible = true
        previewPlaceholderTextView.text = getString(R.string.placeholder_image_preview)
        previewLoadingBar.isVisible = true

        backgroundExecutor.execute {
            val bitmap = runCatching { fetchPreviewBitmap(previewUrl) }.getOrNull()
            mainHandler.post {
                if (requestToken != previewRequestToken || isFinishing || isDestroyed) {
                    return@post
                }
                previewLoadingBar.isVisible = false
                if (bitmap != null) {
                    previewImageView.setImageBitmap(bitmap)
                    previewPlaceholderTextView.isVisible = false
                } else {
                    showPlaceholder(getString(R.string.placeholder_no_preview))
                }
            }
        }
    }

    private fun fetchPreviewBitmap(previewUrl: String): Bitmap? {
        val connection = (URL(previewUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*,*/*")
            setRequestProperty("Referer", "https://www.douyin.com/")
            setRequestProperty("User-Agent", PREVIEW_USER_AGENT)
        }

        return try {
            if (connection.responseCode !in 200..299) {
                null
            } else {
                connection.inputStream.use(BitmapFactory::decodeStream)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun showPlaceholder(text: String) {
        previewRequestToken += 1
        previewLoadingBar.isVisible = false
        previewImageView.setImageDrawable(null)
        previewImageView.isVisible = false
        previewVideoView.stopPlayback()
        previewVideoView.isVisible = false
        previewPlaceholderTextView.isVisible = true
        previewPlaceholderTextView.text = text
    }

    private fun showRelativeImage(delta: Int) {
        val extraction = currentExtraction ?: return
        if (extraction.imageItems.isEmpty()) {
            return
        }
        currentImageIndex = (currentImageIndex + delta).coerceIn(0, extraction.imageItems.lastIndex)
        renderImageTab(extraction)
    }

    private fun handlePrimaryAction() {
        when (currentTab) {
            ResultTab.VIDEO -> saveVideo()
            ResultTab.IMAGE -> saveCurrentImage()
            ResultTab.TITLE -> copyTitle()
        }
    }

    private fun saveVideo() {
        val extraction = currentExtraction ?: return
        setBusyState(
            isBusy = true,
            title = getString(R.string.status_saving),
            detail = getString(R.string.status_network_hint),
        )
        backgroundExecutor.execute {
            try {
                val result = coordinator.saveVideo(extraction) { progress ->
                    mainHandler.post { renderProgress(progress) }
                }
                mainHandler.post { handleSaveSuccess(result) }
            } catch (error: Exception) {
                mainHandler.post { handleSaveFailure(error) }
            }
        }
    }

    private fun saveCurrentImage() {
        val extraction = currentExtraction ?: return
        if (extraction.imageItems.isEmpty()) {
            return
        }
        setBusyState(
            isBusy = true,
            title = getString(R.string.status_saving),
            detail = getString(R.string.status_network_hint),
        )
        val itemIndex = currentImageIndex
        backgroundExecutor.execute {
            try {
                val result = coordinator.saveImageItem(extraction, itemIndex) { progress ->
                    mainHandler.post { renderProgress(progress) }
                }
                mainHandler.post { handleSaveSuccess(result) }
            } catch (error: Exception) {
                mainHandler.post { handleSaveFailure(error) }
            }
        }
    }

    private fun saveAllMedia() {
        val extraction = currentExtraction ?: return
        setBusyState(
            isBusy = true,
            title = getString(R.string.status_saving),
            detail = getString(R.string.status_network_hint),
        )
        backgroundExecutor.execute {
            try {
                val result = coordinator.saveAll(extraction) { progress ->
                    mainHandler.post { renderProgress(progress) }
                }
                mainHandler.post { handleSaveSuccess(result) }
            } catch (error: Exception) {
                mainHandler.post { handleSaveFailure(error) }
            }
        }
    }

    private fun handleSaveSuccess(result: DownloadResult) {
        setIdleStatus(
            title = getString(R.string.status_success),
            detail = getString(
                R.string.result_saved_summary,
                result.savedAssets.size,
                result.savedRelativePath,
            ),
        )
        Toast.makeText(
            this,
            buildSavedFilesToast(result),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun handleSaveFailure(error: Exception) {
        setIdleStatus(
            title = getString(R.string.status_failed),
            detail = error.message ?: getString(R.string.status_failed),
        )
    }

    private fun copyResolvedLink() {
        val extraction = currentExtraction ?: return
        copyTextToClipboard(extraction.resolvedUrl, getString(R.string.toast_link_copied))
    }

    private fun copyTitle() {
        val extraction = currentExtraction ?: return
        val text = extraction.description.ifBlank { return }
        copyTextToClipboard(text, getString(R.string.toast_title_copied))
    }

    private fun copyTextToClipboard(text: String, toastMessage: String) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
    }

    private fun renderProgress(progress: DownloadProgress) {
        statusTextView.isVisible = true
        detailTextView.isVisible = true
        statusTextView.text = progress.message
        if (progress.totalBytes > 0) {
            actionProgressBar.isVisible = true
            actionProgressBar.isIndeterminate = false
            actionProgressBar.progress =
                ((progress.downloadedBytes * 100) / progress.totalBytes).toInt().coerceIn(0, 100)
            detailTextView.text = "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
        } else {
            actionProgressBar.isVisible = true
            actionProgressBar.isIndeterminate = true
            detailTextView.text = getString(R.string.status_network_hint)
        }
    }

    private fun setBusyState(isBusy: Boolean, title: String, detail: String) {
        shareEditText.isEnabled = !isBusy
        pasteButton.isEnabled = !isBusy
        clearButton.isEnabled = !isBusy
        extractButton.isEnabled = !isBusy
        previousImageButton.isEnabled = !isBusy && previousImageButton.isEnabled
        nextImageButton.isEnabled = !isBusy && nextImageButton.isEnabled
        copyLinkButton.isEnabled = !isBusy && currentExtraction != null
        savePrimaryButton.isEnabled = !isBusy && currentExtraction != null
        saveAllButton.isEnabled = !isBusy && saveAllButton.isVisible
        statusTextView.text = title
        detailTextView.text = detail
        val compactSuccessState = !isBusy && resultCard.isVisible && title == getString(R.string.status_success)
        statusTextView.isVisible = !compactSuccessState && title.isNotBlank()
        detailTextView.isVisible = detail.isNotBlank()
        detailTextView.maxLines = if (compactSuccessState) 1 else 2
        detailTextView.ellipsize = TextUtils.TruncateAt.END
        if (isBusy) {
            actionProgressBar.isVisible = true
            actionProgressBar.isIndeterminate = true
            actionProgressBar.progress = 0
        } else {
            actionProgressBar.isVisible = false
            actionProgressBar.isIndeterminate = true
            actionProgressBar.progress = 0
            renderCurrentTab()
        }
    }

    private fun setIdleStatus(title: String, detail: String) {
        setBusyState(isBusy = false, title = title, detail = detail)
    }

    private fun buildResultSummary(extraction: ExtractionResult): String {
        return when {
            extraction.imageItems.isNotEmpty() -> {
                val motionCount = extraction.imageItems.count { it.motionCandidates.isNotEmpty() }
                getString(
                    R.string.result_image_summary,
                    extraction.imageItems.size,
                    motionCount,
                )
            }

            extraction.videoItem != null -> getString(R.string.result_video_summary)
            else -> getString(R.string.result_title_summary)
        }
    }

    private fun buildExtractionStatusDetail(extraction: ExtractionResult): String {
        return extraction.description.ifBlank { getString(R.string.label_title_empty) }
    }

    private fun buildSavedFilesToast(result: DownloadResult): String {
        val fileLabel = result.savedAssets.take(2).joinToString(separator = "，") { it.fileName }
        return if (fileLabel.isBlank()) {
            getString(R.string.status_success)
        } else {
            fileLabel
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex += 1
        }
        return "${DecimalFormat("0.0").format(value)} ${units[unitIndex]}"
    }

    private enum class ResultTab(val position: Int) {
        VIDEO(0),
        IMAGE(1),
        TITLE(2),
        ;

        companion object {
            fun fromPosition(position: Int): ResultTab {
                return values().firstOrNull { it.position == position } ?: IMAGE
            }
        }
    }

    companion object {
        private const val PREVIEW_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S9210) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
