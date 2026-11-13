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

package dev.carcara.kotlinx.locale.language.cldr

import at.asitplus.testballoon.matrix.matrixConfig
import at.asitplus.testballoon.matrix.matrixSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import dev.carcara.kotlinx.locale.DisplayNameType
import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleLookup
import dev.carcara.kotlinx.locale.NameFallback
import dev.carcara.kotlinx.locale.NameOrigin
import dev.carcara.kotlinx.locale.NameWidth
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

val StructuredDisplayNameTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("calendarAndFieldNamesUsePublicLookup") {
        val calendar = localeDisplayName("gregory", DisplayNameType.CALENDAR, Locale.of("en"))
        assertTrue(calendar is LocaleLookup.Value)
        val field = localeDisplayName("year", DisplayNameType.DATE_TIME_FIELD, Locale.of("en"), NameWidth.NARROW)
        assertTrue(field is LocaleLookup.Value)
    }
    test("composedNamesDoNotEchoMissingVariantsWithFallbackNone") {
        val locale = Locale.forUnicodeLanguageTagOrNull("en-unkno")!!
        assertEquals(LocaleLookup.MissingData, localeDisplayName(locale, Locale.of("en")))
        val code = localeDisplayName(locale, Locale.of("en"), fallback = NameFallback.CODE)
        assertTrue(code is LocaleLookup.Value && code.value.origin == NameOrigin.CODE && code.value.text.contains("unkno"))
    }
}
