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
import dev.carcara.kotlinx.locale.segmentation.TextSegment
import dev.carcara.kotlinx.locale.segmentation.TextSegments
import dev.carcara.kotlinx.locale.segmentation.conformance.GRAPHEME_BREAK_VERSION
import dev.carcara.kotlinx.locale.segmentation.conformance.graphemeBreakCases
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertFailsWith
import dev.carcara.kotlinx.locale.test.assertNull
import dev.carcara.kotlinx.locale.test.assertTrue

val UnicodeSegmentationTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    val locale = Locale.forLanguageTag("en")
    val segmenter = requireNotNull(UnicodeSegmentation.segmenterOrNull(locale, SegmentationGranularity.GRAPHEME))

    test("capabilities and locale-independent rules") {
        assertEquals(SegmentationGranularity.entries.toSet(), UnicodeSegmentation.supportedGranularities)
        assertTrue(UnicodeSegmentation.supportedLocales.isEmpty())
        for (granularity in SegmentationGranularity.entries) {
            assertEquals(SegmentationLocaleCoverage.Universal, UnicodeSegmentation.localeCoverageOrNull(granularity))
        }
        assertEquals(GRAPHEME_BREAK_VERSION, UnicodeSegmentation.unicodeVersion)
        val japanese = Locale.forLanguageTag("ja-u-ca-japanese")
        val other = requireNotNull(UnicodeSegmentation.segmenterOrNull(japanese, SegmentationGranularity.GRAPHEME))
        assertEquals(japanese, other.locale)
        assertEquals(SegmentationGranularity.GRAPHEME, other.granularity)
        assertEquals(segmenter.segment("a\u0301").toList(), other.segment("a\u0301").toList())
    }

    test("automatic initialization and UTF-16 containing") {
        // No person-name module or internal table installation is on this test's classpath.
        val result = segmenter.segment("a👩‍💻b")
        assertEquals(listOf(TextSegment(0, 1, null), TextSegment(1, 6, null), TextSegment(6, 7, null)), result.toList())
        for (i in 1..5) assertEquals(TextSegment(1, 6, null), result.containingOrNull(i))
        assertPartition(result)
    }

    test("empty strings and malformed UTF-16 are preserved") {
        val empty = segmenter.segment("")
        assertTrue(empty.none())
        assertNull(empty.containingOrNull(0))
        assertNull(empty.containingOrNull(-1))
        val iterator = empty.iterator()
        assertFailsWith<NoSuchElementException> { iterator.next() }
        for (text in listOf("\uD800", "\uDC00", "a\uD800b\uDC00", "\uD800\uD800", "\uDC00\u0301", " \r\n!", "a\u0301", "🇬🇧🇫🇷🇯")) {
            assertPartition(segmenter.segment(text))
        }
        assertEquals(listOf(TextSegment(0, 1, null)), segmenter.segment("\uD800").toList())
    }

    test("iterators and results have independent state") {
        val first = segmenter.segment("a\u0301👩‍💻b")
        val second = segmenter.segment("xy")
        val a = first.iterator()
        val b = first.iterator()
        assertEquals(a.next(), b.next())
        val middle = a.next()
        assertEquals(TextSegment(0, 1, null), second.iterator().next())
        assertEquals(middle, first.containingOrNull(3))
        assertEquals(middle, b.next())
        assertEquals(a.next(), b.next())
        assertTrue(!a.hasNext() && !b.hasNext())
        assertFailsWith<NoSuchElementException> { a.next() }
        assertPartition(first)
        assertPartition(second)
    }

    test("all Unicode grapheme fixtures through the public API") {
        assertTrue(graphemeBreakCases.size > 1000)
        for (case in graphemeBreakCases) {
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
            val actual = listOf(0) + result.map { it.endExclusive }
            assertEquals(expected, actual, "Unicode $GRAPHEME_BREAK_VERSION: $case")
            assertPartition(result)
        }
    }

    test("long inputs and reverse repeated random access") {
        val result = segmenter.segment("a\u0301👩‍💻🇬🇧".repeat(2000))
        val segments = result.toList()
        assertEquals(6000, segments.size)
        repeat(3) {
            for (segment in segments.asReversed()) {
                assertEquals(segment, result.containingOrNull(segment.endExclusive - 1))
                assertEquals(segment, result.containingOrNull(segment.start))
            }
        }
    }
}

internal fun assertPartition(result: TextSegments, granularity: SegmentationGranularity = SegmentationGranularity.GRAPHEME) {
    var end = 0
    val restored = StringBuilder()
    for (segment in result) {
        assertEquals(end, segment.start)
        assertTrue(segment.endExclusive > segment.start)
        if (granularity == SegmentationGranularity.WORD) {
            assertTrue(segment.isWordLike != null)
        } else {
            assertNull(segment.isWordLike)
        }
        restored.append(result.input.substring(segment.start, segment.endExclusive))
        for (index in segment.start until segment.endExclusive) {
            assertEquals(segment, result.containingOrNull(index))
        }
        end = segment.endExclusive
    }
    assertEquals(result.input.length, end)
    assertEquals(result.input, restored.toString())
    assertNull(result.containingOrNull(-1))
    assertNull(result.containingOrNull(Int.MIN_VALUE))
    assertNull(result.containingOrNull(result.input.length))
    assertNull(result.containingOrNull(Int.MAX_VALUE))
}
