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

/** An exact finite decimal coefficient times ten to minus [scale], retaining sign at zero. */
public class Decimal private constructor(public val coefficientDigits: String, public val scale: Int, public val isNegative: Boolean) :
    Comparable<Decimal> {
    public val isZero: Boolean get() = coefficientDigits == "0"
    public val isInteger: Boolean get() = scale <= 0 || coefficientDigits.takeLast(scale).all { it == '0' }
    public val absoluteValue: Decimal get() = if (isNegative) Decimal(coefficientDigits, scale, false) else this
    public val integerDigits: String get() = when {
        scale <= 0 -> if (isZero) "0" else coefficientDigits + "0".repeat(-scale)
        scale >= coefficientDigits.length -> "0"
        else -> coefficientDigits.dropLast(scale)
    }
    public val fractionDigits: String get() = if (scale <= 0) "" else coefficientDigits.takeLast(scale).padStart(scale, '0')
    public val magnitude: Int get() = if (isZero) 0 else checkedInt(coefficientDigits.length.toLong() - scale - 1)

    /** The signed coefficient when it fits Long; null otherwise. */
    public fun unscaledOrNull(): Long? = ((if (isNegative) "-" else "") + coefficientDigits).toLongOrNull()

    /** Integer part truncated toward zero, or null outside Long's range. */
    public fun integerPartOrNull(): Long? = ((if (isNegative) "-" else "") + integerDigits).toLongOrNull()

    public fun absoluteDigits(): String = coefficientDigits
    public operator fun unaryMinus(): Decimal = Decimal(coefficientDigits, scale, !isNegative)

    /** Move the decimal point by [power] places without rounding. */
    public fun shifted(power: Int): Decimal = Decimal(coefficientDigits, checkedInt(scale.toLong() - power), isNegative)

    /** Remove optional fraction zeros, retaining at least [minimumScale] fraction digits. */
    public fun trimmed(minimumScale: Int = 0): Decimal {
        if (scale <= minimumScale) return this
        if (isZero) return Decimal("0", minimumScale, isNegative)
        val drop = minOf(coefficientDigits.length - coefficientDigits.trimEnd('0').length, scale - minimumScale)
        return if (drop == 0) this else Decimal(coefficientDigits.dropLast(drop), scale - drop, isNegative)
    }

    /** Round once to an increment of 10 to minus [scale], preserving the sign of zero. */
    public fun rescaled(scale: Int, roundingMode: NumberRoundingMode = NumberRoundingMode.HALF_EVEN, increment: Int = 1): Decimal {
        require(increment > 0) { "increment must be positive" }
        val difference = scale.toLong() - this.scale
        val whole: String
        val discarded: String
        val drop: Long
        if (difference >= 0) {
            whole = coefficientDigits + "0".repeat(checkedInt(difference))
            discarded = ""
            drop = 0
        } else {
            drop = -difference
            whole = if (drop >= coefficientDigits.length) "0" else coefficientDigits.dropLast(drop.toInt())
            discarded = if (drop >= coefficientDigits.length) coefficientDigits else coefficientDigits.takeLast(drop.toInt())
        }
        val (quotient, remainder) = divideDigits(whole, increment)
        val hasFraction = discarded.any { it != '0' }
        val halfFraction = when {
            !hasFraction -> -1
            drop > discarded.length -> -1
            discarded.first() < '5' -> -1
            discarded.first() > '5' || discarded.drop(1).any { it != '0' } -> 1
            else -> 0
        }
        val twice = remainder.toLong() * 2
        val half = when {
            twice > increment -> 1
            twice == increment.toLong() -> if (hasFraction) 1 else 0
            twice == increment - 1L -> halfFraction
            else -> -1
        }
        val nonzero = remainder != 0 || hasFraction
        val up = when (roundingMode) {
            NumberRoundingMode.CEIL -> !isNegative && nonzero
            NumberRoundingMode.FLOOR -> isNegative && nonzero
            NumberRoundingMode.EXPAND -> nonzero
            NumberRoundingMode.TRUNC -> false
            NumberRoundingMode.HALF_CEIL -> half > 0 || (half == 0 && !isNegative)
            NumberRoundingMode.HALF_FLOOR -> half > 0 || (half == 0 && isNegative)
            NumberRoundingMode.HALF_EXPAND -> half >= 0
            NumberRoundingMode.HALF_TRUNC -> half > 0
            NumberRoundingMode.HALF_EVEN -> half > 0 || (half == 0 && (quotient.last() - '0') % 2 != 0)
        }
        val rounded = if (up) addOne(quotient) else quotient
        return Decimal(multiplyDigits(rounded, increment), scale, isNegative)
    }

    /** Exact remainder by a positive integral divisor, including the fractional part. */
    public fun remainder(divisor: Int): Decimal {
        require(divisor > 0)
        val rem = divideDigits(integerDigits, divisor).second
        return ofDigits(rem.toString() + fractionDigits, maxOf(scale, 0), isNegative)
    }

    public fun toPlainString(): String = (if (isNegative) "-" else "") + integerDigits +
        if (scale > 0) "." + fractionDigits else ""

    override fun compareTo(other: Decimal): Int {
        if (isZero && other.isZero) return 0
        if (isZero) return if (other.isNegative) 1 else -1
        if (other.isZero) return if (isNegative) -1 else 1
        if (isNegative != other.isNegative) return if (isNegative) -1 else 1
        val magnitudeComparison = magnitude.compareTo(other.magnitude)
        val comparison = if (magnitudeComparison != 0) {
            magnitudeComparison
        } else {
            val length = maxOf(coefficientDigits.length, other.coefficientDigits.length)
            coefficientDigits.padEnd(length, '0').compareTo(other.coefficientDigits.padEnd(length, '0'))
        }
        return if (isNegative) -comparison else comparison
    }
    override fun equals(other: Any?): Boolean =
        other is Decimal && coefficientDigits == other.coefficientDigits && scale == other.scale && isNegative == other.isNegative
    override fun hashCode(): Int = 31 * (31 * coefficientDigits.hashCode() + scale) + isNegative.hashCode()
    override fun toString(): String = toPlainString()

    public companion object {
        public val ZERO: Decimal = Decimal("0", 0, false)
        public fun of(value: Long): Decimal = Decimal(value.toString().removePrefix("-"), 0, value < 0)
        public fun ofUnscaled(unscaled: Long, scale: Int): Decimal = ofDigits(unscaled.toString().removePrefix("-"), scale, unscaled < 0)
        public fun ofDigits(coefficientDigits: String, scale: Int, negative: Boolean = false): Decimal {
            require(coefficientDigits.isNotEmpty() && coefficientDigits.all { it in '0'..'9' }) { "Expected ASCII coefficient digits" }
            require(scale != Int.MIN_VALUE) { "Scale cannot be Int.MIN_VALUE" }
            return Decimal(coefficientDigits.trimStart('0').ifEmpty { "0" }, scale, negative)
        }
        public fun parseOrNull(text: String): Decimal? {
            if (text.isEmpty()) return null
            val negative = text.first() == '-'
            val body = if (negative || text.first() == '+') text.drop(1) else text
            val exponentAt = body.indexOfFirst { it == 'e' || it == 'E' }
            val exponent = if (exponentAt < 0) 0 else body.substring(exponentAt + 1).toIntOrNull() ?: return null
            val coefficient = if (exponentAt < 0) body else body.take(exponentAt)
            val point = coefficient.indexOf('.')
            val digits = if (point < 0) coefficient else coefficient.removeRange(point, point + 1)
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
            val scale = (if (point < 0) 0 else coefficient.length - point - 1).toLong() - exponent
            if (scale <= Int.MIN_VALUE || scale > Int.MAX_VALUE) return null
            return ofDigits(digits, scale.toInt(), negative)
        }
        public fun parse(text: String): Decimal = requireNotNull(parseOrNull(text)) { "Not a finite decimal: '$text'" }
        public fun ofOrNull(value: Double, fractionDigits: Int): Decimal? {
            if (!value.isFinite() || fractionDigits < 0) return null
            return parseOrNull(value.toString())?.rescaled(fractionDigits)
        }
        public fun of(value: Double, fractionDigits: Int): Decimal =
            requireNotNull(ofOrNull(value, fractionDigits)) { "Not a finite decimal: $value" }
    }
}

private fun checkedInt(value: Long): Int {
    require(value in (Int.MIN_VALUE + 1L)..Int.MAX_VALUE) { "Decimal scale or output length exceeds Int range" }
    return value.toInt()
}

private fun divideDigits(digits: String, divisor: Int): Pair<String, Int> {
    var remainder = 0L
    val quotient = buildString {
        for (digit in digits) {
            remainder = remainder * 10 + (digit - '0')
            append(('0'.code + (remainder / divisor).toInt()).toChar())
            remainder %= divisor
        }
    }.trimStart('0').ifEmpty { "0" }
    return quotient to remainder.toInt()
}

private fun multiplyDigits(digits: String, multiplier: Int): String {
    var carry = 0L
    val result = StringBuilder()
    for (index in digits.indices.reversed()) {
        val value = (digits[index] - '0').toLong() * multiplier + carry
        result.append(('0'.code + (value % 10).toInt()).toChar())
        carry = value / 10
    }
    while (carry != 0L) {
        result.append(('0'.code + (carry % 10).toInt()).toChar())
        carry /= 10
    }
    return result.reverse().toString().trimStart('0').ifEmpty { "0" }
}

private fun addOne(digits: String): String {
    val result = digits.toCharArray()
    for (index in result.indices.reversed()) {
        if (result[index] != '9') {
            result[index]++
            return result.concatToString()
        }
        result[index] = '0'
    }
    return "1" + result.concatToString()
}
