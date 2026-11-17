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

package dev.carcara.kotlinx.locale.list.cldr.runtime

import dev.carcara.kotlinx.locale.InternalKotlinxLocaleApi
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.internal.sparseRecordValue
import dev.carcara.kotlinx.locale.internal.supportedLocalesOf
import dev.carcara.kotlinx.locale.list.FormattedList
import dev.carcara.kotlinx.locale.list.ListFormatOptions
import dev.carcara.kotlinx.locale.list.ListFormatSource
import dev.carcara.kotlinx.locale.list.ListPart
import dev.carcara.kotlinx.locale.list.ListStyle
import dev.carcara.kotlinx.locale.list.ListType
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** List patterns with inheritance and stable element identities. */
@OptIn(InternalKotlinxLocaleApi::class)
public class PayloadListFormats(records: Map<String, String>) : ListFormatSource {
    private val records = records.toMap()
    private val patterns = PreparedListCache<Triple<String, String, String>, String?>(256)
    private val templates = PreparedListCache<String, List<TemplatePiece>>(128)
    override val supportedLocales: Set<Locale> by lazy { supportedLocalesOf(this.records) }
    private fun pattern(type: String, item: String, locale: Locale): String? =
        patterns.getOrPut(Triple(locale.stripExtensions().toLanguageTag(), type, item)) { resolvePattern(type, item, locale) }

    private fun resolvePattern(type: String, item: String, locale: Locale): String? {
        var current = type
        val seen = HashSet<String>()
        while (seen.add(current)) {
            sparseRecordValue(records, locale, 1, 2, "$current/$item")?.let { return it }
            val alias = sparseRecordValue(records, locale, 1, 2, "$current/alias")
            if (alias != null) {
                current = alias
                continue
            }
            current = when {
                current.endsWith("-narrow") -> current.removeSuffix("-narrow") + "-short"
                current.endsWith("-short") -> current.removeSuffix("-short")
                else -> return null
            }
        }
        return null
    }
    override fun render(values: List<String>, locale: Locale, options: ListFormatOptions): LocaleLookup<FormattedList> {
        val elements = values.mapIndexed { index, value -> PartNode(ListPart.Element(index, value)) }
        if (elements.isEmpty()) return LocaleLookup.Value(FormattedList(emptyList()))
        if (elements.size == 1) return LocaleLookup.Value(FormattedList(listOf(elements.single().part)))
        val base = when (options.type) {
            ListType.CONJUNCTION -> "standard"
            ListType.DISJUNCTION -> "or"
            ListType.UNIT -> "unit"
        }
        val type = base + when (options.style) {
            ListStyle.LONG -> ""
            ListStyle.SHORT -> "-short"
            ListStyle.NARROW -> "-narrow"
        }
        fun combine(item: String, left: RenderNode, right: PartNode): RenderNode? {
            var template = pattern(type, item, locale) ?: return null
            val next = if (locale.language == "es" || locale.language == "he") right.part.value.lowercase() else ""
            if (locale.language == "es") {
                if (next.startsWith("i") || (next.startsWith("hi") && !next.startsWith("hie") && !next.startsWith("hia"))) {
                    template = template.replace(" y {1}", " e {1}")
                }
                if (next.startsWith("o") ||
                    next.startsWith("ho") ||
                    next.startsWith("8") ||
                    (next.startsWith("11") && (next.length == 2 || next[2] !in '0'..'9'))
                ) {
                    template = template.replace(" o {1}", " u {1}")
                }
            }
            if (locale.language == "he" && next.firstOrNull()?.let { it !in '\u0590'..'\u05ff' } == true) {
                template = template.replace("ו{1}", "ו-{1}")
            }
            val pieces = templates.getOrPut(template) { compileTemplate(template) }
            return GroupNode(
                pieces.map { piece ->
                    when (piece.slot) {
                        0 -> left
                        1 -> right
                        else -> PartNode(ListPart.Literal(piece.literal))
                    }
                },
            )
        }
        if (elements.size ==
            2
        ) {
            return combine("2", elements[0], elements[1])?.let { LocaleLookup.Value(FormattedList(flatten(it))) }
                ?: LocaleLookup.MissingData
        }
        var result: RenderNode = combine("start", elements[0], elements[1]) ?: return LocaleLookup.MissingData
        for (index in 2 until elements.lastIndex) result = combine("middle", result, elements[index]) ?: return LocaleLookup.MissingData
        result = combine("end", result, elements.last()) ?: return LocaleLookup.MissingData
        return LocaleLookup.Value(FormattedList(flatten(result)))
    }
    public companion object
}

private sealed interface RenderNode
private class PartNode(val part: ListPart) : RenderNode
private class GroupNode(val children: List<RenderNode>) : RenderNode
private class TemplatePiece(val slot: Int, val literal: String = "")

private fun compileTemplate(template: String): List<TemplatePiece> = buildList {
    var index = 0
    while (index < template.length) {
        val marker = template.indexOf('{', index)
        if (marker < 0) {
            add(TemplatePiece(-1, template.substring(index)))
            break
        }
        if (marker > index) add(TemplatePiece(-1, template.substring(index, marker)))
        require(marker + 2 < template.length && template[marker + 2] == '}') { "Invalid CLDR list pattern" }
        val slot = when (template[marker + 1]) {
            '0' -> 0
            '1' -> 1
            else -> error("Invalid list argument")
        }
        add(TemplatePiece(slot))
        index = marker + 3
    }
}

private fun flatten(root: RenderNode): List<ListPart> {
    val result = ArrayList<ListPart>()
    val pending = ArrayDeque<RenderNode>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        when (val node = pending.removeLast()) {
            is PartNode -> result.add(node.part)
            is GroupNode -> for (index in node.children.indices.reversed()) pending.addLast(node.children[index])
        }
    }
    return result
}

/** Bounded immutable publication, including negative lookups; never mutate a shared map. */
@OptIn(ExperimentalAtomicApi::class)
private class PreparedListCache<K, V>(private val limit: Int = 64) {
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
