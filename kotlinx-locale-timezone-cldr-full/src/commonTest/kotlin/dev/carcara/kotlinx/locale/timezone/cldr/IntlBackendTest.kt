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

package dev.carcara.kotlinx.locale.timezone.cldr

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue
import dev.carcara.kotlinx.locale.timezone.TimeZoneNameContext
import dev.carcara.kotlinx.locale.timezone.TimeZoneNameStyle

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val IntlBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("offsetBeyondEighteenHours") {
        val result =
            value(CldrTimeZone.render(TimeZoneNameContext("+23:59", 23 * 3600 + 59 * 60), TimeZoneNameStyle.OFFSET_LONG, Locale.of("en")))
        assertEquals("GMT+23:59", result)
    }
    test("historicalMetazonesUseInstant") {
        val old =
            value(
                CldrTimeZone.render(
                    TimeZoneNameContext("Europe/Istanbul", 7200, 1262304000, false, true),
                    TimeZoneNameStyle.STANDARD_LONG,
                    Locale.of("en"),
                ),
            )
        val now =
            value(
                CldrTimeZone.render(
                    TimeZoneNameContext("Europe/Istanbul", 10800, 1735689600, false, false),
                    TimeZoneNameStyle.STANDARD_LONG,
                    Locale.of("en"),
                ),
            )
        assertTrue(old != now)
    }
    test("historicalOffsetSecondsArePreserved") {
        val result = value(CldrTimeZone.render(TimeZoneNameContext("Europe/Paris", 561), TimeZoneNameStyle.OFFSET_LONG, Locale.of("en")))
        assertEquals("GMT+00:09:21", result)
    }
}
