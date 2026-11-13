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

/**
 * CLDR's simple language aliases, as `alias` to `canonical`.
 *
 * Only the single-subtag ones, which is all the collation directory needs: the
 * files are named for a language and the aliases that matter here rename one.
 */
internal fun languageAliases(cldrDir: File): Map<String, String> {
    val aliases = LinkedHashMap<String, String>()

    val metadata = cldrDir.resolve("common/supplemental/supplementalMetadata.xml")
    if (metadata.isFile) {
        val pattern = Regex("""<languageAlias\s+type="([a-z]{2,3})"\s+replacement="([a-z]{2,3})"""")
        for (match in pattern.findAll(metadata.readText())) {
            aliases[match.groupValues[1]] = match.groupValues[2]
        }
    }

    // `parentLocales` too, which is the other way CLDR says one locale reads
    // another's file. Nynorsk is the case that needs it: `nn` is not an alias of
    // `no`, it is a language whose declared parent is `no`, and `nn.xml` carries
    // no rules of its own.
    val supplemental = cldrDir.resolve("common/supplemental/supplementalData.xml")
    if (supplemental.isFile) {
        val pattern = Regex("""<parentLocale\s+parent="([a-zA-Z_]+)"\s+locales="([^"]+)"""")
        for (match in pattern.findAll(supplemental.readText())) {
            val parent = canonicalTag(match.groupValues[1])
            for (child in match.groupValues[2].split(' ').filter(String::isNotEmpty)) {
                aliases.putIfAbsent(canonicalTag(child), parent)
            }
        }
    }

    return aliases
}

/** The three collation payloads: the shared pair, and one delta per locale. */
class CollationPayloads(val root: String, val normalization: String, val tailorings: Map<String, String>)

/**
 * Reads the root table once and folds every CLDR tailoring over it.
 *
 * The tailorings are keyed by canonical tag rather than by CLDR file id, and
 * only the locales CLDR actually tailors get an entry. The other thousand reach
 * one of them, or root, through the ordinary parent walk, which is what
 * `resolvedRecord` already does for every other table.
 *
 * A tailoring that cannot be built stops the generation. A locale that silently
 * fell back to root order would sort visibly wrongly with nothing saying so.
 */
fun buildCollationPayloads(cldrDir: File): CollationPayloads {
    val root = parseFractionalUca(cldrDir.resolve("common/uca/FractionalUCA.txt"))
    val ranks = WeightRanks.of(root)
    val normalization = parseNormalizationData()

    val collationDir = cldrDir.resolve("common/collation")
    val deltas = LinkedHashMap<String, String>()
    val files = collationDir.listFiles()?.filter { it.extension == "xml" }?.sortedBy(File::getName).orEmpty()
    val resolveImport: (String) -> String? = { id -> importedRules(collationDir, id) }
    val typeRegistry = parseXml(cldrDir.resolve("common/bcp47/collation.xml")).documentElement
    val aliases = typeRegistry.getElementsByTagName("type").let { nodes ->
        buildMap<String, String> {
            for (i in 0 until nodes.length) {
                val entry = nodes.item(i) as org.w3c.dom.Element
                for (alias in entry.getAttribute("alias").split(' ').filter(String::isNotEmpty)) put(alias, entry.getAttribute("name"))
            }
        }
    }
    val variants = linkedMapOf<String, Map<String, String>>()
    val metadata = linkedMapOf<String, Map<String, String>>()
    for (file in files) {
        val tag = canonicalTag(file.nameWithoutExtension)
        val document = parseXml(file).documentElement.child("collations") ?: continue
        val entries = linkedMapOf<String, String>()
        val settings = linkedMapOf<String, String>()
        settings["default"] = document.child("defaultCollation")?.textContent?.trim()?.let { aliases[it] ?: it } ?: ""
        for (collation in document.childElements("collation")) {
            if (collation.hasAttribute("alt")) continue
            val original = collation.getAttribute("type")
            if (original.startsWith("private-")) continue
            val type = aliases[original] ?: original
            val rules = collation.child("cr")?.textContent.orEmpty()
            if (rules.isEmpty() && type != "standard") continue
            entries[type] = if (rules.isEmpty()) {
                ""
            } else {
                try {
                    tailoringFor(root, ranks, normalization, rules, resolveImport).encodeDelta()
                } catch (e: Exception) {
                    throw IllegalStateException("collation ${file.name}/$type", e)
                }
            }
            for ((name, pattern) in listOf("caseFirst" to "caseFirst", "ignorePunctuation" to "alternate", "sensitivity" to "strength")) {
                Regex("\\[$pattern ([^]]+)\\]").find(rules)?.groupValues?.get(1)?.let { settings["$name/$type"] = it }
            }
        }
        variants[tag] = entries
        metadata[tag] = settings
    }
    fun encode(map: Map<String, String>): String = map.entries.sortedBy { it.key }
        .joinToString(LIST_SEPARATOR) { (key, value) -> key + KEY_SEPARATOR + value }
    for (tag in variants.keys + "root") {
        val parent = if (tag == "root") "" else tag.substringBeforeLast('-', "root")
        deltas[tag] = listOf(parent, encode(variants[tag].orEmpty()), encode(metadata[tag].orEmpty().filterValues(String::isNotEmpty)))
            .joinToString(FIELD_SEPARATOR)
    }
    val payloads = LinkedHashMap(deltas)
    for ((from, to) in languageAliases(cldrDir)) {
        if (from !in payloads && to in deltas) payloads[from] = to + FIELD_SEPARATOR + FIELD_SEPARATOR
    }
    println("[codegen] built ${payloads.size} collation locale records with variant/search data")

    return CollationPayloads(
        root = encodeCollationRoot(root, ranks),
        normalization = encodeNormalizationData(normalization),
        tailorings = payloads,
    )
}
