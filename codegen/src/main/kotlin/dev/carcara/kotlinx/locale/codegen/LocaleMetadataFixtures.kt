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

/** The fixtures and the metadata are regenerated from the same CLDR checkout. */
internal fun emitLocaleMetadataFixtures(root: File, cldr: File) {
    val target = root.resolve(
        "kotlinx-locale-metadata-cldr-full/src/commonTest/kotlin/dev/carcara/kotlinx/locale/metadata/cldr/LocaleIdentityFixtures.kt",
    )
    target.parentFile.mkdirs()
    val fields = listOf(
        "canonicalizationFixture" to "localeCanonicalization.txt",
        "likelySubtagsFixture" to "likelySubtags.txt",
    ).joinToString("\n\n") { (name, file) ->
        val data = cldr.resolve("common/testData/localeIdentifiers/$file").readLines().map { it.substringBefore('#').trim() }.filter {
            it.isNotEmpty() &&
                !it.startsWith('@')
        }.joinToString("\n")
        val chunks = data.chunked(8000).joinToString(",\n") { "    \"${kotlinEscape(it)}\"" }
        "internal val $name: List<String> = listOf(\n$chunks\n).joinToString(\"\").lines()"
    }
    target.writeText(
        LICENSE_HEADER +
            "// GENERATED from CLDR ${CLDR_REPO.tag}. Do not edit.\npackage dev.carcara.kotlinx.locale.metadata.cldr\n\n$fields\n",
    )
}
