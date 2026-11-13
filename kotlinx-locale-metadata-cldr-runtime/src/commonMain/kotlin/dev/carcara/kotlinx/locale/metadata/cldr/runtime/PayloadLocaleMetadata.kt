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

package dev.carcara.kotlinx.locale.metadata.cldr.runtime

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.metadata.CurrencyMetadata
import dev.carcara.kotlinx.locale.metadata.LocaleMatch
import dev.carcara.kotlinx.locale.metadata.LocaleMetadataSource
import dev.carcara.kotlinx.locale.metadata.LocalePreferences
import dev.carcara.kotlinx.locale.metadata.RegionOrigin
import dev.carcara.kotlinx.locale.metadata.RegionPreference
import dev.carcara.kotlinx.locale.metadata.TextDirection
import dev.carcara.kotlinx.locale.metadata.TimeZoneIdentifier

/** Immutable, standalone CLDR locale identity and preference tables. */
public class PayloadLocaleMetadata(override val dataVersion: String, encoded: String) : LocaleMetadataSource {
    private val tables: Map<String, Map<String, String>> = encoded.split('\u001e').filter { it.isNotEmpty() }
        .map { it.split('\u001f', limit = 3).also { fields -> require(fields.size == 3) } }
        .groupBy({ it[0] }, { it[1] to it[2] }).mapValues { (_, pairs) -> pairs.toMap() }
    private val languageAliases = table("languageAlias").entries.sortedWith(
        compareByDescending<Map.Entry<String, String>> { it.key.substringBefore('-') != "und" }
            .thenByDescending { it.key.count { c -> c == '-' } }.thenBy { it.key },
    )

    private fun table(name: String): Map<String, String> = tables[name].orEmpty()
    private fun tag(locale: Locale): String = locale.stripExtensions().toLanguageTag().lowercase()
    private fun languageId(value: String): Locale = requireNotNull(Locale.forUnicodeLanguageTagOrNull(value)) {
        "Invalid CLDR language identifier: $value"
    }

    override fun canonicalize(locale: Locale): Locale {
        var value = locale
        repeat(32) {
            val previous = value.toLanguageTag()
            for ((key, replacement) in languageAliases) {
                val pattern = Locale.forUnicodeLanguageTagOrNull(key) ?: continue
                if ((pattern.language != "und" && pattern.language != value.language) ||
                    (pattern.script != null && pattern.script != value.script) ||
                    (pattern.region != null && pattern.region != value.region) ||
                    !value.variants.containsAll(pattern.variants)
                ) {
                    continue
                }
                val target = Locale.forUnicodeLanguageTagOrNull(replacement.substringBefore(' ')) ?: continue
                value = value.withLanguageId(
                    language = if (target.language == "und") value.language else target.language,
                    script = if (pattern.script != null || value.script == null) target.script else value.script,
                    region = if (pattern.region != null || value.region == null) target.region else value.region,
                    variants = (value.variants - pattern.variants.toSet() + target.variants).distinct(),
                )
                break
            }
            val script = value.script?.let { table("scriptAlias")[it.lowercase()] ?: it }
            val replacements = value.region?.let { table("territoryAlias")[it.lowercase()] }?.split(' ')
            val region = if (replacements != null) {
                val likely = maximizeId(value.withLanguageId(region = null)).region
                replacements.firstOrNull { it.equals(likely, ignoreCase = true) } ?: replacements.first()
            } else {
                value.region
            }
            val variants = value.variants.flatMap { (table("variantAlias")[it] ?: it).lowercase().split('-', ' ') }.distinct().sorted()
            value = value.withLanguageId(script = script, region = region, variants = variants)
            if (value.toLanguageTag() == previous) return canonicalizeExtensions(value)
        }
        error("CLDR locale aliases contain a cycle for ${locale.toLanguageTag()}")
    }

    private fun canonicalizeExtensions(locale: Locale): Locale {
        var result = locale
        for ((key, value) in locale.unicodeKeywords) {
            result = result.withUnicodeKeyword(key, canonicalizeUnicodeValue(key, value).ifEmpty { "true" })
        }
        locale.extensions['t']?.let { transformed ->
            val parts = transformed.split('-')
            val firstKey = parts.indexOfFirst { it.length == 2 && it[0] in 'a'..'z' && it[1] in '0'..'9' }
                .let { if (it < 0) parts.size else it }
            val fields = linkedMapOf<String, String>()
            var index = firstKey
            while (index < parts.size) {
                val key = parts[index++]
                val start = index
                while (index < parts.size && parts[index].length != 2) index++
                fields[key] = canonicalizeUnicodeValue(key, parts.subList(start, index).joinToString("-"))
            }
            val language = if (firstKey ==
                0
            ) {
                ""
            } else {
                canonicalize(languageId(parts.take(firstKey).joinToString("-"))).toLanguageTag().lowercase()
            }
            result =
                result.withExtension(
                    't',
                    (
                        listOf(language).filter { it.isNotEmpty() } +
                            fields.entries.sortedBy { it.key }.map { (k, v) -> "$k-$v" }
                        ).joinToString("-"),
                )
        }
        return result
    }

    override fun canonicalizeUnicodeValue(key: String, value: String): String {
        if (value.isEmpty()) return ""
        var result = value.lowercase()
        repeat(16) {
            val next = table("uAlias")["${key.lowercase()}/$result"]?.lowercase() ?: return result
            if (next == result) return result
            result = next
        }
        error("CLDR Unicode aliases contain a cycle for $key/$value")
    }

    private fun maximizeId(locale: Locale): Locale {
        val language = locale.language
        val script = locale.script
        val region = locale.region
        val candidates = listOfNotNull(
            listOfNotNull(language, script, region).joinToString("-"),
            script?.let { "$language-$it" },
            region?.let { "$language-$it" },
            language,
            script?.let { "und-$it" },
            region?.let { "und-$it" },
            "und",
        )
        val replacement = candidates.firstNotNullOfOrNull { table("likely")[it.lowercase()] } ?: return locale
        val maximized = languageId(replacement)
        return locale.withLanguageId(
            language = if (language == "und") maximized.language else language,
            script = script ?: maximized.script,
            region = region ?: maximized.region,
        )
    }

    override fun maximize(locale: Locale): Locale = maximizeId(canonicalize(locale))

    override fun minimize(locale: Locale): Locale {
        val canonical = canonicalize(locale)
        val maximal = maximizeId(canonical)
        for (candidate in listOf(
            canonical.withLanguageId(language = maximal.language, script = null, region = null),
            canonical.withLanguageId(language = maximal.language, script = null, region = maximal.region),
            canonical.withLanguageId(language = maximal.language, script = maximal.script, region = null),
        )) {
            val test = maximizeId(candidate)
            if (test.language == maximal.language && test.script == maximal.script && test.region == maximal.region) return candidate
        }
        return maximal
    }

    override fun regionPreference(locale: Locale): RegionPreference {
        val canonical = canonicalize(locale)
        val override = canonical.unicodeKeywordOrNull("rg")
        if (override != null && override.endsWith("zzzz")) {
            val region = override.dropLast(4)
            if ((region.length == 2 && region.all { it in 'a'..'z' }) || (region.length == 3 && region.all { it in '0'..'9' })) {
                val resolved = canonicalize(canonical.withLanguageId(region = region.uppercase())).region!!
                return RegionPreference(resolved, RegionOrigin.OVERRIDE)
            }
        }
        canonical.region?.let { return RegionPreference(it, RegionOrigin.EXPLICIT) }
        canonical.unicodeKeywordValueOrNull("sd")?.takeIf(String::isNotEmpty)?.let { raw ->
            val subdivision = table("subdivisionAlias")[raw]?.substringBefore(' ')?.lowercase() ?: raw
            val region = subdivision.take(if (subdivision.firstOrNull() in '0'..'9') 3 else 2).uppercase()
            if (region.length in 2..3) return RegionPreference(region, RegionOrigin.SUBDIVISION)
        }
        return maximizeId(canonical).region?.let { RegionPreference(it, RegionOrigin.LIKELY) }
            ?: RegionPreference("001", RegionOrigin.WORLD)
    }

    private fun lookup(kind: String, locale: Locale): String? {
        val map = table(kind)
        var id = tag(locale)
        val seen = HashSet<String>()
        while (seen.add(id)) {
            map[id]?.let { return it }
            if (id == "root") return null
            id = table("parent")[id] ?: if ('-' in id) id.substringBeforeLast('-') else "root"
        }
        return null
    }

    override fun preferences(locale: Locale): LocalePreferences {
        val canonical = canonicalize(locale)
        val region = regionPreference(canonical).region
        val hours = (
            table("hours")["${canonical.language}-$region".lowercase()] ?: table("hours")[region.lowercase()]
                ?: table("hours")["001"] ?: "h23 h12"
            ).split(' ').distinct()
        val numbered = lookup("number", canonical) ?: "latn"
        val script = maximizeId(canonical).script
        val direction = table("direction").entries.firstOrNull { (id, _) ->
            val language = Locale.forUnicodeLanguageTagOrNull(id) ?: return@firstOrNull false
            maximizeId(language).script == script
        }?.value ?: lookup("direction", maximizeId(canonical)) ?: lookup("direction", canonical)
        return LocalePreferences(
            calendars = (table("calendar")[region] ?: table("calendar")["001"] ?: "gregory").split(' ').map {
                canonicalizeUnicodeValue("ca", it)
            }.distinct(),
            collations = (lookup("collation", canonical) ?: "standard").split(' ').map { canonicalizeUnicodeValue("co", it) }.distinct(),
            numberingSystems = listOf(numbered),
            hourCycles = hours,
            hourCycle12 = hours.firstOrNull { it == "h11" || it == "h12" } ?: "h12",
            hourCycle24 = hours.firstOrNull { it == "h23" || it == "h24" } ?: "h23",
            direction = if (direction == "right-to-left") TextDirection.RTL else TextDirection.LTR,
        )
    }

    override fun timeZonesForRegion(region: String): List<String> = table("zones")[region.uppercase()]?.split(' ') ?: emptyList()
    override fun timeZoneIdentifiers(): List<TimeZoneIdentifier> = table("zoneAlias").map { (identifier, value) ->
        TimeZoneIdentifier(identifier, value.substringBefore(' '), value.substringAfter(' ', "").ifEmpty { null })
    }.sortedBy { it.identifier }

    override fun timeZoneIdentifierOrNull(identifier: String): TimeZoneIdentifier? {
        if (identifier.any { it.code >= 128 }) return null
        return timeZoneIdentifiers().firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }
    }

    override fun currencies(): List<CurrencyMetadata> = table("currency").map { (code, row) ->
        val values = row.split(' ').map(String::toInt)
        CurrencyMetadata(code, values[0].takeIf { it >= 0 }, values[1], values[2], values[3], values[4], values[5] != 0)
    }.sortedBy { it.code }

    /** CLDR language/script/region distances, with deterministic available-tag tie breaking. */
    override fun bestFitOrNull(requested: List<Locale>, available: Set<Locale>): LocaleMatch? {
        val candidates = available.map { canonicalize(it) }.sortedBy { it.toLanguageTag() }
        if (candidates.isEmpty()) return null
        fun inRegion(region: String, group: String, seen: Set<String> = emptySet()): Boolean {
            if (region == group) return true
            if (group in seen) return false
            return table("containment")[group]?.split(' ')?.any { inRegion(region, it, seen + group) } == true
        }
        fun matches(pattern: String, locale: Locale): Boolean {
            val fields = pattern.split('_')
            val actual = listOf(locale.language, locale.script.orEmpty(), locale.region.orEmpty())
            return fields.indices.all { index ->
                val field = fields[index]
                when {
                    field == "*" -> true
                    field.startsWith("$") -> {
                        val negated = field.startsWith("$!")
                        val variable = if (negated) "$" + field.drop(2) else field
                        val found = table("matchVariable")[variable]?.split('+')?.any { inRegion(actual[index], it) } == true
                        found != negated
                    }
                    else -> field.equals(actual[index], ignoreCase = true)
                }
            }
        }
        val rules = table("distance").values.map { it.split(' ') }
        fun distance(desired: Locale, supported: Locale): Int {
            var total = 0
            for (level in 1..3) {
                val same = when (level) {
                    1 -> desired.language == supported.language
                    2 -> desired.script == supported.script
                    else ->
                        desired.region ==
                            supported.region
                }
                if (same) continue
                val rule = rules.firstOrNull {
                    it[0].split('_').size == level &&
                        (
                            (matches(it[0], desired) && matches(it[1], supported)) ||
                                (it.getOrNull(3) != "true" && matches(it[1], desired) && matches(it[0], supported))
                            )
                }
                total += rule?.getOrNull(2)?.toIntOrNull() ?: when (level) {
                    1 -> 80
                    2 -> 50
                    else -> 4
                }
            }
            return total
        }
        var best: LocaleMatch? = null
        var score = Int.MAX_VALUE
        for ((index, request) in requested.withIndex()) {
            val desired = maximize(request)
            for (candidate in candidates) {
                val value = if (canonicalize(request).stripExtensions() ==
                    candidate.stripExtensions()
                ) {
                    0
                } else {
                    distance(desired, maximize(candidate))
                }
                if (value >= 50) continue
                val weighted = value.toLong() + index.toLong() * 5
                if (weighted < score) {
                    score = weighted.toInt()
                    best = LocaleMatch(request, candidate, value)
                }
            }
        }
        return best
    }

    override fun numberingSystems(): Set<String> = table("digits").keys.toSet()
    override fun numberingSystemDigitsOrNull(identifier: String): String? = table("digits")[identifier]
    public companion object
}
