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

package dev.carcara.kotlinx.locale.datetime.cldr.intervals

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.RangeSource
import dev.carcara.kotlinx.locale.datetime.DateTimePartKind
import dev.carcara.kotlinx.locale.datetime.DateTimeRenderContext
import dev.carcara.kotlinx.locale.datetime.FormatStyle
import dev.carcara.kotlinx.locale.datetime.cldr.dateTimeSelect
import dev.carcara.kotlinx.locale.datetime.cldr.skeletons.dateTimeSelectSkeleton
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue
import kotlinx.datetime.LocalDate

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val StructuredBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("skeletonPartsAndStyleRangesPreserveSelection") {
        val start = DateTimeRenderContext(date = LocalDate(2026, 7, 18))
        val end = DateTimeRenderContext(date = LocalDate(2026, 7, 22))
        val skeleton = value(dateTimeSelectSkeleton("yMMMd", Locale.of("en")))
        val interval = value(intervalFormatSelected(start, end, skeleton, "yMMMd"))
        assertTrue(interval.parts.any { it.part.kind == DateTimePartKind.MONTH && it.source == RangeSource.SHARED })
        assertTrue(interval.parts.any { it.part.kind == DateTimePartKind.DAY && it.source == RangeSource.START })
        assertTrue(interval.parts.any { it.part.kind == DateTimePartKind.DAY && it.source == RangeSource.END })
        val style = value(dateTimeSelect(FormatStyle.FULL, null, Locale.of("en")))
        val range = value(intervalFormatSelected(start, end, style))
        assertEquals(range.text, range.parts.joinToString("") { it.part.value })
        assertTrue(range.parts.any { it.source == RangeSource.START })
        assertTrue(range.parts.any { it.source == RangeSource.END })
    }
    test("fractionalSecondsAndZoneSelection") {
        val selected = value(dateTimeSelectSkeleton("HmsSSS", Locale.of("en")))
        val result = value(
            dev.carcara.kotlinx.locale.datetime.cldr.dateTimeFormat(
                DateTimeRenderContext(time = kotlinx.datetime.LocalTime(15, 4, 5, 123456789)),
                selected,
            ),
        )
        assertTrue(result.parts.any { it.kind == DateTimePartKind.FRACTIONAL_SECOND && it.value == "123" })
        val zone = value(dateTimeSelectSkeleton("Hmz", Locale.of("en")))
        assertEquals(dev.carcara.kotlinx.locale.datetime.DateTimeZoneNameStyle.SPECIFIC_SHORT, zone.zoneNameStyle)
        val zoned = value(
            dev.carcara.kotlinx.locale.datetime.cldr.dateTimeFormat(
                DateTimeRenderContext(
                    time = kotlinx.datetime.LocalTime(15, 4),
                    zone = dev.carcara.kotlinx.locale.timezone.TimeZoneNameContext("+23:59", 86340, isDaylight = false),
                ),
                zone,
            ),
        )
        assertTrue(zoned.parts.any { it.kind == DateTimePartKind.TIME_ZONE_NAME && it.value.contains("23:59") })
    }
}
