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

package dev.carcara.kotlinx.locale.metadata

import dev.carcara.kotlinx.locale.Locale

/** Direction of text in the locale's default script. */
public enum class TextDirection {
    LTR,
    RTL,
    ;

    public companion object
}

/** Where a locale's regional preference came from. */
public enum class RegionOrigin {
    OVERRIDE,
    EXPLICIT,
    SUBDIVISION,
    LIKELY,
    WORLD,
    ;

    public companion object
}

/** A canonical region and the source used to select it. */
public data class RegionPreference(public val region: String, public val origin: RegionOrigin) {
    public companion object
}

/** Locale preferences, distinct from any formatter's supported values. */
public data class LocalePreferences(
    public val calendars: List<String>,
    public val collations: List<String>,
    public val numberingSystems: List<String>,
    public val hourCycles: List<String>,
    public val hourCycle12: String,
    public val hourCycle24: String,
    public val direction: TextDirection,
) {
    public companion object
}

/** Data-backed locale operations independent of formatter coverage. */
public interface LocaleMetadataSource {
    public val dataVersion: String
    public fun canonicalize(locale: Locale): Locale
    public fun canonicalizeUnicodeValue(key: String, value: String): String
    public fun maximize(locale: Locale): Locale
    public fun minimize(locale: Locale): Locale
    public fun regionPreference(locale: Locale): RegionPreference
    public fun preferences(locale: Locale): LocalePreferences

    /** Script metadata only; null means unknown, with no language or root fallback. */
    public fun scriptDirectionOrNull(script: String): TextDirection? = null
    public fun timeZonesForRegion(region: String): List<String>
    public fun timeZoneIdentifiers(): List<TimeZoneIdentifier>
    public fun timeZoneIdentifierOrNull(identifier: String): TimeZoneIdentifier?
    public fun currencies(): List<CurrencyMetadata>
    public fun bestFitOrNull(requested: List<Locale>, available: Set<Locale>): LocaleMatch?

    /** Snapshot coverage once and retain this matcher beside an immutable service catalogue. */
    public fun prepareMatcher(available: Set<Locale>): PreparedLocaleMatcher {
        val snapshot = available.toSet()
        return object : PreparedLocaleMatcher {
            override fun bestFitOrNull(requested: List<Locale>): LocaleMatch? = bestFitOrNull(requested, snapshot)
        }
    }
    public fun numberingSystems(): Set<String>
    public fun numberingSystemDigitsOrNull(identifier: String): String?
    public companion object
}

/** Recognized zone spelling, its primary identifier and its region when applicable. */
public data class TimeZoneIdentifier(public val identifier: String, public val primaryIdentifier: String, public val region: String?) {
    public companion object
}

/** ISO storage metadata is distinct from CLDR standard/cash rendering defaults. */
public data class CurrencyMetadata(
    public val code: String,
    public val isoMinorUnits: Int?,
    public val fractionDigits: Int,
    public val roundingIncrement: Int,
    public val cashFractionDigits: Int,
    public val cashRoundingIncrement: Int,
    public val current: Boolean,
) {
    public companion object
}

public data class LocaleMatch(public val requested: Locale, public val available: Locale, public val distance: Int) {
    public companion object
}

/** Owned immutable coverage with bounded, thread-safe implementation caches. */
public interface PreparedLocaleMatcher {
    public fun bestFitOrNull(requested: List<Locale>): LocaleMatch?
    public companion object
}
