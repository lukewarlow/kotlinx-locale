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

@file:OptIn(InternalKotlinxLocaleApi::class)

package dev.carcara.kotlinx.locale.language.cldr.runtime

import dev.carcara.kotlinx.locale.Capitalization
import dev.carcara.kotlinx.locale.DisplayName
import dev.carcara.kotlinx.locale.DisplayNameType
import dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.NameFallback
import dev.carcara.kotlinx.locale.NameOrigin
import dev.carcara.kotlinx.locale.NameWidth
import dev.carcara.kotlinx.locale.internal.ENTRY_SEPARATOR
import dev.carcara.kotlinx.locale.internal.FIELD_SEPARATOR
import dev.carcara.kotlinx.locale.internal.resolvedRecord
import dev.carcara.kotlinx.locale.internal.sparseRecordValue
import dev.carcara.kotlinx.locale.internal.supportedLocalesOf
import dev.carcara.kotlinx.locale.language.LanguageNameSource
import dev.carcara.kotlinx.locale.language.LanguageNameStyle
import dev.carcara.kotlinx.locale.language.LanguageNameUsage
import dev.carcara.kotlinx.locale.language.LocaleDisplayPatterns
import dev.carcara.kotlinx.locale.titlecaseFirstWord

/** The parent tag plus four keyed fields: languages, scripts, territories, patterns. */
private const val FIELD_COUNT = 5

/** The capitalization bit field sits after the keyed fields, resolved rather than sparse. */
private const val CAPITALIZATION_FIELD = 5

/** Where each usage's two bits sit, matching the order the generator writes them in. */
private val USAGE_BIT_BASE = mapOf(
    LanguageNameUsage.LANGUAGE to 8,
    LanguageNameUsage.SCRIPT to 10,
    LanguageNameUsage.TERRITORY to 12,
)

/** The suffix a short-form entry is keyed under, so both spellings share one table. */
private const val SHORT_SUFFIX = "#short"

/**
 * A [LanguageNameSource] over a table of CLDR locale display name records.
 *
 * Each record is the parent tag, then only what this locale's own file declares:
 * language names, script names, territory names and the three composition
 * patterns. A lookup walks the parent chain for whatever the locale leaves out,
 * which is what keeps the table from repeating every English name under every
 * English variant.
 */
public class PayloadLanguageNames(
    private val records: Map<String, String>,
    /**
     * The shared territory names, or nothing.
     *
     * A parameter rather than a direct dependency, because this module is the
     * lookup and knows about no table at all: the shipped artifact passes the
     * one in `kotlinx-locale-territory-cldr-full`, and a generated build passes
     * whatever the plugin wrote. The default answers nothing, which is what a
     * build that generated no territory table needs.
     */
    private val territoryNames: (String, Locale) -> String? = { _, _ -> null },
    private val extendedRecords: Map<String, String> = emptyMap(),
) : LanguageNameSource {

    override val supportedLocales: Set<Locale> by lazy { supportedLocalesOf(records) }

    override fun lookupName(
        type: DisplayNameType,
        code: String,
        locale: Locale,
        width: NameWidth,
        fallback: NameFallback,
    ): LocaleLookup<DisplayName> {
        if (type == DisplayNameType.CURRENCY) return LocaleLookup.Unsupported("currency names use CurrencyNameSource")
        if (extendedRecords.isEmpty() &&
            (width == NameWidth.NARROW || type in setOf(DisplayNameType.CALENDAR, DisplayNameType.DATE_TIME_FIELD, DisplayNameType.VARIANT))
        ) {
            return LocaleLookup.Unsupported("extended display names")
        }
        val canonical = when (type) {
            DisplayNameType.SCRIPT -> code.lowercase().replaceFirstChar(Char::uppercaseChar)
            DisplayNameType.REGION -> code.uppercase()
            DisplayNameType.VARIANT, DisplayNameType.CALENDAR -> code.lowercase()
            else -> code
        }
        val widths = when (width) {
            NameWidth.LONG -> listOf(NameWidth.LONG)
            NameWidth.SHORT -> listOf(NameWidth.SHORT, NameWidth.LONG)
            NameWidth.NARROW -> listOf(NameWidth.NARROW, NameWidth.SHORT, NameWidth.LONG)
        }
        for (candidate in widths) {
            val extra =
                sparseRecordValue(extendedRecords, locale, 1, 2, "${type.name.lowercase()}/$canonical/${candidate.name.lowercase()}")
            val text = extra ?: when {
                type == DisplayNameType.LANGUAGE && candidate == NameWidth.SHORT -> sparseRecordValue(
                    records,
                    locale,
                    1,
                    FIELD_COUNT,
                    canonical.replace('-', '_') + SHORT_SUFFIX,
                )
                type == DisplayNameType.LANGUAGE && candidate == NameWidth.LONG -> languageNameOrNull(
                    canonical,
                    LanguageNameStyle.STANDARD,
                    locale,
                )
                type == DisplayNameType.SCRIPT && candidate == NameWidth.LONG -> scriptNameOrNull(canonical, locale)
                type == DisplayNameType.REGION && candidate == NameWidth.LONG -> regionNameOrNull(canonical, locale)
                else -> null
            }
            if (text !=
                null
            ) {
                return LocaleLookup.Value(
                    DisplayName(
                        text,
                        if (candidate ==
                            width
                        ) {
                            NameOrigin.LOCALIZED
                        } else {
                            NameOrigin.WIDTH_FALLBACK
                        },
                        candidate,
                    ),
                )
            }
        }
        return if (fallback == NameFallback.CODE) LocaleLookup.Value(DisplayName(code, NameOrigin.CODE, null)) else LocaleLookup.MissingData
    }

    override fun languageNameOrNull(subtag: String, style: LanguageNameStyle, locale: Locale): String? {
        val key = subtag.replace('-', '_')
        if (style == LanguageNameStyle.SHORT) {
            sparseRecordValue(records, locale, field = 1, fieldCount = FIELD_COUNT, key = key + SHORT_SUFFIX)
                ?.let { return it }
        }
        return sparseRecordValue(records, locale, field = 1, fieldCount = FIELD_COUNT, key = key)
    }

    override fun scriptNameOrNull(code: String, locale: Locale): String? =
        sparseRecordValue(records, locale, field = 2, fieldCount = FIELD_COUNT, key = code)

    override fun regionNameOrNull(code: String, locale: Locale): String? =
        // The shared territory table first, then this locale's own field. The
        // two are disjoint: everything CLDR calls a country lives in the shared
        // one, and what is left here is the macro-regions and the codes that
        // are not countries, `419` and `EU` and `ZZ`.
        territoryNames(code, locale)
            ?: sparseRecordValue(records, locale, field = 3, fieldCount = FIELD_COUNT, key = code)

    /**
     * The three patterns, walked up the chain together rather than one at a
     * time.
     *
     * Together, because a locale that overrides only its separator still means
     * its parent's bracket style, and reading each independently would be the
     * same answer for more work.
     */
    override fun capitalized(name: String, usage: LanguageNameUsage, capitalization: Capitalization, locale: Locale): String {
        if (name.isEmpty() || capitalization == Capitalization.MIDDLE_OF_SENTENCE) return name
        val record = resolvedRecord(records, locale) ?: return name
        val bits = record.split(FIELD_SEPARATOR).getOrNull(CAPITALIZATION_FIELD)?.toIntOrNull(16) ?: return name
        val base = USAGE_BIT_BASE.getValue(usage)
        val bit = base + if (capitalization == Capitalization.STANDALONE) 0 else 1
        if ((bits shr bit) and 1 == 0) return name
        return titlecaseFirstWord(name, locale.language)
    }

    override fun displayPatternsOrNull(locale: Locale): LocaleDisplayPatterns? {
        val pattern = sparseRecordValue(records, locale, field = 4, fieldCount = FIELD_COUNT, key = "p")
            ?: return null
        val parts = pattern.split(ENTRY_SEPARATOR)
        return LocaleDisplayPatterns(
            localePattern = parts.getOrNull(0)?.takeIf(String::isNotEmpty) ?: LocaleDisplayPatterns.Root.localePattern,
            localeSeparator = parts.getOrNull(1)?.takeIf(String::isNotEmpty)
                ?: LocaleDisplayPatterns.Root.localeSeparator,
            localeKeyTypePattern = parts.getOrNull(2)?.takeIf(String::isNotEmpty)
                ?: LocaleDisplayPatterns.Root.localeKeyTypePattern,
        )
    }

    public companion object
}
