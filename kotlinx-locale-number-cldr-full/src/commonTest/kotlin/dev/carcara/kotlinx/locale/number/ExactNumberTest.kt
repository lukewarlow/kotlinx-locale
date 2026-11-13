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

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.number.cldr.CldrNumber
import dev.carcara.kotlinx.locale.number.cldr.CldrNumberPlurals
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

val ExactNumberTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("exactLargeValuesAndSignedZero") {
        val value = Decimal.parse("123456789012345678901234567890.12500")
        assertEquals("123456789012345678901234567890.12500", value.toPlainString())
        assertEquals(null, value.unscaledOrNull())
        assertTrue(Decimal.parse("-0.000").isNegative)
        assertEquals("-0", Decimal.parse("-0.01").rescaled(0).toPlainString())
        assertTrue(Decimal.ZERO < Decimal.parse("0.1"))
        assertEquals("0.000000000000000000000000000001", Decimal.parse("1e-30").toPlainString())
        val result = CldrNumber.formatOrNull(
            value,
            Locale.of("en"),
            NumberFormatOptions(grouping = NumberGrouping.NEVER, preRounded = true),
        )!!
        assertEquals(value.toPlainString(), result.text)
        assertEquals(result.text, result.parts.joinToString("") { it.value })
    }
    test("roundingModesAndIncrementTies") {
        assertEquals("1.2", Decimal.parse("1.25").rescaled(1).toPlainString())
        assertEquals("1.3", Decimal.parse("1.25").rescaled(1, NumberRoundingMode.HALF_EXPAND).toPlainString())
        assertEquals("-1.2", Decimal.parse("-1.25").rescaled(1, NumberRoundingMode.HALF_CEIL).toPlainString())
        assertEquals("-1.3", Decimal.parse("-1.25").rescaled(1, NumberRoundingMode.HALF_FLOOR).toPlainString())
        assertEquals("1.30", Decimal.parse("1.275").rescaled(2, NumberRoundingMode.HALF_EVEN, 5).toPlainString())
        assertEquals("1.20", Decimal.parse("1.225").rescaled(2, NumberRoundingMode.HALF_EVEN, 5).toPlainString())
        assertEquals("-0.00", Decimal.parse("-0.0000000000000000000000001").rescaled(2).toPlainString())
    }
    test("hugePluralOperandsUseExactModulus") {
        val endingOne = PluralOperands("100000000000000000000000000000000000001")
        assertEquals(PluralCategory.ONE, CldrNumberPlurals.pluralCategoryOrNull(endingOne, PluralType.CARDINAL, Locale.of("ru")))
        assertEquals(
            PluralCategory.MANY,
            CldrNumberPlurals.pluralCategoryOrNull(
                PluralOperands("1", "000000000000000000000000000"),
                PluralType.CARDINAL,
                Locale.of("cs"),
            ),
        )
        assertEquals("100000000000000000000000000000000000001", endingOne.i.toPlainString())
    }
    test("scientificAndEngineeringParts") {
        for ((notation, expected) in listOf(NumberNotation.SCIENTIFIC to "1.235E4", NumberNotation.ENGINEERING to "12.345E3")) {
            val result = CldrNumber.formatOrNull(
                Decimal.of(12345),
                Locale.of("en"),
                NumberFormatOptions(notation = notation, roundingMode = NumberRoundingMode.HALF_EXPAND),
            )!!
            assertEquals(expected, result.text)
            assertTrue(result.parts.any { it.kind == NumberPartKind.EXPONENT_INTEGER })
        }
    }
}
