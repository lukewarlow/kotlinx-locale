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
    fun appendAffix(value: String) {
        fun literal(char: Char): Boolean = char.isWhitespace() || char in "\u200e\u200f\u061c\u2066\u2067\u2068\u2069"
        val first = value.indexOfFirst { !literal(it) }
        if (first < 0) {
            if (value.isNotEmpty()) parts += NumberPart(NumberPartKind.LITERAL, value)
            return
        }
        val end = value.indexOfLast { !literal(it) } + 1
        if (first > 0) parts += NumberPart(NumberPartKind.LITERAL, value.substring(0, first))
        parts += NumberPart(NumberPartKind.UNIT, value.substring(first, end), unit)
        if (end < value.length) parts += NumberPart(NumberPartKind.LITERAL, value.substring(end))
    }
    var start = 0
    while (true) {
        val marker = pattern.indexOf("{0}", start)
        if (marker < 0) {
            appendAffix(pattern.substring(start))
            break
        }
        appendAffix(pattern.substring(start, marker))
        parts += number.parts.map { it.copy(unit = if (it.kind == NumberPartKind.LITERAL) null else unit) }
        start = marker + 3
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
