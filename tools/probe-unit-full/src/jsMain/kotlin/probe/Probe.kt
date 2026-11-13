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

@file:OptIn(ExperimentalJsExport::class)

package probe

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.unit.cldr.unitFormat

/** Measurement-unit patterns, exact numbers and plural selection. */
@JsExport
public fun probe(tag: String, amount: String, unit: String): String =
    when (val result = unitFormat(Decimal.parse(amount), unit, Locale.forLanguageTag(tag))) {
        is LocaleLookup.Value -> result.value.text
        else -> result.toString()
    }
