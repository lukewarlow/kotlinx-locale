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

import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.datetime.CalendarDateFields
import dev.carcara.kotlinx.locale.datetime.DateTimeField
import dev.carcara.kotlinx.locale.datetime.NameContext
import dev.carcara.kotlinx.locale.datetime.SelectedDateTimeFormat
import dev.carcara.kotlinx.locale.datetime.TextStyle
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberFormatSource
import dev.carcara.kotlinx.locale.number.NumberGrouping

internal val CALENDAR_DATE_FIELDS = setOf(
    DateTimeField.ERA,
    DateTimeField.YEAR,
    DateTimeField.RELATED_YEAR,
    DateTimeField.YEAR_NAME,
    DateTimeField.MONTH,
    DateTimeField.DAY,
    DateTimeField.WEEKDAY,
    DateTimeField.QUARTER,
)

@OptIn(dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi::class)
internal fun renderCalendarField(
    token: PatternToken.Field,
    value: CalendarDateFields,
    data: DateTimeRecord,
    format: SelectedDateTimeFormat,
    numbers: NumberFormatSource?,
): LocaleLookup<String> {
    fun missing(field: String): LocaleLookup<String> = LocaleLookup.Unsupported("Calendar input $field is required")
    fun name(key: String): LocaleLookup<String> = data.calendarValueOrNull(key)?.let { LocaleLookup.Value(it) } ?: LocaleLookup.MissingData
    val width = if (token.count >= 5) {
        "narrow"
    } else if (token.count == 4) {
        "wide"
    } else {
        "abbreviated"
    }
    val context = if (token.letter in "Lcq") "stand-alone" else "format"
    fun numeric(input: Int?, minimumDigits: Int = token.count, truncateYear: Boolean = false): LocaleLookup<String> {
        if (input == null) return missing(token.letter.toString())
        val number = if (truncateYear && token.count == 2) input % 100 else input
        val source = numbers ?: return LocaleLookup.Unsupported("Number source is required for calendar fields")
        return source.formatOrNull(
            Decimal.of(number.toLong()),
            format.locale,
            NumberFormatOptions(
                grouping = NumberGrouping.NEVER,
                minimumFractionDigits = 0,
                maximumFractionDigits = 0,
                minimumIntegerDigits = minimumDigits.coerceAtLeast(1),
                numberingSystem = format.numberingSystem,
            ),
        )
            ?.let { LocaleLookup.Value(it.text) } ?: LocaleLookup.MissingData
    }
    return when (token.letter) {
        'G' -> value.era?.let {
            if (data.calendarIdentifier == "gregory") {
                val index = when (it) {
                    "bce" -> 0
                    "ce" -> 1
                    else -> return LocaleLookup.MissingData
                }
                LocaleLookup.Value(data.eraName(index, token.count))
            } else {
                name("era/$width/$it")
            }
        } ?: missing("era")
        'y' -> {
            val first = data.calendarValueOrNull("firstYear/value")
            val literal = data.calendarValueOrNull("firstYear/literal")
            if (value.eraYear == 1 &&
                value.era != "bce" &&
                value.era != "ce" &&
                first != null &&
                literal != null &&
                format.pattern.contains(literal)
            ) {
                LocaleLookup.Value(first)
            } else {
                numeric(value.eraYear, truncateYear = true)
            }
        }
        'u' -> numeric(value.year)
        'r' -> numeric(value.relatedYear)
        'U' -> value.cyclicYear?.let { name("cyclic/$width/$it") } ?: missing("cyclicYear")
        'M', 'L' -> {
            if (token.count < 3) {
                val result = numeric(value.monthNumber)
                if (result is LocaleLookup.Value && value.monthCode?.endsWith("L") == true && value.calendar != "hebrew") {
                    val pattern = data.calendarValueOrNull("leap/$context/numeric") ?: return LocaleLookup.MissingData
                    LocaleLookup.Value(substitute(pattern, result.value))
                } else {
                    result
                }
            } else {
                val code = value.monthCode ?: return missing("monthCode")
                val leapCode = code.endsWith("L")
                val leapYear = value.isLeapYear && value.calendar == "hebrew" && code == "M06"
                val exact = if (leapYear) "$code-leap" else code
                if (data.calendarIdentifier == "gregory" && !leapCode) {
                    val month = code.drop(1).toInt()
                    if (month !in 1..12) return LocaleLookup.MissingData
                    val style = if (token.count >= 5) {
                        TextStyle.NARROW
                    } else if (token.count == 4) {
                        TextStyle.FULL
                    } else {
                        TextStyle.ABBREVIATED
                    }
                    return LocaleLookup.Value(
                        data.month(
                            month - 1,
                            style,
                            if (context ==
                                "format"
                            ) {
                                NameContext.FORMAT
                            } else {
                                NameContext.STANDALONE
                            },
                        ),
                    )
                }
                val names = data.calendarValueOrNull("month/$context/$width/$exact")
                    ?: (
                        if (leapCode &&
                            value.calendar != "hebrew"
                        ) {
                            data.calendarValueOrNull("month/$context/$width/${code.dropLast(1)}")
                        } else {
                            null
                        }
                        )
                    ?: return LocaleLookup.MissingData
                if (leapCode && value.calendar != "hebrew") {
                    val pattern = data.calendarValueOrNull("leap/$context/$width") ?: return LocaleLookup.MissingData
                    LocaleLookup.Value(substitute(pattern, names))
                } else {
                    LocaleLookup.Value(names)
                }
            }
        }
        'd' -> numeric(value.day)
        'E', 'e', 'c' -> {
            val weekday = value.weekday ?: return missing("weekday")
            if (token.letter != 'E' && token.count < 3) return LocaleLookup.Unsupported("Locale-relative numeric weekday rendering")
            val style = if (token.count >= 5) {
                TextStyle.NARROW
            } else if (token.count == 4) {
                TextStyle.FULL
            } else {
                TextStyle.ABBREVIATED
            }
            LocaleLookup.Value(data.dayOfWeek(weekday - 1, style, if (context == "format") NameContext.FORMAT else NameContext.STANDALONE))
        }
        else -> LocaleLookup.Unsupported("Calendar field ${token.letter}")
    }
}
