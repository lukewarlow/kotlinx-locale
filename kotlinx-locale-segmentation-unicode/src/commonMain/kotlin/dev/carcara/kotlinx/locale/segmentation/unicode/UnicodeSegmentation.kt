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

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.segmentation.SegmentationGranularity
import dev.carcara.kotlinx.locale.segmentation.SegmentationLocaleCoverage
import dev.carcara.kotlinx.locale.segmentation.SegmentationSource
import dev.carcara.kotlinx.locale.segmentation.TextSegment
import dev.carcara.kotlinx.locale.segmentation.TextSegmenter
import dev.carcara.kotlinx.locale.segmentation.TextSegments

/**
 * Default Unicode grapheme, word and sentence boundaries per https://unicode.org/reports/tr29/.
 *
 * The backend owns its versioned property tables and initializes them without
 * person-name formatting or mutable installation state. All locales use the
 * same default rules: no dictionaries, abbreviation suppression or other locale
 * tailoring is supplied. In particular, default word boundaries do not provide
 * linguistic word detection for Thai, Lao, Khmer, Myanmar, Chinese or Japanese.
 * [localeCoverageOrNull] reports universal default-rule coverage; [supportedLocales]
 * remains an empty enumeration for compatibility with [SegmentationSource].
 *
 * A word segment is word-like if it contains a Unicode Letter or Number, or a
 * code point with Word_Break ALetter, Hebrew_Letter, Numeric or Katakana.
 * Thus kana and ideographs count as word-like even where default rules break
 * between them. Segments without any of those properties are not word-like,
 * including ordinary punctuation, whitespace, emoji and isolated marks.
 * This is a classification policy, not dictionary segmentation.
 */
public object UnicodeSegmentation : SegmentationSource {
    private val graphemeRules = GraphemeBoundaryRules(graphemeBreakTable)
    private val wordRules = WordBoundaryRules(wordBreakProperties)
    private val sentenceRules = SentenceBoundaryRules(sentenceBreakProperties)

    public override val unicodeVersion: String = segmentationUnicodeVersion
    public override val supportedLocales: Set<Locale> = emptySet()
    public override val supportedGranularities: Set<SegmentationGranularity> = SegmentationGranularity.entries.toSet()

    public override fun localeCoverageOrNull(granularity: SegmentationGranularity): SegmentationLocaleCoverage =
        SegmentationLocaleCoverage.Universal

    public override fun segmenterOrNull(locale: Locale, granularity: SegmentationGranularity): TextSegmenter =
        UnicodeTextSegmenter(locale, granularity)

    private class UnicodeTextSegmenter(override val locale: Locale, override val granularity: SegmentationGranularity) : TextSegmenter {
        override fun segment(text: String): TextSegments {
            // Scan from the beginning once, preserving the context needed by UAX #29.
            val result = when (granularity) {
                SegmentationGranularity.GRAPHEME -> graphemeRules.segment(text)
                SegmentationGranularity.WORD -> wordRules.segment(text)
                SegmentationGranularity.SENTENCE -> sentenceRules.segment(text)
            }
            return UnicodeTextSegments(text, result.boundaries, result.wordLike)
        }
    }
}

/**
 * Returns a reusable segmenter using default Unicode rules for every [locale].
 * All three granularities are available. The requested locale is retained on the
 * segmenter; the Unicode backend applies no locale-specific tailoring.
 */
public fun segmentationSegmenter(
    locale: Locale = Locale.current,
    granularity: SegmentationGranularity = SegmentationGranularity.GRAPHEME,
): TextSegmenter = UnicodeSegmentation.segmenterOrNull(locale, granularity)

/**
 * Segments [value] using default Unicode rules for every [locale].
 * The result preserves the input and reports UTF-16 ranges, with cached boundaries
 * for iteration and random access. Empty input produces no segments.
 */
public fun segmentationSegments(
    value: String,
    locale: Locale = Locale.current,
    granularity: SegmentationGranularity = SegmentationGranularity.GRAPHEME,
): TextSegments = segmentationSegmenter(locale, granularity).segment(value)

private class UnicodeTextSegments(override val input: String, private val boundaries: IntArray, private val wordLike: BooleanArray?) :
    TextSegments {
    override fun containingOrNull(utf16Index: Int): TextSegment? {
        if (utf16Index < 0 || utf16Index >= input.length) return null
        var low = 0
        var high = boundaries.lastIndex
        while (low + 1 < high) {
            val middle = low + (high - low) / 2
            if (boundaries[middle] <= utf16Index) low = middle else high = middle
        }
        return segmentAt(low)
    }

    override fun iterator(): Iterator<TextSegment> = object : Iterator<TextSegment> {
        private var index = 0

        override fun hasNext(): Boolean = index < boundaries.lastIndex

        override fun next(): TextSegment {
            if (!hasNext()) throw NoSuchElementException()
            return segmentAt(index++)
        }
    }

    private fun segmentAt(index: Int): TextSegment = TextSegment(boundaries[index], boundaries[index + 1], wordLike?.get(index))
}
