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
import dev.carcara.kotlinx.locale.datetime.DateTimeRenderContext
import dev.carcara.kotlinx.locale.datetime.FormatStyle
import dev.carcara.kotlinx.locale.datetime.FormattedDateTime
import dev.carcara.kotlinx.locale.datetime.HourCycle
import dev.carcara.kotlinx.locale.datetime.SelectedDateTimeFormat
import dev.carcara.kotlinx.locale.datetime.format
import dev.carcara.kotlinx.locale.datetime.hourCycle
import dev.carcara.kotlinx.locale.internal.resolvedRecord
import dev.carcara.kotlinx.locale.internal.supportedLocalesOf
import dev.carcara.kotlinx.locale.number.NumberFormatSource
import dev.carcara.kotlinx.locale.timezone.TimeZoneNameSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * A source that formats by naming the fields wanted and letting the locale
 * decide their order, rather than by picking one of four fixed lengths.
 *
 * ```
 * format(date, "yMMMd", ptBR)  // "27 de jul. de 2026"
 * format(date, "yMMMd", ja)    // "2026年7月27日"
 * ```
 *
 * This contract is deliberately not part of `kotlinx-locale-datetime-core`, and
 * so is not something a `-platform` source can answer. That is an asymmetry
 * rather than an oversight: the platforms will format from a template, but none
 * of them will hand back the pattern they chose, and half of what makes a
 * skeleton useful is being able to reuse that pattern. A build wanting skeletons
 * takes the CLDR tables.
 *
 * A skeleton is written in the CLDR letters: `y` year, `M` month, `d` day, `E`
 * weekday, `Q` quarter, `h`/`H` hour, `m` minute, `s` second, `a`/`b`/`B` day
 * period, `G` era. Repeat a letter to ask for a width — `MMM` is an abbreviated
 * month name, `MMMM` a full one. `j` asks for whichever hour the locale prefers,
 * with the day period that goes with it; `J` for the hour with no day period;
 * `C` for the locale's first allowed hour format.
 */
public interface SkeletonFormatSource : LocaleDataSource {

    /**
     * The pattern [locale] uses for [skeleton], or `null` when this build has no
     * data for the locale or the skeleton names a field that cannot be rendered.
     *
     * Returning the pattern rather than only the formatted result is what lets a
     * caller hand it to `kotlinx-datetime`'s `DateTimeFormat` and get locale
     * aware *parsing* out of the same table.
     */
    public fun skeletonPatternOrNull(skeleton: String, locale: Locale): String?

    public fun formatOrNull(date: LocalDate, skeleton: String, locale: Locale): String?

    public fun formatOrNull(time: LocalTime, skeleton: String, locale: Locale): String?

    public fun formatOrNull(dateTime: LocalDateTime, skeleton: String, locale: Locale): String?

    public fun selectSkeleton(skeleton: String, locale: Locale, numberingSystem: String? = null): LocaleLookup<SelectedDateTimeFormat> =
        LocaleLookup.Unsupported("Skeleton selection")

    /** Candidate records for callers implementing their own format matcher. */
    public fun candidates(locale: Locale, numberingSystem: String? = null): LocaleLookup<List<SelectedDateTimeFormat>> =
        LocaleLookup.Unsupported("Format candidates")
    public fun render(
        context: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        numbers: NumberFormatSource? = null,
        zones: TimeZoneNameSource? = null,
    ): LocaleLookup<FormattedDateTime> = LocaleLookup.Unsupported("Structured skeleton formatting")

    public companion object
}

/**
 * [date] written for [locale] with the fields [skeleton] names; falls back to
 * ISO 8601, the way the style-based overloads do.
 */
public fun SkeletonFormatSource.format(date: LocalDate, skeleton: String, locale: Locale): String =
    formatOrNull(date, skeleton, locale) ?: date.toString()

/** [time] written for [locale]; falls back to ISO 8601. */
public fun SkeletonFormatSource.format(time: LocalTime, skeleton: String, locale: Locale): String =
    formatOrNull(time, skeleton, locale) ?: time.toString()

/** [dateTime] written for [locale]; falls back to ISO 8601. */
public fun SkeletonFormatSource.format(dateTime: LocalDateTime, skeleton: String, locale: Locale): String =
    formatOrNull(dateTime, skeleton, locale) ?: dateTime.toString()

/**
 * A [SkeletonFormatSource] over generated tables.
 *
 * Takes the datetime records as well as the skeleton ones because matching needs
 * both: the candidate pool includes each locale's four standard date and four
 * standard time patterns, and the two halves of a date-and-time request are
 * joined with the locale's own glue pattern. Rendering then needs the month and
 * weekday names those records carry.
 *
 * Matchers are built lazily and kept, because building one sorts a locale's
 * whole candidate pool and an application tends to ask the same locale
 * repeatedly.
 */
public class PayloadSkeletonFormats(
    skeletonFormats: Map<String, String>,
    skeletonAppendFormats: Map<String, String>,
    skeletonNames: Map<String, String>,
    dateTimeRecords: Map<String, String>,
    /**
     * The stand-alone names, empty when this build did not generate them.
     *
     * Needed here and not only in the style-based source, because a skeleton
     * pattern can ask for them directly: Catalan's `yMMM` is `LLL 'del' y`, and
     * without this table the `L` renders as the format name, giving
     * `de maig del 2026` where CLDR means `maig del 2026`.
     */
    standaloneRecords: Map<String, String> = emptyMap(),
    private val defaultNumbers: NumberFormatSource? = null,
    private val defaultZones: TimeZoneNameSource? = null,
) : SkeletonFormatSource {

    private val skeletonFormats = skeletonFormats.toMap()
    private val skeletonAppendFormats = skeletonAppendFormats.toMap()
    private val skeletonNames = skeletonNames.toMap()
    private val dateTimeRecords = dateTimeRecords.toMap()
    private val standaloneRecords = standaloneRecords.toMap()

    override val supportedLocales: Set<Locale> by lazy { supportedLocalesOf(this.skeletonFormats) }

    private val matchers = PreparedDateCache<Pair<String, HourCycle?>, LocaleSkeletons?>()

    override fun selectSkeleton(skeleton: String, locale: Locale, numberingSystem: String?): LocaleLookup<SelectedDateTimeFormat> {
        val data = skeletonsFor(locale) ?: return LocaleLookup.MissingData
        val fraction = skeleton.count { it == 'S' }
        val request = skeleton.filter { it != 'S' }
        val base = if (request.isEmpty() && fraction > 0) {
            ""
        } else {
            data.matcher.bestPatternOrNull(
                if (fraction > 0 && 's' !in request) request + "s" else request,
                extended = true,
            ) ?: return LocaleLookup.Unsupported("Skeleton $skeleton")
        }
        val system = numberingSystem ?: locale.unicodeKeywordOrNull("nu") ?: data.dateTime.defaultNumberingSystem
        val decimal = if (fraction > 0 && request.isNotEmpty()) {
            defaultNumbers?.symbolsOrNull(locale, system)?.decimal
                ?: return LocaleLookup.Unsupported("Numeric symbols are required for fractional-second patterns")
        } else {
            "."
        }
        val pattern = if (fraction == 0) {
            base
        } else if (base.isEmpty()) {
            "S".repeat(fraction)
        } else {
            buildString {
                for (token in parseDateTimePattern(base)) {
                    when (token) {
                        is PatternToken.Literal -> append(quoteLiteral(token.text))
                        is PatternToken.Field -> {
                            repeat(token.count) { append(token.letter) }
                            if (token.letter == 's') {
                                append(quoteLiteral(decimal))
                                repeat(fraction) { append('S') }
                            }
                        }
                    }
                }
            }
        }
        return LocaleLookup.Value(
            selectedDateTimeFormat(
                pattern,
                locale,
                numberingSystem ?: locale.unicodeKeywordOrNull("nu") ?: data.dateTime.defaultNumberingSystem,
            ),
        )
    }

    override fun candidates(locale: Locale, numberingSystem: String?): LocaleLookup<List<SelectedDateTimeFormat>> {
        val data = skeletonsFor(locale) ?: return LocaleLookup.MissingData
        val dates = data.dateTime.dateFormats
        val times = FormatStyle.entries.map { data.dateTime.timePattern(it, locale.hourCycle) }
        val patterns = (
            data.record.availableFormats.map { it.second } + dates + times + dates.flatMapIndexed { index, date ->
                times.map { time -> data.dateTime.glueFormats[index].replace("{1}", date).replace("{0}", time) }
            }
            ).distinct()
        return LocaleLookup.Value(
            patterns.filter { pattern ->
                parseDateTimePattern(pattern).all { token ->
                    token !is PatternToken.Field ||
                        (token.letter in "GyMLdEecabBhHKkmsSzvO" && !(token.letter in "ec" && token.count <= 2))
                }
            }.map {
                selectedDateTimeFormat(
                    it,
                    locale,
                    numberingSystem ?: locale.unicodeKeywordOrNull("nu") ?: data.dateTime.defaultNumberingSystem,
                )
            },
        )
    }

    override fun render(
        context: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<FormattedDateTime> {
        val data = skeletonsFor(format.locale) ?: return LocaleLookup.MissingData
        return renderDateTimeTokens(
            parseDateTimePattern(format.pattern),
            context,
            format,
            data.dateTime,
            data.record,
            numbers ?: defaultNumbers,
            zones ?: defaultZones,
        )
    }

    override fun skeletonPatternOrNull(skeleton: String, locale: Locale): String? =
        skeletonsFor(locale)?.matcher?.bestPatternOrNull(skeleton)

    override fun formatOrNull(date: LocalDate, skeleton: String, locale: Locale): String? =
        render(skeleton, locale, date = date, time = null)

    override fun formatOrNull(time: LocalTime, skeleton: String, locale: Locale): String? =
        render(skeleton, locale, date = null, time = time)

    override fun formatOrNull(dateTime: LocalDateTime, skeleton: String, locale: Locale): String? =
        render(skeleton, locale, date = dateTime.date, time = dateTime.time)

    private fun render(skeleton: String, locale: Locale, date: LocalDate?, time: LocalTime?): String? {
        val skeletons = skeletonsFor(locale) ?: return null
        val pattern = skeletons.matcher.bestPatternOrNull(skeleton) ?: return null
        // A standard pattern can reach the pool carrying a zone, and a value
        // here has none, so the same trimming the style-based overloads do
        // applies.
        val tokens = parseDateTimePattern(pattern).withoutZoneFields()
        return formatPattern(tokens, skeletons.dateTime, date, time, skeletons.record)
    }

    /**
     * The decoded data and matcher for [locale], built once and kept.
     *
     * Internal rather than private so the interval layer in this module reuses
     * the pool instead of building a second matcher per locale.
     */
    internal fun skeletonsFor(locale: Locale): LocaleSkeletons? {
        val key = locale.stripExtensions().toLanguageTag() to locale.hourCycle
        return matchers.getOrPut(key) { prepareSkeletons(locale) }
    }

    private fun prepareSkeletons(locale: Locale): LocaleSkeletons? {
        val formats = resolvedRecord(skeletonFormats, locale)
        val appendFormats = resolvedRecord(skeletonAppendFormats, locale)
        val names = resolvedRecord(skeletonNames, locale)
        val dateTime = resolvedRecord(dateTimeRecords, locale)
        val built = if (formats == null || appendFormats == null || names == null || dateTime == null) {
            null
        } else {
            val record = SkeletonRecord(formats, appendFormats, names)
            val dateTimeRecord = DateTimeRecord(dateTime, resolvedRecord(standaloneRecords, locale))
            LocaleSkeletons(record, dateTimeRecord, SkeletonMatcher(record, dateTimeRecord, locale.hourCycle))
        }
        return built
    }

    internal fun renderTokens(
        tokens: List<PatternToken>,
        context: DateTimeRenderContext,
        format: SelectedDateTimeFormat,
        numbers: NumberFormatSource?,
        zones: TimeZoneNameSource?,
    ): LocaleLookup<FormattedDateTime> {
        val data = skeletonsFor(format.locale) ?: return LocaleLookup.MissingData
        return renderDateTimeTokens(tokens, context, format, data.dateTime, data.record, numbers ?: defaultNumbers, zones ?: defaultZones)
    }
    public companion object
}

/**
 * One locale's decoded skeleton data and the matcher over it.
 *
 * Internal rather than private so the interval layer can share it. Building a
 * matcher sorts the locale's whole candidate pool, and interval formatting has to
 * pick a pattern for the requested skeleton before it can do anything else, so a
 * second source constructing its own would double that work per locale.
 */
internal class LocaleSkeletons(val record: SkeletonRecord, val dateTime: DateTimeRecord, val matcher: SkeletonMatcher)
