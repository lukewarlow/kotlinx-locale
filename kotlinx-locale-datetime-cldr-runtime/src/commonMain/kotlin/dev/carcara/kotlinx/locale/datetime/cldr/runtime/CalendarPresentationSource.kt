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
import dev.carcara.kotlinx.locale.LocaleCoverage
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.datetime.DateTimeCapabilities
import dev.carcara.kotlinx.locale.datetime.DateTimeField
import dev.carcara.kotlinx.locale.datetime.DateTimeFormatSource
import dev.carcara.kotlinx.locale.datetime.DateTimeIntervalPattern
import dev.carcara.kotlinx.locale.datetime.DateTimeRenderContext
import dev.carcara.kotlinx.locale.datetime.FormatStyle
import dev.carcara.kotlinx.locale.datetime.FormattedDateTime
import dev.carcara.kotlinx.locale.datetime.FormattedDateTimeRange
import dev.carcara.kotlinx.locale.datetime.HourCycle
import dev.carcara.kotlinx.locale.datetime.SelectedDateTimeFormat
import dev.carcara.kotlinx.locale.datetime.SelectedDateTimeIntervals
import dev.carcara.kotlinx.locale.datetime.TextStyle
import dev.carcara.kotlinx.locale.number.NumberFormatSource
import dev.carcara.kotlinx.locale.timezone.TimeZoneNameSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Calendar presentation through the same style, skeleton and interval contracts. */
public interface CalendarPresentationSource :
    DateTimeFormatSource,
    IntervalFormatSource {
    public fun selectSkeleton(skeleton: String, locale: Locale, numberingSystem: String? = null): LocaleLookup<SelectedDateTimeFormat>
    public fun selectSkeleton(
        skeleton: String,
        locale: Locale,
        numberingSystem: String? = null,
        fieldLengthMatching: SkeletonFieldLengthMatching,
    ): LocaleLookup<SelectedDateTimeFormat> = if (fieldLengthMatching == SkeletonFieldLengthMatching.LOCALE_PREFERRED) {
        selectSkeleton(skeleton, locale, numberingSystem)
    } else {
        LocaleLookup.Unsupported("Requested numeric skeleton widths")
    }
    public fun selectSkeleton(
        skeleton: String,
        locale: Locale,
        numberingSystem: String? = null,
        calendar: String,
        fieldLengthMatching: SkeletonFieldLengthMatching,
    ): LocaleLookup<SelectedDateTimeFormat> =
        when (val result = selectSkeleton(skeleton, locale.withUnicodeKeyword("ca", calendar), numberingSystem, fieldLengthMatching)) {
            is LocaleLookup.Value -> LocaleLookup.Value(result.value.copy(locale = locale))
            is LocaleLookup.Unsupported -> result
            LocaleLookup.MissingData -> LocaleLookup.MissingData
        }
    public fun selectSkeleton(
        skeleton: String,
        locale: Locale,
        numberingSystem: String? = null,
        calendar: String,
    ): LocaleLookup<SelectedDateTimeFormat> =
        when (val result = selectSkeleton(skeleton, locale.withUnicodeKeyword("ca", calendar), numberingSystem)) {
            is LocaleLookup.Value -> LocaleLookup.Value(result.value.copy(locale = locale))
            is LocaleLookup.Unsupported -> result
            LocaleLookup.MissingData -> LocaleLookup.MissingData
        }
    public fun candidates(locale: Locale, numberingSystem: String? = null): LocaleLookup<List<SelectedDateTimeFormat>>

    /** Select calendar-specific candidates without changing the supplied locale identity. */
    public fun candidates(locale: Locale, numberingSystem: String? = null, calendar: String): LocaleLookup<List<SelectedDateTimeFormat>> =
        when (val result = candidates(locale.withUnicodeKeyword("ca", calendar), numberingSystem)) {
            is LocaleLookup.Value -> LocaleLookup.Value(result.value.map { it.copy(locale = locale) })
            is LocaleLookup.Unsupported -> result
            LocaleLookup.MissingData -> LocaleLookup.MissingData
        }
    public companion object
}

/** Data-backed presentation only. No conversion, balancing or host formatting. */
@OptIn(dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi::class)
public class PayloadCalendarPresentation(
    tables: Map<String, Map<String, String>>,
    private val gregorian: DateTimeFormatSource,
    private val gregorianSkeletons: SkeletonFormatSource,
    private val gregorianIntervals: IntervalFormatSource,
    private val numbers: NumberFormatSource,
    private val zones: TimeZoneNameSource,
    gregorianRecords: Map<String, String>,
    gregorianStandaloneRecords: Map<String, String>,
    gregorianAppendRecords: Map<String, String>,
    gregorianNameRecords: Map<String, String>,
) : CalendarPresentationSource {
    private val gregorianRecords = gregorianRecords.toMap()
    private val gregorianStandaloneRecords = gregorianStandaloneRecords.toMap()
    private val gregorianAppendRecords = gregorianAppendRecords.toMap()
    private val gregorianNameRecords = gregorianNameRecords.toMap()
    private val tables = tables.mapValues { it.value.toMap() }
    private class Sources(val dates: PayloadDateTimeFormats, val skeletons: PayloadSkeletonFormats, val intervals: PayloadIntervalFormats)
    private val sources = PreparedDateCache<Pair<String, String>, Sources?>(64)
    private val calendars =
        this.tables.keys.filter { it.endsWith("/dateTime") }.map { it.substringBefore('/') }.filter { calendar ->
            listOf("dateTime", "standalone", "skeletons", "intervals").all { section ->
                this.tables["$calendar/$section"]?.containsKey("root") == true
            }
        }.toSet() + setOf("gregory", "iso8601")
    override val supportedLocales: Set<Locale> = gregorian.supportedLocales.toSet()
    override val localeCoverage: LocaleCoverage = gregorian.localeCoverage

    private fun defaultCalendar(locale: Locale): String =
        dev.carcara.kotlinx.locale.internal.resolvedRecord(tables["calendarDefaults"].orEmpty(), locale)
            ?.takeIf { it in calendars } ?: "gregory"
    private fun calendar(locale: Locale): String = locale.unicodeKeywordOrNull("ca") ?: defaultCalendar(locale)
    private fun resolved(table: Map<String, String>, locale: Locale): String? =
        dev.carcara.kotlinx.locale.internal.resolvedRecord(table, locale)
    private fun calendarRow(calendar: String, section: String, locale: Locale): Map<String, String>? {
        val table = tables["$calendar/$section"] ?: return null
        var tag = locale.stripExtensions().toLanguageTag()
        while (tag !in table && tag != "root") tag = tag.substringBeforeLast('-', "root")
        val chain = ArrayList<String>()
        val seen = HashSet<String>()
        while (tag.isNotEmpty()) {
            if (!seen.add(tag)) error("Cyclic calendar inheritance")
            val row = table[tag] ?: return null
            chain += row.substringAfter('\u001f', "")
            tag = row.substringBefore('\u001f')
        }
        return buildMap {
            for (row in chain.asReversed()) {
                for (entry in row.split('\u001e')) {
                    val separator = entry.indexOf('\u001d')
                    if (separator < 0) continue
                    val key = entry.substring(0, separator)
                    val value = entry.substring(separator + 1)
                    if (value.isEmpty()) remove(key) else put(key, value)
                }
            }
        }
    }
    private fun source(calendar: String, locale: Locale): Sources? =
        sources.getOrPut(calendar to locale.stripExtensions().toLanguageTag()) {
            val dateFields = calendarRow(calendar, "dateTime", locale) ?: return@getOrPut null
            val names = calendarRow(calendar, "standalone", locale) ?: return@getOrPut null
            val base = resolved(gregorianRecords, locale)?.split('\u001f')?.toMutableList() ?: return@getOrPut null
            for (style in 0..3) {
                base[10 + style] = dateFields["style/$style"] ?: return@getOrPut null
                base[18 + style] = dateFields["glue/$style"] ?: return@getOrPut null
            }
            val dates = mapOf("root" to base.joinToString("\u001f"))
            val standalone = resolved(gregorianStandaloneRecords, locale)?.split('\u001f')?.toMutableList() ?: return@getOrPut null
            while (standalone.size < 8) standalone.add("")
            standalone[7] += "\u001e" + names.entries.joinToString("\u001e") { "calendar/${it.key}\u001d${it.value}" }
            val standaloneRecords = mapOf("root" to standalone.joinToString("\u001f"))
            val formats = calendarRow(calendar, "skeletons", locale) ?: return@getOrPut null
            val skeletons = PayloadSkeletonFormats(
                mapOf("root" to formats.entries.joinToString("\u001e") { "${it.key}\u001d${it.value}" }),
                mapOf("root" to (resolved(gregorianAppendRecords, locale) ?: return@getOrPut null)),
                mapOf("root" to (resolved(gregorianNameRecords, locale) ?: return@getOrPut null)),
                dates,
                standaloneRecords,
                numbers,
                zones,
            )
            val intervals = calendarRow(calendar, "intervals", locale) ?: return@getOrPut null
            val encodedIntervals = (intervals["fallback"] ?: return@getOrPut null) + "\u001f" +
                intervals.entries.filter { it.key != "fallback" }.joinToString("\u001e") { "${it.key}\u001d${it.value}" }
            Sources(
                PayloadDateTimeFormats(dates, standaloneRecords, numbers, zones),
                skeletons,
                PayloadIntervalFormats(mapOf("root" to encodedIntervals), skeletons),
            )
        }
    private fun dateSource(calendar: String, locale: Locale): DateTimeFormatSource? = if (calendar == "gregory" ||
        calendar == "iso8601"
    ) {
        gregorian
    } else {
        source(calendar, locale)?.dates
    }
    private fun skeletonSource(calendar: String, locale: Locale): SkeletonFormatSource? = if (calendar == "gregory" ||
        calendar == "iso8601"
    ) {
        gregorianSkeletons
    } else {
        source(calendar, locale)?.skeletons
    }
    private fun intervalSource(calendar: String, locale: Locale): IntervalFormatSource? = if (calendar == "gregory" ||
        calendar == "iso8601"
    ) {
        gregorianIntervals
    } else {
        source(calendar, locale)?.intervals
    }

    override fun capabilities(locale: Locale): DateTimeCapabilities {
        val base = gregorian.capabilities(locale)
        return base.copy(
            calendars = if (base.calendars.isEmpty()) emptySet() else calendars,
            defaultCalendar = if (base.calendars.isEmpty()) null else defaultCalendar(locale),
        )
    }
    override fun calendarEras(calendar: String, locale: Locale): LocaleLookup<List<String>> =
        dateSource(calendar, locale)?.calendarEras(calendar, locale) ?: LocaleLookup.Unsupported("Calendar $calendar")
    override fun select(
        dateStyle: FormatStyle?,
        timeStyle: FormatStyle?,
        locale: Locale,
        hourCycle: HourCycle?,
        numberingSystem: String?,
    ): LocaleLookup<SelectedDateTimeFormat> =
        dateSource(calendar(locale), locale)?.select(dateStyle, timeStyle, locale, hourCycle, numberingSystem)
            ?: LocaleLookup.Unsupported("Calendar ${calendar(locale)}")
    override fun selectSkeleton(skeleton: String, locale: Locale, numberingSystem: String?): LocaleLookup<SelectedDateTimeFormat> =
        skeletonSource(calendar(locale), locale)?.selectSkeleton(skeleton, locale, numberingSystem)
            ?: LocaleLookup.Unsupported("Calendar ${calendar(locale)}")
    override fun selectSkeleton(
        skeleton: String,
        locale: Locale,
        numberingSystem: String?,
        fieldLengthMatching: SkeletonFieldLengthMatching,
    ): LocaleLookup<SelectedDateTimeFormat> =
        skeletonSource(calendar(locale), locale)?.selectSkeleton(skeleton, locale, numberingSystem, fieldLengthMatching)
            ?: LocaleLookup.Unsupported("Calendar ${calendar(locale)}")
    override fun candidates(locale: Locale, numberingSystem: String?): LocaleLookup<List<SelectedDateTimeFormat>> =
        skeletonSource(calendar(locale), locale)?.candidates(locale, numberingSystem)
            ?: LocaleLookup.Unsupported("Calendar ${calendar(locale)}")
    override fun render(
        context: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<FormattedDateTime> =
        skeletonSource(format.calendar, format.locale)?.render(context, format, numbers ?: this.numbers, zones ?: this.zones)
            ?: LocaleLookup.Unsupported("Calendar ${format.calendar}")
    override fun selectedIntervals(format: SelectedDateTimeFormat, skeleton: String?): LocaleLookup<SelectedDateTimeIntervals> =
        intervalSource(format.calendar, format.locale)?.selectedIntervals(format, skeleton)
            ?: LocaleLookup.Unsupported("Calendar ${format.calendar}")
    override fun comparisonValues(
        value: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<Map<DateTimeField, String>> =
        intervalSource(format.calendar, format.locale)?.comparisonValues(value, format, numbers ?: this.numbers, zones ?: this.zones)
            ?: LocaleLookup.Unsupported("Calendar ${format.calendar}")
    override fun renderIntervalPattern(
        start: DateTimeRenderContext,
        end: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        pattern: DateTimeIntervalPattern,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<FormattedDateTimeRange> = intervalSource(format.calendar, format.locale)?.renderIntervalPattern(
        start,
        end,
        format,
        pattern,
        numbers ?: this.numbers,
        zones ?: this.zones,
    )
        ?: LocaleLookup.Unsupported("Calendar ${format.calendar}")
    override fun renderRange(
        start: DateTimeRenderContext,
        end: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        skeleton: String?,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<FormattedDateTimeRange> = intervalSource(format.calendar, format.locale)?.renderRange(
        start,
        end,
        format,
        skeleton,
        numbers ?: this.numbers,
        zones ?: this.zones,
    )
        ?: LocaleLookup.Unsupported("Calendar ${format.calendar}")

    // Legacy LocalDate operations carry ISO fields and never reinterpret them as another calendar.
    override fun formatDateOrNull(date: LocalDate, style: FormatStyle, locale: Locale): String? =
        if (calendar(locale) in setOf("gregory", "iso8601")) gregorian.formatDateOrNull(date, style, locale) else null
    override fun formatTimeOrNull(time: LocalTime, style: FormatStyle, locale: Locale): String? =
        gregorian.formatTimeOrNull(time, style, locale)
    override fun formatDateTimeOrNull(dateTime: LocalDateTime, dateStyle: FormatStyle, timeStyle: FormatStyle, locale: Locale): String? =
        if (calendar(locale) in
            setOf("gregory", "iso8601")
        ) {
            gregorian.formatDateTimeOrNull(dateTime, dateStyle, timeStyle, locale)
        } else {
            null
        }
    override fun monthNameOrNull(month: Int, style: TextStyle, locale: Locale): String? =
        if (calendar(locale) in setOf("gregory", "iso8601")) gregorian.monthNameOrNull(month, style, locale) else null
    override fun dayOfWeekNameOrNull(isoDayNumber: Int, style: TextStyle, locale: Locale): String? =
        gregorian.dayOfWeekNameOrNull(isoDayNumber, style, locale)
    override fun intervalFormatOrNull(start: LocalDate, end: LocalDate, skeleton: String, locale: Locale): String? =
        if (calendar(locale) in setOf("gregory", "iso8601")) gregorianIntervals.intervalFormatOrNull(start, end, skeleton, locale) else null
    override fun intervalFormatOrNull(start: LocalTime, end: LocalTime, skeleton: String, locale: Locale): String? =
        gregorianIntervals.intervalFormatOrNull(start, end, skeleton, locale)
    override fun intervalFormatOrNull(start: LocalDateTime, end: LocalDateTime, skeleton: String, locale: Locale): String? =
        if (calendar(locale) in setOf("gregory", "iso8601")) gregorianIntervals.intervalFormatOrNull(start, end, skeleton, locale) else null
    public companion object
}
