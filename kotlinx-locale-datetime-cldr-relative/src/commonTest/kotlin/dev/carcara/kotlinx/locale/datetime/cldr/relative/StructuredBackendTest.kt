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

package dev.carcara.kotlinx.locale.datetime.cldr.relative

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.datetime.RelativeTimeUnit
import dev.carcara.kotlinx.locale.number.Decimal
import dev.carcara.kotlinx.locale.number.NumberPartKind
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val StructuredBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("fractionalAndSignedZeroPastQuantities") {
        val fraction = value(relativeTimeFormat(Decimal.parse("-1.5"), RelativeTimeUnit.HOUR, Locale.of("en")))
        assertEquals("1.5 hours ago", fraction.text)
        assertTrue(fraction.parts.any { it.kind == NumberPartKind.FRACTION && it.unit == "hour" })
        assertEquals("0 hours ago", value(relativeTimeFormat(Decimal.parse("-0"), RelativeTimeUnit.HOUR, Locale.of("en"))).text)
        assertTrue(
            CldrRelativeTime.formatOrNull(
                Long.MIN_VALUE,
                RelativeTimeUnit.HOUR,
                dev.carcara.kotlinx.locale.datetime.RelativeTimeStyle.FULL,
                dev.carcara.kotlinx.locale.datetime.RelativeTimeNumbering.ALWAYS,
                Locale.of("en"),
            )!!.endsWith("hours ago"),
        )
    }
}
