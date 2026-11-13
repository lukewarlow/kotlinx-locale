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

package dev.carcara.kotlinx.locale.number

/** Composes a CLDR quantity pattern from the original number parts, retaining operands. */
public fun numberComposeUnit(number: FormattedNumber, pattern: String, unit: String): FormattedNumber {
    val parts = ArrayList<NumberPart>()
    var index = 0
    while (index < pattern.length) {
        if (pattern.startsWith("{0}", index)) {
            parts += number.parts.map { it.copy(unit = unit) }
            index += 3
        } else {
            val literal = pattern[index].isWhitespace() || pattern[index] in "\u200e\u200f\u061c\u2066\u2067\u2068\u2069"
            val begin = index++
            while (index < pattern.length &&
                !pattern.startsWith("{0}", index) &&
                (pattern[index].isWhitespace() || pattern[index] in "\u200e\u200f\u061c\u2066\u2067\u2068\u2069") == literal
            ) {
                index++
            }
            parts +=
                NumberPart(
                    if (literal) NumberPartKind.LITERAL else NumberPartKind.UNIT,
                    pattern.substring(begin, index),
                    if (literal) null else unit,
                )
        }
    }
    return FormattedNumber(
        parts.joinToString("") {
            it.value
        },
        number.operands.integerDigits,
        number.operands.fractionDigits,
        number.operands.c,
        parts,
        number.notationExponent,
    )
}
