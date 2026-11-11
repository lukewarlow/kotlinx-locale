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

package dev.carcara.kotlinx.locale.segmentation.unicode

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.segmentation.SegmentationGranularity
import dev.carcara.kotlinx.locale.segmentation.SegmentationLocaleCoverage
import dev.carcara.kotlinx.locale.segmentation.SegmentationSource
import dev.carcara.kotlinx.locale.segmentation.conformance.GRAPHEME_BREAK_VERSION
import dev.carcara.kotlinx.locale.segmentation.conformance.SENTENCE_BREAK_VERSION
import dev.carcara.kotlinx.locale.segmentation.conformance.WORD_BREAK_VERSION
import dev.carcara.kotlinx.locale.segmentation.conformance.sentenceBreakCases
import dev.carcara.kotlinx.locale.segmentation.conformance.wordBreakCases
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

val WordSentenceSegmentationTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("all word boundaries from the matching Unicode release") {
        assertTrue(wordBreakCases.size > 1800)
        checkBoundaryFixtures(wordBreakCases, WORD_BREAK_VERSION, SegmentationGranularity.WORD)
    }

    test("all sentence boundaries from the matching Unicode release") {
        assertTrue(sentenceBreakCases.size > 500)
        checkBoundaryFixtures(sentenceBreakCases, SENTENCE_BREAK_VERSION, SegmentationGranularity.SENTENCE)
    }

    test("explicit universal coverage and version through the source interface") {
        val source: SegmentationSource = UnicodeSegmentation
        assertEquals(GRAPHEME_BREAK_VERSION, source.unicodeVersion)
        assertEquals(WORD_BREAK_VERSION, source.unicodeVersion)
        assertEquals(SENTENCE_BREAK_VERSION, source.unicodeVersion)
        for (granularity in SegmentationGranularity.entries) {
            assertEquals(SegmentationLocaleCoverage.Universal, source.localeCoverageOrNull(granularity))
            for (tag in listOf("en", "th", "ja", "zh", "zz-ZZ")) {
                val locale = Locale.forLanguageTag(tag)
                val segmenter = requireNotNull(source.segmenterOrNull(locale, granularity))
                assertEquals(locale, segmenter.locale)
                assertEquals(granularity, segmenter.granularity)
            }
        }
        val enumerated = SegmentationLocaleCoverage.Enumerated(setOf(Locale.forLanguageTag("en")))
        assertEquals(setOf(Locale.forLanguageTag("en")), enumerated.locales)
    }

    test("word-like classification includes letters numbers kana and ideographs") {
        val segmenter = UnicodeSegmentation.segmenterOrNull(Locale.forLanguageTag("en"), SegmentationGranularity.WORD)
        for ((text, expected) in listOf(
            "hello" to true, "123.45" to true, "カタカナ" to true, "ひらがな" to true,
            "漢字" to true, "ภาษาไทย" to true, "²" to true, "א'" to true,
            " " to false, "!" to false, "___" to false, "👩‍💻" to false, "🇬🇧" to false,
            "\u0301" to false, "\uD800" to false,
        )) {
            val result = segmenter.segment(text)
            assertTrue(result.all { it.isWordLike == expected }, text)
            assertPartition(result, SegmentationGranularity.WORD)
        }
        val mixed = segmenter.segment("Hello, 世界! 12.5")
        assertEquals(
            listOf("Hello", ",", " ", "世", "界", "!", " ", "12.5"),
            mixed.map { mixed.input.substring(it.start, it.endExclusive) },
        )
        assertEquals(listOf(true, false, false, true, true, false, false, true), mixed.map { it.isWordLike })
    }

    test("sentence terminators include closing punctuation and trailing whitespace") {
        val segmenter = UnicodeSegmentation.segmenterOrNull(Locale.forLanguageTag("en"), SegmentationGranularity.SENTENCE)
        for ((text, expected) in listOf(
            "Hello!  Next?\r\nLast." to listOf("Hello!  ", "Next?\r\n", "Last."),
            "A 3.14 value. lower case" to listOf("A 3.14 value. lower case"),
            "Hello. Next." to listOf("Hello. ", "Next."),
            "One\nTwo" to listOf("One\n", "Two"),
            "Hi!\"  Next." to listOf("Hi!\"  ", "Next."),
        )) {
            val result = segmenter.segment(text)
            assertEquals(expected, result.map { text.substring(it.start, it.endExclusive) }, text)
            assertPartition(result, SegmentationGranularity.SENTENCE)
        }
    }

    test("all granularities preserve malformed UTF-16 and independent traversal") {
        for (granularity in SegmentationGranularity.entries) {
            val segmenter = UnicodeSegmentation.segmenterOrNull(Locale.forLanguageTag("th"), granularity)
            for (text in listOf("", "\uD800", "\uDC00", "a\uD800b\uDC00", " \r\n!", "a\u0301👩‍💻🇬🇧")) {
                val result = segmenter.segment(text)
                assertPartition(result, granularity)
                val a = result.iterator()
                val b = result.iterator()
                val other = segmenter.segment("Other.")
                while (a.hasNext()) {
                    val next = a.next()
                    other.toList()
                    assertEquals(next, result.containingOrNull(next.start))
                    assertEquals(next, b.next())
                }
                assertTrue(!b.hasNext())
            }
        }
    }

    test("long ignored and lookahead runs use cached linear contexts") {
        for (granularity in listOf(SegmentationGranularity.WORD, SegmentationGranularity.SENTENCE)) {
            val segmenter = UnicodeSegmentation.segmenterOrNull(Locale.forLanguageTag("en"), granularity)
            for (text in listOf("a" + "\u0301".repeat(20000) + "b", "End." + " ".repeat(20000) + "lower")) {
                val result = segmenter.segment(text)
                val segments = result.toList()
                repeat(3) {
                    for (segment in segments.asReversed()) {
                        assertEquals(segment, result.containingOrNull(segment.start))
                        assertEquals(segment, result.containingOrNull(segment.endExclusive - 1))
                    }
                }
                assertPartition(result, granularity)
            }
        }
    }
}

private fun checkBoundaryFixtures(cases: List<String>, version: String, granularity: SegmentationGranularity) {
    assertEquals(UnicodeSegmentation.unicodeVersion, version)
    val segmenter = UnicodeSegmentation.segmenterOrNull(Locale.forLanguageTag("en"), granularity)
    for (case in cases) {
        val (points, breaks) = case.split(':')
        val offsets = ArrayList<Int>()
        val text = buildString {
            offsets.add(0)
            for (point in points.split(',')) {
                val cp = point.toInt(36)
                if (cp < 0x10000) {
                    append(cp.toChar())
                } else {
                    val value = cp - 0x10000
                    append((0xD800 + (value shr 10)).toChar())
                    append((0xDC00 + (value and 0x3FF)).toChar())
                }
                offsets.add(length)
            }
        }
        val expected = breaks.split(',').filter(String::isNotEmpty).map { offsets[it.toInt(36)] }
        val result = segmenter.segment(text)
        assertEquals(expected, listOf(0) + result.map { it.endExclusive }, "Unicode $version $granularity: $case")
        assertPartition(result, granularity)
    }
}
