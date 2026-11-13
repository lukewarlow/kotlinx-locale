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

package dev.carcara.kotlinx.locale.datetime.cldr

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.datetime.DateTimePartKind
import dev.carcara.kotlinx.locale.datetime.DateTimeRenderContext
import dev.carcara.kotlinx.locale.datetime.FormatStyle
import dev.carcara.kotlinx.locale.datetime.HourCycle
import dev.carcara.kotlinx.locale.datetime.hourCycle
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val IntlBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("selectedFieldsAndStructuredRendering") {
        val selected = value(CldrDateTime.select(FormatStyle.LONG, FormatStyle.SHORT, Locale.of("en"), HourCycle.H23))
        assertEquals(HourCycle.H23, selected.hourCycle)
        val result = value(CldrDateTime.render(DateTimeRenderContext(LocalDate(2026, 11, 12), LocalTime(15, 4)), selected))
        assertTrue(result.parts.any { it.kind == DateTimePartKind.MONTH })
        assertTrue(result.parts.any { it.kind == DateTimePartKind.HOUR && it.value == "15" })
        assertEquals(result.text, result.parts.joinToString("") { it.value })
    }
    test("zoneRequiredInsteadOfOmitted") {
        val selected = value(CldrDateTime.select(null, FormatStyle.FULL, Locale.of("en")))
        assertTrue(CldrDateTime.render(DateTimeRenderContext(time = LocalTime(12, 0)), selected) is LocaleLookup.Unsupported)
    }
    test("regionalWeekRowsDoNotHideWorldFallback") {
        assertEquals(null, weekInfoForRegionOrNull("QQ"))
        assertTrue(weekInfoForRegionOrNull("GB") != null)
        assertTrue(weekInfoForRegionOrNull("001") != null)
    }
}
