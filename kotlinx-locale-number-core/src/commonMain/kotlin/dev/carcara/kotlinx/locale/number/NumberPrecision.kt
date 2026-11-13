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

/** Exact decimal rounding, independent of the host's floating-point formatting. */
public enum class NumberRoundingMode {
    CEIL,
    FLOOR,
    EXPAND,
    TRUNC,
    HALF_CEIL,
    HALF_FLOOR,
    HALF_EXPAND,
    HALF_TRUNC,
    HALF_EVEN,
    ;

    public companion object
}

public enum class NumberRoundingPriority {
    AUTO,
    MORE_PRECISION,
    LESS_PRECISION,
    ;

    public companion object
}

public enum class TrailingZeroDisplay {
    AUTO,
    STRIP_IF_INTEGER,
    ;

    public companion object
}

/** Round with a formatter's precision policy, retaining the visible fraction digits. */
public fun numberRound(
    value: Decimal,
    options: NumberFormatOptions = NumberFormatOptions.Default,
    patternMinimumFractionDigits: Int = 0,
    patternMaximumFractionDigits: Int = 3,
): Decimal {
    val minimum = options.minimumFractionDigits ?: patternMinimumFractionDigits
    val maximum = maxOf(minimum, options.maximumFractionDigits ?: patternMaximumFractionDigits)
    fun fixed(): Decimal = value.rescaled(maximum, options.roundingMode, options.roundingIncrement).trimmed(minimum)
    fun significant(): Decimal {
        val low = options.minimumSignificantDigits ?: 1
        val high = options.maximumSignificantDigits ?: 21
        val rounded = value.rescaled(high - value.magnitude - 1, options.roundingMode)
        val minimumScale = low - rounded.magnitude - 1
        return rounded.trimmed(minimumScale).let { if (it.scale < minimumScale) it.rescaled(minimumScale) else it }
    }
    val result = when {
        options.roundingPriority == NumberRoundingPriority.AUTO ->
            if (options.maximumSignificantDigits != null || options.minimumSignificantDigits != null) significant() else fixed()
        else -> {
            val significantResult = significant()
            val significantScale = (options.maximumSignificantDigits ?: 21) - significantResult.magnitude - 1
            val useSignificant = if (options.roundingPriority == NumberRoundingPriority.MORE_PRECISION) {
                significantScale >= maximum
            } else {
                significantScale < maximum
            }
            if (useSignificant) significantResult else fixed()
        }
    }
    return if (options.trailingZeroDisplay == TrailingZeroDisplay.STRIP_IF_INTEGER && result.isInteger) result.rescaled(0) else result
}

/** Numeric inputs including non-finite values. Finite signed zero is held by Decimal. */
public sealed interface NumberValue {
    public data class Finite(public val value: Decimal) : NumberValue {
        public companion object
    }
    public data object NaN : NumberValue
    public data object PositiveInfinity : NumberValue
    public data object NegativeInfinity : NumberValue
    public companion object
}

/** Displayed finite value after rounding and notation scaling; no second numeric conversion is performed. */
public data class RoundedNumber(public val value: Decimal, public val notationExponent: Int = 0, public val compactExponent: Int = 0) {
    init {
        require(compactExponent >= 0)
    }
    public val operands: PluralOperands get() = PluralOperands(value.integerDigits, value.fractionDigits, compactExponent)
    public companion object
}
