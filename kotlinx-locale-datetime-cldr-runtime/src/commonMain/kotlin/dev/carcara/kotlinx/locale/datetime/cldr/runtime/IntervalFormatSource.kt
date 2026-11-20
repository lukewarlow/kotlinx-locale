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

package dev.carcara.kotlinx.locale.datetime.cldr.runtime

import dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleDataSource
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.RangePart
import dev.carcara.kotlinx.locale.RangeSource
import dev.carcara.kotlinx.locale.datetime.DateTimeField
import dev.carcara.kotlinx.locale.datetime.DateTimeIntervalPattern
import dev.carcara.kotlinx.locale.datetime.DateTimeIntervalPatternPart
import dev.carcara.kotlinx.locale.datetime.DateTimePart
import dev.carcara.kotlinx.locale.datetime.DateTimePartKind
import dev.carcara.kotlinx.locale.datetime.DateTimeRenderContext
import dev.carcara.kotlinx.locale.datetime.FormattedDateTime
import dev.carcara.kotlinx.locale.datetime.FormattedDateTimeRange
import dev.carcara.kotlinx.locale.datetime.SelectedDateTimeFormat
import dev.carcara.kotlinx.locale.datetime.SelectedDateTimeIntervals
import dev.carcara.kotlinx.locale.datetime.format
import dev.carcara.kotlinx.locale.internal.ENTRY_SEPARATOR
import dev.carcara.kotlinx.locale.internal.FIELD_SEPARATOR
import dev.carcara.kotlinx.locale.internal.KEY_SEPARATOR
import dev.carcara.kotlinx.locale.internal.resolvedRecord
import dev.carcara.kotlinx.locale.internal.supportedLocalesOf
import dev.carcara.kotlinx.locale.number.NumberFormatSource
import dev.carcara.kotlinx.locale.timezone.TimeZoneNameSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * A source of date and time interval formatting.
 *
 * An interval is not two formatted values with a dash between them. CLDR gives
 * each locale a pattern per skeleton per greatest-difference field, so English
 * writes `Jul 18 – 22, 2026` rather than `Jul 18, 2026 – Jul 22, 2026`, and the
 * parts that both ends share appear once.
 */
public interface IntervalFormatSource : LocaleDataSource {

    /** The two dates as one interval, or null when this build carries nothing for [locale]. */
    public fun intervalFormatOrNull(start: LocalDate, end: LocalDate, skeleton: String, locale: Locale): String?

    public fun intervalFormatOrNull(start: LocalTime, end: LocalTime, skeleton: String, locale: Locale): String?

    public fun intervalFormatOrNull(start: LocalDateTime, end: LocalDateTime, skeleton: String, locale: Locale): String?

    /** A selected format is retained verbatim for style ranges. */
    public fun renderRange(
        start: DateTimeRenderContext,
        end: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        skeleton: String? = null,
        numbers: NumberFormatSource? = null,
        zones: TimeZoneNameSource? = null,
    ): LocaleLookup<FormattedDateTimeRange> = LocaleLookup.Unsupported("Structured date/time intervals")

    /** CLDR records compatible with this selected format, without private table access. */
    public fun selectedIntervals(format: SelectedDateTimeFormat, skeleton: String? = null): LocaleLookup<SelectedDateTimeIntervals> =
        LocaleLookup.Unsupported("Selected interval records")

    /** Relevant comparison values, including localized periods and effective rendered zone names. */
    public fun comparisonValues(
        value: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        numbers: NumberFormatSource? = null,
        zones: TimeZoneNameSource? = null,
    ): LocaleLookup<Map<DateTimeField, String>> = LocaleLookup.Unsupported("Interval comparison values")

    /** Render an explicitly selected prepared record, preserving endpoint provenance. */
    public fun renderIntervalPattern(
        start: DateTimeRenderContext,
        end: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        pattern: DateTimeIntervalPattern,
        numbers: NumberFormatSource? = null,
        zones: TimeZoneNameSource? = null,
    ): LocaleLookup<FormattedDateTimeRange> = LocaleLookup.Unsupported("Prepared interval rendering")

    public companion object
}

/**
 * Which CLDR field two values first differ in, largest first, or null when they
 * are equal in every field an interval pattern can name.
 *
 * The hour is reported as `h` and written back out as whichever of `H` or `h`
 * the locale's own pattern uses, because CLDR keys the two separately.
 */
private fun greatestDifference(startDate: LocalDate?, endDate: LocalDate?, startTime: LocalTime?, endTime: LocalTime?): Char? {
    if (startDate != null && endDate != null) {
        if (startDate.year != endDate.year) return 'y'
        if (startDate.month != endDate.month) return 'M'
        if (startDate.day != endDate.day) return 'd'
    }
    if (startTime != null && endTime != null) {
        if ((startTime.hour < 12) != (endTime.hour < 12)) return 'a'
        if (startTime.hour != endTime.hour) return 'h'
        if (startTime.minute != endTime.minute) return 'm'
        if (startTime.second != endTime.second) return 's'
    }
    return null
}

/** The fields an interval pattern can name, coarsest first. */
private const val FIELD_ORDER = "yMdahms"

/**
 * Where a pattern letter sits in [FIELD_ORDER], or -1 for a letter that names no
 * field an interval turns on.
 *
 * The synonyms matter. A skeleton can reach the day through `d`, `E` or `c`, and
 * the hour through any of four letters depending on the locale's cycle, and a
 * comparison that only knew the canonical spelling would decide a skeleton had
 * no day field because the locale wrote a weekday.
 */
private fun fieldRank(letter: Char): Int = when (letter) {
    'y', 'Y', 'u', 'U', 'r' -> 0
    'M', 'L' -> 1
    'd', 'D', 'E', 'e', 'c' -> 2
    'a', 'b', 'B' -> 3
    'h', 'H', 'K', 'k' -> 4
    'm' -> 5
    's' -> 6
    else -> -1
}

/**
 * [requested] widened to reach [difference], or null when it already does.
 *
 * Every field between the one the two values differ in and the coarsest the
 * skeleton names, so a day over two years widens to a whole date rather than to
 * a year and a day with a month-shaped hole between them. Existing letters keep
 * their widths; only the missing fields are added, at their narrowest, which is
 * what CLDR's own fallback does.
 */
private fun widenedSkeleton(requested: String, difference: Char): String? {
    val target = fieldRank(difference)
    if (target < 0) return null
    val coarsest = requested.mapNotNull { letter -> fieldRank(letter).takeIf { it >= 0 } }.minOrNull() ?: return null
    if (target >= coarsest) return null
    return FIELD_ORDER.substring(target, coarsest) + requested
}

/**
 * Interval formatting over a table of CLDR interval records.
 *
 * Takes the skeleton source rather than its tables, because an interval is a
 * split of the pattern that source's matcher picks. Sharing the object shares
 * one matcher pool per locale, and building one sorts the locale's whole
 * candidate list.
 */
@InternalKotlinxLocaleApi
public class PayloadIntervalFormats(records: Map<String, String>, private val skeletons: PayloadSkeletonFormats) :
    IntervalFormatSource {

    private val records = records.toMap()

    @OptIn(dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi::class)
    override val localeCoverage: dev.carcara.kotlinx.locale.LocaleCoverage by lazy {
        dev.carcara.kotlinx.locale.internal.payloadLocaleCoverage(this.records, supportedLocales)
    }
    override val supportedLocales: Set<Locale> by lazy { supportedLocalesOf(this.records) }

    private val decoded = PreparedDateCache<String, IntervalRecord?>()

    override fun intervalFormatOrNull(start: LocalDate, end: LocalDate, skeleton: String, locale: Locale): String? =
        render(skeleton, locale, startDate = start, endDate = end, startTime = null, endTime = null)

    override fun intervalFormatOrNull(start: LocalTime, end: LocalTime, skeleton: String, locale: Locale): String? =
        render(skeleton, locale, startDate = null, endDate = null, startTime = start, endTime = end)

    override fun intervalFormatOrNull(start: LocalDateTime, end: LocalDateTime, skeleton: String, locale: Locale): String? = render(
        skeleton,
        locale,
        startDate = start.date,
        endDate = end.date,
        startTime = start.time,
        endTime = end.time,
    )

    private fun render(
        skeleton: String,
        locale: Locale,
        startDate: LocalDate?,
        endDate: LocalDate?,
        startTime: LocalTime?,
        endTime: LocalTime?,
    ): String? {
        val locales = skeletons.skeletonsFor(locale) ?: return null
        val pattern = locales.matcher.bestPatternOrNull(skeleton) ?: return null

        fun formatOne(date: LocalDate?, time: LocalTime?): String {
            val tokens = parseDateTimePattern(pattern).withoutZoneFields()
            return formatPattern(tokens, locales.dateTime, date, time, locales.record)
        }

        val difference = greatestDifference(startDate, endDate, startTime, endTime)
        // Equal in every field the interval could name: CLDR's answer is the
        // plain pattern once, not the same text twice with a dash in it.
        if (difference == null) return formatOne(startDate, startTime)

        val record = recordFor(locale) ?: return null
        // Keyed off the request rather than off the matched pattern. CLDR's
        // interval ids are skeletons, so `yMd` is the key; the pattern the
        // matcher returns is `y-MM-dd` in Afrikaans, whose canonical form
        // carries the field widths and matches nothing.
        //
        // `j`, `J` and `C` are the exception, because they are requests for
        // whichever hour the locale prefers and CLDR keys the twelve- and
        // twenty-four-hour entries apart. Only the resolved pattern knows which
        // one this locale reached for, so they are rewritten before the lookup.
        val hourLetter = pattern.firstOrNull { it in "HhKk" } ?: 'H'
        val requested = skeleton.map { if (it in "jJC") hourLetter else it }.joinToString("")
        val canonical = SkeletonFields.of(requested)?.canonicalString()
        val intervalPattern = canonical?.let { record.patternFor(it, difference, pattern) }

        if (intervalPattern != null) {
            val halves = splitIntervalTokens(intervalPattern)
            if (halves != null) {
                val (first, second) = halves
                return formatPattern(first.withoutZoneFields(), locales.dateTime, startDate, startTime, locales.record) +
                    formatPattern(second.withoutZoneFields(), locales.dateTime, endDate, endTime, locales.record)
            }
        }

        // No entry for this field. Which of two things that means depends on
        // whether the field is coarser or finer than anything the skeleton names,
        // and the two want opposite answers.
        //
        // Coarser: the skeleton cannot show what they differ in, so the format
        // has to widen. Without this, a day-only skeleton over two months reads
        // "14 – 14", which is not a narrower answer than the right one, it is a
        // wrong one.
        val widened = widenedSkeleton(requested, difference)?.let(locales.matcher::bestPatternOrNull)
        if (widened != null) {
            val tokens = parseDateTimePattern(widened).withoutZoneFields()
            return substitute(
                record.fallback,
                formatPattern(tokens, locales.dateTime, startDate, startTime, locales.record),
                formatPattern(tokens, locales.dateTime, endDate, endTime, locales.record),
            )
        }

        // Finer: the two are the same value as far as this format can express, so
        // CLDR writes it once. "2026 – 2026" is never the answer to a year-shaped
        // question about two days in the same year.
        val first = formatOne(startDate, startTime)
        val second = formatOne(endDate, endTime)
        if (first == second) return first

        return substitute(record.fallback, first, second)
    }

    override fun renderRange(
        start: DateTimeRenderContext,
        end: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        skeleton: String?,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<FormattedDateTimeRange> {
        val locale = format.locale
        val data = skeletons.skeletonsFor(locale) ?: return LocaleLookup.MissingData
        val record = recordFor(locale) ?: return LocaleLookup.MissingData
        fun render(tokens: List<PatternToken>, context: DateTimeRenderContext): LocaleLookup<FormattedDateTime> =
            skeletons.renderTokens(tokens, context, format, numbers, zones)
        val first = when (val result = render(parseDateTimePattern(format.pattern), start)) {
            is LocaleLookup.Value -> result.value
            is LocaleLookup.Unsupported -> return result
            LocaleLookup.MissingData -> return LocaleLookup.MissingData
        }
        val last = when (val result = render(parseDateTimePattern(format.pattern), end)) {
            is LocaleLookup.Value -> result.value
            is LocaleLookup.Unsupported -> return result
            LocaleLookup.MissingData -> return LocaleLookup.MissingData
        }
        val before = comparisonValues(start, format, first.parts)
        val after = comparisonValues(end, format, last.parts)
        if (before == after) return LocaleLookup.Value(FormattedDateTimeRange(first.parts.map { RangePart(it, RangeSource.SHARED) }))
        val difference = listOf(
            DateTimeField.ERA, DateTimeField.YEAR, DateTimeField.RELATED_YEAR, DateTimeField.YEAR_NAME,
            DateTimeField.MONTH, DateTimeField.DAY,
            DateTimeField.DAY_PERIOD, DateTimeField.HOUR, DateTimeField.MINUTE, DateTimeField.SECOND,
            DateTimeField.FRACTIONAL_SECOND, DateTimeField.WEEKDAY, DateTimeField.TIME_ZONE_NAME,
        )
            .firstOrNull { before[it] != after[it] }
        val selected = when (val result = selectedIntervals(format, skeleton)) {
            is LocaleLookup.Value -> result.value
            is LocaleLookup.Unsupported -> return result
            LocaleLookup.MissingData -> return LocaleLookup.MissingData
        }
        // Epochs remain attached to their endpoints for metazone lookup. Only
        // names consumed by this format participate in the equality decision.
        if (before[DateTimeField.TIME_ZONE_NAME] == after[DateTimeField.TIME_ZONE_NAME]) {
            selected.patterns.firstOrNull { it.difference == difference }?.let {
                return renderIntervalPattern(start, end, format, it, numbers, zones)
            }
        }
        val output = ArrayList<RangePart<DateTimePart>>()
        val template = record.fallback
        var index = 0
        while (index < template.length) {
            when {
                template.startsWith("{0}", index) -> {
                    output += first.parts.map { RangePart(it, RangeSource.START) }
                    index += 3
                }
                template.startsWith("{1}", index) -> {
                    output += last.parts.map { RangePart(it, RangeSource.END) }
                    index += 3
                }
                else -> {
                    val begin = index++
                    while (index < template.length && !template.startsWith("{0}", index) && !template.startsWith("{1}", index)) index++
                    output += RangePart(DateTimePart(DateTimePartKind.LITERAL, template.substring(begin, index)), RangeSource.SHARED)
                }
            }
        }
        return LocaleLookup.Value(FormattedDateTimeRange(output))
    }

    private val selectedIntervalCache =
        PreparedDateCache<Pair<SelectedDateTimeFormat, String?>, LocaleLookup<SelectedDateTimeIntervals>>(128)

    override fun selectedIntervals(format: SelectedDateTimeFormat, skeleton: String?): LocaleLookup<SelectedDateTimeIntervals> {
        val snapshot = format.copy(fields = format.fields.toMap())
        return selectedIntervalCache.getOrPut(snapshot to skeleton) { prepareSelectedIntervals(snapshot, skeleton) }
    }

    private fun prepareSelectedIntervals(format: SelectedDateTimeFormat, skeleton: String?): LocaleLookup<SelectedDateTimeIntervals> {
        val record = recordFor(format.locale) ?: return LocaleLookup.MissingData
        val hour = patternHourLetter(format.pattern) ?: 'H'
        val request = skeleton ?: parseDateTimePattern(format.pattern).filterIsInstance<PatternToken.Field>()
            .filterNot { it.letter == 'a' && hour in "hK" }
            .joinToString("") { it.letter.toString().repeat(it.count) }
        val canonical = SkeletonFields.of(request.map { if (it in "jJC") hour else it }.joinToString(""))?.canonicalString()
            ?: return LocaleLookup.Unsupported("Interval skeleton $request")
        val selectedLetters = parseDateTimePattern(format.pattern).filterIsInstance<PatternToken.Field>()
            .mapNotNull { token -> dateTimeFieldOrNull(token.letter)?.let { it to token.letter } }.toMap()
        val selectedWidths = parseDateTimePattern(format.pattern).filterIsInstance<PatternToken.Field>()
            .mapNotNull { token -> dateTimeFieldOrNull(token.letter)?.let { it to token.count } }.toMap()
        val selectedTokens = parseDateTimePattern(format.pattern)
        val exactRecords = record.patternsFor(canonical)
        val records = if (exactRecords.isEmpty() && canonical.any { it == 's' || it == 'S' }) {
            val seconds = if ('S' in canonical) record.patternsFor(canonical.filterNot { it == 'S' }) else emptyList()
            seconds.ifEmpty { record.patternsFor(canonical.filterNot { it == 's' || it == 'S' }) }
        } else {
            exactRecords
        }
        val patterns = records.mapNotNull { (letter, pattern) ->
            val difference = dateTimeFieldOrNull(letter) ?: return@mapNotNull null
            val reversed = pattern.startsWith("latestFirst:")
            val body = pattern.removePrefix("latestFirst:").removePrefix("earliestFirst:")
            val adjusted = buildString {
                for (token in extendIntervalTimeFields(parseDateTimePattern(body), selectedTokens)) {
                    when (token) {
                        is PatternToken.Literal -> append(quoteLiteral(token.text))
                        is PatternToken.Field -> append(
                            (selectedLetters[dateTimeFieldOrNull(token.letter)] ?: token.letter).toString()
                                .repeat(selectedWidths[dateTimeFieldOrNull(token.letter)] ?: token.count),
                        )
                    }
                }
            }
            val metadata = selectedDateTimeFormat(adjusted, format.locale, format.numberingSystem)
            // A compact record must not add fields or alter the selected widths.
            if (metadata.fields != format.fields) return@mapNotNull null
            DateTimeIntervalPattern(
                difference,
                preparedIntervalParts(adjusted).map { part ->
                    if (!reversed || part.source == RangeSource.SHARED) {
                        part
                    } else {
                        part.copy(source = if (part.source == RangeSource.START) RangeSource.END else RangeSource.START)
                    }
                },
                format.hourCycle,
            )
        }.toMutableList()
        // A minute-difference record with separate endpoint seconds is also a
        // valid compatible record for a smaller difference. Never share the
        // very field whose differing values require both endpoints.
        for ((field, parent) in listOf(
            DateTimeField.SECOND to DateTimeField.MINUTE,
            DateTimeField.FRACTIONAL_SECOND to DateTimeField.SECOND,
        )) {
            if (field !in format.fields || patterns.any { it.difference == field }) continue
            val candidate = patterns.firstOrNull { entry ->
                entry.difference == parent &&
                    listOf(RangeSource.START, RangeSource.END).all { endpoint ->
                        entry.parts.any { part ->
                            part.source == endpoint &&
                                parseDateTimePattern(part.pattern).any {
                                    it is PatternToken.Field && dateTimeFieldOrNull(it.letter) == field
                                }
                        }
                    }
            }
            if (candidate != null) patterns += DateTimeIntervalPattern(field, candidate.parts, format.hourCycle)
        }
        if (patterns.isNotEmpty()) return LocaleLookup.Value(SelectedDateTimeIntervals(format, patterns, record.fallback))
        // Date+time styles can share their date and use a compatible time
        // interval without replacing the selected style's fields or glue.
        val tokens = parseDateTimePattern(format.pattern)
        val dates = tokens.indices.filter { i ->
            (tokens[i] as? PatternToken.Field)?.let {
                dateTimeFieldOrNull(it.letter) in
                    CALENDAR_DATE_FIELDS
            } ==
                true
        }
        val times = tokens.indices.filter { i -> tokens[i] is PatternToken.Field && i !in dates }
        if (dates.isNotEmpty() &&
            times.isNotEmpty() &&
            dates.last() < times.first() &&
            tokens.drop(times.last() + 1).all { it is PatternToken.Literal && it.text.all(Char::isWhitespace) }
        ) {
            fun text(tokens: List<PatternToken>): String = tokens.joinToString("") { token ->
                when (token) {
                    is PatternToken.Literal -> quoteLiteral(token.text)
                    is PatternToken.Field -> token.letter.toString().repeat(token.count)
                }
            }
            val timePattern = text(tokens.subList(times.first(), times.last() + 1))
            val timeFormat = selectedDateTimeFormat(timePattern, format.locale, format.numberingSystem).copy(calendar = format.calendar)
            val prepared = selectedIntervals(timeFormat)
            if (prepared is LocaleLookup.Value && prepared.value.patterns.isNotEmpty()) {
                val prefix = text(tokens.subList(0, times.first()))
                val suffix = text(tokens.subList(times.last() + 1, tokens.size))
                val combined = prepared.value.patterns.filter { entry ->
                    val first = entry.parts.firstOrNull()?.pattern?.let(::parseDateTimePattern)?.firstOrNull()
                    first !is PatternToken.Literal || first.text.all(Char::isWhitespace)
                }.map { entry ->
                    DateTimeIntervalPattern(
                        entry.difference,
                        listOf(DateTimeIntervalPatternPart(prefix, RangeSource.SHARED)) + entry.parts +
                            DateTimeIntervalPatternPart(suffix, RangeSource.SHARED),
                        format.hourCycle,
                    )
                }
                return LocaleLookup.Value(SelectedDateTimeIntervals(format, combined, record.fallback))
            }
        }
        return LocaleLookup.Value(SelectedDateTimeIntervals(format, patterns, record.fallback))
    }

    /** Add only smaller numeric time fields, using separators from the selected pattern. */
    private fun extendIntervalTimeFields(interval: List<PatternToken>, selected: List<PatternToken>): List<PatternToken> {
        var result = interval
        for (letter in listOf('s', 'S')) {
            if (result.any { it is PatternToken.Field && it.letter == letter }) continue
            val index = selected.indexOfFirst { it is PatternToken.Field && it.letter == letter }
            if (index < 0) continue
            val previous = (index - 1 downTo 0).firstOrNull { selected[it] is PatternToken.Field } ?: continue
            val anchor = (selected[previous] as PatternToken.Field).letter
            // Seconds extend minutes; fractional seconds extend seconds. Do
            // not attach them to an unrelated field or guess a separator.
            if (anchor != if (letter == 's') 'm' else 's') continue
            val addition = selected.subList(previous + 1, index + 1)
            result = result.flatMap { token ->
                if (token is PatternToken.Field && token.letter == anchor) listOf(token) + addition else listOf(token)
            }
        }
        return result
    }

    override fun comparisonValues(
        value: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<Map<DateTimeField, String>> {
        val result = skeletons.renderTokens(parseDateTimePattern(format.pattern), value, format, numbers, zones)
        if (result !is LocaleLookup.Value) {
            return when (result) {
                is LocaleLookup.Unsupported -> result
                else -> LocaleLookup.MissingData
            }
        }
        return LocaleLookup.Value(comparisonValues(value, format, result.value.parts))
    }

    private fun comparisonValues(
        value: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        parts: List<DateTimePart>,
    ): Map<DateTimeField, String> {
        val fields = parts.filter { it.kind != DateTimePartKind.LITERAL }
            .associate { DateTimeField.valueOf(it.kind.name) to it.value }.toMutableMap()
        fun replace(field: DateTimeField, number: Int?) {
            if (field in fields && number != null) fields[field] = number.toString()
        }
        replace(DateTimeField.YEAR, value.date?.year)
        replace(DateTimeField.MONTH, value.date?.month?.ordinal?.plus(1))
        replace(DateTimeField.DAY, value.date?.day)
        replace(DateTimeField.HOUR, value.time?.hour)
        replace(DateTimeField.MINUTE, value.time?.minute)
        replace(DateTimeField.SECOND, value.time?.second)
        value.calendarDate?.let { date ->
            replace(
                DateTimeField.YEAR,
                if (parseDateTimePattern(format.pattern).any {
                        it is PatternToken.Field && it.letter == 'u'
                    }
                ) {
                    date.year
                } else {
                    date.eraYear
                },
            )
            replace(DateTimeField.RELATED_YEAR, date.relatedYear)
            replace(DateTimeField.YEAR_NAME, date.year ?: date.relatedYear ?: date.cyclicYear)
            replace(DateTimeField.DAY, date.day)
            date.era?.let { if (DateTimeField.ERA in fields) fields[DateTimeField.ERA] = it }
            if (DateTimeField.MONTH in fields &&
                date.monthCode != null
            ) {
                fields[DateTimeField.MONTH] = date.monthCode + "/" + date.isLeapYear
            }
        }
        return fields.toMap()
    }

    override fun renderIntervalPattern(
        start: DateTimeRenderContext,
        end: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        pattern: DateTimeIntervalPattern,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<FormattedDateTimeRange> {
        if (pattern.hourCycle != format.hourCycle) return LocaleLookup.Unsupported("Interval hour cycle differs from selected format")
        val output = ArrayList<RangePart<DateTimePart>>()
        for (part in pattern.parts) {
            val context = if (part.source == RangeSource.END) end else start
            when (val rendered = skeletons.renderTokens(parseDateTimePattern(part.pattern), context, format, numbers, zones)) {
                is LocaleLookup.Value -> output += rendered.value.parts.map { RangePart(it, part.source) }
                is LocaleLookup.Unsupported -> return rendered
                LocaleLookup.MissingData -> return LocaleLookup.MissingData
            }
        }
        return LocaleLookup.Value(FormattedDateTimeRange(output))
    }

    private fun recordFor(locale: Locale): IntervalRecord? = decoded.getOrPut(locale.stripExtensions().toLanguageTag()) {
        resolvedRecord(records, locale)?.let(::IntervalRecord)
    }

    public companion object
}

/**
 * One locale's interval patterns.
 *
 * Keyed by the canonical skeleton rather than by CLDR's own id, so that a
 * request resolved through the matcher lands on the same key whatever letters it
 * arrived with. The `j` skeleton is the reason: it resolves to `h` or `H`
 * depending on the locale, and only the resolved pattern knows which.
 */
@InternalKotlinxLocaleApi
public class IntervalRecord(record: String) {

    private val fields = record.split(FIELD_SEPARATOR)

    /**
     * The pattern for a pair no entry covers.
     *
     * The default is CLDR root's own `intervalFormatFallback` verbatim, thin
     * spaces included, rather than an approximation of it with ordinary spaces.
     * Codegen guarantees the field, so reaching the default means a record from
     * an older generator.
     */
    public val fallback: String = fields.getOrNull(0)?.takeIf(String::isNotEmpty) ?: "{0}\u2009\u2013\u2009{1}"

    private val byKey: Map<String, String> = buildMap {
        val entries = fields.getOrNull(1)?.takeIf(String::isNotEmpty)?.split(ENTRY_SEPARATOR).orEmpty()
        for (entry in entries) {
            val separator = entry.indexOf(KEY_SEPARATOR)
            if (separator <= 0) continue
            val key = entry.substring(0, separator)
            val pattern = entry.substring(separator + 1)
            val dot = key.lastIndexOf('.')
            if (dot <= 0) continue
            val canonical = SkeletonFields.of(key.substring(0, dot))?.canonicalString() ?: continue
            put(canonical + "." + key.substring(dot + 1), pattern)
        }
    }

    /**
     * The pattern for [canonicalSkeleton] differing in [difference].
     *
     * The hour is looked up as whichever of `H` or `h` [pattern] itself uses,
     * because CLDR keys the twelve- and twenty-four-hour forms separately and
     * only the resolved pattern says which one this locale reached for.
     */
    public fun patternFor(canonicalSkeleton: String, difference: Char, pattern: String): String? {
        if (difference != 'h') return byKey["$canonicalSkeleton.$difference"]
        val hourLetter = pattern.firstOrNull { it in "HhKk" } ?: 'H'
        return byKey["$canonicalSkeleton.$hourLetter"] ?: byKey["$canonicalSkeleton.H"] ?: byKey["$canonicalSkeleton.h"]
    }

    internal fun patternsFor(canonicalSkeleton: String): List<Pair<Char, String>> {
        val exact = byKey.filterKeys { it.substringBeforeLast('.') == canonicalSkeleton }
        if (exact.isNotEmpty()) return exact.map { it.key.last() to it.value }
        val request = SkeletonFields.of(canonicalSkeleton) ?: return emptyList()
        // Width adjustment is permitted only within the same field set and
        // numeric/text family. Zone, year and period semantics remain exact.
        val candidate = byKey.keys.map { it.substringBeforeLast('.') }.distinct().sorted().mapNotNull { key ->
            val fields = SkeletonFields.of(key) ?: return@mapNotNull null
            if (fields.fieldMask() != request.fieldMask()) return@mapNotNull null
            var distance = 0
            for (i in 0 until SkeletonField.COUNT) {
                if (fields.chars[i] != request.chars[i] || fields.isNumeric(i) != request.isNumeric(i)) return@mapNotNull null
                distance += kotlin.math.abs(fields.lengths[i] - request.lengths[i])
            }
            key to distance
        }.minByOrNull { it.second }?.first ?: return emptyList()
        return byKey.filterKeys { it.substringBeforeLast('.') == candidate }.map { it.key.last() to it.value }
    }

    public companion object
}

/**
 * Splits an interval pattern into the half that formats the start and the half
 * that formats the end.
 *
 * The split point is the second occurrence of the first field that repeats, not
 * of the greatest-difference field, and the fields that count as the same are
 * the ones CLDR treats as one: `M` and `L`, `E` and `e` and `c`, the four hour
 * letters. A per-letter implementation gets `d MMM – d LLL` wrong.
 *
 * Null when nothing repeats, which CLDR does write: those patterns format once.
 */
internal fun splitIntervalTokens(pattern: String): Pair<List<PatternToken>, List<PatternToken>>? {
    val tokens = parseDateTimePattern(pattern)
    val seen = HashSet<Char>()
    for ((index, token) in tokens.withIndex()) {
        if (token !is PatternToken.Field) continue
        if (!seen.add(intervalFieldGroup(token.letter))) {
            return tokens.subList(0, index) to tokens.subList(index, tokens.size)
        }
    }
    return null
}

/** The letters CLDR treats as one field for the purpose of finding the repeat. */
private fun intervalFieldGroup(letter: Char): Char = when (letter) {
    'L' -> 'M'
    'e', 'c' -> 'E'
    'k', 'K', 'H' -> 'h'
    'u', 'U', 'r' -> 'y'
    'b', 'B' -> 'a'
    else -> letter
}

/** Prepare fragments once, retaining the semantic source of each field and joining literal. */
internal fun preparedIntervalParts(pattern: String): List<DateTimeIntervalPatternPart> {
    val halves = splitIntervalTokens(pattern) ?: return listOf(DateTimeIntervalPatternPart(pattern, RangeSource.SHARED))
    val repeated = halves.first.filterIsInstance<PatternToken.Field>().map { intervalFieldGroup(it.letter) }.toSet()
        .intersect(halves.second.filterIsInstance<PatternToken.Field>().map { intervalFieldGroup(it.letter) }.toSet())
    return buildList {
        for ((tokens, endpoint) in listOf(halves.first to RangeSource.START, halves.second to RangeSource.END)) {
            val sources = tokens.map { token ->
                if (token is PatternToken.Field && intervalFieldGroup(token.letter) in repeated) endpoint else RangeSource.SHARED
            }.toMutableList()
            for (i in tokens.indices) {
                if (tokens[i] !is PatternToken.Literal) continue
                val before = (i - 1 downTo 0).firstOrNull { tokens[it] is PatternToken.Field }?.let { sources[it] }
                val after = (i + 1 until tokens.size).firstOrNull { tokens[it] is PatternToken.Field }?.let { sources[it] }
                sources[i] = if (before == endpoint && after == endpoint) endpoint else RangeSource.SHARED
            }
            for ((i, token) in tokens.withIndex()) {
                val fragment = when (token) {
                    is PatternToken.Literal -> quoteLiteral(token.text)
                    is PatternToken.Field -> token.letter.toString().repeat(token.count)
                }
                add(DateTimeIntervalPatternPart(fragment, sources[i]))
            }
        }
    }
}
