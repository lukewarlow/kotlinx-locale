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
import dev.carcara.kotlinx.locale.currency.Currency
import dev.carcara.kotlinx.locale.currency.CurrencyFormatOptions
import dev.carcara.kotlinx.locale.currency.CurrencyNameSource
import dev.carcara.kotlinx.locale.currency.code
import dev.carcara.kotlinx.locale.currency.displayName
import dev.carcara.kotlinx.locale.currency.minorUnitDigits
import dev.carcara.kotlinx.locale.currency.symbol
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberNotation
import dev.carcara.kotlinx.locale.number.PluralCategory
import dev.carcara.kotlinx.locale.number.SignDisplay
import dev.carcara.kotlinx.locale.number.cldr.runtime.AffixSubstitution
import dev.carcara.kotlinx.locale.number.cldr.runtime.CompactPatternTable
import dev.carcara.kotlinx.locale.number.cldr.runtime.FormattedNumberSelector
import dev.carcara.kotlinx.locale.number.cldr.runtime.NumberPattern
import dev.carcara.kotlinx.locale.number.cldr.runtime.formatCompact
import dev.carcara.kotlinx.locale.number.cldr.runtime.isAlphaAdjacent
import dev.carcara.kotlinx.locale.number.cldr.runtime.renderNumber
import dev.carcara.kotlinx.locale.number.cldr.runtime.renderScientific

/**
 * Writes an amount with the pattern and symbols of [locale].
 *
 * The number itself is rendered by the shared engine in
 * `kotlinx-locale-number-cldr-runtime`. What is left here is what is actually
 * about money: choosing between the standard and accounting patterns, switching
 * to the `alphaNextToNumber` variant when the symbol that lands against the
 * digits is a letter, applying the currency's fraction digits and rounding
 * increment, and filling the `¤` placeholder.
 */
internal fun formatCurrency(
    data: CurrencyNumberFormat,
    names: CurrencyNameSource,
    compact: CompactPatternTable,
    selectCategory: FormattedNumberSelector,
    minorUnits: Long,
    currency: Currency,
    locale: Locale,
    options: CurrencyFormatOptions,
): String {
    val currencyText = names.symbol(currency, locale, options.style)

    val scaled = scaleCurrencyAmount(minorUnits, currency, options.cash, options.fractionDigits)
    val amount = scaled.value
    val digits = scaled.fractionDigits

    val accounting = options.signDisplay.usesAccountingPattern
    val basePattern = if (accounting) data.accountingPattern else data.standardPattern
    val alphaPattern = if (accounting) data.accountingAlphaPattern else data.standardAlphaPattern
    var pattern = preparedCurrencyPattern(basePattern)
    if (alphaPattern != basePattern && isAlphaAdjacent(pattern, currencyText)) {
        pattern = preparedCurrencyPattern(alphaPattern)
    }

    val affix = AffixSubstitution { run ->
        when (run) {
            1 -> currencyText
            2 -> currency.code
            else -> names.displayName(currency, locale)
        }
    }
    val numberOptions = NumberFormatOptions(
        notation = options.notation,
        signDisplay = options.signDisplay,
        grouping = options.grouping,
    )

    val formatted = if (options.notation == NumberNotation.SCIENTIFIC || options.notation == NumberNotation.ENGINEERING) {
        renderScientific(
            amount,
            pattern,
            data.symbols,
            numberOptions.copy(minimumFractionDigits = digits, maximumFractionDigits = digits),
            affix,
            true,
        )
    } else if (options.notation == NumberNotation.STANDARD || compact.isEmpty) {
        renderNumber(
            value = amount,
            pattern = pattern,
            symbols = data.symbols,
            options = numberOptions,
            fixedFractionDigits = digits,
            useCurrencySeparators = true,
            affix = affix,
            currencySpacing = true,
        )
    } else {
        formatCompact(
            value = amount,
            table = compact,
            standardPattern = pattern,
            symbols = data.symbols,
            selectCategory = selectCategory,
            options = numberOptions,
            fixedFractionDigits = options.fractionDigits,
            useCurrencySeparators = true,
            currencyText = currencyText,
            affix = affix,
            currencySpacing = true,
        )
    }
    return formatted.text
}

/** An amount rounded onto CLDR's scale, and the digit count it prints at. */
internal class ScaledCurrencyAmount(val value: Decimal, val fractionDigits: Int)

/**
 * [minorUnits] of [currency] rounded the way CLDR says to round it.
 *
 * CLDR's digit count for the currency, then its rounding increment, then any
 * override the caller asked for. The order matters: the increment is expressed
 * in units of CLDR's last fraction digit.
 *
 * Exact decimal rescaling preserves a negative-zero sign and avoids overflow
 * when the currency's display scale is wider than its ISO storage scale.
 */
internal fun scaleCurrencyAmount(minorUnits: Long, currency: Currency, cash: Boolean, fractionDigits: Int?): ScaledCurrencyAmount {
    val cldrDigits = if (cash) currency.cldrCashFractionDigits else currency.cldrFractionDigits
    val increment = if (cash) currency.cldrCashRoundingIncrement else currency.cldrRoundingIncrement
    val value = Decimal.ofUnscaled(minorUnits, currency.minorUnitDigits).rescaled(cldrDigits, increment = maxOf(increment, 1))
    return ScaledCurrencyAmount(value, fractionDigits ?: cldrDigits)
}

/** The plural category of a formatted number, which compact pattern selection needs. */
internal val NO_PLURAL_RULES: FormattedNumberSelector = FormattedNumberSelector { PluralCategory.OTHER }

/** True when [signDisplay] asks for CLDR's accounting pattern. */
internal fun usesAccounting(signDisplay: SignDisplay): Boolean = signDisplay.usesAccountingPattern

private val preparedPatterns = PreparedCurrencyCache<String, NumberPattern>(256)

internal fun preparedCurrencyPattern(pattern: String): NumberPattern = preparedPatterns.getOrPut(pattern) { NumberPattern.parse(pattern) }
