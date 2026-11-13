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

package dev.carcara.kotlinx.locale.currency.cldr

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberPartKind
import dev.carcara.kotlinx.locale.number.NumberValue
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val StructuredBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("exactMajorUnitsAndUnknownCurrencyCode") {
        val result =
            value(
                currencyFormat(
                    NumberValue.Finite(Decimal.parse("123456789012345678901.50")),
                    "ZZZ",
                    Locale.of("en"),
                    NumberFormatOptions(minimumFractionDigits = 2, maximumFractionDigits = 2),
                ),
            )
        assertEquals("50", result.operands.fractionDigits)
        assertEquals("123456789012345678901", result.operands.integerDigits)
        assertTrue(result.parts.any { it.kind == NumberPartKind.CURRENCY && it.value == "ZZZ" })
        assertEquals(result.text, result.parts.joinToString("") { it.value })
    }
    test("signedZeroAndInfinityUseCurrencyPatterns") {
        val zero = value(currencyFormat(NumberValue.Finite(Decimal.parse("-0")), "USD", Locale.of("en")))
        assertTrue(zero.parts.any { it.kind == NumberPartKind.MINUS_SIGN })
        val infinity = value(currencyFormat(NumberValue.PositiveInfinity, "USD", Locale.of("en")))
        assertTrue(infinity.parts.any { it.kind == NumberPartKind.INFINITY })
        assertTrue(infinity.parts.any { it.kind == NumberPartKind.CURRENCY })
    }
    test("isoMinorUnitsKeepLongMinimumWithoutOverflow") {
        val formatted = CldrCurrency.formatOrNull(
            Long.MIN_VALUE,
            "USD",
            Locale.of("en"),
            dev.carcara.kotlinx.locale.currency.CurrencyFormatOptions.Default,
        )
        assertEquals("-$92,233,720,368,547,758.08", formatted)
        val cash = CldrCurrency.formatOrNull(
            Long.MIN_VALUE,
            "CHF",
            Locale.of("en"),
            dev.carcara.kotlinx.locale.currency.CurrencyFormatOptions(cash = true),
        )!!
        assertTrue(cash.startsWith("-") && cash.contains("92,233,720,368,547,758.10"))
    }
}
