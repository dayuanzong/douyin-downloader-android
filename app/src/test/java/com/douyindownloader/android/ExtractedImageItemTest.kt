package com.douyindownloader.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractedImageItemTest {
    @Test
    fun primaryAssetKind_prefersMotionWhenMotionCandidateExists() {
        val item = ExtractedImageItem(
            index = 0,
            previewUrl = "https://example.com/preview.mp4",
            imageCandidates = listOf("https://example.com/live-photo.webp"),
            motionCandidates = listOf("https://example.com/live-photo.mp4"),
            imageFileName = "sample_01.webp",
            motionFileName = "sample_01_motion.mp4",
        )

        assertEquals(SavedAssetKind.MOTION, item.primaryAssetKind)
        assertEquals("sample_01_motion.mp4", item.resolvedMotionFileName)
    }

    @Test
    fun primaryAssetKind_fallsBackToImageWhenNoMotionCandidateExists() {
        val item = ExtractedImageItem(
            index = 1,
            previewUrl = "https://example.com/static.jpg",
            imageCandidates = listOf("https://example.com/static.jpg"),
            motionCandidates = emptyList(),
            imageFileName = "sample_02.jpg",
            motionFileName = null,
        )

        assertEquals(SavedAssetKind.IMAGE, item.primaryAssetKind)
        assertEquals("douyin_media_02_motion.mp4", item.resolvedMotionFileName)
    }
}
