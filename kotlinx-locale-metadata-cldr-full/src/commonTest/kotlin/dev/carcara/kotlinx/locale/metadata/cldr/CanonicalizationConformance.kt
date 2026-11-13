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

package dev.carcara.kotlinx.locale.metadata.cldr

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.test.assertEquals

val CanonicalizationConformance by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("cldrStrictIdentifierCases") {
        val failures = mutableListOf<String>()
        var checked = 0
        for (line in canonicalizationFixture) {
            val fields = line.replace('_', '-').split(';').map(String::trim)
            if (fields.size < 2) continue
            val source = fields[0]
            val parsed = Locale.forUnicodeLanguageTagOrNull(source) ?: continue
            val expected = Locale.forUnicodeLanguageTagOrNull(fields[1]) ?: continue
            checked++
            val actual = CldrLocaleMetadata.canonicalize(parsed)
            if (actual != expected) failures.add("$source: ${expected.toLanguageTag()} != ${actual.toLanguageTag()}")
        }
        dev.carcara.kotlinx.locale.test.assertTrue(checked > 800)
        assertEquals(emptyList<String>(), failures)
    }
    test("cldrLikelySubtags") {
        val failures = mutableListOf<String>()
        for (line in likelySubtagsFixture) {
            val fields = line.replace('_', '-').split(';').map(String::trim)
            if (fields.size < 2 || fields[1] == "FAIL") continue
            val input = Locale.forUnicodeLanguageTagOrNull(fields[0]) ?: continue
            val expected = Locale.forUnicodeLanguageTagOrNull(fields[1]) ?: continue
            val actual = CldrLocaleMetadata.maximize(input)
            if (actual != expected) failures += "${fields[0]}: expected ${fields[1]}, got $actual"
            val minimized = fields.getOrNull(3)?.ifEmpty { null } ?: fields.getOrNull(2)?.ifEmpty { null } ?: fields[1]
            val minimal = Locale.forUnicodeLanguageTagOrNull(minimized) ?: continue
            if (CldrLocaleMetadata.minimize(input) !=
                minimal
            ) {
                failures += "${fields[0]}: minimal expected $minimal, got ${CldrLocaleMetadata.minimize(input)}"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }
}
