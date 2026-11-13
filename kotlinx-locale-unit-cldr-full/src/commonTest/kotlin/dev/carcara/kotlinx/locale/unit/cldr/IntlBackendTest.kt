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

package dev.carcara.kotlinx.locale.unit.cldr

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.number.NumberFormatOptions
import dev.carcara.kotlinx.locale.number.NumberPartKind
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val IntlBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("exactUnitsAndCompoundUnits") {
        val result =
            value(
                unitFormat(
                    Decimal.parse("123456789012345678901.50"),
                    "meter",
                    Locale.of("en"),
                    options = NumberFormatOptions(preRounded = true),
                ),
            )
        assertTrue(result.text.endsWith(" meters"))
        assertEquals("50", result.operands.fractionDigits)
        assertTrue(result.parts.any { it.kind == NumberPartKind.UNIT && it.unit == "meter" })
        val compound = value(unitFormat(Decimal.of(1), "meter-per-second", Locale.of("en")))
        assertEquals("1 meter per second", compound.text)
    }
    test("unknownUnitsAreMissingAndNumberingIsSelected") {
        assertEquals(LocaleLookup.MissingData, unitFormat(Decimal.of(1), "not-a-unit", Locale.of("en")))
        val result = value(unitFormat(Decimal.of(2), "hour", Locale.of("en"), options = NumberFormatOptions(numberingSystem = "arab")))
        assertTrue(result.text.startsWith("٢"))
    }
    test("notationUsesTheRoundedQuantityForUnitInflection") {
        for ((notation, expected) in listOf(
            dev.carcara.kotlinx.locale.number.NumberNotation.SCIENTIFIC to "1E6 meters",
            dev.carcara.kotlinx.locale.number.NumberNotation.COMPACT_SHORT to "1M meters",
        )) {
            val formatted =
                value(
                    unitFormat(
                        Decimal.of(1000000),
                        "meter",
                        Locale.of("en"),
                        options = NumberFormatOptions(notation = notation, maximumFractionDigits = 0),
                    ),
                )
            assertEquals(expected, formatted.text)
            assertEquals("1000000", formatted.quantityOperands.integerDigits)
        }
    }
}
