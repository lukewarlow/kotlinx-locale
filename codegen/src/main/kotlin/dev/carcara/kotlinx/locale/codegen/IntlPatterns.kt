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

/** Sparse list and measurement patterns, retaining explicit CLDR parent chains. */
internal fun extractIntlPatterns(cldr: File): Map<String, Map<String, String>> {
    val parents = parseSupplemental(cldr).parentOverrides
    val lists = linkedMapOf<String, String>()
    val units = linkedMapOf<String, String>()
    val numberSystems = linkedMapOf<String, String>()
    val displayNames = linkedMapOf<String, String>()
    val numberingDigits = parseSupplemental(cldr).numberingSystemDigits
    for (file in cldr.resolve("common/main").listFiles().orEmpty().filter { it.extension == "xml" }.sorted()) {
        val root = parseXml(file).documentElement
        checkNoContainerDrafts(root, file.name)
        val id = file.nameWithoutExtension
        val parent = if (id == "root") "" else parents[id] ?: id.substringBeforeLast('_', "root")
        fun record(rows: Map<String, String>): String = canonicalTag(parent) + FIELD_SEPARATOR + rows.entries.sortedBy { it.key }
            .joinToString(LIST_SEPARATOR) { (k, v) -> "$k$KEY_SEPARATOR$v" }
        val listRows = linkedMapOf<String, String>()
        for (pattern in root.child("listPatterns")?.childElements("listPattern").orEmpty()) {
            val type = pattern.getAttribute("type").ifEmpty { "standard" }
            pattern.child("alias")?.let { alias ->
                val target = Regex("listPattern\\[@type=['\"]([^'\"]+)['\"]\\]").find(alias.getAttribute("path"))?.groupValues?.get(1)
                    ?: if (alias.getAttribute(
                            "path",
                        ).endsWith("/listPattern")
                    ) {
                        "standard"
                    } else {
                        error("Unsupported list alias: ${alias.getAttribute("path")}")
                    }
                listRows["$type/alias"] = target
            }
            for (part in pattern.childElements("listPatternPart")) {
                if (part.hasAttribute("alt")) continue
                part.textContent.cleaned()?.let { listRows["$type/${part.getAttribute("type")}"] = it }
            }
        }
        lists[canonicalTag(id)] = record(listRows)
        val unitRows = linkedMapOf<String, String>()
        for (length in root.child("units")?.childElements("unitLength").orEmpty()) {
            val width = length.getAttribute("type")
            if (width !in listOf("long", "short", "narrow")) continue
            for (unit in length.childElements("unit")) {
                val identifier = unit.getAttribute("type").substringAfter('-')
                for (part in unit.childElements()) {
                    if (part.hasAttribute("case") || part.hasAttribute("alt")) continue
                    val suffix = when (part.tagName) {
                        "displayName" -> "name"
                        "perUnitPattern" -> "per"
                        "unitPattern" -> part.getAttribute("count")
                        else -> continue
                    }
                    part.textContent.cleaned()?.let { unitRows["$width/$identifier/$suffix"] = it }
                }
            }
            for (compound in length.childElements("compoundUnit")) {
                if (compound.getAttribute("type") == "per") {
                    compound.child("compoundUnitPattern")?.textContent?.cleaned()?.let {
                        unitRows["$width/compound-per"] = it
                    }
                }
            }
        }
        units[canonicalTag(id)] = record(unitRows)
        val numericRows = linkedMapOf<String, String>()
        if (id == "root") {
            numericRows["systems"] = numberingDigits.keys.sorted().joinToString(" ")
            for ((system, digits) in numberingDigits) numericRows["$system/digits"] = digits
        }
        for (symbols in root.child("numbers")?.childElements("symbols").orEmpty()) {
            val system = symbols.getAttribute("numberSystem")
            symbols.child("alias")?.let { alias ->
                Regex("numberSystem=['\"]([^'\"]+)['\"]").find(alias.getAttribute("path"))?.groupValues?.get(1)?.let {
                    numericRows["$system/alias"] = it
                }
            }
            for (field in symbols.childElements()) {
                if (field.tagName != "alias" &&
                    !field.hasAttribute("alt")
                ) {
                    field.textContent.cleaned()?.let { numericRows["$system/${field.tagName}"] = it }
                }
            }
        }
        for (misc in root.child("numbers")?.childElements("miscPatterns").orEmpty()) {
            val system = misc.getAttribute("numberSystem").ifEmpty { "latn" }
            for (pattern in misc.childElements("pattern")) {
                pattern.textContent.cleaned()?.let {
                    numericRows["$system/pattern-${pattern.getAttribute("type")}"] =
                        it
                }
            }
        }
        numberSystems[canonicalTag(id)] = record(numericRows)
        val names = linkedMapOf<String, String>()
        val display = root.child("localeDisplayNames")
        for ((container, element, kind) in listOf(
            Triple("languages", "language", "language"),
            Triple("scripts", "script", "script"),
            Triple("territories", "territory", "region"),
            Triple("variants", "variant", "variant"),
        )) {
            for (entry in display?.child(container)?.childElements(element).orEmpty()) {
                val alt = entry.getAttribute("alt")
                if (alt !in listOf("", "short", "narrow")) continue
                if (kind != "variant" && alt.isEmpty()) continue
                val code = entry.getAttribute("type").let { if (kind == "variant") it.lowercase() else it.replace('_', '-') }
                entry.textContent.cleaned()?.let { names["$kind/$code/${alt.ifEmpty { "long" }}"] = it }
            }
        }
        for (entry in display?.child("types")?.childElements("type").orEmpty()) {
            if (entry.getAttribute("key") != "calendar") continue
            val alt = entry.getAttribute("alt")
            if (alt !in listOf("", "short", "narrow")) continue
            val code = when (val type = entry.getAttribute("type")) {
                "gregorian" -> "gregory"
                "ethiopic-amete-alem" -> "ethioaa"
                else -> type
            }
            entry.textContent.cleaned()?.let { names["calendar/$code/${alt.ifEmpty { "long" }}"] = it }
        }
        val fieldCodes = mapOf(
            "era" to "era", "year" to "year", "quarter" to "quarter", "month" to "month", "week" to "weekOfYear",
            "weekday" to "weekday", "day" to "day", "dayperiod" to "dayPeriod", "hour" to "hour",
            "minute" to "minute", "second" to "second",
            "zone" to "timeZoneName",
        )
        for (field in root.child("dates")?.child("fields")?.childElements("field").orEmpty()) {
            val type = field.getAttribute("type")
            val width = when {
                type.endsWith("-short") -> "short"
                type.endsWith("-narrow") -> "narrow"
                else -> "long"
            }
            val code = fieldCodes[type.removeSuffix("-short").removeSuffix("-narrow")] ?: continue
            field.child("displayName")?.textContent?.cleaned()?.let { names["date_time_field/$code/$width"] = it }
        }
        displayNames[canonicalTag(id)] = record(names)
    }
    val ranges = linkedMapOf("root" to "other/other=other")
    val rangeRoot = parseXml(cldr.resolve("common/supplemental/pluralRanges.xml")).documentElement
    for (group in rangeRoot.child("plurals")?.childElements("pluralRanges").orEmpty()) {
        val encoded = group.childElements("pluralRange").joinToString(";") {
            "${it.getAttribute("start")}/${it.getAttribute("end")}=${it.getAttribute("result")}"
        }
        for (id in group.getAttribute("locales").split(' ')) ranges[canonicalTag(id)] = encoded
    }
    return mapOf(
        "listPatterns" to lists,
        "unitPatterns" to units,
        "pluralRanges" to ranges,
        "numberSystems" to numberSystems,
        "extendedDisplayNames" to displayNames,
    )
}

/** Append resolved era/day-period widths to the existing calendar auxiliary record. */
internal fun augmentDateTimeNames(cldr: File, standalone: Map<String, String>): Map<String, String> {
    val parents = parseSupplemental(cldr).parentOverrides
    val own = linkedMapOf<String, Map<String, String>>()
    for (file in cldr.resolve("common/main").listFiles().orEmpty().filter { it.extension == "xml" }) {
        val rows = linkedMapOf<String, String>()
        val document = parseXml(file).documentElement
        document.child("numbers")?.child("defaultNumberingSystem")?.textContent?.cleaned()?.let { rows["default/numberingSystem"] = it }
        val calendar = document.child("dates")?.child("calendars")?.childElements("calendar")?.firstOrNull {
            it.getAttribute("type") ==
                "gregorian"
        }
        for ((container, width) in listOf("eraNames" to "wide", "eraAbbr" to "abbreviated", "eraNarrow" to "narrow")) {
            for (entry in calendar?.child("eras")?.child(container)?.childElements("era").orEmpty()) {
                if (entry.hasAttribute("alt")) continue
                entry.textContent.cleaned()?.let { rows["era/$width/${entry.getAttribute("type")}"] = it }
            }
        }
        for (context in calendar?.child("dayPeriods")?.childElements("dayPeriodContext").orEmpty()) {
            for (width in context.childElements("dayPeriodWidth")) {
                for (period in width.childElements("dayPeriod")) {
                    if (period.hasAttribute("alt")) continue
                    period.textContent.cleaned()?.let {
                        rows["period/${context.getAttribute("type")}/${width.getAttribute("type")}/${period.getAttribute("type")}"] =
                            it
                    }
                }
            }
        }
        own[file.nameWithoutExtension] = rows
    }
    fun resolved(id: String): Map<String, String> {
        val chain = ArrayList<String>()
        var next = id
        while (next !in chain) {
            chain += next
            if (next == "root") break
            next = parents[next] ?: next.substringBeforeLast('_', "root")
        }
        return buildMap { for (level in chain.asReversed()) putAll(own[level].orEmpty()) }
    }
    return standalone.mapValues { (tag, record) ->
        record.split(FIELD_SEPARATOR).take(7).joinToString(FIELD_SEPARATOR) + FIELD_SEPARATOR +
            resolved(tag.replace('-', '_')).entries.sortedBy { it.key }.joinToString(LIST_SEPARATOR) { (key, value) ->
                key + KEY_SEPARATOR +
                    value
            }
    }
}
