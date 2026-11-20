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

package dev.carcara.kotlinx.locale.codegen

import java.io.File

/** Consumer entry points shared by full and narrowed bindings. */
internal fun emitIntlFacade(
    root: File,
    spec: BindingSpec,
    binding: GeneratedBinding,
    tables: Set<GeneratedTable>,
    numberObject: String? = null,
    listObject: String? = null,
) {
    val objectName = spec.objectName
    val body = when (binding) {
        GeneratedBinding.NUMBER ->
            """
            public fun numberFormat(value: NumberValue, locale: Locale = Locale.current, options: NumberFormatOptions = NumberFormatOptions.Default): LocaleLookup<FormattedNumber> = $objectName.render(value, locale, options)
            public fun numberFormatRounded(value: RoundedNumber, locale: Locale = Locale.current, options: NumberFormatOptions = NumberFormatOptions.Default): LocaleLookup<FormattedNumber> = $objectName.renderRounded(value, locale, options)
            public fun numberFormatPercent(value: NumberValue, locale: Locale = Locale.current, options: NumberFormatOptions = NumberFormatOptions.Default): LocaleLookup<FormattedNumber> = $objectName.renderPercent(value, locale, PercentScale.FRACTION, options)
            public fun numberFormatPercentValue(value: NumberValue, locale: Locale = Locale.current, options: NumberFormatOptions = NumberFormatOptions.Default): LocaleLookup<FormattedNumber> = $objectName.renderPercent(value, locale, PercentScale.PERCENT, options)
            public fun numberComposeRange(start: FormattedNumber, end: FormattedNumber, locale: Locale = Locale.current, options: NumberFormatOptions = NumberFormatOptions.Default, approximately: Boolean = false, collapse: Boolean = true): LocaleLookup<FormattedNumberRange> = $objectName.composeRange(start, end, locale, options, approximately, collapse)
            public fun numberCapabilities(locale: Locale = Locale.current): NumberFormatCapabilities = $objectName.capabilities(locale)
            public fun pluralCategoryOrNull(value: PluralOperands, locale: Locale = Locale.current, type: PluralType = PluralType.CARDINAL): PluralCategory? = ${objectName}Plurals.pluralCategoryOrNull(value, type, locale)
            public fun pluralCategoriesOrNull(locale: Locale = Locale.current, type: PluralType = PluralType.CARDINAL): Set<PluralCategory>? = ${objectName}Plurals.pluralCategoriesOrNull(type, locale)
            public fun pluralRangeOrNull(start: PluralCategory, end: PluralCategory, locale: Locale = Locale.current): PluralCategory? = ${objectName}Plurals.pluralRangeOrNull(start, end, locale)
        """
        GeneratedBinding.CURRENCY -> if (GeneratedTable.CURRENCY_FORMATS in tables) {
            """
            public fun currencyFormat(value: NumberValue, currencyCode: String, locale: Locale = Locale.current, options: NumberFormatOptions = NumberFormatOptions.Default, style: CurrencySymbolStyle = CurrencySymbolStyle.SYMBOL): LocaleLookup<FormattedNumber> = $objectName.render(value, currencyCode, locale, options, style)
            public fun currencyDisplayName(currencyCode: String, locale: Locale = Locale.current, width: NameWidth = NameWidth.LONG, fallback: NameFallback = NameFallback.NONE): LocaleLookup<DisplayName> = $objectName.lookupName(currencyCode, locale, width, fallback)
        """
        } else {
            ""
        }
        GeneratedBinding.CURRENCY_PLURALS -> if (numberObject == null) {
            ""
        } else {
            """
            public fun currencyFormatName(value: NumberValue, currencyCode: String, locale: Locale = Locale.current, options: NumberFormatOptions = NumberFormatOptions.Default): LocaleLookup<FormattedNumber> =
                dev.carcara.kotlinx.locale.currency.cldr.runtime.currencyFormatName(value, currencyCode, locale, $objectName, $numberObject, options)
        """
        }
        GeneratedBinding.LANGUAGE ->
            """
            public fun localeDisplayName(value: Locale, locale: Locale = Locale.current, width: NameWidth = NameWidth.LONG, fallback: NameFallback = NameFallback.NONE, languageDisplay: LanguageDisplay = LanguageDisplay.DIALECT): LocaleLookup<DisplayName> = $objectName.localeName(value, locale, languageDisplay, width, fallback)
            public fun localeDisplayName(code: String, type: DisplayNameType, locale: Locale = Locale.current, width: NameWidth = NameWidth.LONG, fallback: NameFallback = NameFallback.NONE): LocaleLookup<DisplayName> = $objectName.lookupName(type, code, locale, width, fallback)
        """
        GeneratedBinding.COLLATION ->
            """
            public fun collationResolve(locale: Locale = Locale.current, options: CollationOptions = CollationOptions()): LocaleLookup<ResolvedCollation> = $objectName.resolve(locale, options)
            public fun collationValues(): Set<String> = $objectName.collations()
            public fun collationCapabilities(locale: Locale = Locale.current): CollationCapabilities = $objectName.capabilities(locale)
        """
        GeneratedBinding.DATE_TIME ->
            """
            public fun durationDigitalFormat(locale: Locale = Locale.current, numberingSystem: String? = null): LocaleLookup<DigitalDurationFormat> =
                dev.carcara.kotlinx.locale.datetime.cldr.runtime.durationDigitalFormat(locale, dateTimeSource, ${numberObject ?: "null"}, numberingSystem)
            public fun dateTimeCapabilities(locale: Locale = Locale.current): DateTimeCapabilities = $objectName.capabilities(locale)
            public fun dateTimeSelect(dateStyle: FormatStyle?, timeStyle: FormatStyle?, locale: Locale = Locale.current, hourCycle: HourCycle? = null, numberingSystem: String? = null): LocaleLookup<SelectedDateTimeFormat> = $objectName.select(dateStyle, timeStyle, locale, hourCycle, numberingSystem)
            public fun dateTimeSelect(dateStyle: FormatStyle?, timeStyle: FormatStyle?, locale: Locale, calendar: String, hourCycle: HourCycle? = null, numberingSystem: String? = null): LocaleLookup<SelectedDateTimeFormat> = $objectName.select(dateStyle, timeStyle, locale, calendar, hourCycle, numberingSystem)
            public fun dateTimeFormat(value: DateTimeRenderContext, format: SelectedDateTimeFormat, numbers: NumberFormatSource? = null, zones: TimeZoneNameSource? = null): LocaleLookup<FormattedDateTime> = $objectName.render(value, format, numbers, zones)
        """
        GeneratedBinding.SKELETONS ->
            """
            public fun dateTimeSelectSkeleton(skeleton: String, locale: Locale = Locale.current, numberingSystem: String? = null): LocaleLookup<SelectedDateTimeFormat> = $objectName.selectSkeleton(skeleton, locale, numberingSystem)
            public fun dateTimeSelectSkeleton(skeleton: String, locale: Locale, numberingSystem: String? = null, calendar: String): LocaleLookup<SelectedDateTimeFormat> = $objectName.selectSkeleton(skeleton, locale, numberingSystem, calendar)
            public fun dateTimeSelectSkeleton(skeleton: String, locale: Locale = Locale.current, numberingSystem: String? = null, fieldLengthMatching: dev.carcara.kotlinx.locale.datetime.cldr.runtime.SkeletonFieldLengthMatching): LocaleLookup<SelectedDateTimeFormat> = $objectName.selectSkeleton(skeleton, locale, numberingSystem, fieldLengthMatching)
            public fun dateTimeSelectSkeleton(skeleton: String, locale: Locale, numberingSystem: String? = null, calendar: String, fieldLengthMatching: dev.carcara.kotlinx.locale.datetime.cldr.runtime.SkeletonFieldLengthMatching): LocaleLookup<SelectedDateTimeFormat> = $objectName.selectSkeleton(skeleton, locale, numberingSystem, calendar, fieldLengthMatching)
            public fun dateTimeCandidates(locale: Locale, numberingSystem: String? = null, calendar: String): LocaleLookup<List<SelectedDateTimeFormat>> = $objectName.candidates(locale, numberingSystem, calendar)
            public fun dateTimeCandidates(locale: Locale = Locale.current, numberingSystem: String? = null): LocaleLookup<List<SelectedDateTimeFormat>> = $objectName.candidates(locale, numberingSystem)
        """
        GeneratedBinding.INTERVALS ->
            """
            public fun intervalFormatSelected(start: DateTimeRenderContext, end: DateTimeRenderContext, format: SelectedDateTimeFormat, skeleton: String? = null, numbers: NumberFormatSource? = null, zones: TimeZoneNameSource? = null): LocaleLookup<FormattedDateTimeRange> = $objectName.renderRange(start, end, format, skeleton, numbers, zones)
            public fun intervalSelectedPatterns(format: SelectedDateTimeFormat, skeleton: String? = null): LocaleLookup<SelectedDateTimeIntervals> = $objectName.selectedIntervals(format, skeleton)
            public fun intervalComparisonValues(value: DateTimeRenderContext, format: SelectedDateTimeFormat, numbers: NumberFormatSource? = null, zones: TimeZoneNameSource? = null): LocaleLookup<Map<DateTimeField, String>> = $objectName.comparisonValues(value, format, numbers, zones)
            public fun intervalFormatPattern(start: DateTimeRenderContext, end: DateTimeRenderContext, format: SelectedDateTimeFormat, pattern: DateTimeIntervalPattern, numbers: NumberFormatSource? = null, zones: TimeZoneNameSource? = null): LocaleLookup<FormattedDateTimeRange> = $objectName.renderIntervalPattern(start, end, format, pattern, numbers, zones)
        """
        GeneratedBinding.RELATIVE_TIME ->
            """
            public fun relativeTimeFormat(value: Decimal, unit: RelativeTimeUnit, locale: Locale = Locale.current, style: RelativeTimeStyle = RelativeTimeStyle.FULL, numbering: RelativeTimeNumbering = RelativeTimeNumbering.ALWAYS, options: NumberFormatOptions = NumberFormatOptions.Default): LocaleLookup<FormattedRelativeTime> = $objectName.render(value, unit, style, numbering, locale, options)
        """
        GeneratedBinding.DURATION_UNITS ->
            """
            public fun durationFormat(value: Decimal, unit: DurationUnit, locale: Locale, width: dev.carcara.kotlinx.locale.datetime.cldr.runtime.UnitWidth, options: NumberFormatOptions): LocaleLookup<FormattedNumber> = $objectName.render(value, unit, width, locale, options)
            public fun durationComposeUnit(value: FormattedNumber, unit: DurationUnit, locale: Locale = Locale.current, width: dev.carcara.kotlinx.locale.datetime.cldr.runtime.UnitWidth = dev.carcara.kotlinx.locale.datetime.cldr.runtime.UnitWidth.LONG): LocaleLookup<FormattedNumber> = $objectName.compose(value, unit, width, locale)
            ${if (listObject == null) "" else "public fun durationCompose(elements: List<FormattedNumber>, locale: Locale = Locale.current, style: dev.carcara.kotlinx.locale.list.ListStyle = dev.carcara.kotlinx.locale.list.ListStyle.LONG): LocaleLookup<List<NumberPart>> = dev.carcara.kotlinx.locale.datetime.cldr.runtime.durationCompose(elements, locale, $listObject, style)"}
        """
        GeneratedBinding.TIME_ZONE ->
            """
            public fun timeZoneName(value: TimeZoneNameContext, locale: Locale = Locale.current, style: TimeZoneNameStyle = TimeZoneNameStyle.GENERIC_LONG): LocaleLookup<String> = $objectName.render(value, style, locale)
        """
        else -> ""
    }.trimIndent()
    if (body.isEmpty()) return
    val imports = buildList {
        add("dev.carcara.kotlinx.locale.*")
        when (binding) {
            GeneratedBinding.NUMBER -> add("dev.carcara.kotlinx.locale.number.*")
            GeneratedBinding.CURRENCY, GeneratedBinding.CURRENCY_PLURALS -> {
                add("dev.carcara.kotlinx.locale.number.*")
                add("dev.carcara.kotlinx.locale.currency.*")
            }
            GeneratedBinding.LANGUAGE -> add("dev.carcara.kotlinx.locale.language.*")
            GeneratedBinding.COLLATION -> add("dev.carcara.kotlinx.locale.collation.*")
            GeneratedBinding.TIME_ZONE -> add("dev.carcara.kotlinx.locale.timezone.*")
            else -> {
                add("dev.carcara.kotlinx.locale.datetime.*")
                add("dev.carcara.kotlinx.locale.datetime.cldr.runtime.*")
                add("dev.carcara.kotlinx.locale.number.*")
                add("dev.carcara.kotlinx.locale.timezone.*")
            }
        }
    }
    root.packageFile(spec.packageName, "Intl${binding.objectSuffix}.kt").writeText(
        LICENSE_HEADER + "// GENERATED by :codegen. Do not edit.\npackage ${spec.packageName}\n\n" +
            imports.joinToString("\n") { "import $it" } +
            "\n\n" +
            body +
            "\n",
    )
}
