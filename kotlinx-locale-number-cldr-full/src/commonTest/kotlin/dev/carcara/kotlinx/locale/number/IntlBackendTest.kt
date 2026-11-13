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
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.RangeSource
import dev.carcara.kotlinx.locale.number.cldr.CldrNumber
import dev.carcara.kotlinx.locale.number.cldr.CldrNumberPlurals
import dev.carcara.kotlinx.locale.number.cldr.pluralCategoriesOrNull
import dev.carcara.kotlinx.locale.number.cldr.pluralRangeOrNull
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val IntlBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("allRoundingModesOnBothSigns") {
        val expected = listOf("2/-1", "1/-2", "2/-2", "1/-1", "2/-1", "1/-2", "2/-2", "1/-1", "2/-2")
        for ((mode, answer) in NumberRoundingMode.entries.zip(expected)) {
            assertEquals(
                answer,
                Decimal.parse("1.5").rescaled(0, mode).toPlainString() + "/" + Decimal.parse("-1.5").rescaled(0, mode).toPlainString(),
                mode.name,
            )
        }
        assertEquals(
            "0.00120",
            numberRound(
                Decimal.parse("0.0012"),
                NumberFormatOptions(minimumSignificantDigits = 3, maximumSignificantDigits = 3),
            ).toPlainString(),
        )
    }
    test("percentSpecialValuesAndSignedZeroParts") {
        val percent =
            value(
                CldrNumber.renderPercent(
                    NumberValue.Finite(Decimal.parse("0.125")),
                    Locale.of("en"),
                    options = NumberFormatOptions(maximumFractionDigits = 0, roundingMode = NumberRoundingMode.HALF_EXPAND),
                ),
            )
        assertEquals("13%", percent.text)
        val infinity = value(CldrNumber.renderPercent(NumberValue.NegativeInfinity, Locale.of("en")))
        assertEquals("-∞%", infinity.text)
        assertEquals(
            "NaN",
            value(CldrNumber.render(NumberValue.NaN, Locale.of("en"), NumberFormatOptions(signDisplay = SignDisplay.EXCEPT_ZERO))).text,
        )
        assertEquals(
            "+NaN",
            value(CldrNumber.render(NumberValue.NaN, Locale.of("en"), NumberFormatOptions(signDisplay = SignDisplay.ALWAYS))).text,
        )
        assertTrue(infinity.parts.any { it.kind == NumberPartKind.INFINITY })
        assertTrue(
            value(CldrNumber.render(NumberValue.Finite(Decimal.parse("-0")), Locale.of("en"))).parts.any {
                it.kind ==
                    NumberPartKind.MINUS_SIGN
            },
        )
    }
    test("numberSystemsRangesAndPreRoundedExponent") {
        val options = NumberFormatOptions(numberingSystem = "mathbold", grouping = NumberGrouping.NEVER)
        assertEquals("𝟏𝟐", value(CldrNumber.render(NumberValue.Finite(Decimal.of(12)), Locale.of("en"), options)).text)
        val start = value(CldrNumber.render(NumberValue.Finite(Decimal.of(1)), Locale.of("en")))
        val end = value(CldrNumber.render(NumberValue.Finite(Decimal.of(2)), Locale.of("en")))
        val range = value(CldrNumber.composeRange(start, end, Locale.of("en")))
        assertTrue(range.parts.any { it.source == RangeSource.START })
        assertTrue(range.parts.any { it.source == RangeSource.END })
        assertEquals(range.text, range.parts.joinToString("") { it.part.value })
        assertEquals(
            "1.2300E42",
            value(
                CldrNumber.renderRounded(
                    RoundedNumber(Decimal.parse("1.2300"), 42),
                    Locale.of("en"),
                    NumberFormatOptions(notation = NumberNotation.SCIENTIFIC),
                ),
            ).text,
        )
        assertTrue(CldrNumberPlurals.pluralCategoriesOrNull(PluralType.CARDINAL, Locale.of("ru"))!!.contains(PluralCategory.OTHER))
        assertEquals(PluralCategory.OTHER, CldrNumberPlurals.pluralRangeOrNull(PluralCategory.ONE, PluralCategory.OTHER, Locale.of("en")))
    }
    test("mixedPrecisionTiesUseTheSpecifiedBranch") {
        val options =
            NumberFormatOptions(
                minimumSignificantDigits = 3,
                maximumSignificantDigits = 3,
                minimumFractionDigits = 1,
                maximumFractionDigits = 2,
            )
        assertEquals(
            "1.00",
            numberRound(Decimal.of(1), options.copy(roundingPriority = NumberRoundingPriority.MORE_PRECISION)).toPlainString(),
        )
        assertEquals(
            "1.0",
            numberRound(Decimal.of(1), options.copy(roundingPriority = NumberRoundingPriority.LESS_PRECISION)).toPlainString(),
        )
        val compact =
            value(
                CldrNumber.renderPercent(
                    NumberValue.Finite(Decimal.of(12)),
                    Locale.of("en"),
                    options = NumberFormatOptions(notation = NumberNotation.COMPACT_SHORT),
                ),
            )
        assertEquals("1.2K%", compact.text)
    }
}
