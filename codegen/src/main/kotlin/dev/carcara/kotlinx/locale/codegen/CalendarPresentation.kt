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

import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.DateFormatSymbols
import com.ibm.icu.text.DateIntervalInfo
import com.ibm.icu.text.DateTimePatternGenerator
import com.ibm.icu.text.SimpleDateFormat
import com.ibm.icu.util.ULocale
import java.io.File

internal val presentationCalendars = BundleSection.ALL.mapNotNull {
    it.name.takeIf { name ->
        name.startsWith("calendar.") && name.endsWith(".dateTime")
    }?.removePrefix("calendar.")?.removeSuffix(".dateTime")
}

/** Resolve packaged calendar inheritance with pinned ICU; raw CLDR supplies stable era codes. */
internal fun extractCalendarPresentation(cldr: File, bundle: LocaleDataBundle): Map<String, Map<String, String>> {
    val result = linkedMapOf<String, Map<String, String>>()
    val eras = parseXml(cldr.resolve("common/supplemental/supplementalData.xml")).documentElement
        .child("calendarData")!!.childElements("calendar").associate { calendar ->
        calendar.getAttribute("type") to calendar.child("eras")?.childElements("era").orEmpty().associate {
            it.getAttribute("type").toInt() to it.getAttribute("code").ifEmpty { "era-${it.getAttribute("type")}" }
        }
    }
    fun base(section: String, tag: String): String {
        var key = tag
        val table = bundle.section(section)
        while (true) {
            table[key]?.let { return it }
            if (key == "root") error("Missing $section root")
            key = key.substringBeforeLast('-', "root")
        }
    }
    for (calendar in presentationCalendars) {
        val dates = linkedMapOf<String, String>()
        val standalone = linkedMapOf<String, String>()
        val skeletons = linkedMapOf<String, String>()
        val intervals = linkedMapOf<String, String>()
        val cldrCalendar = when (calendar) {
            "ethioaa" -> "ethiopic-amete-alem"
            else -> calendar
        }
        val eraCodes = eras[cldrCalendar] ?: if (calendar.startsWith("islamic")) eras["islamic"].orEmpty() else emptyMap()
        for (tag in (listOf("root") + bundle.localeTags).distinct()) {
            val locale = ULocale((if (tag == "root") "root" else tag.replace('-', '_')) + "@calendar=$cldrCalendar")
            val symbols = DateFormatSymbols(locale)
            val generator = DateTimePatternGenerator.getInstance(locale)
            val fields = base("dateTime", tag).split('\u001f').toMutableList()
            for (style in 0..3) fields[10 + style] = (DateFormat.getDateInstance(style, locale) as SimpleDateFormat).toPattern()
            for (style in 0..3) fields[18 + style] = generator.getDateTimeFormat(style)
            dates[tag] =
                (
                    (0..3).map {
                        "style/$it\u001d${fields[10 + it]}"
                    } + (0..3).map { "glue/$it\u001d${fields[18 + it]}" }
                    ).joinToString("\u001e")
            val extra = linkedMapOf<String, String>()
            extra["id"] = calendar
            if (calendar == "japanese") {
                // Extract the pinned ICU/CLDR Japanese-year numbering rule;
                // the runtime reads data and never invokes a host formatter.
                val yearNumbers = SimpleDateFormat("y年", locale).getNumberFormat('y')
                if (yearNumbers is com.ibm.icu.text.RuleBasedNumberFormat) {
                    val first = yearNumbers.format(1)
                    if (first.none(Char::isDigit) && yearNumbers.format(2).any(Char::isDigit)) {
                        extra["firstYear/value"] = first
                        extra["firstYear/literal"] = "年"
                    }
                }
            }
            for ((contextName, context) in listOf("format" to DateFormatSymbols.FORMAT, "stand-alone" to DateFormatSymbols.STANDALONE)) {
                for ((widthName, width) in listOf(
                    "wide" to DateFormatSymbols.WIDE,
                    "abbreviated" to DateFormatSymbols.ABBREVIATED,
                    "narrow" to DateFormatSymbols.NARROW,
                )) {
                    val months = symbols.getMonths(context, width)
                    for ((index, name) in months.withIndex()) {
                        if (name.isEmpty()) continue
                        val code = if (calendar == "hebrew") {
                            when {
                                index == 5 -> "M05L"
                                index == 13 -> "M06-leap"
                                index >= 6 -> "M" + index.toString().padStart(2, '0')
                                else -> "M" + (index + 1).toString().padStart(2, '0')
                            }
                        } else {
                            "M" + (index + 1).toString().padStart(2, '0')
                        }
                        extra["month/$contextName/$widthName/$code"] = name
                    }
                    symbols.getLeapMonthPattern(context, width)?.let { extra["leap/$contextName/$widthName"] = it }
                    symbols.getYearNames(context, width)?.forEachIndexed { index, name -> extra["cyclic/$widthName/${index + 1}"] = name }
                }
                @Suppress("DEPRECATION")
                val numeric = symbols.getLeapMonthPattern(DateFormatSymbols.NUMERIC, DateFormatSymbols.WIDE)
                numeric?.let { extra["leap/$contextName/numeric"] = it }
            }
            for ((width, names) in listOf("abbreviated" to symbols.eras, "wide" to symbols.eraNames, "narrow" to symbols.narrowEras)) {
                for ((index, name) in names.withIndex()) {
                    if (name.isEmpty()) continue
                    val code = eraCodes[index] ?: "era-$index"
                    extra["era/$width/$code"] = name
                    extra["eraCode/$code"] = index.toString()
                }
            }
            if (calendar == "japanese") {
                // Modern ICU4X Japanese fields use Gregorian eras before 1873.
                // Keep the supplied era identity; only localized names belong here.
                val gregorian = DateFormatSymbols(ULocale(locale.baseName + "@calendar=gregorian"))
                for ((width, names) in listOf(
                    "abbreviated" to gregorian.eras,
                    "wide" to gregorian.eraNames,
                    "narrow" to gregorian.narrowEras,
                )) {
                    for ((index, code) in listOf("bce", "ce").withIndex()) {
                        names.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { extra["era/$width/$code"] = it }
                        extra["eraCode/$code"] = (index - 2).toString()
                    }
                }
            }
            standalone[tag] = extra.entries.joinToString("\u001e") { "${it.key}\u001d${it.value}" }
            skeletons[tag] =
                generator.getSkeletons(null).entries.sortedBy { it.key }.joinToString("\u001e") { "${it.key}\u001d${it.value}" }
            val info = DateIntervalInfo(locale)
            val patterns = info.rawPatterns.entries.sortedBy { it.key }.flatMap { (skeleton, differences) ->
                differences.entries.sortedBy { it.key }.map { (difference, pattern) ->
                    val prefix = if (pattern.firstDateInPtnIsLaterDate()) "latestFirst:" else ""
                    "$skeleton.$difference\u001d$prefix${pattern.firstPart}${pattern.secondPart.orEmpty()}"
                }
            }
            intervals[tag] = "fallback\u001d" + info.fallbackIntervalPattern + "\u001e" + patterns.joinToString("\u001e")
        }
        result["calendar.$calendar.dateTime"] = compactCalendarRows(dates)
        result["calendar.$calendar.standalone"] = compactCalendarRows(standalone)
        result["calendar.$calendar.skeletons"] = compactCalendarRows(skeletons)
        result["calendar.$calendar.intervals"] = compactCalendarRows(intervals)
        println("[calendars] prepared $calendar (${dates.size} locales)")
    }
    result["calendarDefaults"] = (listOf("root") + bundle.localeTags).distinct().associateWith { tag ->
        when (val calendar = com.ibm.icu.util.Calendar.getInstance(if (tag == "root") ULocale.ROOT else ULocale.forLanguageTag(tag)).type) {
            "gregorian" -> "gregory"
            "ethiopic-amete-alem" -> "ethioaa"
            else -> calendar
        }
    }
    return result
}

/** Emit from committed records too; regeneration does not require a host ICU formatter. */
internal fun emitCalendarPresentation(root: File, bundle: LocaleDataBundle) {
    val output = root.resolve(
        "kotlinx-locale-datetime-cldr-calendars/src/commonMain/kotlin/dev/carcara/kotlinx/locale/datetime/cldr/calendars/internal",
    )
    val pkg = "dev.carcara.kotlinx.locale.datetime.cldr.calendars.internal"
    val bindings = mutableListOf<String>()
    val calendarVersion = "ICU ${com.ibm.icu.util.VersionInfo.ICU_VERSION} calendar resources " +
        "(CLDR ${com.ibm.icu.util.LocaleData.getCLDRVersion()}); supplemental ${bundle.cldrVersion}"
    for (calendar in presentationCalendars) {
        val name = calendar.replace("-", "_")
        for (section in listOf("dateTime", "standalone", "skeletons", "intervals")) {
            val records = bundle.section("calendar.$calendar.$section")
            if (records.isEmpty()) continue
            val property = "${name}_$section"
            KeyedPayloadEmitter(
                output,
                pkg,
                "Calendar_${name}_$section",
                "CAL_${name.uppercase()}_${section.uppercase()}",
                property,
                calendarVersion,
            ).emit(records)
            bindings += "\"$calendar/$section\" to $property"
        }
    }
    if (bindings.isEmpty()) return
    for (section in listOf("calendarDefaults", "skeletonAppendFormats", "skeletonNames")) {
        KeyedPayloadEmitter(
            output,
            pkg,
            "Calendar_$section",
            "CAL_${section.uppercase()}",
            section,
            if (section == "calendarDefaults") calendarVersion else "CLDR ${bundle.cldrVersion}",
        ).emit(bundle.section(section))
        bindings += "\"$section\" to $section"
    }
    output.resolve("CalendarTables.kt").writeText(
        LICENSE_HEADER +
            "// GENERATED by :codegen:generateCalendarPresentation. Do not edit.\npackage $pkg\n\n" +
            "internal val calendarTables: Map<String, Map<String, String>> = mapOf(\n" +
            bindings.joinToString(",\n") { "    $it" } +
            "\n)\n",
    )
}

/** Resolved ICU rows compared against a fixed parent tree; no inheritance is guessed at runtime. */
private fun compactCalendarRows(records: Map<String, String>): Map<String, String> {
    val decoded = records.mapValues { (_, row) ->
        row.split('\u001e').filter { it.isNotEmpty() }.associate {
            it.substringBefore('\u001d') to it.substringAfter('\u001d')
        }
    }
    return records.keys.associateWith { tag ->
        var parent = if (tag == "root") "" else tag.substringBeforeLast('-', "root")
        while (parent.isNotEmpty() && parent !in records) parent = parent.substringBeforeLast('-', "root")
        val previous = decoded[parent].orEmpty()
        val current = decoded.getValue(tag)
        val differences = (previous.keys + current.keys).filter { previous[it] != current[it] }.sorted()
            .joinToString("\u001e") { "$it\u001d${current[it].orEmpty()}" }
        parent + "\u001f" + differences
    }
}
