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

package dev.carcara.kotlinx.locale.currency.cldr.plurals

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.number.NumberValue
import dev.carcara.kotlinx.locale.test.assertEquals

val ExactCurrencyNameTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("currencyDefaultsDriveVisibleDigitsAndPluralSelection") {
        val output = currencyFormatName(NumberValue.Finite(Decimal.of(1)), "USD", Locale.of("en")) as LocaleLookup.Value
        assertEquals("1.00 US dollars", output.value.text)
        assertEquals("00", output.value.operands.fractionDigits)
        val yen = currencyFormatName(NumberValue.Finite(Decimal.of(1)), "JPY", Locale.of("en")) as LocaleLookup.Value
        assertEquals("", yen.value.operands.fractionDigits)
    }
    test("scientificCurrencyNamesInflectTheQuantity") {
        val options = dev.carcara.kotlinx.locale.number.NumberFormatOptions(
            notation = dev.carcara.kotlinx.locale.number.NumberNotation.SCIENTIFIC,
            maximumFractionDigits = 0,
        )
        val output = currencyFormatName(NumberValue.Finite(Decimal.of(1000000)), "USD", Locale.of("en"), options) as LocaleLookup.Value
        assertEquals("1E6 US dollars", output.value.text)
    }
}
