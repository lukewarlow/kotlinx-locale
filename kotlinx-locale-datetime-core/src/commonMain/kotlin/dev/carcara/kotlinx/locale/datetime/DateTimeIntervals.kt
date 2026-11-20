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

package dev.carcara.kotlinx.locale.datetime

import dev.carcara.kotlinx.locale.RangeSource

/** One prepared CLDR pattern fragment and the endpoint whose fields it reads. */
public data class DateTimeIntervalPatternPart(public val pattern: String, public val source: RangeSource) {
    public companion object
}

/** A compact interval for a greatest differing field, in the selected hour cycle. */
public class DateTimeIntervalPattern(
    public val difference: DateTimeField,
    parts: List<DateTimeIntervalPatternPart>,
    public val hourCycle: HourCycle?,
) {
    public val parts: List<DateTimeIntervalPatternPart> = parts.toList()
    public companion object
}

/** Immutable selection data. The fallback uses {0}/{1} for complete endpoint formats. */
public class SelectedDateTimeIntervals(
    public val format: SelectedDateTimeFormat,
    patterns: List<DateTimeIntervalPattern>,
    public val fallback: String,
) {
    public val patterns: List<DateTimeIntervalPattern> = patterns.toList()
    public companion object
}
