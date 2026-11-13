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

import org.w3c.dom.Element
import java.io.File

/** Extract immutable locale identity data independently of any formatter's locale set. */
internal fun encodeLocaleMetadata(cldrDir: File, currencies: List<CurrencyEntry> = emptyList()): String {
    val rows = sortedMapOf<String, String>()
    fun put(kind: String, key: String, value: String) {
        if (key.isNotEmpty() && value.isNotEmpty()) rows["$kind\t$key"] = value
    }
    fun elements(file: File, tag: String): List<Element> {
        if (!file.isFile) return emptyList()
        val nodes = parseXml(file).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { element ->
            generateSequence<org.w3c.dom.Node>(element) { it.parentNode }.all {
                it !is Element || it.getAttribute("draft") in setOf("", "approved", "contributed")
            }
        }
    }
    val supplemental = cldrDir.resolve("common/supplemental")
    for (kind in listOf("language", "script", "territory", "variant", "subdivision")) {
        for (e in elements(supplemental.resolve("supplementalMetadata.xml"), kind + "Alias")) {
            put(kind + "Alias", e.getAttribute("type").replace('_', '-').lowercase(), e.getAttribute("replacement").replace('_', '-'))
        }
    }
    for (e in elements(supplemental.resolve("likelySubtags.xml"), "likelySubtag")) {
        put("likely", e.getAttribute("from").replace('_', '-').lowercase(), e.getAttribute("to").replace('_', '-'))
    }
    for (e in elements(supplemental.resolve("supplementalData.xml"), "calendarPreference")) {
        for (region in e.getAttribute(
            "territories",
        ).split(' ')) {
            put("calendar", region, e.getAttribute("ordering").replace("gregorian", "gregory"))
        }
    }
    for (e in elements(supplemental.resolve("supplementalData.xml"), "hours")) {
        for (region in e.getAttribute("regions").split(' ')) {
            fun cycle(v: String): String = when (v.firstOrNull()) {
                'h' -> "h12"
                'K' -> "h11"
                'k' -> "h24"
                else -> "h23"
            }
            put(
                "hours",
                region.replace('_', '-').lowercase(),
                (listOf(e.getAttribute("preferred")) + e.getAttribute("allowed").split(' ')).filter {
                    it.isNotEmpty()
                }.map(::cycle).distinct().joinToString(" "),
            )
        }
    }
    for (e in elements(supplemental.resolve("numberingSystems.xml"), "numberingSystem")) {
        if (e.getAttribute("type") == "numeric") put("digits", e.getAttribute("id"), e.getAttribute("digits"))
    }
    for (e in elements(supplemental.resolve("supplementalData.xml"), "parentLocale")) {
        if (!e.hasAttribute(
                "component",
            )
        ) {
            for (id in e.getAttribute(
                "locales",
            ).split(' ')) {
                put("parent", id.replace('_', '-').lowercase(), e.getAttribute("parent").replace('_', '-').lowercase())
            }
        }
    }
    for (file in cldrDir.resolve("common/bcp47").listFiles().orEmpty().filter { it.extension == "xml" }) {
        for (e in elements(file, "type")) {
            val key = (e.parentNode as? Element)?.getAttribute("name").orEmpty()
            if (key.isEmpty()) continue
            val preferred = e.getAttribute("preferred").ifEmpty { e.getAttribute("name") }
            for (alias in (
                e.getAttribute(
                    "alias",
                ).split(' ') + e.getAttribute("name")
                )) {
                put("uAlias", "$key/" + alias.lowercase(), preferred)
            }
        }
    }
    for (e in elements(cldrDir.resolve("common/bcp47/timezone.xml"), "type")) {
        val aliases = e.getAttribute("alias").split(' ').filter { it.isNotEmpty() }
        if (aliases.isEmpty()) continue
        val primary = e.getAttribute("iana").ifEmpty { aliases.first() }
        val region = e.getAttribute("region").ifEmpty { e.getAttribute("name").take(2).uppercase() }
        if (region.length == 2 && region.all { it in 'A'..'Z' } && region !in listOf("UT", "UN")) {
            val key = "zones\t$region"
            rows[key] = (rows[key].orEmpty().split(' ') + primary).filter { it.isNotEmpty() }.distinct().sorted().joinToString(" ")
        }
    }
    for (file in cldrDir.resolve("common/main").listFiles().orEmpty().filter { it.extension == "xml" }.sorted()) {
        val id = file.nameWithoutExtension.replace('_', '-').lowercase()
        for (e in elements(file, "defaultNumberingSystem")) if (!e.hasAttribute("alt")) put("number", id, e.textContent.trim())
        for (e in elements(file, "otherNumberingSystems")) {
            for (name in listOf("native", "traditional", "finance")) e.child(name)?.textContent?.trim()?.let { put("number-$name", id, it) }
        }
        for (e in elements(file, "characterOrder")) put("direction", id, e.textContent.trim())
    }
    for (file in cldrDir.resolve("common/collation").listFiles().orEmpty().filter { it.extension == "xml" }) {
        val types = elements(file, "collation").map { it.getAttribute("type") }.filter { it.isNotEmpty() && !it.startsWith("private-") }
        val preferred = elements(file, "defaultCollation").firstOrNull()?.textContent?.trim() ?: "standard"
        put("collation", file.nameWithoutExtension.replace('_', '-').lowercase(), (listOf(preferred) + types).distinct().joinToString(" "))
    }
    for (currency in currencies) {
        put(
            "currency",
            currency.code,
            listOf(
                currency.minorUnits,
                currency.cldrDigits,
                currency.cldrRounding,
                currency.cldrCashDigits,
                currency.cldrCashRounding,
                if (currency.isCurrent) 1 else 0,
            ).joinToString(" "),
        )
    }
    val matching = supplemental.resolve("languageInfo.xml")
    for (e in elements(matching, "matchVariable")) put("matchVariable", e.getAttribute("id"), e.getAttribute("value"))
    elements(matching, "languageMatch").forEachIndexed { index, e ->
        put(
            "distance",
            index.toString().padStart(5, '0'),
            listOf(
                e.getAttribute("desired"),
                e.getAttribute("supported"),
                e.getAttribute("distance"),
                e.getAttribute("oneway").ifEmpty {
                    "false"
                },
            ).joinToString(" "),
        )
    }
    for (e in elements(supplemental.resolve("supplementalData.xml"), "group")) {
        if (e.parentNode.nodeName == "territoryContainment" &&
            e.getAttribute("status") != "deprecated"
        ) {
            put("containment", e.getAttribute("type"), e.getAttribute("contains"))
        }
    }
    for (e in elements(cldrDir.resolve("common/bcp47/timezone.xml"), "type")) {
        if (e.getAttribute("name") == "unk" || e.hasAttribute("preferred")) continue
        val aliases = e.getAttribute("alias").split(' ').filter { it.isNotEmpty() }
        if (aliases.isEmpty()) continue
        val preferred = e.getAttribute("iana").ifEmpty { aliases.first() }
        val primary = if (preferred in listOf("Etc/UTC", "Etc/GMT", "GMT", "UTC")) "UTC" else preferred
        val inferred = e.getAttribute("region").ifEmpty { e.getAttribute("name").take(2).uppercase() }
        val region = inferred.takeIf { it.length == 2 && it !in listOf("UT", "UN") && it.all { c -> c in 'A'..'Z' } }.orEmpty()
        for (alias in (aliases + primary).distinct()) put("zoneAlias", alias, "$primary $region".trim())
    }
    return rows.entries.joinToString("\u001e") { (key, value) -> "${key.replace('\t', '\u001f')}\u001f$value" }
}
