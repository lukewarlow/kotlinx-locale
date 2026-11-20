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

package dev.carcara.kotlinx.locale.datetime.cldr.calendars

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.datetime.DateTimeCapabilities
import dev.carcara.kotlinx.locale.datetime.DateTimeRenderContext
import dev.carcara.kotlinx.locale.datetime.FormatStyle
import dev.carcara.kotlinx.locale.datetime.FormattedDateTime
import dev.carcara.kotlinx.locale.datetime.FormattedDateTimeRange
import dev.carcara.kotlinx.locale.datetime.HourCycle
import dev.carcara.kotlinx.locale.datetime.SelectedDateTimeFormat
import dev.carcara.kotlinx.locale.datetime.cldr.CldrDateTime
import dev.carcara.kotlinx.locale.datetime.cldr.calendars.internal.calendarTables
import dev.carcara.kotlinx.locale.datetime.cldr.intervals.CldrDateTimeIntervals
import dev.carcara.kotlinx.locale.datetime.cldr.runtime.CalendarPresentationSource
import dev.carcara.kotlinx.locale.datetime.cldr.runtime.PayloadCalendarPresentation
import dev.carcara.kotlinx.locale.datetime.cldr.runtime.SkeletonFieldLengthMatching
import dev.carcara.kotlinx.locale.datetime.cldr.skeletons.CldrDateTimeSkeletons
import dev.carcara.kotlinx.locale.number.cldr.CldrNumber
import dev.carcara.kotlinx.locale.timezone.cldr.CldrTimeZone
import dev.carcara.kotlinx.locale.datetime.cldr.calendars.internal.skeletonAppendFormats as calendarAppendFormats
import dev.carcara.kotlinx.locale.datetime.cldr.calendars.internal.skeletonNames as calendarSkeletonNames

public object CldrCalendarPresentation : CalendarPresentationSource by PayloadCalendarPresentation(
    calendarTables, CldrDateTime, CldrDateTimeSkeletons, CldrDateTimeIntervals, CldrNumber, CldrTimeZone,
    CldrDateTime.records, CldrDateTime.standaloneRecords,
    calendarAppendFormats, calendarSkeletonNames,
)

public fun calendarCapabilities(locale: Locale = Locale.current): DateTimeCapabilities = CldrCalendarPresentation.capabilities(locale)
public fun calendarEras(calendar: String, locale: Locale = Locale.current): LocaleLookup<List<String>> =
    CldrCalendarPresentation.calendarEras(calendar, locale)
public fun calendarSelect(
    dateStyle: FormatStyle?,
    timeStyle: FormatStyle?,
    locale: Locale = Locale.current,
    calendar: String? = null,
    hourCycle: HourCycle? = null,
    numberingSystem: String? = null,
): LocaleLookup<SelectedDateTimeFormat> = if (calendar == null) {
    CldrCalendarPresentation.select(dateStyle, timeStyle, locale, hourCycle, numberingSystem)
} else {
    CldrCalendarPresentation.select(dateStyle, timeStyle, locale, calendar, hourCycle, numberingSystem)
}
public fun calendarSelectSkeleton(
    skeleton: String,
    locale: Locale = Locale.current,
    calendar: String? = null,
    numberingSystem: String? = null,
): LocaleLookup<SelectedDateTimeFormat> = if (calendar == null) {
    CldrCalendarPresentation.selectSkeleton(skeleton, locale, numberingSystem)
} else {
    CldrCalendarPresentation.selectSkeleton(skeleton, locale, numberingSystem, calendar)
}

public fun calendarSelectSkeleton(
    skeleton: String,
    locale: Locale = Locale.current,
    calendar: String? = null,
    numberingSystem: String? = null,
    fieldLengthMatching: SkeletonFieldLengthMatching,
): LocaleLookup<SelectedDateTimeFormat> = if (calendar == null) {
    CldrCalendarPresentation.selectSkeleton(skeleton, locale, numberingSystem, fieldLengthMatching)
} else {
    CldrCalendarPresentation.selectSkeleton(skeleton, locale, numberingSystem, calendar, fieldLengthMatching)
}

/** Candidate records for callers implementing a format matcher; explicit calendar selection preserves [locale]. */
public fun calendarCandidates(
    locale: Locale = Locale.current,
    calendar: String? = null,
    numberingSystem: String? = null,
): LocaleLookup<List<SelectedDateTimeFormat>> = if (calendar == null) {
    CldrCalendarPresentation.candidates(locale, numberingSystem)
} else {
    CldrCalendarPresentation.candidates(locale, numberingSystem, calendar)
}
public fun calendarFormat(value: DateTimeRenderContext, format: SelectedDateTimeFormat): LocaleLookup<FormattedDateTime> =
    CldrCalendarPresentation.render(value, format, null, null)
public fun calendarFormatRange(
    start: DateTimeRenderContext,
    end: DateTimeRenderContext,
    format: SelectedDateTimeFormat,
    skeleton: String? = null,
): LocaleLookup<FormattedDateTimeRange> = CldrCalendarPresentation.renderRange(start, end, format, skeleton, null, null)
