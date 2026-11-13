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

package dev.carcara.kotlinx.locale.collation.cldr

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.collation.CollationCaseFirst
import dev.carcara.kotlinx.locale.collation.CollationOptions
import dev.carcara.kotlinx.locale.collation.CollationSensitivity
import dev.carcara.kotlinx.locale.collation.CollationUsage
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

private fun <T> value(result: LocaleLookup<T>): T = when (result) {
    is LocaleLookup.Value -> result.value
    else -> error("Expected value: $result")
}
val IntlBackendTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("caseOnlyIgnoresAccentsButKeepsCase") {
        val comparator = value(CldrCollation.resolve(Locale.of("en"), CollationOptions(sensitivity = CollationSensitivity.CASE))).comparator
        assertEquals(0, comparator.compare("a", "á"))
        assertTrue(comparator.compare("a", "A") != 0)
    }
    test("numericPunctuationAndCaseFirst") {
        val numeric = value(CldrCollation.resolve(Locale.of("en"), CollationOptions(numeric = true))).comparator
        assertTrue(numeric.compare("file2", "file10") < 0)
        assertEquals(0, numeric.compare("file002", "file٢"))
        val ignored = value(CldrCollation.resolve(Locale.of("en"), CollationOptions(ignorePunctuation = true))).comparator
        assertEquals(0, ignored.compare("a-b", "ab"))
        val upper = value(CldrCollation.resolve(Locale.of("en"), CollationOptions(caseFirst = CollationCaseFirst.UPPER))).comparator
        assertTrue(upper.compare("A", "a") < 0)
    }
    test("variantsAndSearchAreRealCapabilities") {
        assertTrue(CldrCollation.capabilities(Locale.of("de")).collations.contains("phonebk"))
        val phonebook =
            value(CldrCollation.resolve(Locale.of("de"), CollationOptions(collation = "phonebk", sensitivity = CollationSensitivity.BASE)))
        assertEquals(0, phonebook.comparator.compare("ä", "ae"))
        assertEquals(
            CollationUsage.SEARCH,
            value(CldrCollation.resolve(Locale.of("de"), CollationOptions(usage = CollationUsage.SEARCH))).usage,
        )
    }
}
