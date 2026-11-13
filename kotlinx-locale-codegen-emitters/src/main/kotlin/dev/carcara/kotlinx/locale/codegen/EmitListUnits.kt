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

public fun emitListBinding(root: File, spec: BindingSpec) {
    root.packageFile(spec.packageName, "ListFormat.kt").writeText(
        preamble(
            spec,
            listOf(
                "dev.carcara.kotlinx.locale.Locale",
                "dev.carcara.kotlinx.locale.LocaleLookup",
                "dev.carcara.kotlinx.locale.list.ListFormatSource",
                "dev.carcara.kotlinx.locale.list.ListFormatOptions",
                "dev.carcara.kotlinx.locale.list.FormattedList",
                "dev.carcara.kotlinx.locale.list.cldr.runtime.PayloadListFormats",
                "${spec.registryPackage}.listPatternsRegistry",
            ),
        ) + """
        |public object ${spec.objectName} : ListFormatSource by PayloadListFormats(listPatternsRegistry)
        |
        |/** Format a list while retaining element indices and all joining literals. */
        |public fun listFormat(value: List<String>, locale: Locale = Locale.current, options: ListFormatOptions = ListFormatOptions()): LocaleLookup<FormattedList> =
        |    ${spec.objectName}.render(value, locale, options)
        |
        """.trimMargin(),
    )
}

public fun emitUnitBinding(root: File, spec: BindingSpec, numberObject: String) {
    root.packageFile(spec.packageName, "UnitFormat.kt").writeText(
        preamble(
            spec,
            listOf(
                "dev.carcara.kotlinx.locale.Locale", "dev.carcara.kotlinx.locale.LocaleLookup",
                "dev.carcara.kotlinx.locale.unit.UnitFormatSource", "dev.carcara.kotlinx.locale.unit.UnitWidth",
                "dev.carcara.kotlinx.locale.number.FormattedNumber", "dev.carcara.kotlinx.locale.number.NumberValue",
                "dev.carcara.kotlinx.locale.number.NumberFormatOptions", "dev.carcara.kotlinx.locale.number.Decimal",
                "dev.carcara.kotlinx.locale.unit.cldr.runtime.PayloadUnitFormats",
                "${spec.registryPackage}.unitPatternsRegistry",
            ),
        ) + """
        |public object ${spec.objectName} : UnitFormatSource by PayloadUnitFormats(unitPatternsRegistry, $numberObject, ${numberObject}Plurals)
        |
        |/** Render a measurement with exact numeric operands and typed unit parts. */
        |public fun unitFormat(value: NumberValue, unit: String, locale: Locale = Locale.current, width: UnitWidth = UnitWidth.LONG, options: NumberFormatOptions = NumberFormatOptions.Default): LocaleLookup<FormattedNumber> =
        |    ${spec.objectName}.render(value, unit, locale, width, options)
        |
        |public fun unitFormat(value: Decimal, unit: String, locale: Locale = Locale.current, width: UnitWidth = UnitWidth.LONG, options: NumberFormatOptions = NumberFormatOptions.Default): LocaleLookup<FormattedNumber> =
        |    unitFormat(NumberValue.Finite(value), unit, locale, width, options)
        |
        """.trimMargin(),
    )
}
