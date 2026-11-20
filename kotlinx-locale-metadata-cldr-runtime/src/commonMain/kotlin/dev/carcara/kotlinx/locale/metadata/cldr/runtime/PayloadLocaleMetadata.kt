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
import dev.carcara.kotlinx.locale.metadata.PreparedLocaleMatcher
import dev.carcara.kotlinx.locale.metadata.RegionOrigin
import dev.carcara.kotlinx.locale.metadata.RegionPreference
import dev.carcara.kotlinx.locale.metadata.TextDirection
import dev.carcara.kotlinx.locale.metadata.TimeZoneIdentifier
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Immutable, standalone CLDR locale identity and preference tables. */
public class PayloadLocaleMetadata(override val dataVersion: String, encoded: String) : LocaleMetadataSource {
    private val tables: Map<String, Map<String, String>> = encoded.split('\u001e').filter { it.isNotEmpty() }
        .map { it.split('\u001f', limit = 3).also { fields -> require(fields.size == 3) } }
        .groupBy({ it[0] }, { it[1] to it[2] }).mapValues { (_, pairs) -> pairs.toMap() }
    private data class LanguageAlias(val pattern: Locale, val target: Locale)

    private val languageAliases: Map<String, List<LanguageAlias>> by lazy {
        table("languageAlias").entries.sortedWith(
            compareByDescending<Map.Entry<String, String>> { it.key.substringBefore('-') != "und" }
                .thenByDescending { it.key.count { c -> c == '-' } }.thenBy { it.key },
        ).mapNotNull { (key, replacement) ->
            val pattern = Locale.forUnicodeLanguageTagOrNull(key) ?: return@mapNotNull null
            val target = Locale.forUnicodeLanguageTagOrNull(replacement.substringBefore(' ')) ?: return@mapNotNull null
            LanguageAlias(pattern, target)
        }.groupBy { it.pattern.language }
    }

    private data class PreparedCandidate(val locale: Locale, val identity: Locale, val maximal: Locale, val order: Int)
    private data class CandidateMatch(val locale: Locale, val distance: Int)
    private data class RegionalRule(val desired: DistanceField, val supported: DistanceField, val distance: Int)
    private data class DistanceGroup(val candidates: List<PreparedCandidate>, val prefixDistance: Int, val rules: List<RegionalRule>)

    @OptIn(ExperimentalAtomicApi::class)
    private class PreparedLocales(val snapshot: Set<Locale>, val candidates: List<PreparedCandidate>) {
        // Keep the first sorted spelling if canonical identities coincide.
        val exact = candidates.asReversed().associateBy { it.identity }
        val groups = candidates.groupBy { it.maximal.language to it.maximal.script }.values.toList()
        val profiles = PreparedMetadataCache<Pair<String, String?>, List<DistanceGroup>>(128)

        // Segment publication so a cold scan does not copy a growing 1024-entry
        // map on every miss. Sixteen bounded buckets retain at most 2048 results.
        val requests = Array(16) { PreparedMetadataCache<Locale, CandidateMatch?>(128) }
    }

    // Immutable snapshots are atomically published. At most eight caller sets are retained;
    // racing callers may prepare twice, but never observe partly initialized candidates.
    @OptIn(ExperimentalAtomicApi::class)
    private val preparedLocales = AtomicReference<List<PreparedLocales>>(emptyList())

    private data class DistanceField(val literal: String?, val members: Set<String>?, val negated: Boolean = false)
    private data class DistanceRule(
        val desired: List<DistanceField>,
        val supported: List<DistanceField>,
        val distance: Int?,
        val oneWay: Boolean,
    )

    private val distanceRules: Map<Int, List<DistanceRule>> by lazy {
        fun regions(group: String, seen: Set<String> = emptySet()): Set<String> {
            if (group in seen) return emptySet()
            return setOf(group) + table("containment")[group].orEmpty().split(' ').filter { it.isNotEmpty() }
                .flatMap { regions(it, seen + group) }
        }
        val variables = table("matchVariable").mapValues { (_, groups) -> groups.split('+').flatMap { regions(it) }.toSet() }
        fun pattern(value: String): List<DistanceField> = value.split('_').map { field ->
            when {
                field == "*" -> DistanceField(null, null)
                field.startsWith("$") -> {
                    val negated = field.startsWith("$!")
                    val variable = if (negated) "$" + field.drop(2) else field
                    DistanceField(null, variables[variable].orEmpty(), negated)
                }
                else -> DistanceField(field, null)
            }
        }
        table("distance").values.map { value ->
            val fields = value.split(' ')
            DistanceRule(pattern(fields[0]), pattern(fields[1]), fields.getOrNull(2)?.toIntOrNull(), fields.getOrNull(3) == "true").also {
                require(
                    (it.distance ?: 0) >= 0,
                )
            }
        }.groupBy { it.desired.size }
    }

    private val preferenceCache = PreparedMetadataCache<String, LocalePreferences>(128)
    private val directionCache = PreparedMetadataCache<String?, String?>()
    private val zoneCatalogue: List<TimeZoneIdentifier> by lazy { prepareTimeZoneIdentifiers() }
    private val zoneIndex: Map<String, TimeZoneIdentifier> by lazy {
        buildMap {
            for (zone in zoneCatalogue) {
                val key = zone.identifier.map { it.uppercaseChar().lowercaseChar() }.joinToString("")
                if (key !in
                    this
                ) {
                    put(key, zone)
                }
            }
        }
    }
    private val currencyCatalogue: List<CurrencyMetadata> by lazy { prepareCurrencies() }

    private fun table(name: String): Map<String, String> = tables[name].orEmpty()
    private val likelyLanguages: Set<String> by lazy { table("likely").keys.map { it.substringBefore('-') }.toSet() }
    private fun tag(locale: Locale): String = locale.stripExtensions().toLanguageTag().lowercase()
    private fun languageId(value: String): Locale = requireNotNull(Locale.forUnicodeLanguageTagOrNull(value)) {
        "Invalid CLDR language identifier: $value"
    }

    override fun canonicalize(locale: Locale): Locale {
        var value = locale
        repeat(32) {
            val previous = value.toLanguageTag()
            val aliases = languageAliases[value.language].orEmpty()
            val fallbacks = if (value.language == "und") emptyList() else languageAliases["und"].orEmpty()
            for ((pattern, target) in aliases + fallbacks) {
                if ((pattern.language != "und" && pattern.language != value.language) ||
                    (pattern.script != null && pattern.script != value.script) ||
                    (pattern.region != null && pattern.region != value.region) ||
                    !value.variants.containsAll(pattern.variants)
                ) {
                    continue
                }
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
                fields[key] = canonicalizeExtensionValue(key, parts.subList(start, index).joinToString("-"))
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

    override fun canonicalizeUnicodeValue(key: String, value: String): String =
        canonicalizeExtensionValue(key, value).let { if (it == "true") "" else it }

    private fun canonicalizeExtensionValue(key: String, value: String): String {
        if (value.isEmpty()) return ""
        val normalizedKey = asciiLowercase(key)
        var result = asciiLowercase(value)
        if (normalizedKey == "sd" || normalizedKey == "rg") {
            repeat(16) {
                val replacement = table("subdivisionAlias")[result]?.substringBefore(' ')?.lowercase()
                if (replacement == null || replacement == result) return result
                result = if (replacement.length == 2 || replacement.length == 3 && replacement.all { c -> c in '0'..'9' }) {
                    replacement + "zzzz"
                } else {
                    replacement
                }
            }
            error("CLDR subdivision aliases contain a cycle for $key/$value")
        }
        repeat(16) {
            val next = table("uAlias")["$normalizedKey/$result"]?.lowercase() ?: return result
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

    override fun maximize(locale: Locale): Locale {
        val canonical = canonicalize(locale)
        // UTS #35 Add Likely Subtags fails for an unknown language. The matcher
        // still uses its internal fallback model for distance comparisons.
        if (canonical.language !in likelyLanguages) return canonical
        return maximizeId(canonical)
    }

    override fun minimize(locale: Locale): Locale {
        val canonical = canonicalize(locale)
        if (canonical.language !in likelyLanguages) return canonical
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

    override fun preferences(locale: Locale): LocalePreferences =
        preferenceCache.getOrPut(locale.toLanguageTag()) { preparePreferences(locale) }

    override fun scriptDirectionOrNull(script: String): TextDirection? = when (table("scriptDirection")[asciiLowercase(script)]) {
        "ltr" -> TextDirection.LTR
        "rtl" -> TextDirection.RTL
        else -> null
    }

    private fun preparePreferences(locale: Locale): LocalePreferences {
        val canonical = canonicalize(locale)
        val region = regionPreference(canonical).region
        val hours = (
            table("hours")["${canonical.language}-$region".lowercase()] ?: table("hours")[region.lowercase()]
                ?: table("hours")["001"] ?: "h23 h12"
            ).split(' ').distinct()
        val numbered = lookup("number", canonical) ?: "latn"
        val script = maximizeId(canonical).script
        val direction = directionCache.getOrPut(script) {
            table("direction").entries.firstOrNull { (id, _) ->
                val language = Locale.forUnicodeLanguageTagOrNull(id) ?: return@firstOrNull false
                maximizeId(language).script == script
            }?.value
        } ?: lookup("direction", maximizeId(canonical)) ?: lookup("direction", canonical)
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
    override fun timeZoneIdentifiers(): List<TimeZoneIdentifier> = zoneCatalogue.toList()

    private fun prepareTimeZoneIdentifiers(): List<TimeZoneIdentifier> = table("zoneAlias").map { (identifier, value) ->
        TimeZoneIdentifier(identifier, value.substringBefore(' '), value.substringAfter(' ', "").ifEmpty { null })
    }.sortedBy { it.identifier }

    override fun timeZoneIdentifierOrNull(identifier: String): TimeZoneIdentifier? {
        if (identifier.any { it.code >= 128 }) return null
        return zoneIndex[identifier.lowercase()]
    }

    override fun currencies(): List<CurrencyMetadata> = currencyCatalogue.toList()

    private fun prepareCurrencies(): List<CurrencyMetadata> = table("currency").map { (code, row) ->
        val values = row.split(' ').map(String::toInt)
        CurrencyMetadata(code, values[0].takeIf { it >= 0 }, values[1], values[2], values[3], values[4], values[5] != 0)
    }.sortedBy { it.code }

    @OptIn(ExperimentalAtomicApi::class)
    private fun prepare(available: Set<Locale>): PreparedLocales {
        // Set equality ignores caller iteration order. Copy even on a cache hit because
        // callers can mutate their sets between calls; identity alone is not a key.
        val snapshot = available.toSet()
        preparedLocales.load().firstOrNull { it.snapshot == snapshot }?.let { return it }
        val candidates = snapshot.map { canonicalize(it) }.sortedBy { it.toLanguageTag() }.mapIndexed { index, locale ->
            PreparedCandidate(locale, locale.stripExtensions(), maximizeId(locale), index)
        }
        val prepared = PreparedLocales(snapshot, candidates)
        while (true) {
            val previous = preparedLocales.load()
            previous.firstOrNull { it.snapshot == snapshot }?.let { return it }
            if (preparedLocales.compareAndSet(previous, listOf(prepared) + previous.take(7))) return prepared
        }
    }

    private fun matches(pattern: List<DistanceField>, locale: Locale): Boolean = pattern.indices.all { index ->
        val actual = when (index) {
            0 -> locale.language
            1 -> locale.script.orEmpty()
            else -> locale.region.orEmpty()
        }
        val field = pattern[index]
        when {
            field.literal != null -> field.literal.equals(actual, ignoreCase = true)
            field.members != null -> (actual in field.members) != field.negated
            else -> true
        }
    }

    private fun matchesField(field: DistanceField, actual: String): Boolean = when {
        field.literal != null -> field.literal.equals(actual, ignoreCase = true)
        field.members != null -> (actual in field.members) != field.negated
        else -> true
    }

    private fun prefixMatches(pattern: List<DistanceField>, locale: Locale): Boolean =
        matchesField(pattern[0], locale.language) && matchesField(pattern[1], locale.script.orEmpty())

    private fun profiles(desired: Locale, prepared: PreparedLocales): List<DistanceGroup> =
        prepared.profiles.getOrPut(desired.language to desired.script) {
            prepared.groups.mapNotNull { candidates ->
                val supported = candidates[0].maximal
                var prefixDistance = 0
                for (level in 1..2) {
                    if (if (level == 1) desired.language == supported.language else desired.script == supported.script) continue
                    val rule = distanceRules[level].orEmpty().firstOrNull {
                        (matches(it.desired, desired) && matches(it.supported, supported)) ||
                            (!it.oneWay && matches(it.supported, desired) && matches(it.desired, supported))
                    }
                    prefixDistance += rule?.distance ?: if (level == 1) 80 else 50
                }
                // CLDR distances are nonnegative, so a prefix already at the
                // threshold cannot be rescued by a region decision.
                if (prefixDistance >= 50) return@mapNotNull null
                val rules = buildList {
                    for (rule in distanceRules[3].orEmpty()) {
                        if (prefixMatches(rule.desired, desired) && prefixMatches(rule.supported, supported)) {
                            add(RegionalRule(rule.desired[2], rule.supported[2], rule.distance ?: 4))
                        }
                        if (!rule.oneWay && prefixMatches(rule.supported, desired) && prefixMatches(rule.desired, supported)) {
                            add(RegionalRule(rule.supported[2], rule.desired[2], rule.distance ?: 4))
                        }
                    }
                }
                DistanceGroup(candidates, prefixDistance, rules)
            }
        }

    private fun bestCandidate(canonical: Locale, prepared: PreparedLocales): CandidateMatch? {
        val identity = canonical.stripExtensions()
        val hash = identity.hashCode()
        val bucket = prepared.requests[(hash xor (hash ushr 16)) and 15]
        return bucket.getOrPut(identity) {
            // Identity preference is local to this request. The caller still
            // applies priority weighting against all other requests.
            prepared.exact[identity]?.let { return@getOrPut CandidateMatch(it.locale, 0) }
            val desired = maximizeId(canonical)
            val region = desired.region.orEmpty()
            var best: PreparedCandidate? = null
            var bestDistance = 50
            for (group in profiles(desired, prepared)) {
                if (group.prefixDistance > bestDistance) continue
                val rules = group.rules.filter { matchesField(it.desired, region) }
                for (candidate in group.candidates) {
                    val candidateRegion = candidate.maximal.region.orEmpty()
                    val regional = if (region == candidateRegion) {
                        0
                    } else {
                        rules.firstOrNull { matchesField(it.supported, candidateRegion) }?.distance ?: 4
                    }
                    val value = group.prefixDistance + regional
                    if (value < 50 &&
                        (value < bestDistance || (value == bestDistance && candidate.order < (best?.order ?: Int.MAX_VALUE)))
                    ) {
                        best = candidate
                        bestDistance = value
                    }
                }
            }
            best?.let { CandidateMatch(it.locale, bestDistance) }
        }
    }

    override fun prepareMatcher(available: Set<Locale>): PreparedLocaleMatcher {
        val prepared = prepare(available)
        return object : PreparedLocaleMatcher {
            override fun bestFitOrNull(requested: List<Locale>): LocaleMatch? = match(requested, prepared)
        }
    }

    /** CLDR distances, exact identity preference, and deterministic non-exact ties. */
    override fun bestFitOrNull(requested: List<Locale>, available: Set<Locale>): LocaleMatch? {
        if (requested.isEmpty() || available.isEmpty()) return null
        return match(requested, prepare(available))
    }

    private fun match(requested: List<Locale>, prepared: PreparedLocales): LocaleMatch? {
        if (requested.isEmpty() || prepared.candidates.isEmpty()) return null
        var best: LocaleMatch? = null
        var score = Long.MAX_VALUE
        for ((index, request) in requested.withIndex()) {
            val candidate = bestCandidate(canonicalize(request), prepared) ?: continue
            val weighted = candidate.distance.toLong() + index.toLong() * 5
            if (weighted < score) {
                score = weighted
                best = LocaleMatch(request, candidate.locale, candidate.distance)
            }
        }
        return best
    }

    override fun numberingSystems(): Set<String> = table("digits").keys.toSet()
    override fun numberingSystemDigitsOrNull(identifier: String): String? = table("digits")[identifier]
    public companion object
}

private fun asciiLowercase(value: String): String {
    if (value.none { it in 'A'..'Z' }) return value
    return buildString(value.length) {
        for (c in value) append(if (c in 'A'..'Z') c + 32 else c)
    }
}

/** Bounded immutable publication, including negative lookups; never mutate a shared map. */
@OptIn(ExperimentalAtomicApi::class)
private class PreparedMetadataCache<K, V>(private val limit: Int = 64) {
    private val entries = AtomicReference<Map<K, V>>(emptyMap())
    fun getOrPut(key: K, create: () -> V): V {
        val initial = entries.load()
        if (initial.containsKey(key)) return initial.getValue(key)
        val value = create()
        while (true) {
            val previous = entries.load()
            if (previous.containsKey(key)) return previous.getValue(key)
            val retained = if (previous.size < limit) previous else previous.entries.drop(1).associate { it.key to it.value }
            if (entries.compareAndSet(previous, retained + (key to value))) return value
        }
    }
}
