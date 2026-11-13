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

/** Semantic pieces emitted by the number rendering pass. */
public enum class NumberPartKind {
    INTEGER,
    GROUP,
    DECIMAL,
    FRACTION,
    MINUS_SIGN,
    PLUS_SIGN,
    PERCENT_SIGN,
    PER_MILLE,
    CURRENCY,
    UNIT,
    EXPONENT_SEPARATOR,
    EXPONENT_INTEGER,
    EXPONENT_MINUS_SIGN,
    COMPACT,
    NAN,
    INFINITY,
    APPROXIMATELY_SIGN,
    LITERAL,
    ;

    public companion object
}

public data class NumberPart(public val kind: NumberPartKind, public val value: String, public val unit: String? = null) {
    public companion object
}

/** Exact UTS #35 operands built from the digits that will actually be displayed. */
public class PluralOperands(integerDigits: String, fractionDigits: String = "", compactExponent: Int = 0) {
    public val integerDigits: String = integerDigits.trimStart('0').ifEmpty { "0" }
    public val fractionDigits: String = fractionDigits
    public val i: Decimal = Decimal.ofDigits(this.integerDigits, 0)
    public val v: Int = fractionDigits.length
    public val w: Int = fractionDigits.trimEnd('0').length
    public val f: Decimal = Decimal.ofDigits(fractionDigits.ifEmpty { "0" }, 0)
    public val t: Decimal = Decimal.ofDigits(fractionDigits.trimEnd('0').ifEmpty { "0" }, 0)
    public val c: Int = compactExponent
    public val e: Int get() = c
    public val n: Decimal = Decimal.ofDigits(this.integerDigits + fractionDigits, fractionDigits.length)
    init {
        require(integerDigits.isNotEmpty() && integerDigits.all { it in '0'..'9' })
        require(fractionDigits.all { it in '0'..'9' })
        require(compactExponent >= 0)
    }
    public companion object
}

/** Localized text, semantic parts and exact operands from one formatting pass. */
public class FormattedNumber(
    public val text: String,
    integerDigits: String,
    fractionDigits: String,
    compactExponent: Int = 0,
    parts: List<NumberPart> = listOf(NumberPart(NumberPartKind.LITERAL, text)),
    public val notationExponent: Int = 0,
) : CharSequence {
    public val operands: PluralOperands = PluralOperands(integerDigits, fractionDigits, compactExponent)

    /** Exact rounded quantity after restoring the notation's power of ten, for unit/currency inflection. */
    public val quantityOperands: PluralOperands get() {
        val quantity = operands.n.shifted(if (c != 0) c else notationExponent)
        return PluralOperands(quantity.integerDigits, quantity.fractionDigits, c)
    }
    public val parts: List<NumberPart> = parts.toList()
    public val isFinite: Boolean get() = parts.none { it.kind == NumberPartKind.NAN || it.kind == NumberPartKind.INFINITY }
    public val i: Decimal get() = operands.i
    public val v: Int get() = operands.v
    public val w: Int get() = operands.w
    public val f: Decimal get() = operands.f
    public val t: Decimal get() = operands.t
    public val n: Decimal get() = operands.n
    public val c: Int get() = operands.c
    public val e: Int get() = operands.e
    init {
        require(parts.joinToString("") { it.value } == text) { "Number parts must concatenate to text" }
    }
    override val length: Int get() = text.length
    override fun get(index: Int): Char = text[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
    override fun toString(): String = text
    public companion object
}
