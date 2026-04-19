package com.douyindownloader.android

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameSanitizerTest {
    @Test
    fun buildVideoFileName_alwaysUsesMp4() {
        assertEquals("示例作品_123456.mp4", FileNameSanitizer.buildVideoFileName("示例作品", "123456"))
    }

    @Test
    fun buildImageFileName_usesDetectedExtension() {
        val fileName = FileNameSanitizer.buildImageFileName(
            baseName = "图文示例_123456",
            index = 1,
            candidateUrls = listOf("https://example.com/sample.webp"),
        )

        assertEquals("图文示例_123456_01.webp", fileName)
    }
}
