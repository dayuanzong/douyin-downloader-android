package com.douyindownloader.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DouyinNetworkInstrumentedTest {
    @Test
    fun extractsVideoThroughWebDetailApi() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DouyinDownloadCoordinator(appContext)

        val result = coordinator.extractFromShareText("https://v.douyin.com/Vv8WuBzAlrk/") { }

        assertEquals("7626246835962727707", result.awemeId)
        assertTrue(result.imageItems.isEmpty())
        assertNotNull(result.videoItem)
        assertTrue(result.videoItem!!.candidateUrls.isNotEmpty())
    }

    @Test
    fun extractsImageNoteThroughWebDetailApi() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DouyinDownloadCoordinator(appContext)

        val result = coordinator.extractFromShareText("https://v.douyin.com/RllB83OlZ3Q/") { }

        assertEquals("7630290857161395057", result.awemeId)
        assertTrue(result.imageItems.isNotEmpty())
        assertTrue(result.imageItems.first().imageCandidates.isNotEmpty())
    }
}
