package com.douyindownloader.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DouyinShareParserTest {
    private val parser = DouyinShareParser()

    @Test
    fun extractShareUrl_fromShareText() {
        val input =
            """{6.69 复制打开抖音，看看【Halcyon的作品】"不同的人看待事情的思维是不一样的“ https://v.douyin.com/CIz48j5c-74/ a@A.go iPX:/ 07/06 }"""

        assertEquals("https://v.douyin.com/CIz48j5c-74/", parser.extractShareUrl(input))
    }

    @Test
    fun extractAwemeId_fromVideoUrl() {
        val input = "https://www.douyin.com/video/7489654321123456789"

        assertEquals("7489654321123456789", parser.extractAwemeId(input))
    }

    @Test
    fun extractAwemeId_fromSlidesShareUrl() {
        val input = "https://www.iesdouyin.com/share/slides/7627888901880992422/?from_ssr=1"

        assertEquals("7627888901880992422", parser.extractAwemeId(input))
    }

    @Test
    fun extractAwemeId_returnsNullWhenMissing() {
        assertNull(parser.extractAwemeId("这是一段没有链接的文本"))
    }
}
