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

package dev.carcara.kotlinx.locale

/** Whether a source can answer for every locale, a finite set, or cannot report coverage. */
public sealed interface LocaleCoverage {
    public data object Universal : LocaleCoverage
    public data class Enumerated(public val locales: Set<Locale>) : LocaleCoverage {
        public companion object
    }

    /** Enumerated localized data, with root data available for other locales. */
    public data class RootFallback(public val locales: Set<Locale>) : LocaleCoverage {
        public companion object
    }
    public data object Unknown : LocaleCoverage
    public companion object
}

/** An answer, a supported lookup with no data, or an operation the source cannot perform. */
public sealed interface LocaleLookup<out T> {
    public data class Value<T>(public val value: T) : LocaleLookup<T> {
        public companion object
    }
    public data object MissingData : LocaleLookup<Nothing>
    public data class Unsupported(public val operation: String) : LocaleLookup<Nothing> {
        public companion object
    }
    public companion object
}

/** Which endpoint contributed a range part. */
public enum class RangeSource {
    START,
    END,
    SHARED,
    ;

    public companion object
}

/** A typed formatting part with its range provenance. */
public data class RangePart<P>(public val part: P, public val source: RangeSource) {
    public companion object
}
