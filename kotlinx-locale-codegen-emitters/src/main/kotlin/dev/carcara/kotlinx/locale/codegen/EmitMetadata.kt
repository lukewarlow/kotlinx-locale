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

package dev.carcara.kotlinx.locale.codegen

import java.io.File

/** Emit metadata without filtering aliases or likely subtags by formatting coverage. */
public fun emitMetadataBinding(root: File, packageName: String, objectName: String, version: String, encoded: String) {
    val literal = encoded.chunked(8000).joinToString(",\n") { "    \"${kotlinEscape(it)}\"" }
    root.packageFile(packageName, "LocaleMetadata.kt").writeText(
        LICENSE_HEADER + "// GENERATED from $version. Do not edit.\n" +
            "package $packageName\n\n" +
            "import dev.carcara.kotlinx.locale.Locale\n" +
            "import dev.carcara.kotlinx.locale.metadata.LocaleMetadataSource\n" +
            "import dev.carcara.kotlinx.locale.metadata.LocalePreferences\n" +
            "import dev.carcara.kotlinx.locale.metadata.TextDirection\n" +
            "import dev.carcara.kotlinx.locale.metadata.CurrencyMetadata\n" +
            "import dev.carcara.kotlinx.locale.metadata.TimeZoneIdentifier\n" +
            "import dev.carcara.kotlinx.locale.metadata.LocaleMatch\n" +
            "import dev.carcara.kotlinx.locale.metadata.RegionPreference\n" +
            "import dev.carcara.kotlinx.locale.metadata.cldr.runtime.PayloadLocaleMetadata\n\n" +
            "private val metadata = listOf(\n$literal\n).joinToString(\"\")\n\n" +
            "public object $objectName : LocaleMetadataSource by PayloadLocaleMetadata(\"${kotlinEscape(version)}\", metadata)\n\n" +
            "public fun localeCanonicalize(value: Locale): Locale = $objectName.canonicalize(value)\n" +
            "public fun localeMaximize(value: Locale): Locale = $objectName.maximize(value)\n" +
            "public fun localeMinimize(value: Locale): Locale = $objectName.minimize(value)\n" +
            "public fun localePreferences(locale: Locale = Locale.current): LocalePreferences = $objectName.preferences(locale)\n" +
            "public fun localeScriptDirectionOrNull(script: String): TextDirection? = $objectName.scriptDirectionOrNull(script)\n" +
            "public fun localeRegionPreference(locale: Locale = Locale.current): RegionPreference =\n" +
            "    $objectName.regionPreference(locale)\n" +
            "public fun localeCanonicalizeUnicodeValue(key: String, value: String): String =\n" +
            "    $objectName.canonicalizeUnicodeValue(key, value)\n" +
            "public fun localeCurrencies(): List<CurrencyMetadata> = $objectName.currencies()\n" +
            "public fun localeTimeZones(region: String): List<String> = $objectName.timeZonesForRegion(region)\n" +
            "public fun localeTimeZoneIdentifiers(): List<TimeZoneIdentifier> = $objectName.timeZoneIdentifiers()\n" +
            "public fun localeTimeZoneIdentifierOrNull(identifier: String): TimeZoneIdentifier? =\n" +
            "    $objectName.timeZoneIdentifierOrNull(identifier)\n" +
            "public fun localeNumberingSystems(): Set<String> = $objectName.numberingSystems()\n" +
            "public fun localeMatchOrNull(requested: List<Locale>, available: Set<Locale>): LocaleMatch? =\n" +
            "    $objectName.bestFitOrNull(requested, available)\n",
    )
}
