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

package dev.carcara.kotlinx.locale.segmentation

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleDataSource

/** The boundaries a segmentation backend can provide. Availability is reported by the source. */
public enum class SegmentationGranularity {
    GRAPHEME,
    WORD,
    SENTENCE,
    ;

    public companion object
}

/** A source of reusable text segmenters. Unsupported granularities return null. */
public interface SegmentationSource : LocaleDataSource {
    public val supportedGranularities: Set<SegmentationGranularity>

    /** The Unicode data/rules release, or null when a backend cannot report it. */
    public val unicodeVersion: String? get() = null

    /**
     * Explicit locale coverage, or null when [granularity] is unsupported.
     * Prefer this to the inherited enumeration in [supportedLocales].
     */
    public fun localeCoverageOrNull(granularity: SegmentationGranularity): SegmentationLocaleCoverage?

    public fun segmenterOrNull(locale: Locale, granularity: SegmentationGranularity): TextSegmenter?

    public companion object
}

/** Locale availability for a particular segmentation granularity. */
public sealed interface SegmentationLocaleCoverage {
    /** Default segmentation is available for every locale; no tailoring or dictionaries are implied. */
    public data object Universal : SegmentationLocaleCoverage

    /** Only these locales are supported. Backends must supply an immutable set. */
    public data class Enumerated(public val locales: Set<Locale>) : SegmentationLocaleCoverage {
        public companion object
    }

    public companion object
}

/** A reusable segmenter. Each result and each iterator has independent traversal state. */
public interface TextSegmenter {
    /** The locale used by this backend, which may apply default rules without locale tailoring. */
    public val locale: Locale
    public val granularity: SegmentationGranularity

    /**
     * Segments the original UTF-16 string without normalization or replacement.
     * Lone surrogates are accepted. Nonempty segments cover the entire input,
     * including whitespace and punctuation; empty input produces no segments.
     */
    public fun segment(text: String): TextSegments

    public companion object
}

/** Segments of [input], in order, with offsets into that exact string. */
public interface TextSegments : Iterable<TextSegment> {
    public val input: String

    /**
     * Returns the same segment as iteration for a UTF-16 code-unit index,
     * including an index inside a surrogate pair or combining sequence.
     * Returns null for negative indices and indices at or beyond [input]'s length.
     * Implementations cache boundaries so repeated lookups do not rescan the input.
     *
     * See https://tc39.es/ecma402/#sec-%intlsegmentsprototype%.containing.
     */
    public fun containingOrNull(utf16Index: Int): TextSegment?

    public companion object
}

/**
 * A nonempty range of UTF-16 code units in the original input.
 * [isWordLike] is a backend-supplied Boolean for words and null for other granularities.
 * See https://tc39.es/ecma402/#sec-createsegmentdataobject.
 */
public data class TextSegment(public val start: Int, public val endExclusive: Int, public val isWordLike: Boolean?) {
    init {
        require(start >= 0 && endExclusive > start) { "A segment must be a nonempty, nonnegative UTF-16 range" }
    }

    public companion object
}
