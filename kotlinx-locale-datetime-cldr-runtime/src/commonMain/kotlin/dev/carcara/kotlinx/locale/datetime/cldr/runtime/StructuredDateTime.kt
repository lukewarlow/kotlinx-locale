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
import dev.carcara.kotlinx.locale.datetime.DateTimeField
import dev.carcara.kotlinx.locale.datetime.DateTimeFieldWidth
import dev.carcara.kotlinx.locale.datetime.DateTimePart
import dev.carcara.kotlinx.locale.datetime.DateTimePartKind
import dev.carcara.kotlinx.locale.datetime.DateTimeRenderContext
import dev.carcara.kotlinx.locale.datetime.DateTimeZoneNameStyle
import dev.carcara.kotlinx.locale.datetime.FormattedDateTime
import dev.carcara.kotlinx.locale.datetime.HourCycle
import dev.carcara.kotlinx.locale.datetime.SelectedDateTimeFormat
import dev.carcara.kotlinx.locale.datetime.format
import dev.carcara.kotlinx.locale.number.NumberFormatSource
import dev.carcara.kotlinx.locale.timezone.TimeZoneNameSource
import dev.carcara.kotlinx.locale.timezone.TimeZoneNameStyle

internal fun selectedDateTimeFormat(pattern: String, locale: Locale, numberingSystem: String?): SelectedDateTimeFormat {
    val fields = linkedMapOf<DateTimeField, DateTimeFieldWidth>()
    var cycle: HourCycle? = null
    var fraction: Int? = null
    var zoneStyle: DateTimeZoneNameStyle? = null
    for (token in parseDateTimePattern(pattern)) {
        if (token !is PatternToken.Field) continue
        val field = dateTimeFieldOrNull(token.letter) ?: continue
        fields[field] = when {
            token.letter in "GabBEecvzVO" -> when {
                token.count >= 5 -> DateTimeFieldWidth.NARROW
                token.count == 4 -> DateTimeFieldWidth.LONG
                else -> DateTimeFieldWidth.SHORT
            }
            token.letter in "MLQq" && token.count >= 3 -> when (token.count) {
                3 -> DateTimeFieldWidth.SHORT
                4 -> DateTimeFieldWidth.LONG
                else -> DateTimeFieldWidth.NARROW
            }
            token.count == 2 -> DateTimeFieldWidth.TWO_DIGIT
            else -> DateTimeFieldWidth.NUMERIC
        }
        if (token.letter in
            "hHKk"
        ) {
            cycle = when (token.letter) {
                'h' -> HourCycle.H12
                'H' -> HourCycle.H23
                'K' -> HourCycle.H11
                else -> HourCycle.H24
            }
        }
        if (token.letter == 'S') fraction = token.count
        zoneStyle = when (token.letter) {
            'z' -> if (token.count >= 4) DateTimeZoneNameStyle.SPECIFIC_LONG else DateTimeZoneNameStyle.SPECIFIC_SHORT
            'v' -> if (token.count >= 4) DateTimeZoneNameStyle.GENERIC_LONG else DateTimeZoneNameStyle.GENERIC_SHORT
            'O' -> if (token.count >= 4) DateTimeZoneNameStyle.OFFSET_LONG else DateTimeZoneNameStyle.OFFSET_SHORT
            'V' -> DateTimeZoneNameStyle.LOCATION
            else -> zoneStyle
        }
    }
    return SelectedDateTimeFormat(
        locale,
        pattern,
        fields.toMap(),
        cycle,
        fraction,
        numberingSystem = numberingSystem,
        zoneNameStyle = zoneStyle,
    )
}

internal fun dateTimeFieldOrNull(letter: Char): DateTimeField? = when (letter) {
    'G' -> DateTimeField.ERA
    'y', 'u' -> DateTimeField.YEAR
    'M', 'L' -> DateTimeField.MONTH
    'd', 'D' -> DateTimeField.DAY
    'E', 'e', 'c' -> DateTimeField.WEEKDAY
    'a', 'b', 'B' -> DateTimeField.DAY_PERIOD
    'h', 'H', 'K', 'k' -> DateTimeField.HOUR
    'm' -> DateTimeField.MINUTE
    's' -> DateTimeField.SECOND
    'S' -> DateTimeField.FRACTIONAL_SECOND
    'z', 'v', 'O', 'Z', 'X', 'x', 'V' -> DateTimeField.TIME_ZONE_NAME
    'Q', 'q' -> DateTimeField.QUARTER
    else -> null
}

internal fun renderDateTimeTokens(
    tokens: List<PatternToken>,
    context: DateTimeRenderContext,
    format: SelectedDateTimeFormat,
    data: DateTimeRecord,
    skeletons: SkeletonRecord? = null,
    numbers: NumberFormatSource? = null,
    zones: TimeZoneNameSource? = null,
): LocaleLookup<FormattedDateTime> {
    if (format.calendar != "gregory") return LocaleLookup.Unsupported("Calendar ${format.calendar}")
    val symbols = format.numberingSystem?.let { system ->
        numbers?.symbolsOrNull(format.locale, system)
            ?: if (system == data.defaultNumberingSystem) null else return LocaleLookup.Unsupported("Numbering system $system")
    }
    val parts = ArrayList<DateTimePart>()
    for (token in tokens) {
        if (token is PatternToken.Literal) {
            parts += DateTimePart(DateTimePartKind.LITERAL, token.text)
            continue
        }
        token as PatternToken.Field
        val field = dateTimeFieldOrNull(token.letter) ?: return LocaleLookup.Unsupported("Date field ${token.letter}")
        val text = when {
            field == DateTimeField.TIME_ZONE_NAME -> {
                val zone = context.zone ?: return LocaleLookup.Unsupported("Zone context is required")
                val style = when (token.letter) {
                    'v' -> if (token.count >= 4) TimeZoneNameStyle.GENERIC_LONG else TimeZoneNameStyle.GENERIC_SHORT
                    'O' -> if (token.count >= 4) TimeZoneNameStyle.OFFSET_LONG else TimeZoneNameStyle.OFFSET_SHORT
                    'z' -> if (zone.isDaylight ==
                        true
                    ) {
                        if (token.count >= 4) TimeZoneNameStyle.DAYLIGHT_LONG else TimeZoneNameStyle.DAYLIGHT_SHORT
                    } else {
                        if (zone.isDaylight ==
                            null
                        ) {
                            return LocaleLookup.Unsupported("Daylight status is required for specific zone names")
                        }
                        if (token.count >= 4) TimeZoneNameStyle.STANDARD_LONG else TimeZoneNameStyle.STANDARD_SHORT
                    }
                    'V' -> if (token.count ==
                        4
                    ) {
                        TimeZoneNameStyle.LOCATION
                    } else {
                        return LocaleLookup.Unsupported("Zone field V${token.count}")
                    }
                    else -> return LocaleLookup.Unsupported("ISO/RFC zone field ${token.letter}")
                }
                val source = zones ?: return LocaleLookup.Unsupported("Zone name source is required")
                when (
                    val rendered = source.render(
                        zone.copy(numberingSystem = format.numberingSystem ?: zone.numberingSystem),
                        style,
                        format.locale,
                    )
                ) {
                    is LocaleLookup.Value -> rendered.value
                    is LocaleLookup.Unsupported -> return rendered
                    LocaleLookup.MissingData -> return LocaleLookup.MissingData
                }
            }
            else -> {
                val needsDate =
                    field in
                        setOf(
                            DateTimeField.ERA,
                            DateTimeField.YEAR,
                            DateTimeField.MONTH,
                            DateTimeField.DAY,
                            DateTimeField.WEEKDAY,
                            DateTimeField.QUARTER,
                        )
                if (needsDate &&
                    context.date == null ||
                    !needsDate &&
                    context.time == null
                ) {
                    return LocaleLookup.Unsupported("Local ${if (needsDate) "date" else "time"} is required")
                }
                if (field == DateTimeField.QUARTER && skeletons == null) return LocaleLookup.Unsupported("Quarter names are not included")
                var rendered = formatPattern(listOf(token), data, context.date, context.time, skeletons)
                val numeric =
                    field !in setOf(DateTimeField.ERA, DateTimeField.WEEKDAY, DateTimeField.DAY_PERIOD) &&
                        !(field in setOf(DateTimeField.MONTH, DateTimeField.QUARTER) && token.count >= 3)
                if (symbols != null && numeric) {
                    val oldDigits = codePointsAsStrings(data.digits)
                    rendered = buildString {
                        var i = 0
                        while (i < rendered.length) {
                            val digit = oldDigits.indexOfFirst { rendered.startsWith(it, i) }
                            if (digit >= 0) {
                                append(symbols.digits[digit])
                                i += oldDigits[digit].length
                            } else {
                                append(rendered[i])
                                i++
                            }
                        }
                    }
                }
                rendered
            }
        }
        parts += DateTimePart(DateTimePartKind.valueOf(field.name), text)
    }
    return LocaleLookup.Value(FormattedDateTime(parts))
}

private fun codePointsAsStrings(text: String): List<String> = buildList {
    var i = 0
    while (i < text.length) {
        val size = if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        add(text.substring(i, i + size))
        i += size
    }
}
