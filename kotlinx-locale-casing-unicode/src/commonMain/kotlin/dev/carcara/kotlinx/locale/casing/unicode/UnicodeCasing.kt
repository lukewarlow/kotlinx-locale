/*
 * Copyright 2026 Carcara.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.carcara.kotlinx.locale.casing.unicode

import dev.carcara.kotlinx.locale.Locale

/**
 * Full Unicode case conversion, including contextual and language-sensitive mappings.
 *
 * All locales are accepted. [supportedLanguages] enumerates language-specific
 * tailorings; other languages use default Unicode casing, including final sigma.
 * Data initializes independently of formatters and host Unicode versions.
 * Pass `Locale.of("und")` for default Unicode casing regardless of the host locale.
 */
public object UnicodeCasing {
    public val unicodeVersion: String get() = casingDataVersion

    /** Languages with special casing rules, rather than an enumeration of all accepted locales. */
    public val supportedLanguages: Set<String> get() = languages.toSet()
    private val languages = casingLanguages.split(' ').toSet()
    private val lower by lazy { mappings(lowercaseMappings) }
    private val upper by lazy { mappings(uppercaseMappings) }
    private val properties by lazy { CasingProperties(casingProperties) }
    private val conditional by lazy {
        conditionalMappings.split('|').map { encoded ->
            val fields = encoded.split(';')
            var required = 0
            var forbidden = 0
            for (condition in fields[4].split(',').filter { it.isNotEmpty() }) {
                when (condition) {
                    "Final_Sigma" -> {
                        required = required or 1
                        forbidden = forbidden or 32
                    }
                    "After_I" -> required = required or 2
                    "After_Soft_Dotted" -> required = required or 4
                    "More_Above" -> required = required or 8
                    "Before_Dot" -> required = required or 16
                    "Not_Before_Dot" -> forbidden = forbidden or 16
                    else -> error("Unknown generated casing context $condition")
                }
            }
            CasingRule(fields[0].toInt(36), decodeMapping(fields[1]), decodeMapping(fields[2]), fields[3], required, forbidden)
        }.groupBy { it.codePoint }
    }

    /** Preserves unpaired surrogates; evaluates context in the original, unnormalized input. */
    public fun lowercase(text: String, locale: Locale): String = convert(text, locale, uppercase = false)

    /** May expand characters to multiple code points; preserves unpaired surrogates. */
    public fun uppercase(text: String, locale: Locale): String = convert(text, locale, uppercase = true)

    private fun convert(text: String, locale: Locale, uppercase: Boolean): String {
        if (text.isEmpty()) return text
        val points = IntArray(text.length)
        var size = 0
        var offset = 0
        while (offset < text.length) {
            val first = text[offset++]
            points[size++] = if (first.isHighSurrogate() && offset < text.length && text[offset].isLowSurrogate()) {
                0x10000 + ((first.code - 0xD800) shl 10) + (text[offset++].code - 0xDC00)
            } else {
                first.code
            }
        }
        val props = IntArray(size) { properties.valueOf(points[it]) }
        val flags = IntArray(size)
        var beforeCased = false
        var afterI = false
        var afterSoftDotted = false
        for (i in 0 until size) {
            flags[i] = (if (beforeCased) 1 else 0) or (if (afterI) 2 else 0) or (if (afterSoftDotted) 4 else 0)
            val p = props[i]
            val ccc = p ushr 3
            if (p and 2 == 0) beforeCased = p and 1 != 0
            if (points[i] == 0x49) {
                afterI = true
            } else if (ccc == 0 || ccc == 230) {
                afterI = false
            }
            if (p and 4 != 0) {
                afterSoftDotted = true
            } else if (ccc == 0 || ccc == 230) {
                afterSoftDotted = false
            }
        }
        var afterCased = false
        var moreAbove = false
        var beforeDot = false
        for (i in size - 1 downTo 0) {
            flags[i] = flags[i] or (if (afterCased) 32 else 0) or (if (moreAbove) 8 else 0) or (if (beforeDot) 16 else 0)
            val p = props[i]
            val ccc = p ushr 3
            if (p and 2 == 0) afterCased = p and 1 != 0
            if (ccc == 0) {
                moreAbove = false
            } else if (ccc == 230) {
                moreAbove = true
            }
            if (points[i] == 0x307) {
                beforeDot = true
            } else if (ccc == 0 || ccc == 230) {
                beforeDot = false
            }
        }
        val mappings = if (uppercase) upper else lower
        return buildString(text.length) {
            for (i in 0 until size) {
                val cp = points[i]
                val rule = conditional[cp]?.firstOrNull {
                    (it.language.isEmpty() || it.language == locale.language) &&
                        flags[i] and it.required == it.required &&
                        flags[i] and it.forbidden == 0
                }
                val mapped = if (rule != null) {
                    if (uppercase) rule.upper else rule.lower
                } else {
                    mappings[cp]
                }
                if (mapped == null) appendCodePoint(this, cp) else append(mapped)
            }
        }
    }
}

/** Full lowercase conversion. Untailored languages use the pinned default Unicode rules. */
public fun casingLowercase(text: String, locale: Locale = Locale.current): String = UnicodeCasing.lowercase(text, locale)

/** Full uppercase conversion, including mappings which expand to multiple characters. */
public fun casingUppercase(text: String, locale: Locale = Locale.current): String = UnicodeCasing.uppercase(text, locale)

private data class CasingRule(
    val codePoint: Int,
    val lower: String,
    val upper: String,
    val language: String,
    val required: Int,
    val forbidden: Int,
)

private fun mappings(encoded: String): Map<Int, String> = encoded.split(';').associate {
    it.substringBefore(':').toInt(36) to decodeMapping(it.substringAfter(':'))
}

private fun decodeMapping(encoded: String): String = buildString {
    if (encoded.isNotEmpty()) for (point in encoded.split('.')) appendCodePoint(this, point.toInt(36))
}

private fun appendCodePoint(builder: StringBuilder, cp: Int) {
    if (cp < 0x10000) {
        builder.append(cp.toChar())
    } else {
        builder.append((0xD800 + ((cp - 0x10000) ushr 10)).toChar())
        builder.append((0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar())
    }
}

private class CasingProperties(encoded: String) {
    private val rows = encoded.split(';').map { row -> row.split(',').map { it.toInt(36) }.toIntArray() }.toTypedArray()
    fun valueOf(cp: Int): Int {
        var low = 0
        var high = rows.lastIndex
        while (low <= high) {
            val middle = low + (high - low) / 2
            val row = rows[middle]
            when {
                cp < row[0] -> high = middle - 1
                cp > row[0] + row[1] -> low = middle + 1
                else -> return row[2]
            }
        }
        return 0
    }
}
