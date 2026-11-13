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
import dev.carcara.kotlinx.locale.metadata.TextDirection
import dev.carcara.kotlinx.locale.test.assertEquals
import dev.carcara.kotlinx.locale.test.assertTrue

val LocaleMetadataTest by matrixSuite(matrixConfig { testConfig = TestConfig.testScope(isEnabled = false) }) {
    test("canonicalizesAliasesWithoutFormatterCoverage") {
        for ((source, expected) in listOf(
            "cel-gaulish" to "xtg", "art-lojban" to "jbo", "de-DD" to "de-DE", "en-554" to "en-NZ",
            "hy-SU" to "hy-AM", "ru-SU" to "ru-RU", "en-aaland" to "en-AX",
            "ja-Latn-hepburn-heploc" to "ja-Latn-alalc97", "hy-arevmda" to "hyw",
            "zzzzz-rozaj-biske-u-ca-islamicc-x-test" to "zzzzz-biske-rozaj-u-ca-islamic-civil-x-test",
        )) {
            assertEquals(expected, localeCanonicalize(Locale.forUnicodeLanguageTagOrNull(source)!!).toLanguageTag(), source)
        }
    }
    test("likelySubtagsPreserveVariantsAndExtensions") {
        val locale = Locale.forUnicodeLanguageTagOrNull("zh-TW-fonipa-u-ca-gregory-x-test")!!
        assertEquals("zh-Hant-TW-fonipa-u-ca-gregory-x-test", localeMaximize(locale).toLanguageTag())
        assertEquals("zh-TW-fonipa-u-ca-gregory-x-test", localeMinimize(localeMaximize(locale)).toLanguageTag())
    }
    test("preferencesAreSeparateFromSupportedFormatters") {
        assertEquals("buddhist", localePreferences(Locale.of("th", region = "TH")).calendars.first())
        assertEquals(TextDirection.RTL, localePreferences(Locale.of("ar")).direction)
        assertEquals(TextDirection.RTL, localePreferences(Locale.forLanguageTag("en-Arab")).direction)
        assertEquals(TextDirection.LTR, localePreferences(Locale.forLanguageTag("ar-Latn")).direction)
        assertEquals("GB", localeRegionPreference(Locale.forLanguageTag("en-US-u-rg-gbzzzz")).region)
        assertEquals("AX", localeRegionPreference(Locale.forLanguageTag("en-u-sd-fi01")).region)
        assertEquals("US", localeRegionPreference(Locale.forLanguageTag("en-u-sd")).region)
        assertEquals("US", localeRegionPreference(Locale.forLanguageTag("en-u-rg-aaazzzz")).region)
        assertEquals(null, CldrLocaleMetadata.timeZoneIdentifierOrNull("Europe/Pariſ"))
        assertTrue(!localePreferences(Locale.of("de")).collations.contains("phonebook"))
        assertTrue(CldrLocaleMetadata.numberingSystems().contains("latn"))
        assertEquals("0123456789", CldrLocaleMetadata.numberingSystemDigitsOrNull("latn"))
    }
}
