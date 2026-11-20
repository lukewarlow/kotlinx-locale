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

package dev.carcara.kotlinx.locale.datetime

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.RangePart
import dev.carcara.kotlinx.locale.timezone.TimeZoneNameContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

public enum class DateTimeField {
    ERA,
    YEAR,
    MONTH,
    DAY,
    WEEKDAY,
    DAY_PERIOD,
    HOUR,
    MINUTE,
    SECOND,
    FRACTIONAL_SECOND,
    TIME_ZONE_NAME,
    QUARTER,
    RELATED_YEAR,
    YEAR_NAME,
    ;

    public companion object
}
public enum class DateTimeFieldWidth {
    NUMERIC,
    TWO_DIGIT,
    SHORT,
    LONG,
    NARROW,
    ;

    public companion object
}
public enum class DateTimePartKind {
    LITERAL,
    ERA,
    YEAR,
    MONTH,
    DAY,
    WEEKDAY,
    DAY_PERIOD,
    HOUR,
    MINUTE,
    SECOND,
    FRACTIONAL_SECOND,
    TIME_ZONE_NAME,
    QUARTER,
    RELATED_YEAR,
    YEAR_NAME,
    ;

    public companion object
}
public data class DateTimePart(public val kind: DateTimePartKind, public val value: String) {
    public companion object
}

/** The selected pattern's period semantics, independent of whether a component was requested. */
public enum class DateTimeDayPeriodKind {
    AM_PM,
    NOON_MIDNIGHT,
    FLEXIBLE,
    ;

    public companion object
}

/** Actual selected fields, rather than a copy of the requested skeleton. */
public data class SelectedDateTimeFormat(
    public val locale: Locale,
    public val pattern: String,
    public val fields: Map<DateTimeField, DateTimeFieldWidth>,
    public val hourCycle: HourCycle?,
    public val fractionalSecondDigits: Int?,
    public val calendar: String = "gregory",
    public val numberingSystem: String? = null,
    public val zoneNameStyle: DateTimeZoneNameStyle? = null,
) {
    /** Period kind in the actual pattern; its width is [fields]' DAY_PERIOD entry. */
    public val dayPeriodKind: DateTimeDayPeriodKind?
        get() {
            var quoted = false
            for (letter in pattern) {
                if (letter == '\'') quoted = !quoted
                if (quoted) continue
                when (letter) {
                    'a' -> return DateTimeDayPeriodKind.AM_PM
                    'b' -> return DateTimeDayPeriodKind.NOON_MIDNIGHT
                    'B' -> return DateTimeDayPeriodKind.FLEXIBLE
                }
            }
            return null
        }

    public companion object
}

/** Whether exact midnight may use a locale's potentially ambiguous midnight name. */
public enum class DateTimeMidnightPolicy {
    SUPPRESS,
    ALLOW,
    ;

    public companion object
}

/** Local fields have already been converted by the caller. No host TimeZone is required. */
public data class DateTimeRenderContext(
    public val date: LocalDate? = null,
    public val time: LocalTime? = null,
    public val zone: TimeZoneNameContext? = null,
    public val calendarDate: CalendarDateFields? = null,
    public val midnightPolicy: DateTimeMidnightPolicy,
) {
    /** Existing converted-field construction defaults to midnight suppression. */
    public constructor(
        date: LocalDate? = null,
        time: LocalTime? = null,
        zone: TimeZoneNameContext? = null,
        calendarDate: CalendarDateFields?,
    ) : this(date, time, zone, calendarDate, DateTimeMidnightPolicy.SUPPRESS)

    /** Original JVM no-argument constructor retained. */
    public constructor() : this(null, null, null, null)

    /** Original constructor retained for existing consumers. */
    public constructor(date: LocalDate? = null, time: LocalTime? = null, zone: TimeZoneNameContext? = null) :
        this(date, time, zone, null)

    init {
        require(date == null || calendarDate == null) { "Supply ISO local date or converted calendar fields, not both" }
    }

    /** Original copy signature retained, including default arguments. */
    public fun copy(
        date: LocalDate? = this.date,
        time: LocalTime? = this.time,
        zone: TimeZoneNameContext? = this.zone,
    ): DateTimeRenderContext = DateTimeRenderContext(date, time, zone, calendarDate, midnightPolicy)

    /** Original converted-field copy signature, preserving the rendering policy. */
    public fun copy(
        date: LocalDate? = this.date,
        time: LocalTime? = this.time,
        zone: TimeZoneNameContext? = this.zone,
        calendarDate: CalendarDateFields? = this.calendarDate,
    ): DateTimeRenderContext = DateTimeRenderContext(date, time, zone, calendarDate, midnightPolicy)

    public companion object
}
public class FormattedDateTime(parts: List<DateTimePart>) {
    public val parts: List<DateTimePart> = parts.toList()
    public val text: String = parts.joinToString("") { it.value }
    public companion object
}
public class FormattedDateTimeRange(parts: List<RangePart<DateTimePart>>) {
    public val parts: List<RangePart<DateTimePart>> = parts.toList()
    public val text: String = parts.joinToString("") { it.part.value }
    public companion object
}

/** Implemented service values, separate from locale calendar/numbering preferences. */
public data class DateTimeCapabilities(
    public val coverage: dev.carcara.kotlinx.locale.LocaleCoverage,
    public val calendars: Set<String>,
    public val defaultCalendar: String?,
    public val numberingSystems: Set<String>,
    public val defaultNumberingSystem: String?,
    public val hourCycle: HourCycle?,
    public val hourCycle12: HourCycle?,
    public val hourCycle24: HourCycle?,
    public val structuredParts: Boolean,
    public val zoneNames: Boolean,
) {
    public companion object
}

/** The selected zone field's semantics; specific names choose standard/daylight at rendering. */
public enum class DateTimeZoneNameStyle {
    SPECIFIC_SHORT,
    SPECIFIC_LONG,
    GENERIC_SHORT,
    GENERIC_LONG,
    OFFSET_SHORT,
    OFFSET_LONG,
    LOCATION,
    ;

    public companion object
}
