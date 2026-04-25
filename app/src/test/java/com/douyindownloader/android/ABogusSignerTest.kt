package com.douyindownloader.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ABogusSignerTest {
    @Test
    fun generatesExpectedSignatureForStableInputs() {
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
            "aweme_id" to "7626246835962727707",
            "msToken" to "",
        )

        val actual = ABogusSigner.generateValue(
            params = params,
            startTimeMs = 1_711_111_111_111L,
            endTimeMs = 1_711_111_111_117L,
            randomNum1 = 1234.0,
            randomNum2 = 2234.0,
            randomNum3 = 3234.0,
        )

        assertEquals(
            "E7mhBQufdDdNDDyg5AQLfY3q6IfVYmsR0SVkMD2fgBDOUy39HMP09exoS1kvFyfjLT/AIeEjy4hbT3ohrQ2y0Hwf9W0L/25ksDSkKl5Q5xSSs1X9eghgJ04qmkt5SMx2RvB-rOXmqhZHKRbp09oHmhK4b1dzFgf3qJLzUE==",
            actual,
        )
    }
}
