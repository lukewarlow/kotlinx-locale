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

package dev.carcara.kotlinx.locale.currency.cldr.runtime

import dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.currency.Currency
import dev.carcara.kotlinx.locale.currency.CurrencyFormatOptions
import dev.carcara.kotlinx.locale.currency.CurrencyFormatSource
import dev.carcara.kotlinx.locale.currency.CurrencyNameSource
import dev.carcara.kotlinx.locale.currency.CurrencySymbolStyle
import dev.carcara.kotlinx.locale.currency.forCodeOrNull
import dev.carcara.kotlinx.locale.internal.FIELD_SEPARATOR
import dev.carcara.kotlinx.locale.internal.resolvedRecord
import dev.carcara.kotlinx.locale.internal.sparseRecordValue
import dev.carcara.kotlinx.locale.internal.supportedLocalesOf
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberFormatSource
import dev.carcara.kotlinx.locale.number.NumberNotation
import dev.carcara.kotlinx.locale.number.NumberSymbols
import dev.carcara.kotlinx.locale.number.NumberValue
import dev.carcara.kotlinx.locale.number.PluralRuleSource
import dev.carcara.kotlinx.locale.number.PluralType
import dev.carcara.kotlinx.locale.number.cldr.runtime.AffixSubstitution
import dev.carcara.kotlinx.locale.number.cldr.runtime.CompactPatternTable
import dev.carcara.kotlinx.locale.number.cldr.runtime.FormattedNumberSelector
import dev.carcara.kotlinx.locale.number.cldr.runtime.digitStringsOf
import dev.carcara.kotlinx.locale.number.cldr.runtime.formatCompact
import dev.carcara.kotlinx.locale.number.cldr.runtime.isAlphaAdjacent
import dev.carcara.kotlinx.locale.number.cldr.runtime.renderNumber
import dev.carcara.kotlinx.locale.number.cldr.runtime.renderScientific
import dev.carcara.kotlinx.locale.number.cldr.runtime.renderSpecialNumber

/**
 * A [CurrencyNameSource] over a table of CLDR symbol and name records.
 *
 * Each record is `parent`, the symbols this locale declares, then the names it
 * declares, so one table answers both questions and a lookup walks the parent
 * chain for whatever the locale leaves out.
 */
public class PayloadCurrencyNames(records: Map<String, String>) : CurrencyNameSource {
    private val records = records.toMap()
    private val lookups = PreparedCurrencyCache<Triple<String, Int, String>, String?>(256)
    private fun lookup(locale: Locale, field: Int, key: String): String? =
        lookups.getOrPut(Triple(locale.stripExtensions().toLanguageTag(), field, key)) {
            sparseRecordValue(records, locale, field, 3, key)
        }

    override val localeCoverage: dev.carcara.kotlinx.locale.LocaleCoverage by lazy {
        dev.carcara.kotlinx.locale.internal.payloadLocaleCoverage(this.records, supportedLocales)
    }
    override val supportedLocales: Set<Locale> by lazy {
        supportedLocalesOf(this.records)
    }

    override fun currencySymbolOrNull(currencyCode: String, locale: Locale): String? = lookup(locale, 1, currencyCode)

    /**
     * The alternative spellings share the symbol table under a suffixed key, so
     * each walks the parent chain on its own before the caller falls back to the
     * plain symbol. That is what makes a parent's narrow spelling win over a
     * child's standard one, which is the order ICU resolves them in too.
     */
    override fun currencySymbolOrNull(currencyCode: String, locale: Locale, style: CurrencySymbolStyle): String? {
        val alt = style.altKey ?: return currencySymbolOrNull(currencyCode, locale)
        return lookup(locale, 1, "$currencyCode#$alt")
    }

    override fun currencyNameOrNull(currencyCode: String, locale: Locale): String? = lookup(locale, 2, currencyCode)

    public companion object
}

/**
 * The CLDR `alt` attribute a style is stored under, or null for the plain
 * symbol. [CurrencySymbolStyle.CODE] never reaches here: it names no symbol, and
 * `symbol` answers it from the currency itself.
 */
internal val CurrencySymbolStyle.altKey: String?
    get() = when (this) {
        CurrencySymbolStyle.NARROW_SYMBOL -> "narrow"
        CurrencySymbolStyle.FORMAL_SYMBOL -> "formal"
        CurrencySymbolStyle.VARIANT_SYMBOL -> "variant"
        CurrencySymbolStyle.SYMBOL, CurrencySymbolStyle.CODE -> null
    }

/**
 * A [CurrencyFormatSource] over CLDR number-format records and the symbol table
 * the patterns substitute into.
 *
 * Both tables are needed: the pattern comes from [formatRecords] and the `¤`
 * placeholder in it is filled from [nameRecords]. Formatting and parsing return
 * `null` for a code this build's [Currency] does not carry, because the code is
 * what fixes the scale an ISO minor-unit amount sits on.
 */
public class PayloadCurrencyFormats(
    formatRecords: Map<String, String>,
    nameRecords: Map<String, String>,
    /**
     * The compact money patterns, empty when this build did not ask for them.
     *
     * Empty is not a half-working state: compact notation then renders the
     * standard pattern, which is what CLDR's own `0` sentinel means for a
     * magnitude a locale has no compact form for.
     */
    compactRecords: Map<String, String> = emptyMap(),
    /**
     * How a divided value's plural category is chosen, which is step 8 of the
     * compact algorithm. Answers `other` when this build has no plural rules.
     */
    private val selectCategory: FormattedNumberSelector = NO_PLURAL_RULES,
    private val numbers: NumberFormatSource? = null,
    private val plurals: PluralRuleSource? = null,
) : CurrencyFormatSource {

    private val formatRecords = formatRecords.toMap()
    private val compactRecords = compactRecords.toMap()
    private val decodedRecords = PreparedCurrencyCache<String, CurrencyNumberFormat?>()
    private val names = PayloadCurrencyNames(nameRecords.toMap())
    private val compactCache = PreparedCurrencyCache<String, CompactPatternTable>()

    // Building one walks every currency in the entry set and every spelling of
    // it, so it is built once per locale and kept, the way ICU caches its
    // parsing tries.
    private val parseIndexCache = PreparedCurrencyCache<String, CurrencyParseIndex>()

    // Same reason, one currency at a time: the six spellings a named currency is
    // recognized by are six walks up the parent chain, and an application parses
    // the same currency in the same locale over and over.
    private val parseTokenCache = PreparedCurrencyCache<String, List<String>>()

    override val localeCoverage: dev.carcara.kotlinx.locale.LocaleCoverage by lazy {
        dev.carcara.kotlinx.locale.internal.payloadLocaleCoverage(this.formatRecords, supportedLocales)
    }
    override val supportedLocales: Set<Locale> by lazy {
        supportedLocalesOf(this.formatRecords)
    }

    override fun formatOrNull(minorUnits: Long, currencyCode: String, locale: Locale, options: CurrencyFormatOptions): String? {
        val currency = Currency.forCodeOrNull(currencyCode) ?: return null
        val format = numberFormatFor(locale) ?: return null
        val selector = plurals?.let { source ->
            FormattedNumberSelector { number ->
                source.pluralCategoryOrNull(number, PluralType.CARDINAL, locale) ?: dev.carcara.kotlinx.locale.number.PluralCategory.OTHER
            }
        } ?: selectCategory
        return formatCurrency(format, names, compactFor(locale), selector, minorUnits, currency, locale, options)
    }

    override fun render(
        value: NumberValue,
        currencyCode: String,
        locale: Locale,
        options: NumberFormatOptions,
        style: CurrencySymbolStyle,
    ): LocaleLookup<dev.carcara.kotlinx.locale.number.FormattedNumber> {
        require(
            currencyCode.length == 3 &&
                currencyCode.all {
                    it in 'A'..'Z' || it in 'a'..'z'
                },
        ) { "Currency code must be three ASCII letters" }
        val code = currencyCode.uppercase()
        val data = numberFormatFor(locale) ?: return LocaleLookup.MissingData
        val currency = Currency.forCodeOrNull(code)
        val digits = currency?.cldrFractionDigits ?: 2
        val effective = options.copy(
            minimumFractionDigits = options.minimumFractionDigits ?: minOf(digits, options.maximumFractionDigits ?: digits),
            maximumFractionDigits = options.maximumFractionDigits ?: maxOf(digits, options.minimumFractionDigits ?: digits),
        )
        val requestedSystem = options.numberingSystem ?: locale.unicodeKeywordOrNull("nu")
        val symbols = if (requestedSystem != null) {
            numbers?.symbolsOrNull(locale, requestedSystem)
                ?: return LocaleLookup.Unsupported("numbering system $requestedSystem")
        } else {
            numbers?.symbolsOrNull(locale) ?: data.symbols
        }
        val text = if (style == CurrencySymbolStyle.CODE) {
            code
        } else {
            names.currencySymbolOrNull(code, locale, style)
                ?: names.currencySymbolOrNull(code, locale) ?: code
        }
        val accounting = options.signDisplay.usesAccountingPattern
        val base = if (accounting) data.accountingPattern else data.standardPattern
        val alpha = if (accounting) data.accountingAlphaPattern else data.standardAlphaPattern
        val plain = preparedCurrencyPattern(base)
        val pattern = if (alpha != base && isAlphaAdjacent(plain, text)) preparedCurrencyPattern(alpha) else plain
        val affix = AffixSubstitution { run ->
            if (run ==
                1
            ) {
                text
            } else if (run == 2) {
                code
            } else {
                names.currencyNameOrNull(code, locale) ?: code
            }
        }
        if (value !is NumberValue.Finite) return LocaleLookup.Value(renderSpecialNumber(value, pattern, symbols, effective, affix, true))
        val result = when (options.notation) {
            NumberNotation.STANDARD -> renderNumber(
                value.value,
                pattern,
                symbols,
                effective,
                useCurrencySeparators = true,
                affix = affix,
                currencySpacing = true,
            )
            NumberNotation.SCIENTIFIC, NumberNotation.ENGINEERING -> renderScientific(
                value.value,
                pattern,
                symbols,
                effective,
                affix,
                true,
            )
            else -> {
                val compact = compactFor(locale)
                if (compact.isEmpty) return LocaleLookup.Unsupported("compact currency data")
                formatCompact(
                    value.value, compact, pattern, symbols,
                    FormattedNumberSelector {
                        plurals?.pluralCategoryOrNull(it.operands, PluralType.CARDINAL, locale)
                            ?: dev.carcara.kotlinx.locale.number.PluralCategory.OTHER
                    },
                    effective, useCurrencySeparators = true, currencyText = text, affix = affix, currencySpacing = true,
                )
            }
        }
        return LocaleLookup.Value(result)
    }

    private fun compactFor(locale: Locale): CompactPatternTable = compactCache.getOrPut(locale.toLanguageTag()) {
        CompactPatternTable(resolvedRecord(compactRecords, locale).orEmpty())
    }

    override fun parseToMinorUnitsOrNull(text: String, currencyCode: String, locale: Locale): Long? {
        val currency = Currency.forCodeOrNull(currencyCode) ?: return null
        val format = numberFormatFor(locale) ?: return null
        val tokens = parseTokenCache.getOrPut("$currencyCode|${locale.toLanguageTag()}") {
            currencyParseTokens(names, currency, locale)
        }
        return parseFormattedCurrency(format, tokens, text, currency, locale)
    }

    override fun currencyCodeOrNull(text: String, locale: Locale): String? =
        parseIndexCache.getOrPut(locale.toLanguageTag()) { CurrencyParseIndex(names, locale) }.codeIn(text)

    private fun numberFormatFor(locale: Locale): CurrencyNumberFormat? = decodedRecords.getOrPut(locale.stripExtensions().toLanguageTag()) {
        resolvedRecord(formatRecords, locale)?.let(::CurrencyNumberFormat)
    }

    public companion object
}

/**
 * Decoded number-formatting data for one locale, fully resolved at generation
 * time.
 *
 * Public under the internal-API marker so that the ICU cross-check can compare
 * the tables directly. No source interface exposes this, because no platform
 * could implement one that did.
 */
@InternalKotlinxLocaleApi
public class CurrencyNumberFormat(record: String) {
    private val fields = record.split(FIELD_SEPARATOR)

    /** The ten digits of the locale's default numbering system. */
    public val digits: String = fields[0]
    public val decimal: String = fields[1]
    public val group: String = fields[2]

    /** Decimal and group separators used inside currency values (rarely different). */
    public val currencyDecimal: String = fields[3]
    public val currencyGroup: String = fields[4]
    public val minusSign: String = fields[5]
    public val minimumGroupingDigits: Int = fields[6].toIntOrNull() ?: 1

    /**
     * The same values as the number domain's symbol table, in the shape the
     * shared engine takes.
     *
     * The currency record carries its own copy rather than reaching into
     * `kotlinx-locale-number-cldr-full`, so a consumer who formats money does
     * not have to take the decimal, percent and compact tables to get a decimal
     * separator. It is a few kilobytes against a whole artifact.
     */
    public val symbols: NumberSymbols = NumberSymbols(
        numberingSystem = "latn",
        digits = digitStringsOf(digits),
        decimal = decimal,
        group = group,
        currencyDecimal = currencyDecimal,
        currencyGroup = currencyGroup,
        minusSign = minusSign,
        plusSign = "+",
        percentSign = "%",
        perMille = "‰",
        approximatelySign = "~",
        exponential = "E",
        superscriptingExponent = "×",
        infinity = "∞",
        nan = "NaN",
        listSeparator = ";",
        timeSeparator = ":",
        minimumGroupingDigits = minimumGroupingDigits,
    )

    public val standardPattern: String = fields[7]
    public val standardAlphaPattern: String = fields[8]
    public val accountingPattern: String = fields[9]
    public val accountingAlphaPattern: String = fields[10]

    public companion object
}

/** The number-format record for [locale], for the ICU cross-check. */
@InternalKotlinxLocaleApi
public fun currencyNumberFormatFor(records: Map<String, String>, locale: Locale): CurrencyNumberFormat =
    CurrencyNumberFormat(requireNotNull(resolvedRecord(records, locale)) { "no number format for $locale and no root" })
