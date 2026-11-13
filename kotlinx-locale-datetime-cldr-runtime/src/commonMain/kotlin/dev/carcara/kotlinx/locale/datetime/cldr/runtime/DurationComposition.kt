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

package dev.carcara.kotlinx.locale.datetime.cldr.runtime

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.list.ListFormatOptions
import dev.carcara.kotlinx.locale.list.ListFormatSource
import dev.carcara.kotlinx.locale.list.ListPart
import dev.carcara.kotlinx.locale.list.ListStyle
import dev.carcara.kotlinx.locale.list.ListType
import dev.carcara.kotlinx.locale.number.FormattedNumber
import dev.carcara.kotlinx.locale.number.NumberPart
import dev.carcara.kotlinx.locale.number.NumberPartKind

/** Joins already-rendered elements with unit-list patterns, preserving their typed parts. */
public fun durationCompose(
    elements: List<FormattedNumber>,
    locale: Locale,
    lists: ListFormatSource,
    style: ListStyle = ListStyle.LONG,
): LocaleLookup<List<NumberPart>> {
    val composed = lists.render(elements.map { it.text }, locale, ListFormatOptions(ListType.UNIT, style))
    return when (composed) {
        is LocaleLookup.Value -> LocaleLookup.Value(
            composed.value.parts.flatMap { part ->
                when (part) {
                    is ListPart.Element -> elements[part.index].parts
                    is ListPart.Literal -> listOf(NumberPart(NumberPartKind.LITERAL, part.value))
                }
            },
        )
        is LocaleLookup.Unsupported -> composed
        LocaleLookup.MissingData -> LocaleLookup.MissingData
    }
}
