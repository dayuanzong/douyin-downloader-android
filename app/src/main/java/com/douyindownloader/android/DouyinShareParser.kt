package com.douyindownloader.android

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class DouyinShareParser {
    private val urlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val awemePathPatterns = listOf(
        Regex("/(?:video|note)/(\\d+)", RegexOption.IGNORE_CASE),
        Regex("/share/(?:video|note|slides)/(\\d+)", RegexOption.IGNORE_CASE),
    )
    private val trailingChars = charArrayOf(
        '，',
        '。',
        '！',
        '？',
        '；',
        ',',
        '.',
        '!',
        '?',
        ';',
        ')',
        ']',
        '}',
        '>',
        '》',
        '】',
        '"',
        '\'',
    )
    private val awemeQueryKeys = listOf("modal_id", "aweme_id", "item_id")

    fun extractShareUrl(text: String): String? {
        val stripped = text.trim()
        if (stripped.isEmpty()) {
            return null
        }

        val matched = urlPattern.find(stripped)?.value ?: return null
        return matched.trimEnd(*trailingChars)
    }

    fun extractAwemeId(urlOrText: String): String? {
        val candidate = extractShareUrl(urlOrText) ?: urlOrText.trim().takeIf { it.startsWith("http") } ?: return null
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        val path = parsed.path.orEmpty()

        for (pattern in awemePathPatterns) {
            val match = pattern.find(path)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        val queryValues = parseQuery(parsed.rawQuery)
        for (key in awemeQueryKeys) {
            val value = queryValues[key]
            if (!value.isNullOrBlank() && value.all(Char::isDigit)) {
                return value
            }
        }

        return null
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }

        return rawQuery
            .split("&")
            .mapNotNull { segment ->
                val keyValue = segment.split("=", limit = 2)
                val key = keyValue.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = keyValue.getOrNull(1).orEmpty()
                key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }
            .toMap()
    }
}
