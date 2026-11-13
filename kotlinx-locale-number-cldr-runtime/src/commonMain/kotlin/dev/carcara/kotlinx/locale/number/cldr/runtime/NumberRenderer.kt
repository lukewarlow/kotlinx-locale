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
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.number.FormattedNumber
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberGrouping
import dev.carcara.kotlinx.locale.number.NumberNotation
import dev.carcara.kotlinx.locale.number.NumberPart
import dev.carcara.kotlinx.locale.number.NumberPartKind
import dev.carcara.kotlinx.locale.number.NumberSymbols
import dev.carcara.kotlinx.locale.number.NumberValue
import dev.carcara.kotlinx.locale.number.SignDisplay
import dev.carcara.kotlinx.locale.number.numberRound

/**
 * How a run of `¤` characters in an affix is filled in.
 *
 * A callback rather than a currency argument, because this module does not know
 * about `Currency` and must not: the layering rule that lets any `-cldr-full`
 * link against any `-types` is what keeps the number engine reusable by the
 * currency domain without either naming the other's entries.
 */
@InternalKotlinxLocaleApi
public fun interface AffixSubstitution {

    /** The text for a run of [runLength] `¤`s: 1 is the symbol, 2 the code, 3 or more the display name. */
    public fun currencyText(runLength: Int): String

    public companion object {

        /** Leaves `¤` runs untouched, which is what a non-currency pattern wants. */
        public val None: AffixSubstitution = AffixSubstitution { runLength -> "¤".repeat(runLength) }
    }
}

/**
 * Renders [value] through [pattern] with [symbols].
 *
 * [fixedFractionDigits], when given, overrides the pattern's own minimum and
 * maximum. Currency formatting always passes it: `¤#,##0.00` is CLDR's shape for
 * the pattern, and the digit count belongs to the currency rather than to the
 * pattern.
 *
 * [useCurrencySeparators] picks `currencyDecimal` and `currencyGroup` over the
 * plain pair. A handful of locales differ between the two and the rest do not.
 */
@InternalKotlinxLocaleApi
public fun renderNumber(
    value: Decimal,
    pattern: NumberPattern,
    symbols: NumberSymbols,
    options: NumberFormatOptions = NumberFormatOptions.Default,
    fixedFractionDigits: Int? = null,
    useCurrencySeparators: Boolean = false,
    affix: AffixSubstitution = AffixSubstitution.None,
    compactExponent: Int = 0,
    groupingFloor: Int = 1,
    currencySpacing: Boolean = false,
    affixLiteralKind: NumberPartKind = NumberPartKind.LITERAL,
): FormattedNumber {
    val scaled = if (options.preRounded) value else applyMultiplier(value, pattern.multiplier)
    val minimum = fixedFractionDigits ?: options.minimumFractionDigits ?: pattern.minimumFractionDigits
    val maximum = fixedFractionDigits ?: options.maximumFractionDigits ?: pattern.maximumFractionDigits
    val rendered = if (options.preRounded) {
        scaled
    } else if (fixedFractionDigits != null) {
        scaled.rescaled(fixedFractionDigits, options.roundingMode, options.roundingIncrement)
    } else {
        numberRound(scaled, options, minimum, maximum)
    }
    val zero = rendered.isZero
    val negative = scaled.isNegative && !(zero && options.signDisplay.suppressesNegativeZero)
    val fractionPart = rendered.fractionDigits
    val integerPart = rendered.integerDigits.padStart(maxOf(pattern.minimumIntegerDigits, options.minimumIntegerDigits), '0')

    val digitStrings = digitStringsOf(symbols.digits)
    val group = if (useCurrencySeparators) symbols.currencyGroup else symbols.group
    val decimal = if (useCurrencySeparators) symbols.currencyDecimal else symbols.decimal

    val bodyParts = ArrayList<NumberPart>()
    val primary = pattern.primaryGroupSize
    val groupingMinimum = when (options.grouping) {
        NumberGrouping.NEVER -> Int.MAX_VALUE
        NumberGrouping.ALWAYS -> 1
        NumberGrouping.AUTO -> maxOf(symbols.minimumGroupingDigits, groupingFloor)
        NumberGrouping.MIN2 -> maxOf(2, groupingFloor)
    }
    val grouped = primary > 0 && groupingMinimum != Int.MAX_VALUE && integerPart.length >= primary + groupingMinimum
    var chunk = StringBuilder()
    for (index in integerPart.indices) {
        val afterPrimary = integerPart.length - index - primary
        if (grouped && index > 0 && (afterPrimary == 0 || (afterPrimary > 0 && afterPrimary % pattern.secondaryGroupSize == 0))) {
            bodyParts.add(NumberPart(NumberPartKind.INTEGER, chunk.toString()))
            bodyParts.add(NumberPart(NumberPartKind.GROUP, group))
            chunk = StringBuilder()
        }
        chunk.append(digitStrings[integerPart[index] - '0'])
    }
    bodyParts.add(NumberPart(NumberPartKind.INTEGER, chunk.toString()))
    if (fractionPart.isNotEmpty()) {
        bodyParts.add(NumberPart(NumberPartKind.DECIMAL, decimal))
        bodyParts.add(NumberPart(NumberPartKind.FRACTION, fractionPart.map { digitStrings[it - '0'] }.joinToString("")))
    }
    val effective = signPattern(pattern, symbols, options.signDisplay, negative, zero)
    val prefix = affixParts(effective.prefix, symbols, affix, affixLiteralKind)
    val suffix = affixParts(effective.suffix, symbols, affix, affixLiteralKind)
    val body = bodyParts.joinToString("") { it.value }
    val parts = buildList {
        addAll(prefix)
        if (currencySpacing) {
            val spacing = currencySpacingInsert(effective.prefix, prefix.joinToString("") { it.value }, body, digitStrings, atPrefix = true)
            if (spacing.isNotEmpty()) add(NumberPart(NumberPartKind.LITERAL, spacing))
        }
        addAll(bodyParts)
        if (currencySpacing) {
            val spacing =
                currencySpacingInsert(effective.suffix, suffix.joinToString("") { it.value }, body, digitStrings, atPrefix = false)
            if (spacing.isNotEmpty()) add(NumberPart(NumberPartKind.LITERAL, spacing))
        }
        addAll(suffix)
    }
    return FormattedNumber(parts.joinToString("") { it.value }, integerPart, fractionPart, compactExponent, parts)
}

private fun applyMultiplier(value: Decimal, multiplier: Int): Decimal = when (multiplier) {
    100 -> value.shifted(2)
    1000 -> value.shifted(3)
    else -> value
}

internal fun affixParts(
    raw: String,
    symbols: NumberSymbols,
    substitution: AffixSubstitution,
    literalKind: NumberPartKind = NumberPartKind.LITERAL,
): List<NumberPart> {
    val result = ArrayList<NumberPart>()
    var literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) {
            val value = literal.toString()
            if (literalKind == NumberPartKind.LITERAL) {
                result.add(NumberPart(literalKind, value))
            } else {
                var start = 0
                fun isLiteral(char: Char): Boolean = char.isWhitespace() ||
                    char in "()[]" ||
                    char == '\u00a0' ||
                    char == '\u202f' ||
                    char == '\u200e' ||
                    char == '\u200f' ||
                    char == '\u061c'
                while (start < value.length) {
                    val ordinary = isLiteral(value[start])
                    var end = start + 1
                    while (end < value.length && isLiteral(value[end]) == ordinary) end++
                    result.add(NumberPart(if (ordinary) NumberPartKind.LITERAL else literalKind, value.substring(start, end)))
                    start = end
                }
            }
        }
        literal = StringBuilder()
    }
    fun symbol(kind: NumberPartKind, value: String) {
        flush()
        var content = StringBuilder()
        fun contentPart() {
            if (content.isNotEmpty()) result.add(NumberPart(kind, content.toString()))
            content = StringBuilder()
        }
        for (char in value) {
            if (char == '\u061c' || char == '\u200e' || char == '\u200f') {
                contentPart()
                result.add(NumberPart(NumberPartKind.LITERAL, char.toString()))
            } else {
                content.append(char)
            }
        }
        contentPart()
    }
    var index = 0
    while (index < raw.length) {
        when (val char = raw[index++]) {
            '-' -> symbol(NumberPartKind.MINUS_SIGN, symbols.minusSign)
            '+' -> symbol(NumberPartKind.PLUS_SIGN, symbols.plusSign)
            '%' -> symbol(NumberPartKind.PERCENT_SIGN, symbols.percentSign)
            '\u2030' -> symbol(NumberPartKind.PER_MILLE, symbols.perMille)
            '¤' -> {
                var count = 1
                while (index < raw.length && raw[index] == '¤') {
                    count++
                    index++
                }
                symbol(NumberPartKind.CURRENCY, substitution.currencyText(count))
            }
            else -> literal.append(char)
        }
    }
    flush()
    return result
}

private class SignedAffixes(val prefix: String, val suffix: String)

/**
 * The affixes for one value, with the sign placeholders resolved.
 *
 * A pattern carries `-` and `+` as placeholders rather than as text, and UTS #35
 * says they are replaced by the locale's `minusSign` and `plusSign`. That is not
 * cosmetic: Arabic's minus sign carries a leading bidi mark, and several locales
 * write theirs as U+2212 rather than as the ASCII hyphen, so a hard-coded `-`
 * would be the wrong character rather than a plainer spelling of the right one.
 * The same holds for `%` and `‰`.
 */
private fun signPattern(
    pattern: NumberPattern,
    symbols: NumberSymbols,
    signDisplay: SignDisplay,
    negative: Boolean,
    zero: Boolean,
): SignedAffixes {
    fun resolved(prefix: String, suffix: String) = SignedAffixes(prefix, suffix)

    if (signDisplay == SignDisplay.NEVER) {
        return resolved(pattern.positivePrefix, pattern.positiveSuffix)
    }
    if (negative) {
        val negativePrefix = pattern.negativePrefix
        return if (negativePrefix != null) {
            resolved(negativePrefix, pattern.negativeSuffix.orEmpty())
        } else {
            resolved("-" + pattern.positivePrefix, pattern.positiveSuffix)
        }
    }
    val wantsPlus = signDisplay.showsPlus && (!zero || signDisplay.signsZero)
    if (!wantsPlus) return resolved(pattern.positivePrefix, pattern.positiveSuffix)
    val explicit = pattern.withExplicitPlus()
    return if (explicit !== pattern) {
        resolved(explicit.positivePrefix, explicit.positiveSuffix)
    } else {
        resolved("+" + pattern.positivePrefix, pattern.positiveSuffix)
    }
}

private fun StringBuilder.appendGrouped(
    integerPart: String,
    pattern: NumberPattern,
    symbols: NumberSymbols,
    grouping: NumberGrouping,
    separator: String,
    digitStrings: List<String>,
    /**
     * A floor on the locale's `minimumGroupingDigits`, which compact notation
     * raises to two. Both reference implementations do this, and it is what
     * makes German write a sentinel-magnitude 1000 as `1000` while still
     * writing 12000 as `12.000`.
     */
    groupingFloor: Int,
) {
    val length = integerPart.length
    val primary = pattern.primaryGroupSize
    val minimum = when (grouping) {
        NumberGrouping.NEVER -> Int.MAX_VALUE
        NumberGrouping.ALWAYS -> 1
        NumberGrouping.AUTO -> maxOf(symbols.minimumGroupingDigits, groupingFloor)
        NumberGrouping.MIN2 -> maxOf(2, groupingFloor)
    }
    val applyGrouping = primary > 0 && minimum != Int.MAX_VALUE && length >= primary + minimum
    for (index in 0 until length) {
        if (applyGrouping && index > 0) {
            val fromRight = length - index
            val afterPrimary = fromRight - primary
            if (afterPrimary == 0 || (afterPrimary > 0 && afterPrimary % pattern.secondaryGroupSize == 0)) {
                append(separator)
            }
        }
        append(digitStrings[integerPart[index] - '0'])
    }
}

internal fun renderAffix(affix: String, substitution: AffixSubstitution): String {
    if ('¤' !in affix) return affix
    return buildString(affix.length) {
        var index = 0
        while (index < affix.length) {
            if (affix[index] != '¤') {
                append(affix[index])
                index++
                continue
            }
            var run = 0
            while (index < affix.length && affix[index] == '¤') {
                run++
                index++
            }
            append(substitution.currencyText(run))
        }
    }
}

/**
 * CLDR's `alphaNextToNumber` variant applies when the character of the
 * substituted currency text that would sit against the number is a letter.
 */
@InternalKotlinxLocaleApi
public fun isAlphaAdjacent(pattern: NumberPattern, currencyText: String): Boolean {
    if (currencyText.isEmpty()) return false
    if (pattern.positivePrefix.endsWith('¤')) return currencyText.last().isLetter()
    if (pattern.positiveSuffix.startsWith('¤')) return currencyText.first().isLetter()
    return false
}

/**
 * CLDR's `insertBetween` for currency spacing.
 *
 * A constant rather than a generated field because CLDR declares
 * `currencySpacing` in `root.xml` alone and every one of the other 1121 locale
 * files inherits it unchanged, so a per-locale copy would be the same thirty
 * bytes 1121 times. The codegen asserts that this is still true of the pinned
 * release, and generation fails if a locale ever overrides the rule.
 */
private const val CURRENCY_SPACING_INSERT = "\u00A0"

/**
 * The space UTS #35 puts between a currency and the digits, or `""`.
 *
 * Three conditions, all of them CLDR's. The character next to the digits has to
 * be the currency itself rather than a literal the pattern wrote, which is what
 * [patternAffix] ending (or starting) in `¤` says and what stops a pattern that
 * already spells its own space from getting a second one. Then root's
 * `currencyMatch` of `[[:^S:]&[:^Z:]]` has to hold of the currency-side
 * character, so `kr` earns a space and `$` does not, and its `surroundingMatch`
 * of `[:digit:]` has to hold of the number-side one.
 *
 * The third condition is asked of [digitStrings] rather than of a `Char`. This
 * used to read `body.firstOrNull()?.isDigit()`, which is one UTF-16 unit, and
 * nine of the numbering systems CLDR ships live above the basic plane where a
 * digit is a surrogate pair. Chakma saw the half of U+11136 nearest the currency,
 * decided it was not a digit and dropped the space, so 29 locales wrote
 * `123.456BHD`. Asking the locale's own digits is exact and needs no
 * supplementary-plane character properties, which common Kotlin does not carry.
 */
private fun currencySpacingInsert(
    patternAffix: String,
    renderedAffix: String,
    body: String,
    digitStrings: List<String>,
    atPrefix: Boolean,
): String {
    val adjacentToCurrency = if (atPrefix) patternAffix.endsWith('¤') else patternAffix.startsWith('¤')
    if (!adjacentToCurrency) return ""
    val currencyChar = (if (atPrefix) renderedAffix.lastOrNull() else renderedAffix.firstOrNull()) ?: return ""
    if (currencyChar.category in CURRENCY_SPACING_EXCLUDED) return ""
    val nextToDigit = if (atPrefix) digitStrings.any(body::startsWith) else digitStrings.any(body::endsWith)
    if (!nextToDigit) return ""
    return CURRENCY_SPACING_INSERT
}

/** The `S` and `Z` general categories root's `currencyMatch` excludes. */
private val CURRENCY_SPACING_EXCLUDED = setOf(
    CharCategory.MATH_SYMBOL,
    CharCategory.CURRENCY_SYMBOL,
    CharCategory.MODIFIER_SYMBOL,
    CharCategory.OTHER_SYMBOL,
    CharCategory.SPACE_SEPARATOR,
    CharCategory.LINE_SEPARATOR,
    CharCategory.PARAGRAPH_SEPARATOR,
)

/** The ten digits as strings, supporting supplementary-plane numbering systems. */
@InternalKotlinxLocaleApi
public fun digitStringsOf(digits: String): List<String> {
    val result = ArrayList<String>(10)
    var index = 0
    while (index < digits.length) {
        val length = if (digits[index].isHighSurrogate() && index + 1 < digits.length) 2 else 1
        result.add(digits.substring(index, index + length))
        index += length
    }
    if (result.size != 10) {
        result.clear()
        for (ch in "0123456789") result.add(ch.toString())
    }
    return result
}

private fun digitStringsOf(digits: List<String>): List<String> = digits

/** Rescales [value] to [digits] fraction digits, half-even. */
@InternalKotlinxLocaleApi
public fun Decimal.atFractionDigits(digits: Int): Decimal = rescaled(digits)

/**
 * The four sign and scale placeholders in an affix, replaced by [symbols].
 *
 * One pass rather than four `replace` calls, so a symbol that happens to contain
 * another placeholder cannot be rewritten a second time. `¤` is left alone: it
 * is the currency placeholder and belongs to the affix substitution.
 */
private fun String.withSymbols(symbols: NumberSymbols): String {
    if (none { it == '-' || it == '+' || it == '%' || it == '\u2030' }) return this
    return buildString(length) {
        for (ch in this@withSymbols) {
            when (ch) {
                '-' -> append(symbols.minusSign)
                '+' -> append(symbols.plusSign)
                '%' -> append(symbols.percentSign)
                '\u2030' -> append(symbols.perMille)
                else -> append(ch)
            }
        }
    }
}

@InternalKotlinxLocaleApi
public fun renderScientific(
    value: Decimal,
    pattern: NumberPattern,
    symbols: NumberSymbols,
    options: NumberFormatOptions,
    affix: AffixSubstitution = AffixSubstitution.None,
    useCurrencySeparators: Boolean = false,
): FormattedNumber {
    val scaled = if (options.preRounded) value else applyMultiplier(value, pattern.multiplier)
    val step = if (options.notation == NumberNotation.ENGINEERING) 3 else 1
    var exponent: Int =
        options.notationExponent?.takeIf { options.preRounded }
            ?: if (scaled.isZero) 0 else scaled.magnitude - ((scaled.magnitude % step + step) % step)
    fun mantissa(): Decimal = (if (options.preRounded) scaled else scaled.shifted(-exponent)).let {
        if (options.preRounded) it else numberRound(it, options, pattern.minimumFractionDigits, pattern.maximumFractionDigits)
    }
    var rounded = mantissa()
    if (!options.preRounded && !rounded.isZero && rounded.magnitude >= step) {
        exponent += step
        rounded = mantissa()
    }
    val base =
        renderNumber(
            rounded,
            pattern,
            symbols,
            options.copy(preRounded = true, notation = NumberNotation.STANDARD, grouping = NumberGrouping.NEVER),
            affix = affix,
            useCurrencySeparators = useCurrencySeparators,
        )
    val exponentParts = buildList {
        add(NumberPart(NumberPartKind.EXPONENT_SEPARATOR, symbols.exponential))
        if (exponent < 0) add(NumberPart(NumberPartKind.EXPONENT_MINUS_SIGN, symbols.minusSign))
        add(
            NumberPart(
                NumberPartKind.EXPONENT_INTEGER,
                exponent.toString().removePrefix("-").map {
                    symbols.digits[it - '0']
                }.joinToString(""),
            ),
        )
    }
    val insert = base.parts.indexOfLast { it.kind == NumberPartKind.INTEGER || it.kind == NumberPartKind.FRACTION } + 1
    val parts = base.parts.take(insert) + exponentParts + base.parts.drop(insert)
    return FormattedNumber(
        parts.joinToString("") {
            it.value
        },
        base.operands.integerDigits,
        base.operands.fractionDigits,
        parts = parts,
        notationExponent = exponent,
    )
}

@InternalKotlinxLocaleApi
public fun renderSpecialNumber(
    value: NumberValue,
    pattern: NumberPattern,
    symbols: NumberSymbols,
    options: NumberFormatOptions,
    affix: AffixSubstitution = AffixSubstitution.None,
    useCurrencySeparators: Boolean = false,
): FormattedNumber {
    val negative = value == NumberValue.NegativeInfinity
    val base =
        renderNumber(
            Decimal.ofDigits(if (value == NumberValue.NaN) "0" else "1", 0, negative),
            pattern,
            symbols,
            options.copy(preRounded = true),
            affix = affix,
            useCurrencySeparators = useCurrencySeparators,
            currencySpacing = true,
        )
    var inserted = false
    val parts = buildList {
        for (part in base.parts) {
            if (part.kind in setOf(NumberPartKind.INTEGER, NumberPartKind.FRACTION, NumberPartKind.GROUP, NumberPartKind.DECIMAL)) {
                if (!inserted) {
                    add(
                        if (value ==
                            NumberValue.NaN
                        ) {
                            NumberPart(NumberPartKind.NAN, symbols.nan)
                        } else {
                            NumberPart(NumberPartKind.INFINITY, symbols.infinity)
                        },
                    )
                    inserted = true
                }
            } else {
                add(part)
            }
        }
    }
    return FormattedNumber(parts.joinToString("") { it.value }, "0", "", parts = parts)
}
