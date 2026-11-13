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

package dev.carcara.kotlinx.locale.list.cldr

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.list.ListFormatOptions
import dev.carcara.kotlinx.locale.list.ListPart
import dev.carcara.kotlinx.locale.list.ListType
import dev.carcara.kotlinx.locale.test.assertEquals

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val IntlBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("repeatedAndEmptyElementsRetainIdentity") {
        val output = value(listFormat(listOf("a, b", "", "a, b"), Locale.of("en")))
        assertEquals(listOf(0, 1, 2), output.parts.filterIsInstance<ListPart.Element>().map { it.index })
        assertEquals(listOf("a, b", "", "a, b"), output.parts.filterIsInstance<ListPart.Element>().map { it.value })
        assertEquals(output.text, output.parts.joinToString("") { it.value })
        assertEquals("", value(listFormat(emptyList(), Locale.of("en"))).text)
    }
    test("contextualConjunctionAndDisjunction") {
        assertEquals("español e inglés", value(listFormat(listOf("español", "inglés"), Locale.of("es"))).text)
        assertEquals("7 u 8", value(listFormat(listOf("7", "8"), Locale.of("es"), ListFormatOptions(ListType.DISJUNCTION))).text)
        assertEquals("7 u 11.5", value(listFormat(listOf("7", "11.5"), Locale.of("es"), ListFormatOptions(ListType.DISJUNCTION))).text)
        assertEquals("א ו-English", value(listFormat(listOf("א", "English"), Locale.of("he"))).text)
    }
}
