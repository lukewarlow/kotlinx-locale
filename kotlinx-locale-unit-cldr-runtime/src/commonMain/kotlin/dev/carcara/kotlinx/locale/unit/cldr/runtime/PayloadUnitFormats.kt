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

package dev.carcara.kotlinx.locale.unit.cldr.runtime

import dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.internal.sparseRecordValue
import dev.carcara.kotlinx.locale.internal.supportedLocalesOf
import dev.carcara.kotlinx.locale.number.FormattedNumber
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberFormatSource
import dev.carcara.kotlinx.locale.number.NumberPart
import dev.carcara.kotlinx.locale.number.NumberPartKind
import dev.carcara.kotlinx.locale.number.NumberValue
import dev.carcara.kotlinx.locale.number.PluralCategory
import dev.carcara.kotlinx.locale.number.PluralRuleSource
import dev.carcara.kotlinx.locale.number.PluralType
import dev.carcara.kotlinx.locale.unit.UnitFormatSource
import dev.carcara.kotlinx.locale.unit.UnitWidth

@OptIn(InternalKotlinxLocaleApi::class)
public class PayloadUnitFormats(
    private val records: Map<String, String>,
    private val numbers: NumberFormatSource,
    private val plurals: PluralRuleSource,
) : UnitFormatSource {
    override val supportedLocales: Set<Locale> by lazy { supportedLocalesOf(records) }
    private fun lookup(key: String, locale: Locale): String? = sparseRecordValue(records, locale, 1, 2, key)
    private fun pattern(unit: String, category: String, width: UnitWidth, locale: Locale): String? {
        for (candidate in listOf(width.name.lowercase(), "short", "long").distinct()) {
            lookup("$candidate/$unit/$category", locale)?.let { return it }
            lookup("$candidate/$unit/other", locale)?.let { return it }
        }
        return null
    }
    override fun render(
        value: NumberValue,
        unit: String,
        locale: Locale,
        width: UnitWidth,
        options: NumberFormatOptions,
    ): LocaleLookup<FormattedNumber> = when (val result = numbers.render(value, locale, options)) {
        is LocaleLookup.Value -> compose(result.value, unit, locale, width)
        LocaleLookup.MissingData -> LocaleLookup.MissingData
        is LocaleLookup.Unsupported -> result
    }

    override fun compose(number: FormattedNumber, unit: String, locale: Locale, width: UnitWidth): LocaleLookup<FormattedNumber> {
        val category = if (number.isFinite) {
            plurals.pluralCategoryOrNull(number.quantityOperands, PluralType.CARDINAL, locale)
                ?: PluralCategory.OTHER
        } else {
            PluralCategory.OTHER
        }
        val direct = pattern(unit, category.name.lowercase(), width, locale)
        if (direct != null) return LocaleLookup.Value(apply(direct, number, unit))
        val split = unit.split("-per-")
        if (split.size != 2) return LocaleLookup.MissingData
        val numerator = compose(number, split[0], locale, width)
        if (numerator !is LocaleLookup.Value) return numerator
        for (candidate in listOf(width.name.lowercase(), "short", "long").distinct()) {
            lookup("$candidate/${split[1]}/per", locale)?.let {
                return LocaleLookup.Value(apply(it, numerator.value, unit))
            }
        }
        val denominator = lookup("${width.name.lowercase()}/${split[1]}/name", locale)
            ?: lookup("long/${split[1]}/name", locale) ?: return LocaleLookup.MissingData
        val compound = lookup("${width.name.lowercase()}/compound-per", locale)
            ?: lookup("long/compound-per", locale) ?: return LocaleLookup.MissingData
        val marker = compound.indexOf("{1}")
        if (marker < 0) return LocaleLookup.MissingData
        return LocaleLookup.Value(apply(compound.replace("{1}", denominator), numerator.value, unit))
    }

    private fun apply(pattern: String, number: FormattedNumber, unit: String): FormattedNumber {
        val marker = pattern.indexOf("{0}")
        val parts = ArrayList<NumberPart>()
        fun appendWording(value: String) {
            var start = 0
            while (start < value.length) {
                fun literal(char: Char): Boolean =
                    char.isWhitespace() || char == '\u00a0' || char == '\u202f' || char == '\u200e' || char == '\u200f' || char == '\u061c'
                val isLiteral = literal(value[start])
                var end = start + 1
                while (end < value.length && literal(value[end]) == isLiteral) end++
                parts.add(
                    NumberPart(
                        if (isLiteral) NumberPartKind.LITERAL else NumberPartKind.UNIT,
                        value.substring(start, end),
                        if (isLiteral) null else unit,
                    ),
                )
                start = end
            }
        }
        if (marker < 0) {
            appendWording(pattern)
        } else {
            appendWording(pattern.take(marker))
            parts.addAll(number.parts.map { NumberPart(it.kind, it.value, if (it.kind == NumberPartKind.LITERAL) null else unit) })
            appendWording(pattern.drop(marker + 3))
        }
        return FormattedNumber(
            parts.joinToString("") {
                it.value
            },
            number.operands.integerDigits,
            number.operands.fractionDigits,
            number.c,
            parts,
            number.notationExponent,
        )
    }
    public companion object
}
