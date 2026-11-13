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

package dev.carcara.kotlinx.locale.list

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleDataSource
import dev.carcara.kotlinx.locale.LocaleLookup

public enum class ListType {
    CONJUNCTION,
    DISJUNCTION,
    UNIT,
    ;

    public companion object
}
public enum class ListStyle {
    LONG,
    SHORT,
    NARROW,
    ;

    public companion object
}
public data class ListFormatOptions(public val type: ListType = ListType.CONJUNCTION, public val style: ListStyle = ListStyle.LONG) {
    public companion object
}

/** Elements retain their source index even when values repeat or are empty. */
public sealed interface ListPart {
    public val value: String
    public data class Element(public val index: Int, override val value: String) : ListPart {
        public companion object
    }
    public data class Literal(override val value: String) : ListPart {
        public companion object
    }
    public companion object
}
public class FormattedList(parts: List<ListPart>) {
    public val parts: List<ListPart> = parts.toList()
    public val text: String get() = parts.joinToString("") { it.value }
    override fun toString(): String = text
    public companion object
}
public interface ListFormatSource : LocaleDataSource {
    public fun render(values: List<String>, locale: Locale, options: ListFormatOptions = ListFormatOptions()): LocaleLookup<FormattedList>
    public companion object
}
