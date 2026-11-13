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

package dev.carcara.kotlinx.locale.collation

import dev.carcara.kotlinx.locale.Locale
import dev.carcara.kotlinx.locale.LocaleCoverage

public enum class CollationUsage {
    SORT,
    SEARCH,
    ;

    public companion object
}
public enum class CollationSensitivity {
    BASE,
    ACCENT,
    CASE,
    VARIANT,
    ;

    public companion object
}
public enum class CollationCaseFirst {
    OFF,
    UPPER,
    LOWER,
    ;

    public companion object
}
public data class CollationOptions(
    public val usage: CollationUsage = CollationUsage.SORT,
    public val sensitivity: CollationSensitivity? = null,
    public val collation: String? = null,
    public val ignorePunctuation: Boolean? = null,
    public val numeric: Boolean? = null,
    public val caseFirst: CollationCaseFirst? = null,
) {
    public companion object
}
public data class CollationCapabilities(
    public val coverage: LocaleCoverage,
    public val collations: Set<String>,
    public val usages: Set<CollationUsage>,
    public val numeric: Boolean,
    public val caseFirst: Boolean,
) {
    public companion object
}
public data class ResolvedCollation(
    public val comparator: Comparator<String>,
    public val locale: Locale,
    public val usage: CollationUsage,
    public val sensitivity: CollationSensitivity,
    public val collation: String,
    public val ignorePunctuation: Boolean,
    public val numeric: Boolean,
    public val caseFirst: CollationCaseFirst,
) {
    public companion object
}
