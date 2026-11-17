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

package dev.carcara.kotlinx.locale.collation.cldr.runtime

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleCoverage
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.collation.CollationCapabilities
import dev.carcara.kotlinx.locale.collation.CollationCaseFirst
import dev.carcara.kotlinx.locale.collation.CollationOptions
import dev.carcara.kotlinx.locale.collation.CollationSensitivity
import dev.carcara.kotlinx.locale.collation.CollationSource
import dev.carcara.kotlinx.locale.collation.CollationStrength
import dev.carcara.kotlinx.locale.collation.CollationUsage
import dev.carcara.kotlinx.locale.collation.ResolvedCollation
import dev.carcara.kotlinx.locale.internal.ENTRY_SEPARATOR
import dev.carcara.kotlinx.locale.internal.FIELD_SEPARATOR
import dev.carcara.kotlinx.locale.internal.KEY_SEPARATOR
import dev.carcara.kotlinx.locale.internal.Normalization
import dev.carcara.kotlinx.locale.internal.resolvedRecord
import dev.carcara.kotlinx.locale.internal.sparseRecordValue
import dev.carcara.kotlinx.locale.internal.supportedLocalesOf

public class PayloadCollationSource(
    private val roots: Map<String, String>,
    private val normalization: Map<String, String>,
    private val tailorings: Map<String, String>,
    private val availableCollations: Set<String> = emptySet(),
    /** Service identities independent of tailoring storage; null enumerates only tailoring records. */
    localeCatalogue: Set<Locale>?,
) : CollationSource {

    /** Original construction path when the caller cannot supply a separate catalogue. */
    public constructor(
        roots: Map<String, String>,
        normalization: Map<String, String>,
        tailorings: Map<String, String>,
        availableCollations: Set<String> = emptySet(),
    ) : this(roots, normalization, tailorings, availableCollations, null)
    private val catalogue = localeCatalogue?.toSet()
    override val tailoredLocales: Set<Locale> by lazy { supportedLocalesOf(tailorings) }
    override val supportedLocales: Set<Locale> by lazy { catalogue ?: tailoredLocales }
    override val localeCoverage: LocaleCoverage by lazy {
        if ("root" in roots && "root" in normalization && "root" in tailorings) {
            LocaleCoverage.RootFallback(supportedLocales)
        } else {
            LocaleCoverage.Enumerated(supportedLocales)
        }
    }
    override fun collations(): Set<String> = availableCollations.toSet()

    private val cache = HashMap<String, PayloadCollation.Tailored>()
    private fun table(delta: String, locale: Locale): PayloadCollation.Tailored? {
        val root = resolvedRecord(roots, locale) ?: return null
        Normalization.install(resolvedRecord(normalization, locale) ?: return null)
        PayloadCollation.install(root)
        return cache.getOrPut(delta) { PayloadCollation.tailored(delta) }
    }
    private fun record(locale: Locale): List<String>? = resolvedRecord(tailorings, locale)?.split(FIELD_SEPARATOR)
    private fun entries(value: String?): Map<String, String> = value.orEmpty().split(ENTRY_SEPARATOR).mapNotNull {
        val separator = it.indexOf(KEY_SEPARATOR)
        if (separator < 0) null else it.substring(0, separator) to it.substring(separator + 1)
    }.toMap()
    private fun value(locale: Locale, field: Int, key: String): String? = sparseRecordValue(tailorings, locale, field, 3, key)

    private fun variants(locale: Locale): Set<String> = (availableCollations + setOf("standard", "search"))
        .filterTo(linkedSetOf()) { value(locale, 1, it) != null }

    override fun comparatorOrNull(locale: Locale, strength: CollationStrength): Comparator<String>? {
        val type = value(locale, 2, "default") ?: "standard"
        val delta = value(locale, 1, type) ?: return null
        return table(delta, locale)?.at(strength)
    }

    override fun capabilities(locale: Locale): CollationCapabilities {
        val variants = variants(locale)
        return CollationCapabilities(
            localeCoverage,
            variants - setOf("standard", "search"),
            if ("search" in variants) CollationUsage.entries.toSet() else setOf(CollationUsage.SORT),
            true,
            true,
        )
    }

    override fun resolve(locale: Locale, options: CollationOptions): LocaleLookup<ResolvedCollation> {
        if (record(locale) == null) return LocaleLookup.MissingData
        val variants = variants(locale)
        val metadata = listOf("default").associateWith { value(locale, 2, it).orEmpty() }.toMutableMap()
        val requested = options.collation ?: locale.unicodeKeywordOrNull("co")
        val default = metadata["default"]?.ifEmpty { null } ?: "standard"
        val type = if (options.usage == CollationUsage.SEARCH) "search" else requested?.takeIf { it in variants } ?: default
        val delta = value(locale, 1, type) ?: return LocaleLookup.Unsupported("Collation $type")
        for (key in listOf("sensitivity", "ignorePunctuation", "caseFirst")) {
            value(locale, 2, "$key/$type")?.let {
                metadata["$key/$type"] =
                    it
            }
        }
        val sensitivity =
            options.sensitivity
                ?: when (metadata["sensitivity/$type"]) {
                    "1" -> CollationSensitivity.BASE
                    "2" -> CollationSensitivity.ACCENT
                    else -> CollationSensitivity.VARIANT
                }
        val punctuation = options.ignorePunctuation ?: (metadata["ignorePunctuation/$type"] == "shifted")
        val numeric = options.numeric ?: (locale.unicodeKeywordOrNull("kn") == "true")
        val case =
            options.caseFirst
                ?: when (locale.unicodeKeywordOrNull("kf") ?: metadata["caseFirst/$type"]) {
                    "upper" -> CollationCaseFirst.UPPER
                    "lower" -> CollationCaseFirst.LOWER
                    else -> CollationCaseFirst.OFF
                }
        val resolved = options.copy(sensitivity = sensitivity, ignorePunctuation = punctuation, numeric = numeric, caseFirst = case)
        val comparator = table(delta, locale)?.configured(resolved) ?: return LocaleLookup.MissingData
        return LocaleLookup.Value(
            ResolvedCollation(
                comparator,
                locale,
                options.usage,
                sensitivity,
                if (type == "standard" ||
                    options.usage == CollationUsage.SEARCH
                ) {
                    "default"
                } else {
                    type
                },
                punctuation,
                numeric,
                case,
            ),
        )
    }
    public companion object
}
