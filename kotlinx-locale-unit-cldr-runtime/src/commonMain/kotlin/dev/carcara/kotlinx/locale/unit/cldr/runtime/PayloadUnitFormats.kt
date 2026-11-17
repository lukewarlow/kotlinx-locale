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
import dev.carcara.kotlinx.locale.number.NumberValue
import dev.carcara.kotlinx.locale.number.PluralCategory
import dev.carcara.kotlinx.locale.number.PluralRuleSource
import dev.carcara.kotlinx.locale.number.PluralType
import dev.carcara.kotlinx.locale.number.numberComposeUnit
import dev.carcara.kotlinx.locale.unit.UnitFormatSource
import dev.carcara.kotlinx.locale.unit.UnitWidth
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(InternalKotlinxLocaleApi::class)
public class PayloadUnitFormats(
    records: Map<String, String>,
    private val numbers: NumberFormatSource,
    private val plurals: PluralRuleSource,
) : UnitFormatSource {
    private val records = records.toMap()
    private val patterns = PreparedUnitCache<Pair<String, String>, String?>(512)

    @OptIn(dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi::class)
    override val localeCoverage: dev.carcara.kotlinx.locale.LocaleCoverage by lazy {
        dev.carcara.kotlinx.locale.internal.payloadLocaleCoverage(this.records, supportedLocales)
    }
    override val supportedLocales: Set<Locale> by lazy { supportedLocalesOf(this.records) }
    private fun lookup(key: String, locale: Locale): String? = patterns.getOrPut(locale.stripExtensions().toLanguageTag() to key) {
        sparseRecordValue(records, locale, 1, 2, key)
    }
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
        val pattern = unitPattern(unit, category.name.lowercase(), width, locale) ?: return LocaleLookup.MissingData
        return LocaleLookup.Value(numberComposeUnit(number, pattern, unit))
    }

    /** Expand compound templates before rendering so the entire unit affix keeps its semantic boundary. */
    private fun unitPattern(unit: String, category: String, width: UnitWidth, locale: Locale): String? {
        pattern(unit, category, width, locale)?.let { return it }
        val split = unit.split("-per-")
        if (split.size != 2) return null
        val numerator = unitPattern(split[0], category, width, locale) ?: return null
        for (candidate in listOf(width.name.lowercase(), "short", "long").distinct()) {
            lookup("$candidate/${split[1]}/per", locale)?.let { return it.replace("{0}", numerator) }
        }
        val denominator = lookup("${width.name.lowercase()}/${split[1]}/name", locale)
            ?: lookup("long/${split[1]}/name", locale) ?: return null
        val compound = lookup("${width.name.lowercase()}/compound-per", locale)
            ?: lookup("long/compound-per", locale) ?: return null
        if ("{1}" !in compound) return null
        return compound.replace("{1}", denominator).replace("{0}", numerator)
    }
    public companion object
}

/** Bounded immutable publication, including negative lookups; never mutate a shared map. */
@OptIn(ExperimentalAtomicApi::class)
private class PreparedUnitCache<K, V>(private val limit: Int = 64) {
    private val entries = AtomicReference<Map<K, V>>(emptyMap())
    fun getOrPut(key: K, create: () -> V): V {
        val initial = entries.load()
        if (initial.containsKey(key)) return initial.getValue(key)
        val value = create()
        while (true) {
            val previous = entries.load()
            if (previous.containsKey(key)) return previous.getValue(key)
            val retained = if (previous.size < limit) previous else previous.entries.drop(1).associate { it.key to it.value }
            if (entries.compareAndSet(previous, retained + (key to value))) return value
        }
    }
}
