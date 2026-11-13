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

@file:OptIn(dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi::class)

package dev.carcara.kotlinx.locale.datetime.cldr.runtime

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.datetime.DurationPatternSource
import dev.carcara.kotlinx.locale.datetime.DurationStyle
import dev.carcara.kotlinx.locale.number.NumberFormatSource

public data class DigitalDurationFormat(
    public val hourMinuteSeparator: String,
    public val minuteSecondSeparator: String,
    public val twoDigitHours: Boolean,
) {
    public companion object
}

/** Resolves clock literals and padding; the caller chooses and formats duration fields. */
public fun durationDigitalFormat(
    locale: Locale,
    patterns: DurationPatternSource,
    numbers: NumberFormatSource? = null,
    numberingSystem: String? = null,
): LocaleLookup<DigitalDurationFormat> {
    val pattern = patterns.durationPatternOrNull(DurationStyle.HOUR_MINUTE_SECOND, locale) ?: return LocaleLookup.MissingData
    val tokens = parseDateTimePattern(pattern)
    val fields = tokens.filterIsInstance<PatternToken.Field>()
    if (fields.map { it.letter } != listOf('h', 'm', 's') &&
        fields.map { it.letter } != listOf('H', 'm', 's')
    ) {
        return LocaleLookup.Unsupported("Duration clock grammar: $pattern")
    }
    if (tokens.firstOrNull() !is PatternToken.Field ||
        tokens.lastOrNull() !is PatternToken.Field
    ) {
        return LocaleLookup.Unsupported("Duration clock affixes: $pattern")
    }
    val separator = numberingSystem?.let {
        numbers?.symbolsOrNull(locale, it)?.timeSeparator
            ?: return LocaleLookup.Unsupported("Numbering system $it")
    }
    val first = tokens.indexOf(fields[0])
    val second = tokens.indexOf(fields[1])
    val third = tokens.indexOf(fields[2])
    fun literals(from: Int, to: Int): String = tokens.subList(from + 1, to).joinToString("") { (it as PatternToken.Literal).text }.let {
        if (separator ==
            null
        ) {
            it
        } else {
            it.replace(":", separator)
        }
    }
    return LocaleLookup.Value(DigitalDurationFormat(literals(first, second), literals(second, third), fields[0].count >= 2))
}
