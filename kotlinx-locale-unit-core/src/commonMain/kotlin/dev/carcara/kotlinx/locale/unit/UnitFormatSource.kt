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

package dev.carcara.kotlinx.locale.unit

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleDataSource
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.number.FormattedNumber
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberValue

public enum class UnitWidth {
    LONG,
    SHORT,
    NARROW,
    ;

    public companion object
}

/** Localized measurement wording, with numeric rendering and exact plural operands shared. */
public interface UnitFormatSource : LocaleDataSource {
    public fun render(
        value: NumberValue,
        unit: String,
        locale: Locale,
        width: UnitWidth = UnitWidth.LONG,
        options: NumberFormatOptions = NumberFormatOptions.Default,
    ): LocaleLookup<FormattedNumber>
    public fun compose(
        number: FormattedNumber,
        unit: String,
        locale: Locale,
        width: UnitWidth = UnitWidth.LONG,
    ): LocaleLookup<FormattedNumber>
    public companion object
}
