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

package dev.carcara.kotlinx.locale.collation

import dev.carcara.kotlinx.locale.Locale

/**
 * How much of a difference counts.
 *
 * Collation compares in levels: base letters first, then accents, then case. A
 * strength says which levels a comparison stops at, so a search that should
 * treat resume and résumé as one word asks for [PRIMARY] and a sort that has to
 * order them asks for [TERTIARY].
 */
public enum class CollationStrength {
    /** Base letters only: a and á are equal. */
    PRIMARY,

    /** Base letters and accents: a and á differ, a and A do not. */
    SECONDARY,

    /** Base letters, accents and case. */
    TERTIARY,
    ;

    public companion object
}

/**
 * Where a build's collation order comes from.
 *
 * Implemented by whichever generated artifact carries the tables. A consumer
 * names [collationComparator] rather than this.
 */
public interface CollationSource {
    /** Service identities, including inherited locales; empty when this source cannot enumerate them. */
    public val supportedLocales: Set<Locale> get() = emptySet()

    /** Negotiation coverage, separate from dedicated tailoring records. */
    public val localeCoverage: dev.carcara.kotlinx.locale.LocaleCoverage
        get() = if (supportedLocales.isEmpty()) {
            dev.carcara.kotlinx.locale.LocaleCoverage.Unknown
        } else {
            dev.carcara.kotlinx.locale.LocaleCoverage.Enumerated(supportedLocales)
        }

    /** Locales with dedicated tailoring records; distinct from the service catalogue. */
    public val tailoredLocales: Set<Locale> get() = emptySet()

    /** Actual public collation variants carried by this source; excludes standard/search. */
    public fun collations(): Set<String> = emptySet()

    /** The order [locale] reads in, or null when this build carries no table for it. */
    public fun comparatorOrNull(locale: Locale, strength: CollationStrength): Comparator<String>?

    public fun resolve(
        locale: Locale,
        options: CollationOptions = CollationOptions(),
    ): dev.carcara.kotlinx.locale.LocaleLookup<ResolvedCollation> = dev.carcara.kotlinx.locale.LocaleLookup.Unsupported("Collation options")
    public fun capabilities(locale: Locale): CollationCapabilities =
        CollationCapabilities(localeCoverage, emptySet(), emptySet(), false, false)

    public companion object
}
