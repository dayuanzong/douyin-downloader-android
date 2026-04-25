package com.douyindownloader.android

import org.bouncycastle.crypto.digests.SM3Digest
import java.net.URLEncoder
import kotlin.math.floor
import kotlin.random.Random

object ABogusSigner {
    private const val END_STRING = "cus"
    private const val RC4_KEY = "y"
    private const val DEFAULT_BROWSER =
        "1536|742|1536|864|0|0|0|0|1536|864|1536|864|1536|742|24|24|MacIntel"
    private const val ALPHABET_S4 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"

    private val uaCode = intArrayOf(
        76, 98, 15, 131, 97, 245, 224, 133,
        122, 199, 241, 166, 79, 34, 90, 191,
        128, 126, 122, 98, 66, 11, 14, 40,
        49, 110, 110, 173, 67, 96, 138, 252,
    )
    private val browserCode = DEFAULT_BROWSER.map { it.code }.toIntArray()

    fun generateValue(
        params: LinkedHashMap<String, String>,
        method: String = "GET",
        startTimeMs: Long = 0L,
        endTimeMs: Long = 0L,
        randomNum1: Double? = null,
        randomNum2: Double? = null,
        randomNum3: Double? = null,
    ): String {
        val string1 = generateString1(randomNum1, randomNum2, randomNum3)
        val string2 = generateString2(
            urlParams = encodeParams(params),
            method = method,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
        )
        return generateResult(string1 + string2, ALPHABET_S4)
    }

    private fun encodeParams(params: LinkedHashMap<String, String>): String {
        return params.entries.joinToString("&") { entry ->
            "${entry.key.urlEncode()}=${entry.value.urlEncode()}"
        }
    }

    private fun generateString1(
        randomNum1: Double? = null,
        randomNum2: Double? = null,
        randomNum3: Double? = null,
    ): String {
        return fromCharCode(list1(randomNum1)) +
            fromCharCode(list2(randomNum2)) +
            fromCharCode(list3(randomNum3))
    }

    private fun generateString2(
        urlParams: String,
        method: String,
        startTimeMs: Long,
        endTimeMs: Long,
    ): String {
        val values = generateString2List(urlParams, method, startTimeMs, endTimeMs).toMutableList()
        val endCheck = endCheckNum(values)
        values += browserCode.toList()
        values += endCheck
        return rc4Encrypt(fromCharCode(values), RC4_KEY)
    }

    private fun generateString2List(
        urlParams: String,
        method: String,
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<Int> {
        val start = if (startTimeMs != 0L) startTimeMs else System.currentTimeMillis()
        val end = if (endTimeMs != 0L) endTimeMs else start + Random.nextLong(4L, 9L)
        val paramsArray = generateParamsCode(urlParams)
        val methodArray = generateMethodCode(method)
        return list4(
            ((end ushr 24) and 255).toInt(),
            paramsArray[21],
            uaCode[23],
            ((end ushr 16) and 255).toInt(),
            paramsArray[22],
            uaCode[24],
            ((end ushr 8) and 255).toInt(),
            (end and 255).toInt(),
            ((start ushr 24) and 255).toInt(),
            ((start ushr 16) and 255).toInt(),
            ((start ushr 8) and 255).toInt(),
            (start and 255).toInt(),
            methodArray[21],
            methodArray[22],
            (end ushr 32).toInt(),
            (start ushr 32).toInt(),
            DEFAULT_BROWSER.length,
        )
    }

    private fun generateParamsCode(params: String): IntArray = sm3ToArray(sm3ToArray(params + END_STRING))

    private fun generateMethodCode(method: String): IntArray = sm3ToArray(sm3ToArray(method + END_STRING))

    private fun sm3ToArray(data: String): IntArray = sm3ToArray(data.toByteArray())

    private fun sm3ToArray(data: IntArray): IntArray {
        val bytes = ByteArray(data.size) { index -> (data[index] and 255).toByte() }
        return sm3ToArray(bytes)
    }

    private fun sm3ToArray(bytes: ByteArray): IntArray {
        val digest = SM3Digest()
        digest.update(bytes, 0, bytes.size)
        val result = ByteArray(digest.digestSize)
        digest.doFinal(result, 0)
        return IntArray(result.size) { index -> result[index].toInt() and 255 }
    }

    private fun list1(randomNum: Double?, a: Int = 170, b: Int = 85, c: Int = 45): IntArray {
        return randomList(randomNum, a, b, 1, 2, 5, c and a)
    }

    private fun list2(randomNum: Double?, a: Int = 170, b: Int = 85): IntArray {
        return randomList(randomNum, a, b, 1, 0, 0, 0)
    }

    private fun list3(randomNum: Double?, a: Int = 170, b: Int = 85): IntArray {
        return randomList(randomNum, a, b, 1, 0, 5, 0)
    }

    private fun randomList(
        randomNum: Double?,
        a: Int,
        b: Int,
        d: Int,
        e: Int,
        f: Int,
        g: Int,
    ): IntArray {
        val value = randomNum ?: (Random.nextDouble() * 10000.0)
        val whole = floor(value).toInt()
        val low = whole and 255
        val high = whole shr 8
        return intArrayOf(
            (low and a) or d,
            (low and b) or e,
            (high and a) or f,
            (high and b) or g,
        )
    }

    private fun list4(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        e: Int,
        f: Int,
        g: Int,
        h: Int,
        i: Int,
        j: Int,
        k: Int,
        m: Int,
        n: Int,
        o: Int,
        p: Int,
        q: Int,
        r: Int,
    ): List<Int> {
        return listOf(
            44, a, 0, 0, 0, 0, 24, b, n, 0, c, d, 0, 0, 0, 1, 0, 239, e, o, f, g,
            0, 0, 0, 0, h, 0, 0, 14, i, j, 0, k, m, 3, p, 1, q, 1, r, 0, 0, 0,
        )
    }

    private fun endCheckNum(values: List<Int>): Int {
        var result = 0
        values.forEach { result = result xor it }
        return result
    }

    private fun fromCharCode(values: IntArray): String = fromCharCode(values.asList())

    private fun fromCharCode(values: List<Int>): String {
        return buildString(values.size) {
            values.forEach { append((it and 255).toChar()) }
        }
    }

    private fun rc4Encrypt(plaintext: String, key: String): String {
        val state = IntArray(256) { it }
        var j = 0
        for (index in 0 until 256) {
            j = (j + state[index] + key[index % key.length].code) % 256
            val temp = state[index]
            state[index] = state[j]
            state[j] = temp
        }

        var i = 0
        j = 0
        val output = CharArray(plaintext.length)
        plaintext.forEachIndexed { index, char ->
            i = (i + 1) % 256
            j = (j + state[i]) % 256
            val temp = state[i]
            state[i] = state[j]
            state[j] = temp
            val t = (state[i] + state[j]) % 256
            output[index] = (state[t] xor char.code).toChar()
        }
        return String(output)
    }

    private fun generateResult(source: String, alphabet: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < source.length) {
            val chunk = when {
                index + 2 < source.length -> {
                    (source[index].code shl 16) or
                        (source[index + 1].code shl 8) or
                        source[index + 2].code
                }

                index + 1 < source.length -> {
                    (source[index].code shl 16) or (source[index + 1].code shl 8)
                }

                else -> {
                    source[index].code shl 16
                }
            }

            val masks = intArrayOf(0xFC0000, 0x03F000, 0x0FC0, 0x3F)
            val shifts = intArrayOf(18, 12, 6, 0)
            for (position in shifts.indices) {
                val shift = shifts[position]
                if (shift == 6 && index + 1 >= source.length) break
                if (shift == 0 && index + 2 >= source.length) break
                result.append(alphabet[(chunk and masks[position]) shr shift])
            }
            index += 3
        }
        val paddingLength = (4 - result.length % 4) % 4
        repeat(paddingLength) { result.append('=') }
        return result.toString()
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}
