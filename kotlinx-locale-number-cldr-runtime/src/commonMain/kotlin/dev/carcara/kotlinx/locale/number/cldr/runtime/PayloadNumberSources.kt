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

package dev.carcara.kotlinx.locale.number.cldr.runtime

import dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.RangePart
import dev.carcara.kotlinx.locale.RangeSource
import dev.carcara.kotlinx.locale.internal.resolvedRecord
import dev.carcara.kotlinx.locale.internal.sparseRecordValue
import dev.carcara.kotlinx.locale.internal.supportedLocalesOf
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.number.FormattedNumber
import dev.carcara.kotlinx.locale.number.FormattedNumberRange
import dev.carcara.kotlinx.locale.number.NumberFormatCapabilities
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberFormatSource
import dev.carcara.kotlinx.locale.number.NumberNotation
import dev.carcara.kotlinx.locale.number.NumberPart
import dev.carcara.kotlinx.locale.number.NumberPartKind
import dev.carcara.kotlinx.locale.number.NumberSymbols
import dev.carcara.kotlinx.locale.number.NumberValue
import dev.carcara.kotlinx.locale.number.OrdinalFormatSource
import dev.carcara.kotlinx.locale.number.PercentScale
import dev.carcara.kotlinx.locale.number.PluralCategory
import dev.carcara.kotlinx.locale.number.PluralRuleSource
import dev.carcara.kotlinx.locale.number.PluralType
import dev.carcara.kotlinx.locale.number.pluralCategory

/**
 * A number source over the generated tables.
 *
 * Decoded records are cached per language tag, the way the skeleton source
 * caches its own, because parsing a pattern is the expensive half and a screen
 * formats many numbers in one locale.
 */
public class PayloadNumberFormats(
    private val symbolRecords: Map<String, String>,
    private val patternRecords: Map<String, String>,
    private val compactShortRecords: Map<String, String>,
    private val compactLongRecords: Map<String, String>,
    private val plurals: PluralRuleSource,
    private val numberingSystemRecords: Map<String, String> = emptyMap(),
) : NumberFormatSource {

    private val cache = HashMap<String, LocaleNumbers?>()

    override val supportedLocales: Set<Locale> get() = supportedLocalesOf(symbolRecords)
    override val localeCoverage: dev.carcara.kotlinx.locale.LocaleCoverage get() = if ("root" in
        symbolRecords
    ) {
        dev.carcara.kotlinx.locale.LocaleCoverage.RootFallback(supportedLocales)
    } else {
        dev.carcara.kotlinx.locale.LocaleCoverage.Enumerated(supportedLocales)
    }

    override fun symbolsOrNull(locale: Locale): NumberSymbols? = numbersFor(locale)?.symbols

    private fun systemValue(key: String, locale: Locale): String? = sparseRecordValue(numberingSystemRecords, locale, 1, 2, key)

    override fun symbolsOrNull(locale: Locale, numberingSystem: String): NumberSymbols? {
        val base = symbolsOrNull(locale) ?: return null
        if (numberingSystemRecords.isEmpty()) return base.takeIf { it.numberingSystem == numberingSystem }
        val digits = systemValue("$numberingSystem/digits", locale)?.let(::digitStringsOf) ?: return null
        fun symbol(key: String, fallback: String): String {
            var current = numberingSystem
            val seen = HashSet<String>()
            while (seen.add(current)) {
                systemValue("$current/$key", locale)?.let { return it }
                current = systemValue("$current/alias", locale) ?: if (current != "latn") "latn" else return fallback
            }
            return fallback
        }
        val root = NumberSymbols.Root
        val decimal = symbol("decimal", root.decimal)
        val group = symbol("group", root.group)
        return NumberSymbols(
            numberingSystem, digits, decimal, group, symbol("currencyDecimal", decimal), symbol("currencyGroup", group),
            symbol("minusSign", root.minusSign), symbol("plusSign", root.plusSign), symbol("percentSign", root.percentSign),
            symbol("perMille", root.perMille), symbol("approximatelySign", root.approximatelySign), symbol("exponential", root.exponential),
            symbol("superscriptingExponent", root.superscriptingExponent), symbol("infinity", root.infinity), symbol("nan", root.nan),
            symbol("list", root.listSeparator), symbol("timeSeparator", root.timeSeparator), base.minimumGroupingDigits,
        )
    }

    private fun selectedNumbers(locale: Locale, options: NumberFormatOptions): LocaleNumbers? {
        val base = numbersFor(locale) ?: return null
        val selected = options.numberingSystem ?: locale.unicodeKeywordOrNull("nu") ?: return base
        val symbols = symbolsOrNull(locale, selected) ?: return null
        return LocaleNumbers(symbols, base.decimalPattern, base.percentPattern, base.compactShort, base.compactLong)
    }

    override fun capabilities(locale: Locale): NumberFormatCapabilities {
        val symbols = symbolsOrNull(locale)
        val notations = mutableSetOf(NumberNotation.STANDARD, NumberNotation.SCIENTIFIC, NumberNotation.ENGINEERING)
        if (compactShortRecords.isNotEmpty()) notations.add(NumberNotation.COMPACT_SHORT)
        if (compactLongRecords.isNotEmpty()) notations.add(NumberNotation.COMPACT_LONG)
        return NumberFormatCapabilities(
            localeCoverage,
            symbols?.numberingSystem,
            (
                systemValue("systems", locale)?.split(' ')?.toSet()
                    ?: symbols?.let { setOf(it.numberingSystem) }.orEmpty()
                ),
            notations,
            true,
            true,
        )
    }

    override fun formatOrNull(value: Decimal, locale: Locale, options: NumberFormatOptions): FormattedNumber? {
        val numbers = selectedNumbers(locale, options) ?: return null
        return render(numbers, numbers.decimalPattern, value, locale, options)
    }

    override fun formatPercentOrNull(value: Decimal, locale: Locale, scale: PercentScale, options: NumberFormatOptions): FormattedNumber? {
        val numbers = selectedNumbers(locale, options) ?: return null
        // The percent pattern's own `%` multiplies by 100, so an already-scaled
        // value is divided first and the pattern puts it back. Doing it this way
        // rather than stripping the multiplier keeps one code path and keeps the
        // sign, grouping and affix handling identical between the two readings.
        val input = if (options.preRounded) {
            value
        } else {
            when (scale) {
                PercentScale.FRACTION -> value
                PercentScale.PERCENT -> value.shifted(-2)
            }
        }
        if (options.notation == NumberNotation.COMPACT_SHORT || options.notation == NumberNotation.COMPACT_LONG) {
            val scaled = if (options.preRounded) input else input.shifted(2)
            val compact = render(numbers, numbers.decimalPattern, scaled, locale, options)
            val proxy = Decimal.ofDigits(if (compact.n.isZero) "0" else "1", 0, scaled.isNegative)
            val outer = renderNumber(proxy, numbers.percentPattern, numbers.symbols, options.copy(preRounded = true))
            var inserted = false
            val parts = buildList {
                for (part in outer.parts) {
                    if (part.kind in setOf(NumberPartKind.INTEGER, NumberPartKind.GROUP, NumberPartKind.DECIMAL, NumberPartKind.FRACTION)) {
                        if (!inserted) {
                            addAll(
                                compact.parts.filter {
                                    it.kind != NumberPartKind.MINUS_SIGN &&
                                        it.kind != NumberPartKind.PLUS_SIGN
                                },
                            )
                        }
                        inserted = true
                    } else {
                        add(part)
                    }
                }
            }
            return FormattedNumber(
                parts.joinToString("") {
                    it.value
                },
                compact.operands.integerDigits,
                compact.operands.fractionDigits,
                compact.c,
                parts,
            )
        }
        return render(numbers, numbers.percentPattern, input, locale, options)
    }

    override fun render(value: NumberValue, locale: Locale, options: NumberFormatOptions): LocaleLookup<FormattedNumber> {
        val numbers =
            selectedNumbers(locale, options)
                ?: return if (options.numberingSystem !=
                    null
                ) {
                    LocaleLookup.Unsupported("numbering system ${options.numberingSystem}")
                } else {
                    LocaleLookup.MissingData
                }
        if (options.numberingSystem != null &&
            options.numberingSystem !in capabilities(locale).numberingSystems
        ) {
            return LocaleLookup.Unsupported("numbering system ${options.numberingSystem}")
        }
        if (options.notation !in capabilities(locale).notations) return LocaleLookup.Unsupported("notation ${options.notation}")
        return LocaleLookup.Value(
            when (value) {
                is NumberValue.Finite -> render(numbers, numbers.decimalPattern, value.value, locale, options)
                else -> renderSpecialNumber(value, numbers.decimalPattern, numbers.symbols, options)
            },
        )
    }

    override fun renderRounded(
        value: dev.carcara.kotlinx.locale.number.RoundedNumber,
        locale: Locale,
        options: NumberFormatOptions,
    ): LocaleLookup<FormattedNumber> = render(
        NumberValue.Finite(value.value),
        locale,
        options.copy(preRounded = true, notationExponent = value.notationExponent, compactExponent = value.compactExponent),
    )

    override fun renderPercent(
        value: NumberValue,
        locale: Locale,
        scale: PercentScale,
        options: NumberFormatOptions,
    ): LocaleLookup<FormattedNumber> {
        if (options.numberingSystem != null &&
            options.numberingSystem !in capabilities(locale).numberingSystems
        ) {
            return LocaleLookup.Unsupported("Numbering system ${options.numberingSystem}")
        }
        val numbers = selectedNumbers(locale, options) ?: return LocaleLookup.MissingData
        return when (value) {
            is NumberValue.Finite -> formatPercentOrNull(value.value, locale, scale, options)?.let { LocaleLookup.Value(it) }
                ?: LocaleLookup.MissingData
            else -> LocaleLookup.Value(renderSpecialNumber(value, numbers.percentPattern, numbers.symbols, options))
        }
    }

    override fun composeRange(
        start: FormattedNumber,
        end: FormattedNumber,
        locale: Locale,
        options: NumberFormatOptions,
        approximately: Boolean,
        collapse: Boolean,
    ): LocaleLookup<FormattedNumberRange> {
        if (numberingSystemRecords.isEmpty()) return LocaleLookup.Unsupported("number range patterns")
        val symbols = selectedNumbers(locale, options)?.symbols ?: return LocaleLookup.MissingData
        fun template(kind: String): String? = systemValue("${symbols.numberingSystem}/pattern-$kind", locale)
            ?: systemValue("latn/pattern-$kind", locale)
        val pattern = template(if (approximately) "approximately" else "range") ?: return LocaleLookup.MissingData
        fun partList(parts: List<NumberPart>, source: RangeSource) = parts.map { RangePart(it, source) }
        var prefix = 0
        var suffix = 0
        val shareable = setOf(NumberPartKind.CURRENCY, NumberPartKind.UNIT, NumberPartKind.COMPACT, NumberPartKind.LITERAL)
        if (collapse && !approximately) {
            while (prefix < minOf(start.parts.size, end.parts.size) &&
                start.parts[prefix] == end.parts[prefix] &&
                start.parts[prefix].kind in shareable
            ) {
                prefix++
            }
            if (start.parts.take(prefix).none { it.kind != NumberPartKind.LITERAL }) prefix = 0
            while (suffix < minOf(start.parts.size, end.parts.size) - prefix &&
                start.parts[start.parts.lastIndex - suffix] == end.parts[end.parts.lastIndex - suffix] &&
                start.parts[start.parts.lastIndex - suffix].kind in shareable
            ) {
                suffix++
            }
            if (start.parts.takeLast(suffix).none { it.kind != NumberPartKind.LITERAL }) suffix = 0
        }
        val left = if (approximately) {
            partList(
                start.parts,
                RangeSource.SHARED,
            )
        } else {
            partList(start.parts.drop(prefix).dropLast(suffix), RangeSource.START)
        }
        val right = partList(end.parts.drop(prefix).dropLast(suffix), RangeSource.END)
        val result = ArrayList<RangePart<NumberPart>>()
        result.addAll(partList(start.parts.take(prefix), RangeSource.SHARED))
        var index = 0
        while (index < pattern.length) {
            val marker = pattern.indexOf('{', index)
            val stop = if (marker < 0) pattern.length else marker
            if (stop >
                index
            ) {
                result.add(
                    RangePart(
                        NumberPart(
                            if (approximately) NumberPartKind.APPROXIMATELY_SIGN else NumberPartKind.LITERAL,
                            pattern.substring(index, stop),
                        ),
                        RangeSource.SHARED,
                    ),
                )
            }
            if (marker < 0) break
            require(marker + 2 < pattern.length && pattern[marker + 2] == '}')
            result.addAll(
                when (pattern[marker + 1]) {
                    '0' -> left
                    '1' -> right
                    else -> error("Invalid number range pattern")
                },
            )
            index = marker + 3
        }
        result.addAll(partList(start.parts.takeLast(suffix), RangeSource.SHARED))
        return LocaleLookup.Value(FormattedNumberRange(result))
    }

    override fun parseDecimalOrNull(text: String, locale: Locale): Decimal? {
        val numbers = numbersFor(locale) ?: return null
        return parseDecimal(text, numbers.symbols)
    }

    private fun render(
        numbers: LocaleNumbers,
        pattern: NumberPattern,
        value: Decimal,
        locale: Locale,
        options: NumberFormatOptions,
    ): FormattedNumber = when (options.notation) {
        NumberNotation.SCIENTIFIC, NumberNotation.ENGINEERING -> renderScientific(value, pattern, numbers.symbols, options)
        NumberNotation.STANDARD -> renderNumber(value, pattern, numbers.symbols, options)
        NumberNotation.COMPACT_SHORT -> formatCompact(
            value,
            numbers.compactShort,
            pattern,
            numbers.symbols,
            FormattedNumberSelector { plurals.pluralCategoryOrNull(it, PluralType.CARDINAL, locale) ?: PluralCategory.OTHER },
            options,
        )
        NumberNotation.COMPACT_LONG -> formatCompact(
            value,
            numbers.compactLong,
            pattern,
            numbers.symbols,
            FormattedNumberSelector { plurals.pluralCategoryOrNull(it, PluralType.CARDINAL, locale) ?: PluralCategory.OTHER },
            options,
        )
    }

    private fun numbersFor(locale: Locale): LocaleNumbers? {
        val key = locale.toLanguageTag()
        if (cache.containsKey(key)) return cache[key]
        val symbolRecord = resolvedRecord(symbolRecords, locale)
        val patternRecord = resolvedRecord(patternRecords, locale)
        val numbers = if (symbolRecord == null || patternRecord == null) {
            null
        } else {
            LocaleNumbers(
                symbols = NumberSymbolsRecord(symbolRecord).toSymbols(),
                decimalPattern = NumberPattern.parse(NumberPatternRecord(patternRecord).decimalPattern),
                percentPattern = NumberPattern.parse(NumberPatternRecord(patternRecord).percentPattern),
                compactShort = CompactPatternTable(resolvedRecord(compactShortRecords, locale).orEmpty()),
                compactLong = CompactPatternTable(resolvedRecord(compactLongRecords, locale).orEmpty()),
            )
        }
        cache[key] = numbers
        return numbers
    }

    public companion object
}

private class LocaleNumbers(
    val symbols: NumberSymbols,
    val decimalPattern: NumberPattern,
    val percentPattern: NumberPattern,
    val compactShort: CompactPatternTable,
    val compactLong: CompactPatternTable,
)

/**
 * A plural rule source over the generated tables.
 *
 * The rule sets are keyed by id rather than by locale, because 1122 locales
 * share about sixty-five of them, and the index maps a tag to the cardinal and
 * ordinal ids it uses.
 */
public class PayloadPluralRules(
    /** rule set id -> encoded conditions. */
    private val ruleSets: Map<String, String>,
    /** locale tag -> `"<cardinalId> <ordinalId>"`. */
    private val ruleIndex: Map<String, String>,
    private val rangeRecords: Map<String, String> = emptyMap(),
) : PluralRuleSource {

    private val parsed = HashMap<String, PluralRuleSet>()

    override val supportedLocales: Set<Locale> get() = supportedLocalesOf(ruleIndex)

    override fun pluralCategoriesOrNull(type: PluralType, locale: Locale): Set<PluralCategory>? {
        val ids = (resolvedRecord(ruleIndex, locale) ?: return null).split(' ')
        val id = ids.getOrNull(if (type == PluralType.CARDINAL) 0 else 1).orEmpty()
        return parsed.getOrPut(id) { PluralRuleSet.parse(ruleSets[id].orEmpty()) }.categories
    }

    override fun pluralRangeOrNull(start: PluralCategory, end: PluralCategory, locale: Locale): PluralCategory? {
        if (rangeRecords.isEmpty()) return null
        val record = resolvedRecord(rangeRecords, locale).orEmpty()
        val key = "${start.name.lowercase()}/${end.name.lowercase()}="
        val value = record.split(';').firstOrNull { it.startsWith(key) }?.substringAfter('=') ?: "other"
        return PluralCategory.forCldrNameOrNull(value) ?: PluralCategory.OTHER
    }

    override fun pluralCategoryOrNull(number: FormattedNumber, type: PluralType, locale: Locale): PluralCategory? {
        val entry = resolvedRecord(ruleIndex, locale) ?: return null
        val ids = entry.split(' ')
        val id = when (type) {
            PluralType.CARDINAL -> ids.getOrNull(0)
            PluralType.ORDINAL -> ids.getOrNull(1)
        }?.takeIf { it.isNotEmpty() } ?: return PluralCategory.OTHER
        val ruleSet = parsed.getOrPut(id) { PluralRuleSet.parse(ruleSets[id].orEmpty()) }
        return ruleSet.select(number)
    }

    public companion object
}

/** An ordinal source over the generated rule closures. */
public class PayloadOrdinalFormats(
    /** closure id -> encoded rule sets. */
    private val closures: Map<String, String>,
    /** locale tag -> closure id. */
    private val index: Map<String, String>,
    private val numbers: NumberFormatSource,
    private val plurals: PluralRuleSource,
) : OrdinalFormatSource {

    private val parsed = HashMap<String, OrdinalRuleClosure>()

    override val supportedLocales: Set<Locale> get() = supportedLocalesOf(index)

    override fun ordinalOrNull(value: Long, locale: Locale): String? {
        val id = resolvedRecord(index, locale)?.takeIf { it.isNotEmpty() } ?: return null
        val closure = parsed.getOrPut(id) { OrdinalRuleClosure(closures[id].orEmpty()) }
        if (closure.isEmpty) return null
        val symbols = numbers.symbolsOrNull(locale) ?: NumberSymbols.Root
        return closure.format(value, symbols) { count ->
            plurals.pluralCategory(count, locale, PluralType.ORDINAL)
        }
    }

    public companion object
}
